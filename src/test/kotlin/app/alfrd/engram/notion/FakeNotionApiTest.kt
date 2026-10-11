package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** The fake is what every later adapter test stands on, so its own behavior is pinned here: where it is strict, Notion is. */
class FakeNotionApiTest {

    private lateinit var notion: FakeNotionApi
    private lateinit var t: FakeTemplate

    private val containers get() = t.sources.containers
    private val items get() = t.sources.items
    private val sessions get() = t.sources.sessions

    @BeforeEach
    fun setUp() {
        notion = FakeNotionApi()
        t = notion.installAlfrdTemplate()
    }

    private fun container(name: String, kind: String = "Project", extra: Map<String, NotionValue> = emptyMap()) = runBlocking {
        notion.createPage(containers, mapOf("Name" to NotionValue.Title(name), "Kind" to NotionValue.Select(kind)) + extra)
    }

    private fun item(title: String, kind: String = "Task", extra: Map<String, NotionValue> = emptyMap()) = runBlocking {
        notion.createPage(items, mapOf("Item" to NotionValue.Title(title), "Kind" to NotionValue.Select(kind)) + extra)
    }

    private fun apiError(block: suspend () -> Unit): NotionApiException = assertThrows(NotionApiException::class.java) { runBlocking { block() } }

    // ── Strict writes ────────────────────────────────────────────────────────

    @Test
    fun `an unknown property is rejected the way Notion rejects it`() {
        val e = apiError { notion.createPage(items, mapOf("Item" to NotionValue.Title("x"), "Nope" to NotionValue.Checkbox(true))) }
        assertEquals(400, e.status)
        assertEquals("validation_error", e.code)
    }

    @Test
    fun `a select option that does not exist is rejected, so a typo in an option name fails a test`() {
        val e = apiError { notion.createPage(items, mapOf("Item" to NotionValue.Title("x"), "Status" to NotionValue.Select("Done"))) }
        assertEquals(400, e.status)
    }

    @Test
    fun `a value of the wrong type for a property is rejected`() {
        apiError { notion.createPage(items, mapOf("Item" to NotionValue.Title("x"), "Tentative" to NotionValue.RichText("yes"))) }
    }

    @Test
    fun `a relation to a page of the wrong table is rejected`() {
        val someContainer = container("Demo")
        apiError { notion.createPage(items, mapOf("Item" to NotionValue.Title("x"), "Session" to NotionValue.Relation(listOf(someContainer.id)))) }
    }

    @Test
    fun `a missing page is a 404`() {
        assertEquals(404, apiError { notion.retrievePage("00000000-0000-0000-0000-000000000000") }.status)
    }

    // ── Dual relations ───────────────────────────────────────────────────────

    @Test
    fun `filing an item into a container shows up on both sides, and moving it takes it off the first`() = runBlocking {
        val demo = container("Demo")
        val dinner = container("Dinner")
        val task = item("Send the deck", extra = mapOf("Container" to NotionValue.Relation(listOf(demo.id))))

        assertEquals(listOf(task.id), notion.page(demo.id).relation("Items"))

        notion.updatePage(task.id, mapOf("Container" to NotionValue.Relation(listOf(dinner.id))))
        assertEquals(emptyList<String>(), notion.page(demo.id).relation("Items"))
        assertEquals(listOf(task.id), notion.page(dinner.id).relation("Items"))
    }

    @Test
    fun `setting Supersedes on the new row fills Superseded by on the old one, as Notion does`() = runBlocking {
        val old = item("Delivery is Friday")
        val new = item("Delivery is Monday", extra = mapOf("Supersedes" to NotionValue.Relation(listOf(old.id))))
        assertEquals(listOf(new.id), notion.page(old.id).relation("Superseded by"))
        assertEquals(emptyList<String>(), notion.page(new.id).relation("Superseded by"))
    }

    @Test
    fun `a self relation pair like Parent and Children stays in step`() = runBlocking {
        val domain = container("Work", kind = "Domain")
        val project = container("Demo", extra = mapOf("Parent" to NotionValue.Relation(listOf(domain.id))))
        assertEquals(listOf(project.id), notion.page(domain.id).relation("Children"))
    }

    // ── Time ─────────────────────────────────────────────────────────────────

    @Test
    fun `last edited time is reported at minute precision, as Notion reports it`() = runBlocking {
        notion.now = Instant.parse("2026-10-06T12:00:41Z")
        val created = item("x")
        assertEquals(Instant.parse("2026-10-06T12:00:00Z"), created.lastEditedTime)
        assertEquals(Instant.parse("2026-10-06T12:00:41Z"), created.createdTime)
    }

