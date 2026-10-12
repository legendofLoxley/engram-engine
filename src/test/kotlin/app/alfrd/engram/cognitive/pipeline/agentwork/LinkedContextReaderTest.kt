package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.NotionValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LinkedContextReaderTest {

    private val set = AgentWorkConfig.load("deploy/config/notion-agent-worker.json").tableSet
    private lateinit var fake: FakeNotionApi
    private lateinit var t: AgentWorkTemplate
    private lateinit var reader: LinkedContextReader

    /** The Sources database page as people paste it: a database id, not the data source id the client queries. */
    private val sourcesDatabaseUrl = "https://www.notion.so/6e9ea25de7334d899d7a52783509ff54"

    @BeforeEach
    fun setUp() {
        fake = FakeNotionApi()
        t = fake.installAgentWorkTemplate(set)
        reader = LinkedContextReader(AllowlistedNotionApi(fake, set), set)
    }

    private fun goal(vararg links: String) = Goal(
        pageId = "g", title = "PPS: read before lock", displayId = "GOAL-1", type = GoalType.WORK, status = GoalStatus.ACTIVE,
        intent = "", scope = "prose scope", metric = "", doneCriteria = "", targetLinksText = "", targetLinks = links.toList(),
        cycleBudget = 10, replanCap = 3, deadline = null, targetSpace = "PPS Evidence Ledger",
    )

    private fun source(title: String, type: String? = "Research Study", url: String? = null) = runBlocking {
        fake.createPage(
            t.sources,
            buildMap {
                put("Title", NotionValue.Title(title))
                type?.let { put("Type", NotionValue.Select(it)) }
                url?.let { put("URL", NotionValue.UrlValue(it)) }
            },
        )
    }

    private fun read(vararg links: String) = runBlocking { reader.read(goal(*links)) }

    // ── A database link lists the table's rows ───────────────────────────────

    @Test
    fun `a link to the Sources database lists its rows, even though only the data source id is queryable`() {
        source("Oct 6 Board memo", "PPS Document", "https://example.org/board-memo.pdf")
        source("SEGC equity audit", "Audit / State Report")

        val context = read(sourcesDatabaseUrl)

        assertEquals(listOf("Oct 6 Board memo", "SEGC equity audit"), context.rows.map { it.title })
        assertEquals(listOf(LinkOutcome.TableListed(sourcesDatabaseUrl, "sources", 2, truncated = false)), context.outcomes)
        val memo = context.rows.first()
        assertEquals("sources", memo.table)
        assertTrue(memo.facts.contains("Type: PPS Document"))
        assertTrue(memo.facts.contains("URL: https://example.org/board-memo.pdf"))
        assertEquals("https://example.org/board-memo.pdf", memo.externalUrl)
    }

    @Test
    fun `the rows listed, their urls, and the urls they carry are the links a plan may use`() {
        val memo = source("Oct 6 Board memo", url = "https://example.org/board-memo.pdf")
        val context = read(sourcesDatabaseUrl)

        assertTrue(context.allowed.allows(notionPageUrl(memo.id)))
        assertTrue(context.allowed.allows("https://app.notion.com/p/${memo.id.replace("-", "")}"))
        assertTrue(context.allowed.allows("https://www.notion.so/Board-memo-${memo.id.replace("-", "")}?pvs=4"))
        assertTrue(context.allowed.allows("https://example.org/board-memo.pdf"))
        assertTrue(context.allowed.allows("https://EXAMPLE.org/board-memo.pdf/"))
        assertTrue(context.allowed.allows(sourcesDatabaseUrl), "the table the goal linked is itself a link the goal made")
    }

    @Test
    fun `an allowed page id outside the path of the url does not make the link allowed`() {
        val memo = source("Oct 6 Board memo")
        val context = read(sourcesDatabaseUrl)
        val id = memo.id.replace("-", "")

        assertTrue(context.allowed.allows("https://www.notion.so/$id"))
        assertTrue(context.allowed.allows("https://www.notion.so/Board-memo-$id?pvs=4"))
        // The id is somewhere other than the path: none of these addresses the allowed page.
        assertFalse(context.allowed.allows("https://$id@www.notion.so/unrelated-page"), "user-info")
        assertFalse(context.allowed.allows("https://www.notion.so/unrelated-page#$id"), "fragment")
        assertFalse(context.allowed.allows("https://www.notion.so/unrelated-page?x=$id"), "query")
        assertFalse(context.allowed.allows("https://www.notion.so/unrelated-page?p=$id"), "peek parameter")
        // And a URL with user-info is refused even when its path is right.
        assertFalse(context.allowed.allows("https://user:pw@www.notion.so/$id"), "user-info with the right path")
    }

    @Test
    fun `a row the goal did not link, and a url nobody supplied, are not allowed`() {
        source("Listed", url = "https://example.org/listed.pdf")
        val context = read(sourcesDatabaseUrl)

        val unlisted = runBlocking { fake.createPage(t.claims, mapOf("Claim" to NotionValue.Title("a claim in another table"))) }
        assertFalse(context.allowed.allows(notionPageUrl(unlisted.id)))
        assertFalse(context.allowed.allows("https://example.org/other.pdf"))
        assertFalse(context.allowed.allows("https://example.org/listed.pdf.evil.example/"))
        assertFalse(context.allowed.allows("not a url"))
        assertFalse(context.allowed.allows(""))
        assertFalse(context.allowed.allows("ftp://example.org/listed.pdf"))
    }

    @Test
    fun `a table with more rows than the limit is cut short and says so`() {
        repeat(7) { source("Source $it") }
        val small = LinkedContextReader(AllowlistedNotionApi(fake, set), set, maxRowsPerTable = 5)
        val context = runBlocking { small.read(goal(sourcesDatabaseUrl)) }
        assertEquals(5, context.rows.size)
        assertTrue(context.truncated)
        assertEquals(LinkOutcome.TableListed(sourcesDatabaseUrl, "sources", 5, truncated = true), context.outcomes.single())
    }

    // ── A page that is not a row is reported and skipped ─────────────────────

    @Test
    fun `the reader itself skips a row of a table outside the set, even if the client hands it one`() {
        // The guard normally refuses such a page before the reader sees it. This reader is built on the raw fake,
        // so the check under test is the reader's own: defence in depth, not the guard.
        val otherTable = fake.defineDataSource(mapOf("Name" to app.alfrd.engram.notion.FakeProperty.Title))
        val stranger = runBlocking { fake.createPage(otherTable, mapOf("Name" to NotionValue.Title("SECRET-OTHER-TABLE-ROW"))) }
        val link = notionPageUrl(stranger.id)

        val context = runBlocking { LinkedContextReader(fake, set).read(goal(link)) }

        val skipped = context.skipped.single()
        assertEquals(SkipReason.NOT_A_CONFIGURED_ROW, skipped.reason)
        assertTrue(context.rows.isEmpty())
        assertFalse(context.allowed.allows(link))
        assertFalse(context.outcomes.toString().contains("SECRET-OTHER-TABLE-ROW"))
    }

    @Test
    fun `a page that is not a row of a configured table is reported and skipped, and nothing from it is kept`() {
        val howItWorks = fake.createPlainPage(listOf("The locking protocol: SECRET-LEDGER-RULES"))
        val link = notionPageUrl(howItWorks)

        val context = read(link)

        val skipped = context.skipped.single()
        assertEquals(link, skipped.link)
        assertEquals(SkipReason.NOT_A_CONFIGURED_ROW, skipped.reason)
        assertTrue(context.rows.isEmpty())
        assertFalse(context.allowed.allows(link), "a skipped page must not become an allowed link")
        assertFalse(context.outcomes.toString().contains("SECRET-LEDGER-RULES"))
    }

    @Test
    fun `the guard is not widened to read arbitrary pages, so a skipped page is checked after the fetch like any other`() {
        val plain = fake.createPlainPage(listOf("body"))
        read(notionPageUrl(plain))
        // The page was fetched once (the guard's own after-the-fetch check) and never listed or read for its blocks.
        assertEquals(1, fake.requestCount("retrievePage"))
        assertEquals(0, fake.requestCount("listBlocks"))
    }

    @Test
    fun `a page Notion cannot find is reported as unreadable with the status`() {
        val link = notionPageUrl("99999999-9999-4999-8999-999999999999")
        val skipped = read(link).skipped.single()
        assertEquals(SkipReason.UNREADABLE, skipped.reason)
        assertTrue(skipped.detail!!.contains("404"))
    }

    @Test
    fun `a notion url with no id in it is skipped as not a page link`() {
        val skipped = read("https://www.notion.so/workspace-home").skipped.single()
        assertEquals(SkipReason.NOT_A_PAGE_LINK, skipped.reason)
    }

    // ── A row link reads the page, which is where formulas and rollups appear ──

    @Test
    fun `a link to one row reads the page, including the formula and rollup values a query leaves out`() {
        val claim = runBlocking { fake.createPage(t.claims, mapOf("Claim" to NotionValue.Title("Closing 10 schools saves $20M"), "Claimed by" to NotionValue.Select("PPS"))) }
        fake.setComputed(claim.id, "Confidence", NotionValue.Formula("number", number = 0.62))
        fake.setComputed(claim.id, "Best Support", NotionValue.Rollup("number", function = "max", number = 4.0))

        val context = read(notionPageUrl(claim.id))

        val row = context.rows.single()
        assertEquals("claims", row.table)
        assertEquals("Closing 10 schools saves $20M", row.title)
        assertTrue(row.facts.contains("Claimed by: PPS"))
        assertTrue(row.facts.contains("Confidence: 0.62 (formula, informational)"), row.facts.toString())
        assertTrue(row.facts.contains("Best Support: 4 (rollup, informational)"), row.facts.toString())
        assertEquals(LinkOutcome.RowRead(notionPageUrl(claim.id), "claims", claim.id), context.outcomes.single())
        assertTrue(context.allowed.allows(notionPageUrl(claim.id)))
    }

    @Test
    fun `rows listed from a table carry no formula values, because a query does not return them`() {
        val claim = runBlocking { fake.createPage(t.claims, mapOf("Claim" to NotionValue.Title("c"))) }
        fake.setComputed(claim.id, "Confidence", NotionValue.Formula("number", number = 0.9))
        val claimsDatabase = "https://www.notion.so/b99dc17f7bd9408a9e28cf3ac4fad04e"
        val row = read(claimsDatabase).rows.single()
        assertTrue(row.facts.none { it.contains("Confidence") }, row.facts.toString())
    }

    // ── External links, duplicates and limits ────────────────────────────────

    @Test
    fun `an external url is kept as an allowed link and never fetched`() {
        val before = fake.requests.size
        val context = read("https://example.org/ledger-overview.pdf")
        assertEquals(before, fake.requests.size)
        assertEquals(listOf(LinkOutcome.External("https://example.org/ledger-overview.pdf")), context.outcomes)
        assertTrue(context.allowed.allows("https://example.org/ledger-overview.pdf"))
    }

    @Test
    fun `a link that is not an http url is skipped`() {
        assertEquals(SkipReason.NOT_A_PAGE_LINK, read("ftp://example.org/x").skipped.single().reason)
    }

    @Test
    fun `a goal with no links has no context and allows no link`() {
        val context = read()
        assertEquals(LinkedContext.EMPTY.rows, context.rows)
        assertTrue(context.outcomes.isEmpty())
        assertFalse(context.allowed.allows("https://example.org/anything"))
    }

    @Test
    fun `a repeated link is read once`() {
        source("One")
        val context = read(sourcesDatabaseUrl, sourcesDatabaseUrl)
        assertEquals(1, context.outcomes.size)
        assertEquals(1, fake.requestCount("query"))
    }

    @Test
    fun `only the first links are followed, and the rest are reported`() {
        val capped = LinkedContextReader(AllowlistedNotionApi(fake, set), set, maxLinks = 2)
        val links = (1..4).map { "https://example.org/doc$it" }
        val context = runBlocking { capped.read(goal(*links.toTypedArray())) }
        assertEquals(2, context.outcomes.filterIsInstance<LinkOutcome.External>().size)
        assertEquals(listOf(SkipReason.TOO_MANY_LINKS, SkipReason.TOO_MANY_LINKS), context.skipped.map { it.reason })
    }

    @Test
    fun `reading context never writes`() {
        source("One")
        val claim = runBlocking { fake.createPage(t.claims, mapOf("Claim" to NotionValue.Title("c"))) }
        val writesBefore = fake.requestCount("createPage") + fake.requestCount("updatePage") + fake.requestCount("appendBlocks")
        read(sourcesDatabaseUrl, notionPageUrl(claim.id), notionPageUrl(fake.createPlainPage()), "https://example.org/x")
        val writesAfter = fake.requestCount("createPage") + fake.requestCount("updatePage") + fake.requestCount("appendBlocks")
        assertEquals(writesBefore, writesAfter)
    }

    // ── The digest ───────────────────────────────────────────────────────────

    @Test
    fun `a long text fact is shortened to one line and a row has a bounded number of facts`() {
        val row = runBlocking {
            fake.createPage(
                t.sources,
                mapOf("Title" to NotionValue.Title("Long"), "Notes" to NotionValue.RichText("word ".repeat(200) + "\nnext line")),
            )
        }
        val fact = read(notionPageUrl(row.id)).rows.single().facts.single { it.startsWith("Notes: ") }
        assertFalse(fact.contains('\n'))
        assertTrue(fact.length <= "Notes: ".length + 240)
        assertTrue(fact.endsWith("…"))
    }

    @Test
    fun `an empty source has a title and no facts`() {
        val row = source("Bare", type = null)
        val context = read(notionPageUrl(row.id))
        assertEquals("Bare", context.rows.single().title)
        assertEquals(emptyList<String>(), context.rows.single().facts)
        assertNull(context.rows.single().externalUrl)
    }
}
