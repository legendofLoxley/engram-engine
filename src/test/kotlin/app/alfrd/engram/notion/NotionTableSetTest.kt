package app.alfrd.engram.notion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotionTableSetTest {

    private fun id(n: Int) = "%08x-abcd-4abc-8abc-%012x".format(n, n)

    private fun rejected(block: () -> Unit): NotionConfigException = assertThrows(NotionConfigException::class.java, block)

    private fun sampleSet() = NotionTableSet(
        "agent-worker",
        listOf(
            NotionTable("goals", id(1)),
            NotionTable("tasks", id(2), idempotencyKeyProperty = "Op key"),
            NotionTable("claims", id(3), TableAccess.READ_ONLY),
        ),
        listOf(NotionReadablePage("home", id(9))),
    )

    // ── NotionTable ──────────────────────────────────────────────────────────

    @Test
    fun `a table defaults to read-write, no appends, no key, and accepting relations`() {
        val t = NotionTable("goals", id(1))
        assertEquals(TableAccess.READ_WRITE, t.access)
        assertTrue(t.writable)
        assertFalse(t.pageBodyAppend)
        assertNull(t.idempotencyKeyProperty)
        assertTrue(t.allowIncomingRelations)
    }

    @Test
    fun `a read-only table defaults to refusing incoming relations`() {
        val t = NotionTable("claims", id(3), TableAccess.READ_ONLY)
        assertFalse(t.writable)
        assertFalse(t.allowIncomingRelations)
    }

    @Test
    fun `a read-only table can be marked as accepting relations explicitly`() {
        assertTrue(NotionTable("sources", id(4), TableAccess.READ_ONLY, allowIncomingRelations = true).allowIncomingRelations)
    }

    @Test
    fun `a read-only table cannot also accept page-body appends or carry an idempotency key`() {
        assertTrue(rejected { NotionTable("claims", id(3), TableAccess.READ_ONLY, pageBodyAppend = true) }.message!!.contains("read-only"))
        assertTrue(rejected { NotionTable("claims", id(3), TableAccess.READ_ONLY, idempotencyKeyProperty = "Op key") }.message!!.contains("read-only"))
    }

    @Test
    fun `names and ids are validated`() {
        rejected { NotionTable("", id(1)) }
        rejected { NotionTable("has space", id(1)) }
        rejected { NotionTable("x".repeat(65), id(1)) }
        rejected { NotionTable("goals", "not-an-id") }
        rejected { NotionTable("goals", id(1).uppercase()) }
        rejected { NotionTable("goals", id(1).replace("-", "")) }
        rejected { NotionTable("goals", "collection://" + id(1)) }
        rejected { NotionTable("goals", id(1), idempotencyKeyProperty = " ") }
        rejected { NotionTable("goals", id(1), idempotencyKeyProperty = "k".repeat(101)) }
    }

    @Test
    fun `making a table read-only removes every write capability and cannot be undone by the copy`() {
        val full = NotionTable("sessions", id(5), pageBodyAppend = true, idempotencyKeyProperty = "Key")
        val tight = full.asReadOnly()
        assertEquals(TableAccess.READ_ONLY, tight.access)
        assertFalse(tight.pageBodyAppend)
        assertNull(tight.idempotencyKeyProperty)
        assertFalse(tight.allowIncomingRelations)
        assertSame(tight, tight.asReadOnly())
        assertEquals(TableAccess.READ_WRITE, full.access, "the original is unchanged")
    }

    // ── NotionTableSet ───────────────────────────────────────────────────────

    @Test
    fun `a set looks tables up by name and by data source id in any accepted spelling`() {
        val set = sampleSet()
        assertEquals(id(2), set.table("tasks").dataSourceId)
        assertEquals("claims", set.tableForDataSource(id(3).replace("-", "").uppercase())?.name)
        assertEquals("goals", set.tableForDataSource("collection://" + id(1))?.name)
        assertNull(set.tableForDataSource(id(8)))
        assertNull(set.tableForDataSource("not-an-id"))
        assertTrue(rejected { set.table("nope") }.message!!.contains("'nope'"))
        assertEquals(setOf(id(9)), set.readablePageIds)
    }

    @Test
    fun `a set must be well formed`() {
        rejected { NotionTableSet("", listOf(NotionTable("a", id(1)))) }
        rejected { NotionTableSet("has space", listOf(NotionTable("a", id(1)))) }
        assertTrue(rejected { NotionTableSet("s", emptyList()) }.message!!.contains("at least one table"))
        assertTrue(rejected { NotionTableSet("s", listOf(NotionTable("a", id(1)), NotionTable("a", id(2)))) }.message!!.contains("two tables named 'a'"))
        assertTrue(rejected { NotionTableSet("s", listOf(NotionTable("a", id(1)), NotionTable("b", id(1)))) }.message!!.contains("same data source"))
    }

    @Test
    fun `readable pages are validated against each other and against the tables`() {
        val t = listOf(NotionTable("a", id(1)))
        rejected { NotionTableSet("s", t, listOf(NotionReadablePage("p", id(9)), NotionReadablePage("p", id(8)))) }
        rejected { NotionTableSet("s", t, listOf(NotionReadablePage("p", id(9)), NotionReadablePage("q", id(9)))) }
        assertTrue(rejected { NotionTableSet("s", t, listOf(NotionReadablePage("p", id(1)))) }.message!!.contains("id of a table"))
        rejected { NotionReadablePage("p", "not-an-id") }
        rejected { NotionReadablePage("bad name", id(9)) }
    }

    @Test
    fun `withReadOnly tightens the named tables only, and never changes the original`() {
        val set = sampleSet()
        val planner = set.withReadOnly(listOf("tasks"))
        assertEquals(TableAccess.READ_ONLY, planner.table("tasks").access)
        assertNull(planner.table("tasks").idempotencyKeyProperty)
        assertEquals(TableAccess.READ_WRITE, planner.table("goals").access)
        assertEquals(TableAccess.READ_ONLY, planner.table("claims").access)
        assertEquals(TableAccess.READ_WRITE, set.table("tasks").access, "the original set is untouched")
        assertEquals(set.readablePages, planner.readablePages)
    }

    @Test
    fun `withReadOnly refuses a name that is not in the set, so a typo cannot silently leave a table writable`() {
        val e = rejected { sampleSet().withReadOnly(listOf("tasks", "taskz")) }
        assertTrue(e.message!!.contains("'taskz'"))
    }

    @Test
    fun `asReadOnly makes every table read-only`() {
        val all = sampleSet().asReadOnly()
        assertTrue(all.tables.none { it.writable })
        assertEquals(3, all.tables.size)
    }

    // ── The alfrd memory template as a table set ─────────────────────────────

    private val memory = NotionDataSources(id(1), id(2), id(3), id(4), id(5), id(6))

    @Test
    fun `the memory template maps to five read-write tables, appends on Sessions only, and a readable persona page`() {
        val set = memory.toTableSet()
        assertEquals("alfrd-memory", set.name)
        assertEquals(listOf("containers", "items", "entities", "connections", "sessions"), set.tables.map { it.name })
        assertTrue(set.tables.all { it.writable })
        assertEquals(listOf("sessions"), set.tables.filter { it.pageBodyAppend }.map { it.name })
        assertTrue(set.tables.all { it.idempotencyKeyProperty == null }, "memory behavior is unchanged unless a caller opts in")
        assertEquals(setOf(id(6)), set.readablePageIds)
        assertEquals(memory.writableDataSourceIds, set.tables.map { it.dataSourceId }.toSet())
    }

    @Test
    fun `the memory template can opt in to idempotency keys on Items and Sessions`() {
        val set = memory.toTableSet(idempotentWrites = true)
        assertEquals("Op key", set.table("items").idempotencyKeyProperty)
        assertEquals("Key", set.table("sessions").idempotencyKeyProperty)
        assertNull(set.table("containers").idempotencyKeyProperty)
    }

    @Test
    fun `an identity configuration exposes its memory tables as a set named for the identity`() {
        val config = NotionIdentityConfig("jacob", "env:ALFRD_NOTION_TOKEN_JACOB", memory)
        assertEquals("alfrd-memory.jacob", config.tableSet.name)
        assertEquals("env:ALFRD_NOTION_TOKEN_JACOB", config.tokenRef)
        assertEquals(memory.toTableSet(name = "alfrd-memory.jacob"), config.tableSet)
    }
}
