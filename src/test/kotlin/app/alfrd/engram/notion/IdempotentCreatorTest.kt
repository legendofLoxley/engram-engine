package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The creator is generic, so it is exercised on the agent-worker template (a table set that is not
 * memory) and, once, on the memory template, to show any set can opt in.
 *
 * The stack under test is the real one: guard over rate limiter over the fake. A 502 on a create is
 * turned into [NotionAmbiguousWriteException] by the rate limiter, never retried.
 */
class IdempotentCreatorTest {

    private lateinit var fake: FakeNotionApi
    private lateinit var worker: WorkerTemplate
    private lateinit var stack: NotionApi
    private lateinit var creator: IdempotentCreator
    private val sleeps = ArrayList<Long>()

    private fun stackOver(delegate: NotionApi, tableSet: NotionTableSet): NotionApi {
        val limited = RateLimitedNotionApi(
            delegate,
            RequestLimiter(ratePerSecond = 100_000.0, burst = 100_000),
            RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 1, jitter = { 0 }),
            sleep = {},
        )
        return AllowlistedNotionApi(limited, tableSet)
    }

    @BeforeEach
    fun setUp() {
        fake = FakeNotionApi()
        worker = fake.installWorkerTemplate()
        stack = stackOver(fake, worker.tableSet)
        creator = IdempotentCreator(stack, worker.tableSet.table("tasks"), sleep = { sleeps += it })
    }

    private val ambiguous502 = NotionApiException(502, "bad_gateway", "upstream")

    private fun task(title: String = "t") = mapOf("Task" to NotionValue.Title(title))

    private fun keys(): List<String> = fake.rows(worker.tasks).map { it.text("Op key") }

    // ── Opting in ────────────────────────────────────────────────────────────

    @Test
    fun `only a table that opted in can have a creator`() {
        val e = assertThrows(NotionConfigException::class.java) { IdempotentCreator(stack, worker.tableSet.table("cycles")) }
        assertTrue(e.message!!.contains("cycles"))
        assertTrue(e.message!!.contains("idempotencyKeyProperty"))
    }

    // ── The ordinary path ────────────────────────────────────────────────────

    @Test
    fun `create writes the key alongside the properties`() = runBlocking {
        val result = creator.create("cycle-1:plan-1:1", task("first"))
        assertEquals(CreateOutcome.CREATED, result.outcome)
        assertEquals("first", fake.page(result.page.id).title("Task"))
        assertEquals(listOf("cycle-1:plan-1:1"), keys())
    }

    @Test
    fun `find returns the row with a key, or null`() = runBlocking {
        creator.create("k-1", task())
        assertEquals("k-1", creator.find("k-1")!!.text("Op key"))
        assertEquals(null, creator.find("k-2"))
    }

    @Test
    fun `find returns the oldest row if a key somehow exists twice`() = runBlocking {
        val first = fake.createPage(worker.tasks, task("older") + ("Op key" to NotionValue.RichText("dup")))
        fake.now = fake.now.plusSeconds(120)
        fake.createPage(worker.tasks, task("newer") + ("Op key" to NotionValue.RichText("dup")))
        assertEquals(first.id, creator.find("dup")!!.id)
    }

    @Test
    fun `ensure creates once and finds the row on every repeat`() = runBlocking {
        val first = creator.ensure("k-1", task("first"))
        val again = creator.ensure("k-1", task("a repeat of the same work"))
        val third = creator.ensure("k-1", task("and again"))

        assertEquals(CreateOutcome.CREATED, first.outcome)
        assertEquals(CreateOutcome.ALREADY_EXISTED, again.outcome)
        assertEquals(CreateOutcome.ALREADY_EXISTED, third.outcome)
        assertEquals(first.page.id, again.page.id)
        assertEquals(1, fake.rows(worker.tasks).size)
        assertEquals(1, fake.requestCount("createPage"))
        assertEquals("first", fake.page(first.page.id).title("Task"), "an existing row is returned as it is, not updated")
    }

    // ── A write of unknown outcome ───────────────────────────────────────────

    @Test
    fun `a create that landed but whose answer was lost is found by its key and not made again`() = runBlocking {
        fake.failAfterApplying("createPage", ambiguous502)

        val result = creator.create("k-1", task("landed"))

        assertEquals(CreateOutcome.RECOVERED, result.outcome)
        assertEquals(1, fake.rows(worker.tasks).size)
        assertEquals(result.page.id, fake.rows(worker.tasks).single().id)
        assertEquals(1, fake.requestCount("createPage"), "the create must not be retried after an ambiguous failure")
    }

    @Test
    fun `a create that never landed is not repeated either, the ambiguity is reported and nothing is duplicated`() {
        fake.failNext("createPage", ambiguous502)

        assertThrows(NotionAmbiguousWriteException::class.java) { runBlocking { creator.create("k-1", task()) } }

        assertEquals(0, fake.rows(worker.tasks).size)
        assertEquals(1, fake.requestCount("createPage"), "an unresolved create is never retried")
        assertEquals(3, fake.requestCount("query"), "one lookup per configured wait")
        assertEquals(listOf(1_000L, 3_000L), sleeps, "waits between lookups; the first lookup is immediate")
    }

    @Test
    fun `the next ensure of the same key settles an ambiguity that could not be resolved the first time`() = runBlocking {
        fake.failNext("createPage", ambiguous502)
        assertThrows(NotionAmbiguousWriteException::class.java) { runBlocking { creator.ensure("k-1", task()) } }

        val retry = creator.ensure("k-1", task())

        assertEquals(CreateOutcome.CREATED, retry.outcome)
        assertEquals(1, fake.rows(worker.tasks).size)
    }

    @Test
    fun `a lookup that lags behind the write is retried until the row shows up`() = runBlocking {
        val lagging = LaggingQueries(fake, hide = 2)
        val laggy = IdempotentCreator(stackOver(lagging, worker.tableSet), worker.tableSet.table("tasks"), sleep = { sleeps += it })
        fake.failAfterApplying("createPage", ambiguous502)

        val result = laggy.create("k-1", task())

        assertEquals(CreateOutcome.RECOVERED, result.outcome)
        assertEquals(3, lagging.queries)
        assertEquals(listOf(1_000L, 3_000L), sleeps)
        assertEquals(1, fake.rows(worker.tasks).size)
    }

    @Test
    fun `a lookup that itself fails does not lose the ambiguity, and the next lookup can still find the row`() = runBlocking {
        fake.failAfterApplying("createPage", ambiguous502)
        fake.failNext("query", NotionApiException(400, "validation_error", "unlucky"))

        val result = creator.create("k-1", task())

        assertEquals(CreateOutcome.RECOVERED, result.outcome)
        assertEquals(1, fake.rows(worker.tasks).size)
    }

    @Test
    fun `when every lookup fails the original ambiguity is what the caller sees`() {
        fake.failAfterApplying("createPage", ambiguous502)
        fake.failNext("query", NotionApiException(400, "validation_error", "unlucky"), times = 3)

        assertThrows(NotionAmbiguousWriteException::class.java) { runBlocking { creator.create("k-1", task()) } }
        assertEquals(1, fake.requestCount("createPage"))
    }

    @Test
    fun `a failure that is certainly not an ambiguity is not turned into one`() {
        fake.failNext("createPage", NotionApiException(400, "validation_error", "bad property"))
        val e = assertThrows(NotionApiException::class.java) { runBlocking { creator.create("k-1", task()) } }
        assertEquals(400, e.status)
        assertEquals(0, fake.requestCount("query"), "no lookup for a create Notion plainly rejected")
    }

    // ── Inputs ───────────────────────────────────────────────────────────────

    @Test
    fun `keys must be 1 to 200 characters, trimmed, without control characters`() {
        for (bad in listOf("", " k", "k ", "a\nb", "a\tb", "x".repeat(201))) {
            assertThrows(IllegalArgumentException::class.java, { runBlocking { creator.create(bad, task()) } }, "accepted key of length ${bad.length}")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { creator.find(bad) } }
        }
        assertEquals(0, fake.requests.size, "an invalid key must be refused before any request")
    }

    @Test
    fun `properties that already carry a different key are refused, the same key is accepted`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { creator.create("k-1", task() + ("Op key" to NotionValue.RichText("other"))) }
        }
        creator.create("k-1", task() + ("Op key" to NotionValue.RichText("k-1")))
        assertEquals(listOf("k-1"), keys())
    }

    @Test
    fun `lookup waits must be a non-empty list of non-negative numbers`() {
        assertThrows(IllegalArgumentException::class.java) { IdempotentCreator(stack, worker.tableSet.table("tasks"), lookupDelaysMs = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { IdempotentCreator(stack, worker.tableSet.table("tasks"), lookupDelaysMs = listOf(-1L)) }
    }

    // ── Generic: the memory template can opt in too ──────────────────────────

    @Test
    fun `the memory template's Items and Sessions can opt in, with the same behaviour`() = runBlocking {
        val memoryFake = FakeNotionApi()
        val template = memoryFake.installAlfrdTemplate()
        val set = template.sources.toTableSet(idempotentWrites = true)
        val memoryStack = stackOver(memoryFake, set)

        val items = IdempotentCreator(memoryStack, set.table("items"), sleep = {})
        val sessions = IdempotentCreator(memoryStack, set.table("sessions"), sleep = {})

        memoryFake.failAfterApplying("createPage", ambiguous502)
        val item = items.create("turn-7:op-2", mapOf("Item" to NotionValue.Title("buy milk"), "Kind" to NotionValue.Select("Task")))
        assertEquals(CreateOutcome.RECOVERED, item.outcome)
        assertEquals(1, memoryFake.rows(template.sources.items).size)

        val day = sessions.ensure("day:2026-10-11", mapOf("Session" to NotionValue.Title("2026-10-11 · alfrd")))
        val sameDay = sessions.ensure("day:2026-10-11", mapOf("Session" to NotionValue.Title("2026-10-11 · alfrd")))
        assertEquals(CreateOutcome.CREATED, day.outcome)
        assertEquals(day.page.id, sameDay.page.id)
        assertEquals(1, memoryFake.rows(template.sources.sessions).size)
    }

    @Test
    fun `a creator on a table the guard has made read-only cannot write, and says so`() {
        val readOnlyStack = stackOver(fake, worker.tableSet.withReadOnly(listOf("tasks")))
        // The table passed to the creator is the writable view; the guard is the authority.
        val tasksCreator = IdempotentCreator(readOnlyStack, worker.tableSet.table("tasks"), sleep = {})
        val e = assertThrows(NotionGuardViolation::class.java) { runBlocking { tasksCreator.create("k-1", task()) } }
        assertTrue(e.message!!.contains("read-only"))
        assertEquals(0, fake.rows(worker.tasks).size)
        assertFalse(sleeps.isNotEmpty())
    }

    // ── A query that has not caught up yet ───────────────────────────────────

    /** Hides every row from the first [hide] queries, as a query index that lags a fresh write would. */
    private class LaggingQueries(private val delegate: FakeNotionApi, private var hide: Int) : NotionApi by delegate {
        var queries = 0
            private set

        override suspend fun query(dataSourceId: String, filter: NotionFilter?, sorts: List<NotionSort>, startCursor: String?, pageSize: Int): QueryPage {
            queries++
            val real = delegate.query(dataSourceId, filter, sorts, startCursor, pageSize)
            return if (hide-- > 0) QueryPage(emptyList(), null) else real
        }
    }
}