    @Test
    fun `a write that changes nothing does not move last edited time, and one that does, does`() = runBlocking {
        val task = item("x")
        val first = task.lastEditedTime
        notion.now = Instant.parse("2026-10-06T12:30:00Z")
        notion.updatePage(task.id, mapOf("Tentative" to NotionValue.Checkbox(false)))
        assertEquals(first, notion.page(task.id).lastEditedTime)
        notion.updatePage(task.id, mapOf("Tentative" to NotionValue.Checkbox(true)))
        assertEquals(Instant.parse("2026-10-06T12:30:00Z"), notion.page(task.id).lastEditedTime)
    }

    @Test
    fun `filing an item also counts as an edit of the container it was filed into`() = runBlocking {
        val demo = container("Demo")
        notion.now = Instant.parse("2026-10-06T13:00:00Z")
        item("Send the deck", extra = mapOf("Container" to NotionValue.Relation(listOf(demo.id))))
        assertEquals(Instant.parse("2026-10-06T13:00:00Z"), notion.page(demo.id).lastEditedTime)
    }

    @Test
    fun `an on-or-after filter on last edited time includes the boundary minute`() = runBlocking {
        notion.now = Instant.parse("2026-10-06T12:00:30Z")
        val a = item("a")
        notion.now = Instant.parse("2026-10-06T12:05:10Z")
        val b = item("b")
        val found = notion.query(items, NotionFilter.LastEditedOnOrAfter("2026-10-06T12:05:00Z")).pages.map { it.id }
        assertEquals(listOf(b.id), found)
        val both = notion.query(items, NotionFilter.LastEditedOnOrAfter("2026-10-06T12:00:00Z")).pages.map { it.id }.toSet()
        assertEquals(setOf(a.id, b.id), both)
    }

    // ── Query ────────────────────────────────────────────────────────────────

    @Test
    fun `query pages through every row by cursor and a page larger than 100 is refused`() = runBlocking {
        repeat(25) { item("task $it") }
        val seen = ArrayList<String>()
        var cursor: String? = null
        var pagesRead = 0
        do {
            val page = notion.query(items, startCursor = cursor, pageSize = 10)
            seen += page.pages.map { it.id }
            cursor = page.nextCursor
            pagesRead++
        } while (cursor != null)
        assertEquals(25, seen.toSet().size)
        assertEquals(3, pagesRead)
        assertEquals(400, apiError { notion.query(items, pageSize = 101) }.status)
    }

    @Test
    fun `with no sort the newest row comes first, so nothing can rely on insertion order`() = runBlocking {
        notion.now = Instant.parse("2026-10-06T12:00:00Z"); val first = item("first")
        notion.now = Instant.parse("2026-10-06T12:01:00Z"); val second = item("second")
        assertEquals(listOf(second.id, first.id), notion.query(items).pages.map { it.id })
    }

    @Test
    fun `select checkbox date relation and text filters each match what they say`() = runBlocking {
        val demo = container("Demo")
        val due = item("Send the deck", extra = mapOf(
            "Container" to NotionValue.Relation(listOf(demo.id)),
            "Date" to NotionValue.DateValue("2026-10-09"),
            "Status" to NotionValue.Select("Open"),
            "Tentative" to NotionValue.Checkbox(true),
            "Op key" to NotionValue.RichText("req-1:0"),
        ))
        val undated = item("Water the plants", extra = mapOf("Status" to NotionValue.Select("Resolved")))

        fun ids(filter: NotionFilter) = runBlocking { notion.query(items, filter).pages.map { it.id } }

        assertEquals(listOf(due.id), ids(NotionFilter.SelectEquals("Status", "Open")))
        assertEquals(listOf(undated.id), ids(NotionFilter.SelectDoesNotEqual("Status", "Open")))
        assertEquals(listOf(due.id), ids(NotionFilter.CheckboxEquals("Tentative", true)))
        assertEquals(listOf(due.id), ids(NotionFilter.DateOnOrBefore("Date", "2026-10-09")))
        assertEquals(emptyList<String>(), ids(NotionFilter.DateOnOrBefore("Date", "2026-10-08")))
        assertEquals(listOf(due.id), ids(NotionFilter.DateOnOrAfter("Date", "2026-10-09T00:00:00Z")))
        assertEquals(listOf(undated.id), ids(NotionFilter.DateIsEmpty("Date")))
        assertEquals(listOf(due.id), ids(NotionFilter.RelationContains("Container", demo.id)))
        assertEquals(listOf(undated.id), ids(NotionFilter.RelationIsEmpty("Container")))
        assertEquals(listOf(due.id), ids(NotionFilter.TextEquals("Op key", "req-1:0")))
        assertEquals(emptyList<String>(), ids(NotionFilter.TextEquals("Op key", "REQ-1:0")))
        assertEquals(listOf(due.id), ids(NotionFilter.TextContains("Item", "DECK", NotionFilter.TextKind.TITLE)))
        assertEquals(listOf(due.id), ids(NotionFilter.And(listOf(NotionFilter.SelectEquals("Status", "Open"), NotionFilter.CheckboxEquals("Tentative", true)))))
        assertEquals(setOf(due.id, undated.id), notion.query(items, NotionFilter.Or(listOf(NotionFilter.SelectEquals("Status", "Open"), NotionFilter.SelectEquals("Status", "Resolved")))).pages.map { it.id }.toSet())
    }

