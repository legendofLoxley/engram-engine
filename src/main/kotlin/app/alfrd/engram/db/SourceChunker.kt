package app.alfrd.engram.db

import java.security.MessageDigest

/**
 * A passage cut from a source: [location] says where it sits (a section, a part, an email message), [text] is the passage.
 *
 * The rest is only set for a chunk that comes from a paged file (see `app.alfrd.engram.pdf`): the physical [page] (1-based), the
 * [printedPage] as printed on it (null when none was found), the [section] it sits in, its [kind] (`text` or `table`), how it was read
 * ([extractor] `textlayer` or `ocr`, with the engine's own [ocrConfidence]), and where on the page it is ([box], JSON) and, for a table,
 * its rows and cells ([table], JSON).
 */
data class ChunkDraft(
    val location: String,
    val text: String,
    val page: Int? = null,
    val printedPage: String? = null,
    val section: String? = null,
    val kind: String? = null,
    val extractor: String? = null,
    val ocrConfidence: Double? = null,
    val box: String? = null,
    val table: String? = null,
)

/**
 * Cuts a source's text into chunks, and fingerprints it. Pure: no graph, no clock, no logging.
 *
 * A document is cut along its headings and blank lines into passages of at most [MAX_CHARS] characters, never in the
 * middle of a paragraph unless one paragraph alone is longer than that. There is no overlap between chunks.
 * An email message is chunked after its quoted reply history is dropped ([stripQuotedReplies]): what a person
 * replied to is not what this message says, and it is already a Source of its own when it was read.
 */
object SourceChunker {
    const val MAX_CHARS = 1_500

    /** Line endings made uniform and the ends trimmed, so the same content hashes the same wherever it was read. */
    fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').trim()

    fun contentHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalize(text).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun chunkDocument(text: String): List<ChunkDraft> = pack(sections(normalize(text)), "part")

    /** [messageLabel] names the message in each location (for example "message 3" or its id). */
    fun chunkEmail(body: String, messageLabel: String): List<ChunkDraft> {
        val own = stripQuotedReplies(normalize(body))
        return pack(listOf(null to own), "$messageLabel, part")
    }

    private val ATTRIBUTION = Regex("""^(On .{5,200}wrote:|Le .{5,200}a écrit\s*:|Am .{5,200}schrieb .*:)\s*$""")
    private val FORWARD_MARKERS = Regex("""^(-{2,}\s*(Original Message|Forwarded message)\s*-{2,}|_{5,})$""", RegexOption.IGNORE_CASE)
    private val HEADER_LINE = Regex("""^(Sent|Date|To|Cc|Subject):\s""", RegexOption.IGNORE_CASE)

    /**
     * Drops the quoted reply history of an email body: the "On <date>, <name> wrote:" line and everything after it,
     * an "-----Original Message-----" or "From:" header block and everything after it, and every line that itself starts
     * with ">" (only that line is dropped, not the lines around it). What the writer wrote stays.
     */
    fun stripQuotedReplies(body: String): String {
        val kept = ArrayList<String>()
        val lines = body.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (ATTRIBUTION.matches(t)) break
            // A wrapped attribution: "On Tue, 3 Mar 2026 at 10:14, Name <x@y>" / "wrote:"
            if (t.startsWith("On ") && !t.endsWith("wrote:") && i + 1 < lines.size && lines[i + 1].trim().endsWith("wrote:")) break
            if (FORWARD_MARKERS.matches(t) && kept.any { it.isNotBlank() }) break
            // An Outlook-style header block: "From: ..." followed by Sent:/Date:/To:/Subject: lines.
            if (t.startsWith("From:", ignoreCase = true) && kept.any { it.isNotBlank() } &&
                lines.drop(i + 1).take(4).any { HEADER_LINE.containsMatchIn(it.trim()) }) break
            if (t.startsWith(">")) { i++; continue }
            kept += line
            i++
        }
        return kept.joinToString("\n").trim()
    }

    /** (heading or null) to (body) pairs, split at markdown headings. */
    private fun sections(text: String): List<Pair<String?, String>> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String?, String>>()
        var heading: String? = null
        val body = StringBuilder()
        fun flush() { if (body.isNotBlank() || heading != null) out += heading to body.toString().trim(); body.clear() }
        for (line in text.lines()) {
            val m = HEADING.matchEntire(line.trim())
            if (m != null) { flush(); heading = m.groupValues[1].trim() } else body.appendLine(line)
        }
        flush()
        return out.filter { (h, b) -> b.isNotBlank() || h != null }
    }

    private val HEADING = Regex("""^#{1,6}\s+(.+)$""")

    private fun pack(sections: List<Pair<String?, String>>, partLabel: String): List<ChunkDraft> {
        val out = ArrayList<ChunkDraft>()
        var n = 0
        for ((heading, body) in sections) {
            val text = if (heading != null) "$heading\n$body".trim() else body
            if (text.isBlank()) continue
            val pieces = split(text)
            for ((idx, piece) in pieces.withIndex()) {
                n++
                val where = when {
                    heading != null && pieces.size == 1 -> "section: $heading"
                    heading != null -> "section: $heading (${idx + 1} of ${pieces.size})"
                    else -> "$partLabel $n"
                }
                out += ChunkDraft(where, piece)
            }
        }
        return out
    }

    /** Paragraphs packed up to [MAX_CHARS]; a single paragraph longer than that is cut at the last space before the limit. */
    internal fun split(text: String): List<String> {
        val pieces = ArrayList<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotBlank()) pieces += current.toString().trim(); current.clear() }
        for (para in text.split(Regex("\n\\s*\n"))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (p.length > MAX_CHARS) {
                flush()
                var rest = p
                while (rest.length > MAX_CHARS) {
                    val cut = rest.lastIndexOf(' ', MAX_CHARS).takeIf { it > MAX_CHARS / 2 } ?: MAX_CHARS
                    pieces += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) pieces += rest
                continue
            }
            if (current.isNotEmpty() && current.length + 2 + p.length > MAX_CHARS) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(p)
        }
        flush()
        return pieces
    }
}
