package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Per-table access modes and the other table-set rules of the guard, against a set that is not the
 * memory template. As in the memory tests, every refusal is checked twice: the guard throws, and
 * nothing reached (or changed) Notion.
 */
class AllowlistedAccessModesTest {

    private lateinit var fake: FakeNotionApi
    private lateinit var worker: WorkerTemplate
    private lateinit var api: AllowlistedNotionApi

    @BeforeEach
    fun setUp() {
        fake = FakeNotionApi()
        worker = fake.installWorkerTemplate()
        api = AllowlistedNotionApi(fake, worker.tableSet)
    }

    private fun violation(block: suspend () -> Unit): NotionGuardViolation =
        assertThrows(NotionGuardViolation::class.java) { runBlocking { block() } }

    private fun assertNoRequests() = assertEquals(0, fake.requests.size, "a request reached Notion that should have been refused")

    /** A row a person made by hand in a read-only table; the guard learns of it only by querying. */
    private fun seedClaim(title: String = "A claim"): NotionPage =
        runBlocking { fake.createPage(worker.claims, mapOf("Claim" to NotionValue.Title(title))) }

    private fun newTask(title: String = "t", key: String = "k-1", extra: Map<String, NotionValue> = emptyMap()): NotionPage = runBlocking {
        api.createPage(worker.tasks, mapOf("Task" to NotionValue.Title(title), "Op key" to NotionValue.RichText(key)) + extra)
    }

    // ── Read-only tables ─────────────────────────────────────────────────────

    @Test
    fun `a read-only table can be queried, and its pages retrieved and listed`() = runBlocking {
        val claim = seedClaim("readable")
        assertEquals(listOf(claim.id), api.query(worker.claims).pages.map { it.id })
        assertEquals("readable", api.retrievePage(claim.id).title("Claim"))
        assertEquals(emptyList<NotionBlockRead>(), api.listBlocks(claim.id).blocks)
        assertTrue(api.isKnownPage(claim.id))
    }

    @Test
    fun `a create in a read-only table is refused before any request is made`() {
        val e = violation { api.createPage(worker.claims, mapOf("Claim" to NotionValue.Title("sneaky"))) }
        assertNoRequests()
        assertTrue(e.message!!.contains("read-only"))
        assertTrue(fake.rows(worker.claims).isEmpty())
    }

    @Test
    fun `a page from a read-only table cannot be updated even though a query returned it`() = runBlocking {
        val claim = seedClaim("original")
        api.query(worker.claims)
        val before = fake.requestCount()

        violation { api.updatePage(claim.id, mapOf("Claim" to NotionValue.Title("changed"))) }
        violation { api.updatePage(claim.id, mapOf("Confidence" to NotionValue.Select("High"))) }

        assertEquals(before, fake.requestCount(), "a refused update reached Notion")
        assertEquals("original", fake.page(claim.id).title("Claim"))
        assertEquals(null, fake.page(claim.id).select("Confidence"))
    }

