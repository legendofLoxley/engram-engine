package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.MentionKind
import app.alfrd.engram.notion.NotionMention
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.normalizeNotionId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a goal's Scope puts in reach, decided in code from its @-mentions: the mentioned pages, the
 * mentioned databases and the rows of a mentioned database, and nothing else. Run against the real
 * configuration's ids and the guard the runtime builds.
 */
class ScopeReaderTest {

    private val f = AgentWorkFixture()
    private val t = f.template

    private fun reader(maxRows: Int = 100, maxMentions: Int = 12) = ScopeReader(f.guarded(), f.tableSet, maxRowsPerTable = maxRows, maxMentions = maxMentions)
    private fun read(vararg mentions: NotionMention, reader: ScopeReader = reader()): ScopeContext =
        runBlocking { reader.read(GoalScope.fromRichText(scopeOf("", *mentions))) }

    private fun db(table: String) = databaseMention(f.tableSet.table(table).databaseId!!, "the $table database")
    private fun item(title: String, extra: Map<String, NotionValue> = emptyMap()): NotionPage = runBlocking {
        f.fake.createPage(t.items, mapOf("Item" to NotionValue.Title(title)) + extra).also { f.fake.now = f.fake.now.plusSeconds(1) }
    }

    private fun url(pageId: String) = notionPageUrl(pageId)

    // ── A mentioned database ─────────────────────────────────────────────────

    @Test
    fun `a mentioned database puts its rows in scope, and links to the database and to a row are allowed`() {
        val a = f.source("Oct 6 Board memo")
        val b = f.source("Audit")
        val context = read(f.sourcesMention)

        val listed = context.outcomes.single() as ScopeOutcome.TableListed
        assertEquals("sources", listed.table)
        assertEquals(2, listed.rows)
        assertTrue(context.usable)
        assertTrue(context.allowed.allows(url(a.id)) && context.allowed.allows(url(b.id)))
        assertTrue(context.allowed.allows(url(f.tableSet.table("sources").databaseId!!)), "the database itself is in scope")
        assertTrue(context.allowed.allows(url(f.tableSet.table("sources").dataSourceId)), "and so is its data source id")
        assertTrue(context.allowed.isDatabase(url(f.tableSet.table("sources").databaseId!!)))
        assertFalse(context.allowed.isDatabase(url(a.id)))
        assertEquals(listOf("Oct 6 Board memo", "Audit"), context.rowsOf("sources").map { it.title })
    }

    @Test
    fun `rows of a table that was not mentioned are not in scope, however close the table is`() {
        val mine = f.source("A source")
        val notMentioned = runBlocking { f.fake.createPage(t.findings, mapOf("Claim" to NotionValue.Title("a finding"))) }
        val aboutItems = item("an item")
        val context = read(f.sourcesMention)

        assertTrue(context.allowed.allows(url(mine.id)))
        assertFalse(context.allowed.allows(url(notMentioned.id)))
        assertFalse(context.allowed.allows(url(aboutItems.id)))
        assertFalse(context.allowed.allows(url(f.tableSet.table("findings").databaseId!!)))
    }

    @Test
    fun `the data source id spelling of a database mention works too, and so does a page mention that is really a database`() {
        val a = f.source("memo")
        val byDataSource = read(databaseMention(f.tableSet.table("sources").dataSourceId))
        assertTrue(byDataSource.allowed.allows(url(a.id)))
        val pageMentionOfDatabase = read(pageMention(f.tableSet.table("sources").databaseId!!, "PPS Sources"))
        assertTrue(pageMentionOfDatabase.outcomes.single() is ScopeOutcome.TableListed, "people paste database pages as page mentions")
        assertTrue(pageMentionOfDatabase.allowed.allows(url(a.id)))
    }

    @Test
    fun `the same table mentioned twice is listed once`() {
        f.source("memo")
        val before = f.fake.requestCount("query")
        val context = read(f.sourcesMention, pageMention(f.tableSet.table("sources").databaseId!!, "again"))
        assertEquals(1, f.fake.requestCount("query") - before)
        assertEquals(1, context.rows.size)
    }

