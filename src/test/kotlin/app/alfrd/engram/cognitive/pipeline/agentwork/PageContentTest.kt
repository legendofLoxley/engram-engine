package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.FakeBlock
import app.alfrd.engram.notion.NotionApiException
import app.alfrd.engram.notion.NotionGuardViolation
import app.alfrd.engram.notion.NotionValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reading the text of a page's blocks (one level of children, bounded), and staging it for a task within the limits. */
class PageContentTest {

    private val f = AgentWorkFixture()
    private val t = f.template

    /** One guarded client for the whole test: the guard learns a page when it is returned, so the scope read and the staging must share it, as they do in the processor. */
    private val api = f.guarded()

    /** A row of the memory Items table with these blocks, known to the guard the way a scope read would make it. */
    private fun itemPage(title: String, vararg blocks: FakeBlock): String = runBlocking {
        val row = f.fake.createPage(t.items, mapOf("Item" to NotionValue.Title(title), "Said" to NotionValue.RichText("said: $title")))
        f.fake.now = f.fake.now.plusSeconds(1)
        f.fake.setBlocks(row.id, blocks.toList())
        row.id
    }

    private fun contentOf(pageId: String, limits: ContentLimits = ContentLimits(), maxChars: Int = limits.perPageChars): PageContent = runBlocking {
        api.query(t.items) // the guard has now seen the rows
        PageContentReader(api, limits).read(pageId, maxChars)
    }

    // ── Block types ──────────────────────────────────────────────────────────

    @Test
    fun `paragraphs, headings, lists, to-dos, callouts, quotes and toggles are read as plain text`() {
        val id = itemPage(
            "Plan",
            FakeBlock("heading_1", "Plan"),
            FakeBlock("heading_2", "Week one"),
            FakeBlock("heading_3", "Monday"),
            FakeBlock("paragraph", "Start with the deck."),
            FakeBlock("bulleted_list_item", "ask Priya"),
            FakeBlock("numbered_list_item", "first"),
            FakeBlock("numbered_list_item", "second"),
            FakeBlock("to_do", "send deck", checked = true),
            FakeBlock("to_do", "book room", checked = false),
            FakeBlock("callout", "mind the date"),
            FakeBlock("quote", "less is more"),
            FakeBlock("toggle", "Details"),
        )
        val content = contentOf(id)

        assertEquals(
            listOf(
                "# Plan", "## Week one", "### Monday", "Start with the deck.", "- ask Priya", "1. first", "2. second",
                "[x] send deck", "[ ] book room", "> (callout) mind the date", "> less is more", "▸ Details",
            ),
            content.text.lines(),
        )
        assertFalse(content.truncated)
        assertEquals(12, content.blocksRead)
        assertEquals(0, content.blocksSkipped)
    }

    @Test
    fun `numbering restarts after a block that is not a numbered item`() {
        val id = itemPage("N", FakeBlock("numbered_list_item", "a"), FakeBlock("numbered_list_item", "b"), FakeBlock("paragraph", "break"), FakeBlock("numbered_list_item", "c"))
        assertEquals(listOf("1. a", "2. b", "break", "1. c"), contentOf(id).text.lines())
    }

    @Test
    fun `one level of children is read under a toggle, a list item and a callout, indented`() {
        val id = itemPage(
            "Nested",
            FakeBlock("toggle", "Rules", children = listOf(FakeBlock("paragraph", "Never loud."), FakeBlock("bulleted_list_item", "no puns"))),
            FakeBlock("bulleted_list_item", "outer", children = listOf(FakeBlock("to_do", "inner", checked = true))),
            FakeBlock("callout", "Note", children = listOf(FakeBlock("paragraph", "inside the callout"))),
        )
        assertEquals(
            listOf("▸ Rules", "  Never loud.", "  - no puns", "- outer", "  [x] inner", "> (callout) Note", "  inside the callout"),
            contentOf(id).text.lines(),
        )
    }

    @Test
    fun `nothing deeper than one level is read, and it is counted as skipped`() {
        val id = itemPage(
            "Deep",
            FakeBlock("toggle", "Top", children = listOf(FakeBlock("toggle", "Middle", children = listOf(FakeBlock("paragraph", "TOO DEEP"))))),
        )
        val content = contentOf(id)
        assertEquals(listOf("▸ Top", "  ▸ Middle"), content.text.lines())
        assertFalse(content.text.contains("TOO DEEP"))
        assertEquals(1, content.blocksSkipped)
    }

