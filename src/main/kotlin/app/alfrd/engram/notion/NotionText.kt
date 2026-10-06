package app.alfrd.engram.notion

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Splits [text] into pieces of at most [max] UTF-16 units that join back to exactly [text]. A piece
 * never ends between the two halves of a surrogate pair. Empty text gives no pieces, which Notion
 * stores as an empty rich-text value.
 */
fun chunkText(text: String, max: Int = NotionApi.MAX_TEXT_CHARS): List<String> {
    require(max >= 2) { "max must be at least 2 so a surrogate pair always fits in one piece" }
    if (text.isEmpty()) return emptyList()
    val pieces = ArrayList<String>((text.length + max - 1) / max)
    var start = 0
    while (start < text.length) {
        var end = minOf(start + max, text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        pieces += text.substring(start, end)
        start = end
    }
    return pieces
}

/** A page title: one line, at most [NotionApi.MAX_TEXT_CHARS] characters. The full words belong in `Said`, which is not truncated. */
fun fitTitle(text: String): String {
    val oneLine = text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()
    if (oneLine.length <= NotionApi.MAX_TEXT_CHARS) return oneLine
    var end = NotionApi.MAX_TEXT_CHARS - 1
    if (Character.isHighSurrogate(oneLine[end - 1])) end--
    return oneLine.substring(0, end) + "…"
}

/** What a date with no time of day stands for, matching how the interpreter reads one: an event starts at the start of its day, a deadline falls at the end of it. */
enum class DateOnlyMeans { START_OF_DAY, END_OF_DAY }

/**
 * Epoch milliseconds to and from Notion date values, in the person's time zone, so a value read
 * back is exactly the value written. A moment that is precisely the day's start (events) or last
 * millisecond (everything else) is written as a plain date; any other moment keeps its time and offset.
 */
object NotionDates {
    private const val ONE_MILLI_NANOS = 1_000_000L

    fun dateOnlyMoment(date: LocalDate, zone: ZoneId, means: DateOnlyMeans): Long = when (means) {
        DateOnlyMeans.START_OF_DAY -> date.atStartOfDay(zone).toInstant().toEpochMilli()
        DateOnlyMeans.END_OF_DAY -> date.plusDays(1).atStartOfDay(zone).minusNanos(ONE_MILLI_NANOS).toInstant().toEpochMilli()
    }

    fun toValue(millis: Long, zone: ZoneId, means: DateOnlyMeans): NotionValue.DateValue {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val date = at.toLocalDate()
        return if (millis == dateOnlyMoment(date, zone, means)) {
            NotionValue.DateValue(date.toString())
        } else {
            NotionValue.DateValue(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.toOffsetDateTime()))
        }
    }

    /** Null for an empty or unreadable date. A range reads as its start. */
    fun toMillis(value: NotionValue.DateValue?, zone: ZoneId, means: DateOnlyMeans): Long? {
        val start = value?.start?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            if (start.contains('T')) {
                runCatching { OffsetDateTime.parse(start).toInstant().toEpochMilli() }
                    .getOrElse { LocalDateTime.parse(start).atZone(zone).toInstant().toEpochMilli() }
            } else {
                dateOnlyMoment(LocalDate.parse(start), zone, means)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Who spoke a ledger entry. The labels are what the Session page shows. */
enum class LedgerRole(val label: String) {
    PERSON("Person"),
    ALFRD("alfrd");

    companion object {
        fun fromLabel(label: String): LedgerRole? = entries.firstOrNull { it.label == label }
    }
}

data class LedgerEntry(val role: LedgerRole, val at: Instant, val text: String)

/**
 * The verbatim ledger as it sits in a Session page body. Each entry is a bold label paragraph
 * (`Person · 2026-10-06T14:02:11Z`) followed by the exact words, in plain paragraphs of at most
 * [NotionApi.MAX_TEXT_CHARS] characters. Only a *bold* paragraph of that exact shape starts an entry,
 * and the words are never bold, so nothing a person types can be mistaken for a label.
 *
 * Notion lists a page's blocks only from the top, so reading a long page back costs one request per
 * 100 blocks; callers keep the recent tail in memory and use [decode] only to restore it.
 */
object LedgerCodec {
    private val LABEL = Regex("^(Person|alfrd) · (\\S+)$")
    private const val SEPARATOR = " · "

    fun encode(entry: LedgerEntry): List<NotionBlock> {
        val header = NotionBlock.Paragraph(listOf(RichSegment("${entry.role.label}$SEPARATOR${entry.at}", bold = true)))
        val body = chunkText(entry.text).map { NotionBlock.Paragraph(listOf(RichSegment(it))) }
        return listOf(header) + body
    }

    /** Whole entries packed into appends of at most [maxPerAppend] blocks, so a header is never separated from its first words by a failed request. An entry too big for one append is split. */
    fun pack(entries: List<LedgerEntry>, maxPerAppend: Int = NotionApi.MAX_BLOCKS_PER_APPEND): List<List<NotionBlock>> {
        val batches = ArrayList<List<NotionBlock>>()
        var current = ArrayList<NotionBlock>()
        for (entry in entries) {
            val blocks = encode(entry)
            if (current.isNotEmpty() && current.size + blocks.size > maxPerAppend) {
                batches += current
                current = ArrayList()
            }
            if (blocks.size > maxPerAppend) {
                blocks.chunked(maxPerAppend).forEach { batches += it }
            } else {
                current.addAll(blocks)
            }
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    /** Entries in page order. Blocks before the first label, and labels that do not parse, are ignored rather than guessed at. */
    fun decode(blocks: List<NotionBlockRead>): List<LedgerEntry> {
        val entries = ArrayList<LedgerEntry>()
        var role: LedgerRole? = null
        var at: Instant? = null
        val text = StringBuilder()

        fun flush() {
            val r = role
            val t = at
            if (r != null && t != null) entries += LedgerEntry(r, t, text.toString())
            role = null
            at = null
            text.setLength(0)
        }

        for (block in blocks) {
            if (block.type != "paragraph") continue
            val isLabel = block.segments.size == 1 && block.segments[0].bold
            val parsed = if (isLabel) LABEL.matchEntire(block.segments[0].text) else null
            if (parsed != null) {
                flush()
                role = LedgerRole.fromLabel(parsed.groupValues[1])
                at = runCatching { Instant.parse(parsed.groupValues[2]) }.getOrNull()
            } else if (role != null) {
                text.append(block.plainText)
            }
        }
        flush()
        return entries
    }
}
