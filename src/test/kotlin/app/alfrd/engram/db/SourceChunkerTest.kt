package app.alfrd.engram.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceChunkerTest {

    @Test
    fun `a document is cut at its headings and each chunk says where it sits`() {
        val chunks = SourceChunker.chunkDocument("# Scope\nWhat is in.\n\n## Risks\nSupplier delay.\n\nBudget slips.")
        assertEquals(listOf("section: Scope", "section: Risks"), chunks.map { it.location })
        assertTrue(chunks[0].text.contains("What is in."))
        assertTrue(chunks[1].text.contains("Supplier delay.") && chunks[1].text.contains("Budget slips."))
    }

    @Test
    fun `a document without headings is cut into numbered parts no longer than the limit, at paragraph edges`() {
        val para = "word ".repeat(100).trim() // 499 chars
        val chunks = SourceChunker.chunkDocument(List(10) { para }.joinToString("\n\n"))
        assertTrue(chunks.size > 1)
        assertEquals((1..chunks.size).map { "part $it" }, chunks.map { it.location })
        assertTrue(chunks.all { it.text.length <= SourceChunker.MAX_CHARS })
        assertTrue(chunks.all { it.text.split("\n\n").all { p -> p == para } }, "no paragraph was cut when none was too long")
    }

    @Test
    fun `one paragraph longer than the limit is cut at a space, and nothing is lost`() {
        val long = (1..800).joinToString(" ") { "w$it" }
        val chunks = SourceChunker.chunkDocument(long)
        assertTrue(chunks.size > 1 && chunks.all { it.text.length <= SourceChunker.MAX_CHARS })
        assertEquals(long, chunks.joinToString(" ") { it.text })
    }

    @Test
    fun `the content hash ignores line endings and surrounding blank space, and nothing else`() {
        val a = SourceChunker.contentHash("Line one\nLine two\n")
        assertEquals(a, SourceChunker.contentHash("\r\nLine one\r\nLine two  \r\n\r\n".trimEnd() + "\n"))
        assertEquals(64, a.length)
        assertNotEquals(a, SourceChunker.contentHash("Line one\nLine 2\n"))
    }

    @Test
    fun `quoted reply history is dropped from an email and what the sender wrote stays`() {
        val own = "Yes, Thursday works.\n\nI will bring the figures."
        val bodies = listOf(
            "$own\n\nOn Tue, 3 Mar 2026 at 10:14, Ana Silva <ana@example.org> wrote:\n> Can we meet Thursday?\n> Let me know.",
            "$own\n\nOn Tue, 3 Mar 2026 at 10:14, Ana Silva <ana@example.org>\nwrote:\n> Can we meet Thursday?",
            "$own\n\n-----Original Message-----\nFrom: Ana\nSent: Tuesday\nSubject: Thursday\n\nCan we meet Thursday?",
            "$own\n\nFrom: Ana Silva <ana@example.org>\nSent: Tuesday, March 3, 2026 10:14 AM\nTo: Jo\nSubject: Thursday\n\nCan we meet Thursday?",
            "> Can we meet Thursday?\n$own",
        )
        for (body in bodies) {
            val kept = SourceChunker.stripQuotedReplies(body)
            assertEquals(own, kept, "for:\n$body")
        }
        val chunks = SourceChunker.chunkEmail(bodies[0], "message 1")
        assertEquals(1, chunks.size)
        assertEquals("message 1, part 1", chunks[0].location)
        assertFalse(chunks[0].text.contains("meet Thursday"))
    }

    @Test
    fun `a From line in the middle of a message that is not a header block is kept`() {
        val body = "Notes for the team.\n\nFrom: the supplier we learned that prices rose.\nSo plan for it."
        assertEquals(body, SourceChunker.stripQuotedReplies(body))
    }
}