    @Test
    fun `blocks that carry no readable text here are counted as skipped, and empty text blocks are not`() {
        val id = itemPage("Mixed", FakeBlock("image"), FakeBlock("divider"), FakeBlock("paragraph", ""), FakeBlock("code", "val x = 1"), FakeBlock("paragraph", "kept"))
        val content = contentOf(id)
        assertEquals(listOf("kept"), content.text.lines())
        assertEquals(3, content.blocksSkipped, "image, divider and code are unsupported; the empty paragraph is simply empty")
    }

    @Test
    fun `an empty page reads as empty text, not as an error`() {
        val content = contentOf(itemPage("Empty"))
        assertEquals("", content.text)
        assertFalse(content.truncated)
    }

    // ── Limits, each reported ────────────────────────────────────────────────

    @Test
    fun `a page longer than the character limit is cut at a line and says so`() {
        val id = itemPage("Long", *(1..200).map { FakeBlock("paragraph", "paragraph number $it with some padding words") }.toTypedArray())
        val content = contentOf(id, maxChars = 500)

        assertTrue(content.truncated)
        assertTrue(content.text.endsWith("[… the rest of this page was not read: size limit]"), content.text.takeLast(80))
        val body = content.text.removeSuffix("\n[… the rest of this page was not read: size limit]")
        assertTrue(body.length <= 500, "kept ${body.length} characters")
        assertTrue(body.lines().all { it.startsWith("paragraph number ") && it.endsWith("padding words") }, "a line was cut in half")
    }

    @Test
    fun `a page with more blocks than the limit is cut, and says so`() {
        val id = itemPage("Many", *(1..30).map { FakeBlock("paragraph", "b$it") }.toTypedArray())
        val content = contentOf(id, ContentLimits(maxBlocksPerPage = 10))
        assertTrue(content.truncated)
        assertEquals(10, content.blocksRead)
        assertTrue(content.text.contains("b10") && !content.text.contains("b11"))
    }

    @Test
    fun `a page of more than one hundred blocks is read across pages of results`() {
        val id = itemPage("Huge", *(1..250).map { FakeBlock("paragraph", "p$it") }.toTypedArray())
        val before = f.fake.requestCount("listBlocks")
        val content = contentOf(id, ContentLimits(perPageChars = 100_000, maxBlocksPerPage = 1_000), maxChars = 100_000)
        assertEquals(250, content.blocksRead)
        assertEquals(3, f.fake.requestCount("listBlocks") - before)
        assertTrue(content.text.contains("p250") && !content.truncated)
    }

    @Test
    fun `only so many children are read under one block, and the cut is reported`() {
        val id = itemPage("Wide", FakeBlock("toggle", "Many", children = (1..20).map { FakeBlock("paragraph", "child $it") }))
        val content = contentOf(id, ContentLimits(maxChildrenPerBlock = 5))
        assertTrue(content.truncated)
        assertEquals(5, content.text.lines().count { it.startsWith("  child ") })
    }

    @Test
    fun `a child list Notion refuses is skipped and the rest of the page is still read`() {
        val id = itemPage("Flaky", FakeBlock("toggle", "Has children", children = listOf(FakeBlock("paragraph", "inside"))), FakeBlock("paragraph", "after"))
        runBlocking { api.query(t.items) }
        val reader = PageContentReader(object : app.alfrd.engram.notion.NotionApi by api {
            private var calls = 0
            override suspend fun listBlocks(parentBlockId: String, startCursor: String?, pageSize: Int): app.alfrd.engram.notion.BlockPage {
                if (++calls == 2) throw NotionApiException(500, "internal_server_error", "boom")
                return api.listBlocks(parentBlockId, startCursor, pageSize)
            }
        })
        val content = runBlocking { reader.read(id) }
        assertEquals(listOf("▸ Has children", "after"), content.text.lines())
        assertEquals(1, content.blocksSkipped)
    }

    // ── The guard stays in front ─────────────────────────────────────────────