    @Test
    fun `only the oldest rows up to the limit are read, the rest are out of scope, and the cut is reported`() {
        val rows = (1..5).map { f.source("source $it") }
        val context = read(f.sourcesMention, reader = reader(maxRows = 3))

        assertTrue(context.truncated)
        assertTrue((context.outcomes.single() as ScopeOutcome.TableListed).truncated)
        assertTrue(rows.take(3).all { context.allowed.allows(url(it.id)) })
        assertFalse(context.allowed.allows(url(rows[3].id)), "a row beyond the limit is not in the set: the cautious side")
        assertFalse(context.allowed.allows(url(rows[4].id)))
    }

    // ── A mentioned page ─────────────────────────────────────────────────────

    @Test
    fun `a mentioned page is in scope and its siblings are not`() {
        val a = item("the one mentioned", mapOf("Said" to NotionValue.RichText("something Jacob said")))
        val b = item("its sibling")
        val context = read(pageMention(a.id, "the one mentioned"))

        val read = context.outcomes.single() as ScopeOutcome.PageRead
        assertEquals("memory-items", read.label)
        assertTrue(context.allowed.allows(url(a.id)))
        assertFalse(context.allowed.allows(url(b.id)))
        assertFalse(context.allowed.allows(url(f.tableSet.table("memory-items").databaseId!!)), "mentioning a row does not mention its table")
        assertEquals("the one mentioned", context.row(a.id)!!.title)
        assertTrue(context.row(a.id)!!.facts.any { it.contains("something Jacob said") })
    }

    @Test
    fun `the Persona page can be mentioned and read`() {
        val context = read(pageMention(t.personaPageId, "Persona"))
        val read = context.outcomes.single() as ScopeOutcome.PageRead
        assertEquals("persona", read.label)
        assertTrue(context.allowed.allows(url(t.personaPageId)))
        assertEquals("Persona", context.row(t.personaPageId)!!.title)
    }

    @Test
    fun `a listed reading page can be mentioned and read`() {
        val context = read(pageMention(t.essayExcerptPageId, "Test material: essay excerpt"))
        val read = context.outcomes.single() as ScopeOutcome.PageRead
        assertEquals("reading-essay-excerpt", read.label)
        assertTrue(context.allowed.allows(url(t.essayExcerptPageId)))
        assertEquals("Test material: essay excerpt", context.row(t.essayExcerptPageId)!!.title)
    }

    @Test
    fun `the refusal of an unlisted page names the allowlist`() {
        val stranger = f.fake.createPlainPage(listOf("private notes"))
        val skipped = read(pageMention(stranger, "Private notes")).outcomes.single() as ScopeOutcome.Skipped
        assertTrue(skipped.detail!!.contains("readablePages allowlist"), skipped.detail)
    }

    @Test
    fun `a plain page that is not allowlisted is skipped without being read, and is not in scope`() {
        val stranger = f.fake.createPlainPage(listOf("private notes"))
        val context = read(pageMention(stranger, "Private notes"))

        val skipped = context.outcomes.single() as ScopeOutcome.Skipped
        assertEquals(SkipReason.NOT_READABLE, skipped.reason)
        assertFalse(context.usable)
        assertFalse(context.allowed.allows(url(stranger)))
        assertEquals(0, f.fake.requestCount("listBlocks"), "nothing about the page was read")
    }

    @Test
    fun `a database that is not one of the configured tables is skipped without a request`() {
        val before = f.fake.requests.size
        val context = read(databaseMention("99999999-9999-4999-8999-999999999999", "Somebody's database"))
        assertEquals(SkipReason.DATABASE_NOT_CONFIGURED, (context.outcomes.single() as ScopeOutcome.Skipped).reason)
        assertEquals(before, f.fake.requests.size)
        assertFalse(context.usable)
    }

    @Test
    fun `a page Notion cannot find is reported as unreadable`() {
        val context = read(pageMention("88888888-8888-4888-8888-888888888888", "Gone"))
        val skipped = context.outcomes.single() as ScopeOutcome.Skipped
        assertEquals(SkipReason.UNREADABLE, skipped.reason)
        assertTrue(skipped.detail!!.contains("404"), skipped.detail)
    }

    @Test
    fun `mentions past the limit are skipped, and a scope with some readable mentions is usable`() {
        val a = f.source("memo")
        val ok = pageMention(item("one").id, "one")
        val context = read(f.sourcesMention, ok, pageMention(item("two").id, "two"), reader = reader(maxMentions = 2))
        assertEquals(SkipReason.TOO_MANY_MENTIONS, (context.outcomes.last() as ScopeOutcome.Skipped).reason)
        assertTrue(context.usable)
        assertTrue(context.allowed.allows(url(a.id)))
    }