    @Test
    fun `a filter on a property that does not exist is an error, not an empty result`() {
        assertEquals(400, apiError { notion.query(items, NotionFilter.SelectEquals("Nope", "x")) }.status)
    }

    @Test
    fun `sorts by property and by timestamp work in both directions`() = runBlocking {
        val b = item("b"); val a = item("a"); val c = item("c")
        assertEquals(listOf(a.id, b.id, c.id), notion.query(items, sorts = listOf(NotionSort.ByProperty("Item"))).pages.map { it.id })
        assertEquals(listOf(c.id, b.id, a.id), notion.query(items, sorts = listOf(NotionSort.ByProperty("Item", ascending = false))).pages.map { it.id })
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    @Test
    fun `appending more than 100 blocks, or a segment over 2000 characters, is rejected`() = runBlocking {
        val session = notion.createPage(sessions, mapOf("Session" to NotionValue.Title("today")))
        val tooMany = List(101) { NotionBlock.Paragraph(listOf(RichSegment("x"))) }
        assertEquals(400, apiError { notion.appendBlocks(session.id, tooMany) }.status)
        val tooLong = listOf(NotionBlock.Paragraph(listOf(RichSegment("y".repeat(2001)))))
        assertEquals(400, apiError { notion.appendBlocks(session.id, tooLong) }.status)
        notion.appendBlocks(session.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("y".repeat(2000))))))
        assertEquals(1, notion.listBlocks(session.id).blocks.size)
    }

    @Test
    fun `blocks come back in order, page by page, and appending moves the page's last edited time`() = runBlocking {
        val session = notion.createPage(sessions, mapOf("Session" to NotionValue.Title("today")))
        notion.now = Instant.parse("2026-10-06T14:00:00Z")
        notion.appendBlocks(session.id, List(30) { NotionBlock.Paragraph(listOf(RichSegment("line $it"))) })
        assertEquals(Instant.parse("2026-10-06T14:00:00Z"), notion.page(session.id).lastEditedTime)

        val first = notion.listBlocks(session.id, pageSize = 20)
        assertEquals(20, first.blocks.size)
        assertEquals("line 0", first.blocks.first().plainText)
        val second = notion.listBlocks(session.id, startCursor = first.nextCursor, pageSize = 20)
        assertEquals(10, second.blocks.size)
        assertEquals("line 29", second.blocks.last().plainText)
        assertNull(second.nextCursor)
    }

    // ── Fault injection and the request log ─────────────────────────────────

    @Test
    fun `an injected fault is thrown once and the call is still recorded`() = runBlocking {
        notion.failNext("query", NotionRateLimitedException(1000))
        assertThrows(NotionRateLimitedException::class.java) { runBlocking { notion.query(items) } }
        assertEquals(0, notion.query(items).pages.size)
        assertEquals(2, notion.requestCount("query"))
    }

    @Test
    fun `the persona page is a plain page with blocks and no properties to update`() = runBlocking {
        assertEquals(2, notion.listBlocks(t.sources.personaPageId).blocks.size)
        assertEquals(400, apiError { notion.updatePage(t.sources.personaPageId, mapOf("Item" to NotionValue.Title("x"))) }.status)
        assertFalse(notion.page(t.sources.personaPageId).dataSourceId != null)
        assertTrue(notion.rows(items).isEmpty())
    }
}