    @Test
    fun `a page the guard has not let through cannot be read, whatever the reader is asked`() {
        val stranger = f.fake.createPlainPage(listOf("private"))
        assertThrows(NotionGuardViolation::class.java) { runBlocking { PageContentReader(api).read(stranger) } }
    }

    @Test
    fun `the Persona page is read through the allowlist, children included`() {
        f.fake.setBlocks(t.personaPageId, listOf(FakeBlock("toggle", "Voice", children = listOf(FakeBlock("paragraph", "plain and warm"))), FakeBlock("paragraph", "Be brief.")))
        val content = runBlocking { PageContentReader(api).read(t.personaPageId) }
        assertEquals(listOf("▸ Voice", "  plain and warm", "Be brief."), content.text.lines())
    }

    @Test
    fun `reading never writes`() {
        val id = itemPage("R", FakeBlock("paragraph", "x"))
        val mark = f.fake.requests.size
        contentOf(id)
        assertTrue(f.fake.requests.drop(mark).none { it.operation in setOf("createPage", "updatePage", "appendBlocks") })
    }

    // ── Staging for a task ───────────────────────────────────────────────────

    private fun scopeFor(vararg ids: String): ScopeContext = runBlocking {
        ScopeReader(api, f.tableSet).read(GoalScope.fromRichText(scopeOf("", *ids.map { pageMention(it, "page $it") }.toTypedArray())))
    }

    private fun stager(scope: ScopeContext, limits: ContentLimits = ContentLimits()) = TaskStager(scope, PageContentReader(api, limits), limits)
    private fun stage(stager: TaskStager, vararg links: String): StagedContext = runBlocking { stager.stage(links.toList()) }
    private fun url(id: String) = notionPageUrl(id)

    @Test
    fun `an in-scope page is staged with its properties and its text, and the trace says how much`() {
        val id = itemPage("Persona notes", FakeBlock("paragraph", "Jacob prefers short answers."))
        val staged = stage(stager(scopeFor(id)), url(id))

        val page = staged.pages.single()
        assertEquals("Persona notes", page.title)
        assertEquals("memory-items", page.source)
        assertTrue(page.text.startsWith("Properties:") && page.text.contains("Said: said: Persona notes") && page.text.contains("Jacob prefers short answers."), page.text)
        assertTrue(staged.problems.isEmpty())
        assertEquals(page.text.length, staged.traces.single().chars)
    }

    @Test
    fun `a link outside the goal's scope is reported and nothing at all is read for it`() {
        val inScope = itemPage("in scope", FakeBlock("paragraph", "fine"))
        val outside = itemPage("outside", FakeBlock("paragraph", "SECRET OUTSIDE TEXT"))
        val stager = stager(scopeFor(inScope))
        val before = f.fake.requestCount("listBlocks")

        val staged = stage(stager, url(outside), "https://example.org/spec", url(inScope))

        assertEquals(listOf(url(outside), "https://example.org/spec"), staged.outOfScope.map { it.link })
        assertEquals(listOf("in scope"), staged.pages.map { it.title })
        assertFalse(staged.pages.any { it.text.contains("SECRET OUTSIDE TEXT") })
        assertEquals(1, f.fake.requestCount("listBlocks") - before, "only the in-scope page's blocks were listed")
    }

    @Test
    fun `a mentioned database is staged as the digest of its rows`() {
        val a = f.source("Board memo")
        f.source("Audit")
        val scope = runBlocking { ScopeReader(api, f.tableSet).read(GoalScope.fromRichText(scopeOf("", f.sourcesMention))) }
        val staged = stage(stager(scope), url(f.tableSet.table("sources").databaseId!!))

        val page = staged.pages.single()
        assertEquals("database", page.source)
        assertTrue(page.text.contains("### [R1] Board memo") && page.text.contains("### [R2] Audit"), page.text)
        assertFalse(page.text.contains(url(a.id)), "rows are shown by handle, not by id")
        assertEquals(0, f.fake.requestCount("listBlocks"), "a database digest comes from the rows already read, not from page bodies")
    }