    @Test
    fun `blocks cannot be appended to a page of a read-only table`() = runBlocking {
        val claim = seedClaim()
        api.query(worker.claims)
        violation { api.appendBlocks(claim.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("nope"))))) }
        assertEquals(emptyList<String>(), fake.blockTexts(claim.id))
        assertEquals(0, fake.requestCount("appendBlocks"))
    }

    @Test
    fun `blocks can be appended only where the table opted in to page-body appends`() = runBlocking {
        val cycle = api.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("c1")))
        api.appendBlocks(cycle.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("summary")))))
        assertEquals(listOf("summary"), fake.blockTexts(cycle.id))

        val task = newTask()
        violation { api.appendBlocks(task.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("nope"))))) }
        assertEquals(emptyList<String>(), fake.blockTexts(task.id))
    }

    @Test
    fun `a read-only table of an otherwise writable set does not make its neighbours read-only`() = runBlocking {
        seedClaim()
        api.query(worker.claims)
        val cycle = api.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("c1")))
        api.updatePage(cycle.id, mapOf("Status" to NotionValue.Select("Running")))
        assertEquals("Running", fake.page(cycle.id).select("Status"))
    }

    // ── Relations into read-only tables ──────────────────────────────────────

    @Test
    fun `without the guard a relation write does edit the read-only row on the other side, which is why the rule exists`() = runBlocking {
        val claim = seedClaim()
        val task = fake.createPage(worker.tasks, mapOf("Task" to NotionValue.Title("t"), "Claim" to NotionValue.Relation(listOf(claim.id))))
        assertEquals(listOf(task.id), fake.page(claim.id).relation("Tasks"), "Notion mirrors a dual relation onto the target row")
    }

    @Test
    fun `a relation into a read-only table is refused by default, so the read-only row is never edited`() = runBlocking {
        val claim = seedClaim()
        api.query(worker.claims)
        val task = newTask()

        val onCreate = violation { api.createPage(worker.tasks, mapOf("Task" to NotionValue.Title("t2"), "Op key" to NotionValue.RichText("k-2"), "Claim" to NotionValue.Relation(listOf(claim.id)))) }
        violation { api.updatePage(task.id, mapOf("Claim" to NotionValue.Relation(listOf(claim.id)))) }

        assertTrue(onCreate.message!!.contains("does not accept incoming relations"))
        assertEquals(emptyList<String>(), fake.page(claim.id).relation("Tasks"))
        assertEquals(1, fake.rows(worker.tasks).size, "the refused create still made a row")
    }

    @Test
    fun `a read-only table marked as accepting relations can be the target of a one-way relation`() = runBlocking {
        val source = runBlocking { fake.createPage(worker.sources, mapOf("Source" to NotionValue.Title("a paper"))) }
        api.query(worker.sources)
        val task = newTask(extra = mapOf("Source" to NotionValue.Relation(listOf(source.id))))
        assertEquals(listOf(source.id), fake.page(task.id).relation("Source"))
        assertEquals("a paper", fake.page(source.id).title("Source"), "the read-only row itself is untouched")
    }

    @Test
    fun `a relation to a read-only page the guard has not seen is still refused as unknown`() {
        val hidden = runBlocking { fake.createPage(worker.sources, mapOf("Source" to NotionValue.Title("never queried"))) }
        val task = newTask()
        val e = violation { api.updatePage(task.id, mapOf("Source" to NotionValue.Relation(listOf(hidden.id)))) }
        assertTrue(e.message!!.contains("was not returned from a configured table"))
    }

    // ── Idempotency keys ─────────────────────────────────────────────────────

    @Test
    fun `a table that opted in to a key refuses a create without it`() {
        val noKey = violation { api.createPage(worker.tasks, mapOf("Task" to NotionValue.Title("t"))) }
        violation { api.createPage(worker.tasks, mapOf("Task" to NotionValue.Title("t"), "Op key" to NotionValue.RichText("   "))) }
        violation { api.createPage(worker.tasks, mapOf("Task" to NotionValue.Title("t"), "Op key" to NotionValue.Title("wrong type"))) }
        assertNoRequests()
        assertTrue(noKey.message!!.contains("Op key"))
        assertTrue(fake.rows(worker.tasks).isEmpty())
    }

    @Test
    fun `a create with the key goes through, and the key can never be changed by an update`() = runBlocking {
        val task = newTask(key = "cycle-1:plan-1:task-1")
        assertEquals("cycle-1:plan-1:task-1", fake.page(task.id).text("Op key"))

        api.updatePage(task.id, mapOf("Status" to NotionValue.Select("Done")))
        assertEquals("Done", fake.page(task.id).select("Status"))

        val before = fake.requestCount("updatePage")
        violation { api.updatePage(task.id, mapOf("Op key" to NotionValue.RichText("something-else"))) }
        violation { api.updatePage(task.id, mapOf("Op key" to NotionValue.RichText("cycle-1:plan-1:task-1"))) }
        assertEquals(before, fake.requestCount("updatePage"))
        assertEquals("cycle-1:plan-1:task-1", fake.page(task.id).text("Op key"))
    }

    @Test
    fun `a table that did not opt in needs no key`() = runBlocking {
        val cycle = api.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("no key needed")))
        assertEquals("no key needed", fake.page(cycle.id).title("Cycle"))
    }

    // ── Tightening a set ─────────────────────────────────────────────────────

    @Test
    fun `the same set made read-only for a table refuses the writes that were allowed before`() = runBlocking {
        val planner = AllowlistedNotionApi(fake, worker.tableSet.withReadOnly(listOf("tasks")))
        violation { planner.createPage(worker.tasks, mapOf("Task" to NotionValue.Title("t"), "Op key" to NotionValue.RichText("k"))) }
        assertTrue(fake.rows(worker.tasks).isEmpty())

        val cycle = planner.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("still writable")))
        assertEquals("still writable", fake.page(cycle.id).title("Cycle"))
    }

    @Test
    fun `a whole set made read-only can only be read`() = runBlocking {
        val seeded = fake.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("existing")))
        val readOnly = AllowlistedNotionApi(fake, worker.tableSet.asReadOnly())
        assertEquals(listOf(seeded.id), readOnly.query(worker.cycles).pages.map { it.id })
        violation { readOnly.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("x"))) }
        violation { readOnly.updatePage(seeded.id, mapOf("Status" to NotionValue.Select("Done"))) }
        violation { readOnly.appendBlocks(seeded.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("x"))))) }
        assertNull(fake.page(seeded.id).select("Status"))
    }

    // ── The allowlist is static ──────────────────────────────────────────────

    @Test
    fun `a table outside the set is refused whatever its neighbours allow`() {
        violation { api.query(worker.foreignDataSourceId) }
        violation { api.createPage(worker.foreignDataSourceId, mapOf("Name" to NotionValue.Title("sneaky"))) }
        assertNoRequests()
    }

    @Test
    fun `the allowlist is fixed when the guard is built, so editing the list afterwards cannot widen it`() {
        val mutable = worker.tableSet.tables.toMutableList()
        val guard = AllowlistedNotionApi(fake, NotionTableSet("mutable", mutable))
        mutable += NotionTable("late", worker.foreignDataSourceId)
        assertThrows(NotionGuardViolation::class.java) { runBlocking { guard.query(worker.foreignDataSourceId) } }
        assertNoRequests()
    }

    @Test
    fun `scope is not the client's business, so any row of a configured table is reachable once the guard has seen it`() = runBlocking {
        // Per-goal Scope is enforced by the Director above the client; the client's allowlist is only the tables.
        val a = api.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("goal A's cycle")))
        val b = api.createPage(worker.cycles, mapOf("Cycle" to NotionValue.Title("goal B's cycle")))
        api.updatePage(a.id, mapOf("Status" to NotionValue.Select("Done")))
        api.updatePage(b.id, mapOf("Status" to NotionValue.Select("Done")))
        assertEquals("Done", fake.page(b.id).select("Status"))
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    @Test
    fun `a configured table that Notion cannot find says to check for a database id used in place of a data source id`() {
        val missing = "99999999-9999-4999-8999-999999999999"
        val guard = AllowlistedNotionApi(fake, NotionTableSet("s", listOf(NotionTable("pretend-database", missing))))

        val onQuery = assertThrows(NotionApiException::class.java) { runBlocking { guard.query(missing) } }
        val onCreate = assertThrows(NotionApiException::class.java) { runBlocking { guard.createPage(missing, mapOf("Name" to NotionValue.Title("x"))) } }

        for (e in listOf(onQuery, onCreate)) {
            assertEquals(404, e.status)
            assertEquals("object_not_found", e.code)
            assertTrue(e.message!!.contains("pretend-database"))
            assertTrue(e.message!!.contains("not a database id"), e.message)
            assertTrue(e.message!!.contains("connected"), e.message)
        }
    }

    @Test
    fun `an error other than not-found passes through without the hint`() {
        fake.failNext("query", NotionApiException(400, "validation_error", "bad filter"))
        val e = assertThrows(NotionApiException::class.java) { runBlocking { api.query(worker.cycles) } }
        assertEquals(400, e.status)
        assertFalse(e.message!!.contains("database id"))
    }

    @Test
    fun `a refusal names tables and ids, never the values being written`() {
        val claim = seedClaim()
        runBlocking { api.query(worker.claims) }
        val e = violation { api.updatePage(claim.id, mapOf("Claim" to NotionValue.Title("TOPSECRET-VALUE"))) }
        val f = violation { api.createPage(worker.claims, mapOf("Claim" to NotionValue.Title("TOPSECRET-VALUE"))) }
        val g = violation { api.createPage(worker.tasks, mapOf("Task" to NotionValue.Title("TOPSECRET-VALUE"), "Op key" to NotionValue.RichText(" "))) }
        for (m in listOf(e, f, g)) assertFalse(m.message!!.contains("TOPSECRET"), "a guard message carried a value")
        assertTrue(e.message!!.contains("claims"))
    }
}
