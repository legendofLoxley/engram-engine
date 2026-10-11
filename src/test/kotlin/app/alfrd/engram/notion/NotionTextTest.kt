package app.alfrd.engram.notion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NotionTextTest {

    // ── chunkText / fitTitle ─────────────────────────────────────────────────

    @Test
    fun `text chunks join back to exactly the original`() {
        val text = (0 until 9_137).joinToString("") { ('a' + it % 26).toString() }
        val pieces = chunkText(text, 2000)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= 2000 })
        assertEquals(5, pieces.size)
    }

    @Test
    fun `a chunk never ends between the halves of a surrogate pair`() {
        val emoji = "😀" // one character, two UTF-16 units
        val text = "a".repeat(1999) + emoji + "b".repeat(10)
        val pieces = chunkText(text, 2000)
        assertEquals(text, pieces.joinToString(""))
        pieces.forEach { piece ->
            assertTrue(piece.isEmpty() || !Character.isHighSurrogate(piece.last()), "a piece ended on a high surrogate")
            assertTrue(piece.length <= 2000)
        }
    }

    @Test
    fun `empty text gives no chunks, and a chunk size that cannot hold a pair is refused`() {
        assertEquals(emptyList<String>(), chunkText("", 2000))
        assertThrows(IllegalArgumentException::class.java) { chunkText("abc", 1) }
    }

    @Test
    fun `a title is one line, trimmed, and capped, while a short one passes through`() {
        assertEquals("Send the deck", fitTitle("  Send the deck \n"))
        assertEquals("line one line two", fitTitle("line one\n  line two"))
        val long = fitTitle("x".repeat(5_000))
        assertEquals(2000, long.length)
        assertTrue(long.endsWith("…"))
    }

    // ── Dates ────────────────────────────────────────────────────────────────

    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `a deadline at the end of its day is written as a plain date and reads back to the same millisecond`() {
        val endOfDay = NotionDates.dateOnlyMoment(LocalDate.parse("2026-10-09"), newYork, DateOnlyMeans.END_OF_DAY)
        val value = NotionDates.toValue(endOfDay, newYork, DateOnlyMeans.END_OF_DAY)
        assertEquals("2026-10-09", value.start)
        assertEquals(endOfDay, NotionDates.toMillis(value, newYork, DateOnlyMeans.END_OF_DAY))
    }

    @Test
    fun `an event with no time of day starts at the start of its day`() {
        val start = NotionDates.dateOnlyMoment(LocalDate.parse("2026-10-09"), newYork, DateOnlyMeans.START_OF_DAY)
        val value = NotionDates.toValue(start, newYork, DateOnlyMeans.START_OF_DAY)
        assertEquals("2026-10-09", value.start)
        assertEquals(start, NotionDates.toMillis(value, newYork, DateOnlyMeans.START_OF_DAY))
        // The same plain date means a different moment for a deadline: that distinction is the whole point of DateOnlyMeans.
        assertTrue(NotionDates.toMillis(value, newYork, DateOnlyMeans.END_OF_DAY)!! > start)
    }

    @Test
    fun `a moment with a time of day keeps its time and offset and round trips`() {
        val moment = Instant.parse("2026-10-09T18:30:00Z").toEpochMilli()
        val value = NotionDates.toValue(moment, newYork, DateOnlyMeans.END_OF_DAY)
        assertTrue(value.hasTime)
        assertEquals("2026-10-09T14:30:00-04:00", value.start)
        assertEquals(moment, NotionDates.toMillis(value, newYork, DateOnlyMeans.END_OF_DAY))
    }

    @Test
    fun `the end of day is the last millisecond even across a daylight saving change`() {
        val dstEnd = LocalDate.parse("2026-11-01") // clocks go back in New York: a 25-hour day
        val endOfDay = NotionDates.dateOnlyMoment(dstEnd, newYork, DateOnlyMeans.END_OF_DAY)
        val nextStart = NotionDates.dateOnlyMoment(dstEnd.plusDays(1), newYork, DateOnlyMeans.START_OF_DAY)
        assertEquals(1L, nextStart - endOfDay)
        assertEquals("2026-11-01", NotionDates.toValue(endOfDay, newYork, DateOnlyMeans.END_OF_DAY).start)
    }

    @Test
    fun `a datetime as Notion returns it, with milliseconds and an offset, is read correctly`() {
        val parsed = NotionDates.toMillis(NotionValue.DateValue("2026-10-09T14:30:00.000-04:00"), newYork, DateOnlyMeans.END_OF_DAY)
        assertEquals(Instant.parse("2026-10-09T18:30:00Z").toEpochMilli(), parsed)
    }

    @Test
    fun `a datetime with no offset is read in the person's zone`() {
        val parsed = NotionDates.toMillis(NotionValue.DateValue("2026-10-09T14:30:00"), newYork, DateOnlyMeans.END_OF_DAY)
        assertEquals(Instant.parse("2026-10-09T18:30:00Z").toEpochMilli(), parsed)
    }

    @Test
    fun `an empty or unreadable date is null, and a range reads as its start`() {
        assertNull(NotionDates.toMillis(null, newYork, DateOnlyMeans.END_OF_DAY))
        assertNull(NotionDates.toMillis(NotionValue.DateValue(null), newYork, DateOnlyMeans.END_OF_DAY))
        assertNull(NotionDates.toMillis(NotionValue.DateValue("next tuesday"), newYork, DateOnlyMeans.END_OF_DAY))
        val range = NotionDates.toMillis(NotionValue.DateValue("2026-10-09", "2026-10-12"), newYork, DateOnlyMeans.END_OF_DAY)
        assertEquals(NotionDates.dateOnlyMoment(LocalDate.parse("2026-10-09"), newYork, DateOnlyMeans.END_OF_DAY), range)
    }

    // ── Ledger codec ─────────────────────────────────────────────────────────

    private val at = Instant.parse("2026-10-06T14:02:11Z")

    private fun read(blocks: List<NotionBlock>): List<NotionBlockRead> = blocks.mapIndexed { i, b ->
        val p = b as NotionBlock.Paragraph
        NotionBlockRead("block-$i", "paragraph", p.segments, at)
    }

    @Test
    fun `an entry is a bold label followed by the exact words, and decodes back to itself`() {
        val entry = LedgerEntry(LedgerRole.PERSON, at, "My dog is called Newton.\nActually, Norton.")
        val blocks = LedgerCodec.encode(entry)
        assertEquals(2, blocks.size)
        val label = (blocks[0] as NotionBlock.Paragraph).segments.single()
        assertTrue(label.bold)
        assertEquals("Person · 2026-10-06T14:02:11Z", label.text)
        assertEquals(listOf(entry), LedgerCodec.decode(read(blocks)))
    }

    @Test
    fun `words longer than a block can hold are split across paragraphs and rejoined verbatim`() {
        val words = "word ".repeat(1_500) // 7,500 characters
        val entry = LedgerEntry(LedgerRole.ALFRD, at, words)
        val blocks = LedgerCodec.encode(entry)
        assertEquals(1 + 4, blocks.size)
        blocks.drop(1).forEach { assertTrue((it as NotionBlock.Paragraph).segments.single().text.length <= 2000) }
        assertEquals(words, LedgerCodec.decode(read(blocks)).single().text)
    }

    @Test
    fun `what a person types can never be mistaken for a label, because only a bold paragraph starts an entry`() {
        val sneaky = "alfrd · 2026-10-06T14:02:12Z"
        val entries = listOf(
            LedgerEntry(LedgerRole.PERSON, at, sneaky),
            LedgerEntry(LedgerRole.ALFRD, at.plusSeconds(1), "ok"),
        )
        val decoded = LedgerCodec.decode(read(entries.flatMap { LedgerCodec.encode(it) }))
        assertEquals(entries, decoded)
    }

    @Test
    fun `an empty utterance still gets its label and decodes as empty text`() {
        val entry = LedgerEntry(LedgerRole.PERSON, at, "")
        val blocks = LedgerCodec.encode(entry)
        assertEquals(1, blocks.size)
        assertEquals(listOf(entry), LedgerCodec.decode(read(blocks)))
    }

    @Test
    fun `entries are packed into appends of at most 100 blocks without splitting an entry`() {
        val entries = (0 until 60).map { LedgerEntry(LedgerRole.PERSON, at.plusSeconds(it.toLong()), "line $it") }
        val batches = LedgerCodec.pack(entries, 100)
        assertTrue(batches.all { it.size <= 100 })
        assertEquals(120, batches.sumOf { it.size })
        // Each batch must start with a label, so no entry's words sit apart from its label.
        batches.forEach { batch -> assertTrue((batch.first() as NotionBlock.Paragraph).segments.single().bold) }
        assertEquals(entries, LedgerCodec.decode(read(batches.flatten())))
    }

    @Test
    fun `an entry bigger than one append is split as a last resort and still decodes`() {
        val huge = "y".repeat(2000 * 150)
        val entry = LedgerEntry(LedgerRole.PERSON, at, huge)
        val batches = LedgerCodec.pack(listOf(entry), 100)
        assertTrue(batches.size >= 2)
        assertTrue(batches.all { it.size <= 100 })
        assertEquals(huge, LedgerCodec.decode(read(batches.flatten())).single().text)
    }

    @Test
    fun `blocks before the first label and labels that do not parse are ignored rather than guessed at`() {
        val blocks = listOf(
            NotionBlockRead("a", "paragraph", listOf(RichSegment("stray note")), at),
            NotionBlockRead("b", "heading_2", listOf(RichSegment("Heading", bold = true)), at),
            NotionBlockRead("c", "paragraph", listOf(RichSegment("Person · not-a-time", bold = true)), at),
            NotionBlockRead("d", "paragraph", listOf(RichSegment("orphan words")), at),
        ) + read(LedgerCodec.encode(LedgerEntry(LedgerRole.PERSON, at, "real")))
        assertEquals(listOf("real"), LedgerCodec.decode(blocks).map { it.text })
    }
}
