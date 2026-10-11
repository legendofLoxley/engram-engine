package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The fake's late fault ("the write landed and the response was lost") is what makes a create ambiguous in tests, so it has to do exactly that. */
class FakeNotionFaultsTest {

    private val boom = NotionApiException(502, "bad_gateway", "lost response")

    @Test
    fun `a create that fails after applying has still made its row`() {
        val fake = FakeNotionApi()
        val worker = fake.installWorkerTemplate()
        fake.failAfterApplying("createPage", boom)

        assertThrows(NotionApiException::class.java) {
            runBlocking { fake.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("landed"))) }
        }

        assertEquals(listOf("landed"), fake.rows(worker.cycles).map { it.title("Cycle") })
    }

    @Test
    fun `an update that fails after applying has still changed the row`() = runBlocking {
        val fake = FakeNotionApi()
        val worker = fake.installWorkerTemplate()
        val cycle = fake.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("c")))
        fake.failAfterApplying("updatePage", boom)

        assertThrows(NotionApiException::class.java) {
            runBlocking { fake.updatePage(cycle.id, mapOf("Status" to NotionValue.Select("Running"))) }
        }

        assertEquals("Running", fake.page(cycle.id).select("Status"))
    }

    @Test
    fun `an append that fails after applying has still added its block`() = runBlocking {
        val fake = FakeNotionApi()
        val worker = fake.installWorkerTemplate()
        val cycle = fake.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("c")))
        fake.failAfterApplying("appendBlocks", boom)

        assertThrows(NotionApiException::class.java) {
            runBlocking { fake.appendBlocks(cycle.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("kept"))))) }
        }

        assertEquals(listOf("kept"), fake.blockTexts(cycle.id))
    }

    @Test
    fun `a late fault fires the requested number of times and then stops`() = runBlocking {
        val fake = FakeNotionApi()
        val worker = fake.installWorkerTemplate()
        fake.failAfterApplying("createPage", boom, times = 2)

        repeat(2) { i ->
            assertThrows(NotionApiException::class.java) {
                runBlocking { fake.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("c$i"))) }
            }
        }
        fake.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("c2")))

        assertEquals(3, fake.rows(worker.cycles).size)
    }

    @Test
    fun `an ordinary fault, unlike a late one, applies nothing`() {
        val fake = FakeNotionApi()
        val worker = fake.installWorkerTemplate()
        fake.failNext("createPage", boom)

        assertThrows(NotionApiException::class.java) {
            runBlocking { fake.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("never landed"))) }
        }

        assertEquals(0, fake.rows(worker.cycles).size)
    }

    @Test
    fun `an operation with nothing to apply cannot be given a late fault`() {
        val fake = FakeNotionApi()
        assertThrows(IllegalArgumentException::class.java) { fake.failAfterApplying("query", boom) }
        assertThrows(IllegalArgumentException::class.java) { fake.failAfterApplying("retrievePage", boom) }
    }
}