    @Test
    fun `reading a scope only ever reads`() {
        f.source("memo")
        val itemRow = item("x")
        val mark = f.fake.requests.size
        read(f.sourcesMention, pageMention(t.personaPageId, "Persona"), pageMention(itemRow.id, "x"))
        val after = f.fake.requests.drop(mark)
        assertTrue(after.isNotEmpty())
        assertTrue(after.none { it.operation in setOf("createPage", "updatePage", "appendBlocks") }, after.toString())
    }

    // ── What is never in scope ───────────────────────────────────────────────

    @Test
    fun `an external URL is never in scope, nor a lookalike host, nor a bare id`() {
        val a = f.source("memo")
        val scope = read(f.sourcesMention).allowed
        val id = a.id.replace("-", "")
        assertTrue(scope.allows("https://www.notion.so/$id"))
        assertTrue(scope.allows("http://notion.so/$id"), "a Notion host over http is still Notion")
        assertTrue(scope.allows("https://app.notion.com/p/$id?pvs=204"))
        for (bad in listOf(
            "https://example.org/$id", "https://notion.so.evil.example/$id", "https://evil.example/www.notion.so/$id", "https://www.notion.so@evil.example/$id",
            "ftp://www.notion.so/$id", id, "notion.so/$id", "", "   ",
        )) assertFalse(scope.allows(bad), "allowed: '$bad'")
        assertFalse(AllowedScope.NONE.allows("https://www.notion.so/$id"))
    }

    @Test
    fun `an allowed page id outside the path of the url does not make the link allowed`() {
        val memo = f.source("Oct 6 Board memo")
        val scope = read(f.sourcesMention).allowed
        val id = memo.id.replace("-", "")

        assertTrue(scope.allows("https://www.notion.so/$id"))
        assertTrue(scope.allows("https://www.notion.so/Board-memo-$id?pvs=4"))
        // The id is somewhere other than the path: none of these addresses the allowed page.
        assertFalse(scope.allows("https://$id@www.notion.so/unrelated-page"), "user-info")
        assertFalse(scope.allows("https://www.notion.so/unrelated-page#$id"), "fragment")
        assertFalse(scope.allows("https://www.notion.so/unrelated-page?x=$id"), "query")
        assertFalse(scope.allows("https://www.notion.so/unrelated-page?p=$id"), "peek parameter")
        // And a URL with user-info is refused even when its path is right.
        assertFalse(scope.allows("https://user:pw@www.notion.so/$id"), "user-info with the right path")
        assertNull(notionPageIdInPath("https://$id@www.notion.so/unrelated-page"))
    }

    @Test
    fun `a mentioned database is told apart from a row only by the id in the path`() {
        val sources = f.tableSet.table("sources").databaseId!!
        val scope = read(f.sourcesMention).allowed
        assertTrue(scope.isDatabase("https://www.notion.so/$sources"))
        assertFalse(scope.isDatabase("https://www.notion.so/unrelated#$sources"))
        assertFalse(scope.isDatabase("https://$sources@www.notion.so/unrelated"))
    }

    // ── What the planner is given about a row ────────────────────────────────

    @Test
    fun `a row is summarised as a bounded digest of its properties, and a formula is marked informational`() {
        val row = f.source("Oct 6 Board memo")
        runBlocking { f.fake.setComputed(row.id, "Default Strength", NotionValue.Formula("number", number = 3.0)) }
        val context = read(pageMention(row.id, "memo"))
        val facts = context.row(row.id)!!.facts
        assertTrue(facts.contains("Type: PPS Document"), facts.toString())
        assertTrue(facts.any { it.startsWith("URL: https://example.org/") })
        assertTrue(facts.any { it.contains("Default Strength: 3") && it.contains("informational") }, facts.toString())
    }

    @Test
    fun `a very long property is shortened to one line and a row has a bounded number of facts`() {
        val long = item("long", mapOf("Said" to NotionValue.RichText("word ".repeat(500))))
        val digest = read(pageMention(long.id, "long")).row(long.id)!!
        assertTrue(digest.facts.all { it.length <= 260 }, digest.facts.toString())
        assertTrue(digest.facts.size <= 12)
        assertNotNull(normalizeNotionId(digest.pageUrl))
    }
}