    @Test
    fun `one task is given no more than its share of page text, and the cut is reported`() {
        val limits = ContentLimits(perPageChars = 6_000, perTaskChars = 8_000)
        val a = itemPage("A", *(1..150).map { FakeBlock("paragraph", "alpha line $it with some filler text") }.toTypedArray())
        val b = itemPage("B", *(1..150).map { FakeBlock("paragraph", "beta line $it with some filler text") }.toTypedArray())
        val c = itemPage("C", FakeBlock("paragraph", "gamma"))
        val staged = stage(stager(scopeFor(a, b, c), limits), url(a), url(b), url(c))

        assertTrue(staged.pages.sumOf { it.text.length } <= 8_100, "staged ${staged.pages.sumOf { it.text.length }} characters")
        assertTrue(staged.pages.any { it.truncated })
        assertTrue(staged.problems.any { it is StagingProblem.OverBudget } || staged.pages.size < 3, "the page that did not fit is reported: ${staged.problems}")
    }

    @Test
    fun `once a cycle has read its share of page text, the next page is reported as not read`() {
        val limits = ContentLimits(perPageChars = 3_000, perTaskChars = 20_000, perCycleChars = 3_000)
        val ids = (1..2).map { n -> itemPage("P$n", *(1..120).map { FakeBlock("paragraph", "page $n line $it with filler words") }.toTypedArray()) }
        val stager = stager(scopeFor(*ids.toTypedArray()), limits)

        val first = stage(stager, url(ids[0]))
        val second = stage(stager, url(ids[1]))

        assertTrue(first.problems.isEmpty() && first.pages.size == 1 && first.pages.single().truncated, "the first page used the whole budget")
        assertTrue(second.pages.isEmpty() && second.problems.single() is StagingProblem.OverBudget, "the second got nothing: ${second.problems}")
        assertTrue(second.traces.single().note!!.contains("cycle content limit"))
    }

    @Test
    fun `a page read after most of the cycle's budget is spent gets only what is left`() {
        val limits = ContentLimits(perPageChars = 3_000, perTaskChars = 20_000, perCycleChars = 4_500)
        val ids = (1..2).map { n -> itemPage("P$n", *(1..120).map { FakeBlock("paragraph", "page $n line $it with filler words") }.toTypedArray()) }
        val stager = stager(scopeFor(*ids.toTypedArray()), limits)

        val first = stage(stager, url(ids[0]))
        val second = stage(stager, url(ids[1]))

        assertTrue(second.pages.single().truncated)
        assertTrue(second.pages.single().text.length < first.pages.single().text.length, "the second page got more than what was left")
    }

    @Test
    fun `a page read for one task is reused by the next at no cost and no request`() {
        val limits = ContentLimits(perPageChars = 3_000, perCycleChars = 3_500)
        val id = itemPage("Shared", *(1..80).map { FakeBlock("paragraph", "shared line $it padding padding") }.toTypedArray())
        val stager = stager(scopeFor(id), limits)

        val one = stage(stager, url(id))
        val before = f.fake.requestCount("listBlocks")
        val two = stage(stager, url(id))

        assertEquals(before, f.fake.requestCount("listBlocks"), "the page was read again")
        assertEquals(one.pages.single().text, two.pages.single().text)
        assertTrue(two.problems.isEmpty(), "a cached page must not be charged to the cycle's budget again")
    }

    @Test
    fun `a page Notion will not give is reported as unreadable, and the rest are still staged`() {
        val good = itemPage("good", FakeBlock("paragraph", "fine"))
        val bad = itemPage("bad", FakeBlock("paragraph", "never seen"))
        val stager = stager(scopeFor(good, bad))
        f.fake.failNext("listBlocks", NotionApiException(404, "object_not_found", "gone"))

        val staged = stage(stager, url(bad), url(good))

        assertEquals(listOf("good"), staged.pages.map { it.title })
        val problem = staged.problems.single() as StagingProblem.Unreadable
        assertEquals(url(bad), problem.link)
        assertTrue(problem.detail.contains("404"))
    }

    @Test
    fun `the same link twice is staged once, and a task with no links stages nothing`() {
        val id = itemPage("once", FakeBlock("paragraph", "x"))
        val stager = stager(scopeFor(id))
        assertEquals(1, stage(stager, url(id), url(id), " ${url(id)} ").pages.size)
        val none = stage(stager)
        assertTrue(none.pages.isEmpty() && none.problems.isEmpty())
    }
}
