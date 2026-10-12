package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.pipeline.agentwork.CycleProcessor.TickResult
import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.ToolCall
import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionApiException
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.RateLimitedNotionApi
import app.alfrd.engram.notion.RequestLimiter
import app.alfrd.engram.notion.RetryPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class CycleProcessorTest {

    private val set = AgentWorkConfig.load("deploy/config/notion-agent-worker.json").tableSet
    private lateinit var fake: FakeNotionApi
    private lateinit var t: AgentWorkTemplate
    private lateinit var api: NotionApi
    private val traces = CycleTraceStore()

    private val base = Instant.parse("2026-10-12T10:00:00Z")
    private var clockNow = base

    private val sourcesDatabaseUrl = "https://www.notion.so/6e9ea25de7334d899d7a52783509ff54"
    private val ping502 = NotionApiException(502, "bad_gateway", "upstream")

    /** Where the fake's request log stood when the processor last started; writes are counted from there, not from fixture setup. */
    private var mark = 0

    @BeforeEach
    fun setUp() {
        fake = FakeNotionApi(now = base)
        t = fake.installAgentWorkTemplate(set)
        api = stackOver(fake)
    }

    private fun stackOver(delegate: NotionApi): NotionApi = AllowlistedNotionApi(
        RateLimitedNotionApi(delegate, RequestLimiter(100_000.0, 100_000), RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 1, jitter = { 0 }), sleep = {}),
        set,
    )

    private fun processor(
        llm: LlmClient?,
        worker: String = "arx-1",
        lease: Duration = Duration.ofMinutes(2),
        maxAttempts: Int = 3,
        over: NotionApi = api,
    ) = CycleProcessor(over, set, CyclePlanDirector(llm), traces, worker, { clockNow }, leaseDuration = lease, heartbeatInterval = lease.dividedBy(3), maxAttemptsPerCycle = maxAttempts, sleep = {})

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Rows are ordered by creation time, so each fixture moves the fake's clock on. */
    private fun tick() { fake.now = fake.now.plusSeconds(1) }

    private fun source(title: String, url: String? = null): NotionPage = runBlocking {
        fake.createPage(
            t.sources,
            buildMap {
                put("Title", NotionValue.Title(title)); put("Type", NotionValue.Select("PPS Document"))
                url?.let { put("URL", NotionValue.UrlValue(it)) }
            },
        ).also { tick() }
    }

    private fun goal(status: String = "Active", links: List<String> = listOf(sourcesDatabaseUrl), budget: Double? = 10.0, title: String = "PPS: read before lock"): NotionPage = runBlocking {
        fake.createPage(
            t.goals,
            buildMap {
                put("Goal", NotionValue.Title(title)); put("Goal Type", NotionValue.Select("Work")); put("Status", NotionValue.Select(status))
                put("Intent", NotionValue.RichText("Read the primary documents before answer standards are locked."))
                put("Scope", NotionValue.RichText("The 10 listed primary documents and the claims they bear on. No answer standards or locking."))
                put("Target Links", NotionValue.RichText("PPS Sources", links = links))
                budget?.let { put("Cycle Budget", NotionValue.NumberValue(it)) }
            },
        ).also { tick() }
    }

    private fun cycle(goalId: String?, status: String = "Queued", extra: Map<String, NotionValue> = emptyMap()): NotionPage = runBlocking {
        fake.createPage(
            t.cycles,
            buildMap {
                put("Cycle", NotionValue.Title("a cycle")); put("Status", NotionValue.Select(status))
                goalId?.let { put("Goal", NotionValue.Relation(listOf(it))) }
                putAll(extra)
            },
        ).also { tick() }
    }

    private fun taskRow(cycleId: String, order: Int, label: String = "CYCLE-1"): NotionPage = runBlocking {
        fake.createPage(
            t.tasks,
            mapOf(
                "Task" to NotionValue.Title("Task $order"), "Type" to NotionValue.Select("Read"), "Order" to NotionValue.NumberValue(order.toDouble()),
                "Status" to NotionValue.Select("Planned"), "Plan Version" to NotionValue.NumberValue(1.0),
                "Op key" to NotionValue.RichText("cycle:$label:v1:o$order"), "Cycle" to NotionValue.Relation(listOf(cycleId)),
            ),
        ).also { tick() }
    }

    private fun escalation(goalId: String, resolution: String): NotionPage = runBlocking {
        fake.createPage(
            t.escalations,
            mapOf(
                "Escalation" to NotionValue.Title("A/B boxes"), "Kind" to NotionValue.Select("Error found"), "Status" to NotionValue.Select("Resolved"),
                "Ask" to NotionValue.RichText("Which box is right?"), "Resolution" to NotionValue.RichText(resolution), "Goal" to NotionValue.Relation(listOf(goalId)),
            ),
        ).also { tick() }
    }

    private fun llmPlan(vararg tasks: JsonObject, rationale: String? = "because") = ScriptedLlm.answering(ScriptedLlm.planOf(*tasks, rationale = rationale))
    private fun read(title: String, vararg links: String) = ScriptedLlm.taskJson(title, "Read", "Read it and note the findings.", *links)

    private fun cycleRow(id: String) = fake.page(id)
    private fun tasksFor(cycleId: String): List<NotionPage> = fake.rows(t.tasks).filter { cycleId in it.relation("Cycle") }.sortedBy { it.number("Order") }
    private fun run(p: CycleProcessor): List<TickResult> { mark = fake.requests.size; return runBlocking { p.runOnce() } }
    private fun writes(operation: String) = fake.requests.drop(mark).filter { it.operation == operation }
    private fun pageUrl(page: NotionPage) = "https://www.notion.so/${page.id.replace("-", "")}"

    // ── Acceptance: a Queued cycle on an Active goal is planned ───────────────

    @Test
    fun `a queued cycle on an active goal is picked up, planned within budget, and finished as a dry run`() {
        val memo = source("Oct 6 Board memo", "https://example.org/board-memo.pdf")
        val c = cycle(goal().id)
        val llm = llmPlan(
            read("Read the Oct 6 Board memo", pageUrl(memo), "https://example.org/board-memo.pdf"),
            ScriptedLlm.taskJson("Check the memo's headline figure", "Check", "Compare the table with the narrative text."),
            ScriptedLlm.taskJson("Consolidate the findings", "Consolidate", "Gather the findings into one list."),
        )

        val results = run(processor(llm))

        val finished = results.single() as TickResult.Finished
        assertEquals("CYCLE-1", finished.cycle)
        assertEquals(CycleStatus.DONE, finished.status)

        val row = cycleRow(c.id)
        assertEquals("Done", row.select("Status"))
        assertEquals("arx-1", row.text("Worker"))
        assertEquals(0.0, row.number("Tasks Done"))
        assertEquals(10.0, row.number("Task Budget"), "copied from the goal at start")
        assertEquals("2026-10-12T10:00:00", row.date("Started")!!.start!!.substring(0, 19))
        assertNotNull(row.date("Finished"))
        assertNull(row.date("Lease Until"), "the lease is released")
        assertTrue(row.text("Summary").startsWith("Dry run: 3 planned tasks written; nothing was executed."), row.text("Summary"))

        val tasks = tasksFor(c.id)
        assertEquals(listOf(1.0, 2.0, 3.0), tasks.map { it.number("Order") })
        assertEquals(listOf("Read", "Check", "Consolidate"), tasks.map { it.select("Type") })
        assertTrue(tasks.all { it.select("Status") == "Planned" && it.number("Plan Version") == 1.0 })
        assertEquals(listOf("cycle:CYCLE-1:v1:o1", "cycle:CYCLE-1:v1:o2", "cycle:CYCLE-1:v1:o3"), tasks.map { it.text("Op key") })
        assertEquals("${pageUrl(memo)}\nhttps://example.org/board-memo.pdf", tasks[0].text("Target Links"))
    }

    @Test
    fun `the only things written are the cycle and its tasks, and PPS and Goals are never touched`() {
        source("Memo")
        val g = goal()
        val c = cycle(g.id)

        run(processor(llmPlan(read("Read the memo"))))

        assertTrue(writes("createPage").isNotEmpty() && writes("createPage").all { it.target == t.tasks }, "a row was created outside Agent Tasks: ${writes("createPage")}")
        assertTrue(writes("updatePage").isNotEmpty() && writes("updatePage").all { it.target == c.id }, "a page other than the cycle was updated: ${writes("updatePage")}")
        assertTrue(writes("appendBlocks").isEmpty())
        assertEquals("Active", fake.page(g.id).select("Status"))
    }

    @Test
    fun `a queued cycle on a draft goal is ignored and nothing is written or asked of the model`() {
        val c = cycle(goal(status = "Draft").id)
        val llm = llmPlan(read("never"))

        val results = run(processor(llm))

        assertTrue(results.isEmpty())
        assertEquals("Queued", cycleRow(c.id).select("Status"))
        assertTrue(llm.requests.isEmpty())
        assertEquals(0, writes("updatePage").size + writes("createPage").size)
    }

    @Test
    fun `goals that are not active are ignored whatever else they are`() {
        for (status in listOf("Queued", "Met", "Abandoned", "Expired")) {
            val c = cycle(goal(status = status, title = status).id)
            assertTrue(run(processor(llmPlan(read("never")))).isEmpty(), status)
            assertEquals("Queued", cycleRow(c.id).select("Status"), status)
        }
    }

    @Test
    fun `a queued cycle with no goal, or two, is ignored`() {
        val none = cycle(null)
        val two = cycle(null, extra = mapOf("Goal" to NotionValue.Relation(listOf(goal().id, goal(title = "other").id))))
        assertTrue(run(processor(llmPlan(read("never")))).isEmpty())
        assertEquals("Queued", cycleRow(none.id).select("Status"))
        assertEquals("Queued", cycleRow(two.id).select("Status"))
    }

    @Test
    fun `an idle poll is one request`() {
        val before = fake.requests.size
        assertTrue(run(processor(llmPlan())).isEmpty())
        assertEquals(1, fake.requests.size - before)
    }

    // ── Acceptance: rejected by code ─────────────────────────────────────────

    @Test
    fun `tasks that link outside, lock a standard, delete a row or take a Director step are rejected by code and never written`() {
        val memo = source("Oct 6 Board memo")
        val outside = runBlocking { fake.createPage(t.claims, mapOf("Claim" to NotionValue.Title("a claim the goal never linked"))) }
        val c = cycle(goal(links = listOf(pageUrl(memo))).id)
        val llm = llmPlan(
            read("Read the Oct 6 Board memo", pageUrl(memo)),
            read("Read a claim the goal never named", pageUrl(outside)),
            ScriptedLlm.taskJson("Lock the answer standards", "Check", "Lock the answer standard for question 3."),
            ScriptedLlm.taskJson("Clean up", "Check", "Then delete the duplicate claim row."),
            ScriptedLlm.taskJson("Run QC", "QC", "Recompute the metric."),
        )

        run(processor(llm))

        assertEquals(listOf("Read the Oct 6 Board memo"), tasksFor(c.id).map { it.title("Task") })
        assertEquals(
            listOf("LINK_NOT_ALLOWED", "LOCKS_OR_CHANGES_ANSWER_STANDARD", "DELETES_OR_OVERWRITES_ROW", "TYPE_NOT_ALLOWED"),
            traces.recent(1).single().rejected.map { it.reason },
        )
        assertTrue(cycleRow(c.id).text("Summary").contains("4 of 5 proposed tasks were rejected by validation"), cycleRow(c.id).text("Summary"))
    }

    @Test
    fun `the plan never exceeds the cycle's own task budget`() {
        val c = cycle(goal().id, extra = mapOf("Task Budget" to NotionValue.NumberValue(2.0)))
        run(processor(llmPlan(*(1..6).map { read("Task $it") }.toTypedArray())))
        assertEquals(2, tasksFor(c.id).size)
        assertEquals(2.0, cycleRow(c.id).number("Task Budget"), "a budget the cycle set is kept")
    }

    @Test
    fun `a mistyped huge budget is limited so one cycle cannot become hundreds of writes`() {
        val c = cycle(goal(budget = 1000.0).id)
        run(processor(llmPlan(*(1..40).map { read("Task $it") }.toTypedArray())))
        assertEquals(CycleProcessor.MAX_BUDGET, tasksFor(c.id).size)
        assertTrue(cycleRow(c.id).text("Summary").contains("limited to ${CycleProcessor.MAX_BUDGET}"), cycleRow(c.id).text("Summary"))
    }

    @Test
    fun `when nothing the model proposed is valid the cycle fails and no row is written`() {
        val c = cycle(goal().id)
        run(processor(llmPlan(ScriptedLlm.taskJson("Delete it", "Check", "Delete the claim row."), ScriptedLlm.taskJson("Gate", "Gate", "Decide."))))
        assertEquals("Failed", cycleRow(c.id).select("Status"))
        assertTrue(cycleRow(c.id).text("Summary").startsWith("Dry run failed: no valid tasks."), cycleRow(c.id).text("Summary"))
        assertTrue(tasksFor(c.id).isEmpty())
    }

    @Test
    fun `an empty plan fails the cycle rather than finishing it as done`() {
        val c = cycle(goal().id)
        run(processor(llmPlan()))
        assertEquals("Failed", cycleRow(c.id).select("Status"))
    }

    // ── A model that is down fails the cycle; nothing is re-queued ───────────

    @Test
    fun `with no model, or a model that throws, the cycle fails with a plain reason and stays failed`() {
        val a = cycle(goal().id)
        run(processor(null))
        assertEquals("Failed", cycleRow(a.id).select("Status"))
        assertTrue(cycleRow(a.id).text("Summary").contains("no local model is available"))

        val b = cycle(goal(title = "another").id)
        run(processor(ScriptedLlm { throw IllegalStateException("boom with SECRET-PROMPT") }))
        assertEquals("Failed", cycleRow(b.id).select("Status"))
        assertTrue(cycleRow(b.id).text("Summary").contains("the model call failed (IllegalStateException)"))
        assertFalse(cycleRow(b.id).text("Summary").contains("SECRET"))

        assertTrue(run(processor(llmPlan(read("never")))).isEmpty(), "a failed cycle is not picked up again")
    }

    // ── What the planner is given ────────────────────────────────────────────

    @Test
    fun `the prompt carries Jacob's resolutions and the last cycle's summary`() {
        val g = goal()
        cycle(g.id, "Done", mapOf("Summary" to NotionValue.RichText("Dry run: 4 planned tasks written; nothing was executed.")))
        escalation(g.id, "Box B.")
        cycle(g.id)
        val llm = llmPlan(read("Read something"))

        run(processor(llm))

        val prompt = llm.requests.single().prompt
        assertTrue(prompt.contains("Error found [Resolved]: Which box is right? => Box B."), prompt)
        assertTrue(prompt.contains("LAST CYCLE (CYCLE-1) SUMMARY:") && prompt.contains("4 planned tasks written"), prompt)
    }

    @Test
    fun `the links of the goal are reported in the trace, a page that is not a row being skipped`() {
        val howItWorks = fake.createPlainPage(listOf("The locking protocol."))
        source("Memo")
        val c = cycle(goal(links = listOf(sourcesDatabaseUrl, "https://www.notion.so/${howItWorks.replace("-", "")}")).id)

        run(processor(llmPlan(read("Read the memo"))))

        val trace = traces.recent(1).single()
        assertEquals(listOf("table", "skipped"), trace.links.map { it.kind })
        assertTrue(trace.links[1].detail!!.startsWith("not_a_configured_row"))
        assertTrue(cycleRow(c.id).text("Summary").contains("1 linked page was skipped"), cycleRow(c.id).text("Summary"))
    }

    // ── Acceptance: a restart mid-cycle ──────────────────────────────────────

    private fun expiredLease() = NotionValue.DateValue("2026-10-12T09:00:00.000+00:00")
    private fun deadWorker(extra: Map<String, NotionValue> = emptyMap()) = mapOf("Worker" to NotionValue.RichText("dead-process"), "Lease Until" to expiredLease()) + extra

    @Test
    fun `a running cycle whose lease has expired is taken over, keeping its original start`() {
        val c = cycle(
            goal().id, "Running",
            deadWorker(mapOf("Started" to NotionValue.DateValue("2026-10-12T09:00:00.000+00:00"), "Task Budget" to NotionValue.NumberValue(3.0))),
        )

        run(processor(llmPlan(read("Read A"), read("Read B")), worker = "arx-2"))

        val row = cycleRow(c.id)
        assertEquals("Done", row.select("Status"))
        assertEquals("arx-2", row.text("Worker"))
        assertEquals("2026-10-12T09:00:00.000+00:00", row.date("Started")!!.start, "a takeover must not rewrite the start")
        assertEquals(2, tasksFor(c.id).size)
        assertTrue(traces.recent(1).single().reclaimed)
    }

    @Test
    fun `a cycle taken over after its plan was fully written is completed without asking the model or writing again`() {
        val c = cycle(goal().id, "Running", deadWorker(mapOf("Summary" to NotionValue.RichText("Dry run in progress: writing 3 planned tasks."))))
        (1..3).forEach { taskRow(c.id, it) }
        val llm = llmPlan(read("never asked"))
        val creates = fake.requestCount("createPage")

        run(processor(llm, worker = "arx-2"))

        assertTrue(llm.requests.isEmpty(), "the model was asked again")
        assertEquals(creates, fake.requestCount("createPage"), "rows were created again")
        assertEquals(3, tasksFor(c.id).size)
        assertEquals("Done", cycleRow(c.id).select("Status"))
        assertTrue(cycleRow(c.id).text("Summary").contains("Resumed after a restart"))
    }

    @Test
    fun `a cycle taken over with only part of its plan written is failed on purpose, never completed from a guess`() {
        val c = cycle(goal().id, "Running", deadWorker(mapOf("Summary" to NotionValue.RichText("Dry run in progress: writing 4 planned tasks."))))
        (1..2).forEach { taskRow(c.id, it) }
        val llm = llmPlan(read("never asked"))
        val creates = fake.requestCount("createPage")

        run(processor(llm, worker = "arx-2"))

        assertTrue(llm.requests.isEmpty())
        assertEquals(creates, fake.requestCount("createPage"), "a partial plan was added to")
        assertEquals(2, tasksFor(c.id).size)
        assertEquals("Failed", cycleRow(c.id).select("Status"))
        assertTrue(cycleRow(c.id).text("Summary").contains("2 of 4 planned tasks were found"), cycleRow(c.id).text("Summary"))
        assertTrue(cycleRow(c.id).text("Summary").contains("Queue a new cycle"))
    }

    @Test
    fun `tasks found with no record of how many were meant are not treated as a finished plan`() {
        val c = cycle(goal().id, "Running", deadWorker())
        taskRow(c.id, 1)
        run(processor(llmPlan(read("never")), worker = "arx-2"))
        assertEquals("Failed", cycleRow(c.id).select("Status"))
        assertTrue(cycleRow(c.id).text("Summary").contains("1 of an unknown number"), cycleRow(c.id).text("Summary"))
    }

    @Test
    fun `a running cycle with a live lease is left alone, and so is a queued cycle of the same goal`() {
        val g = goal()
        val live = cycle(g.id, "Running", mapOf("Worker" to NotionValue.RichText("other-director"), "Lease Until" to NotionValue.DateValue("2026-10-12T10:01:00.000+00:00")))
        val waiting = cycle(g.id)
        val llm = llmPlan(read("never"))

        assertTrue(run(processor(llm)).isEmpty())

        assertEquals("other-director", cycleRow(live.id).text("Worker"))
        assertEquals("Queued", cycleRow(waiting.id).select("Status"))
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `a running cycle with no lease at all counts as expired`() {
        val c = cycle(goal().id, "Running", mapOf("Worker" to NotionValue.RichText("dead")))
        run(processor(llmPlan(read("Read A")), worker = "arx-2"))
        assertEquals("Done", cycleRow(c.id).select("Status"))
    }

    // ── Acceptance: no duplicate plan rows ───────────────────────────────────

    @Test
    fun `a create that landed but whose answer was lost is found by its Op key and not made twice`() {
        val c = cycle(goal().id)
        val llm = llmPlan(read("Read A"), read("Read B"), read("Read C"))
        fake.failAfterApplying("createPage", ping502)

        run(processor(llm))

        assertEquals(3, tasksFor(c.id).size, "a duplicate row was made")
        assertEquals(listOf("cycle:CYCLE-1:v1:o1", "cycle:CYCLE-1:v1:o2", "cycle:CYCLE-1:v1:o3"), tasksFor(c.id).map { it.text("Op key") })
        assertEquals("Done", cycleRow(c.id).select("Status"))
        assertEquals(1, traces.recent(1).single().writes.count { it.outcome == "RECOVERED" })
    }

    @Test
    fun `a create that never landed leaves the cycle running, and the takeover plans it again with no duplicates`() {
        val c = cycle(goal().id)
        val llm = llmPlan(read("Read A"), read("Read B"))
        fake.failNext("createPage", ping502)

        val first = run(processor(llm, worker = "arx-1"))
        assertTrue(first.single() is TickResult.Aborted)
        assertEquals("Running", cycleRow(c.id).select("Status"), "the cycle must be left for a takeover, not finished")
        assertEquals(0, tasksFor(c.id).size)

        clockNow = base.plusSeconds(300) // past the lease
        val second = run(processor(llm, worker = "arx-2"))

        assertEquals(CycleStatus.DONE, (second.single() as TickResult.Finished).status)
        assertEquals(listOf("cycle:CYCLE-1:v1:o1", "cycle:CYCLE-1:v1:o2"), tasksFor(c.id).map { it.text("Op key") })
        assertEquals("arx-2", cycleRow(c.id).text("Worker"))
    }

    @Test
    fun `a restart after some rows were written fails the cycle and leaves those rows exactly as they are`() {
        val c = cycle(goal().id)
        val llm = llmPlan(read("Read A"), read("Read B"), read("Read C"))
        // The second create never lands. The wrapper sits below the rate limiter, as a dropped connection would.
        val failSecondCreate = object : NotionApi by fake {
            private var creates = 0
            override suspend fun createPage(dataSourceId: String, properties: Map<String, NotionValue>): NotionPage {
                if (++creates == 2) throw ping502
                return fake.createPage(dataSourceId, properties)
            }
        }

        val first = run(processor(llm, worker = "arx-1", over = stackOver(failSecondCreate)))
        assertTrue(first.single() is TickResult.Aborted)
        val rowsLeft = tasksFor(c.id).map { it.id }
        assertEquals(1, rowsLeft.size)

        clockNow = base.plusSeconds(300)
        val llmAgain = llmPlan(read("never asked"))
        run(processor(llmAgain, worker = "arx-2"))

        assertTrue(llmAgain.requests.isEmpty())
        assertEquals("Failed", cycleRow(c.id).select("Status"))
        assertEquals(rowsLeft, tasksFor(c.id).map { it.id }, "the takeover added or removed rows")
        assertTrue(cycleRow(c.id).text("Summary").contains("1 of 3 planned tasks were found"), cycleRow(c.id).text("Summary"))
    }

    @Test
    fun `a cycle taken over more often than the limit is failed instead of looping`() {
        val c = cycle(goal().id)
        val llm = llmPlan(read("Read A"))
        val p = processor(llm, worker = "arx-1", maxAttempts = 1)
        fake.failNext("createPage", ping502)

        assertTrue(run(p).single() is TickResult.Aborted)
        assertEquals("Running", cycleRow(c.id).select("Status"))

        clockNow = base.plusSeconds(300)
        val second = run(p)

        assertEquals(CycleStatus.FAILED, (second.single() as TickResult.Finished).status)
        assertTrue(cycleRow(c.id).text("Summary").startsWith("Dry run abandoned"), cycleRow(c.id).text("Summary"))
        assertEquals(1, llm.requests.size, "the model was asked again after the limit")
    }

    // ── A claim that another process wins ────────────────────────────────────

    @Test
    fun `if another worker holds the cycle after the claim, nothing is planned`() {
        val c = cycle(goal().id)
        val llm = llmPlan(read("never"))
        val racing = object : NotionApi by api {
            override suspend fun updatePage(pageId: String, properties: Map<String, NotionValue>): NotionPage {
                val result = api.updatePage(pageId, properties)
                if (properties["Worker"] != null) fake.updatePage(pageId, mapOf("Worker" to NotionValue.RichText("other-director")))
                return result
            }
        }

        val results = run(processor(llm, over = racing))

        assertTrue(results.single() is TickResult.LostClaim)
        assertTrue(llm.requests.isEmpty())
        assertEquals("other-director", cycleRow(c.id).text("Worker"))
        assertEquals("Running", cycleRow(c.id).select("Status"))
        assertTrue(tasksFor(c.id).isEmpty())
        assertEquals("lost_claim", traces.recent(1).single().outcome)
    }

    @Test
    fun `a cycle that is no longer ready when it is re-read is not claimed`() {
        val c = cycle(goal().id)
        val cancelling = object : NotionApi by api {
            private var first = true
            override suspend fun retrievePage(pageId: String): NotionPage {
                if (pageId == c.id && first) { first = false; fake.updatePage(c.id, mapOf("Status" to NotionValue.Select("Cancelled"))) }
                return api.retrievePage(pageId)
            }
        }
        val results = run(processor(llmPlan(read("never")), over = cancelling))
        assertTrue(results.single() is TickResult.LostClaim)
        assertEquals("Cancelled", cycleRow(c.id).select("Status"))
    }

    // ── Several cycles ───────────────────────────────────────────────────────

    @Test
    fun `cycles are taken oldest first, and several in one poll`() {
        val older = cycle(goal(title = "first goal").id)
        val newer = cycle(goal(title = "second goal").id)

        val results = run(processor(llmPlan(read("Read A"))))

        assertEquals(listOf("CYCLE-1", "CYCLE-2"), results.map { (it as TickResult.Finished).cycle })
        assertEquals(1, tasksFor(older.id).size)
        assertEquals(1, tasksFor(newer.id).size)
    }

    @Test
    fun `two queued cycles of one goal run one after the other`() {
        val g = goal()
        val a = cycle(g.id)
        val b = cycle(g.id)
        assertEquals(2, run(processor(llmPlan(read("Read A")))).size)
        assertEquals("Done", cycleRow(a.id).select("Status"))
        assertEquals("Done", cycleRow(b.id).select("Status"))
    }

    @Test
    fun `a poll does no more than its limit of cycles`() {
        val g = goal()
        repeat(4) { cycle(g.id) }
        val limited = CycleProcessor(api, set, CyclePlanDirector(llmPlan(read("Read A"))), traces, "arx-1", { clockNow }, maxCyclesPerTick = 2, sleep = {})
        assertEquals(2, run(limited).size)
        assertEquals(2, fake.rows(t.cycles).count { it.select("Status") == "Queued" })
    }

    // ── Notion trouble ───────────────────────────────────────────────────────

    @Test
    fun `if the cycles cannot be listed the poll reports an error and does not throw`() {
        fake.failNext("query", NotionApiException(500, "internal_server_error", "x"), times = 10)
        assertTrue(run(processor(llmPlan(read("never")))).single() is TickResult.Error)
    }

    // ── Heartbeat (virtual time) ─────────────────────────────────────────────

    @Test
    fun `the lease is renewed while the model works, so a long call does not look like a crash`() = runTest {
        val c = cycle(goal().id)
        val slow = object : LlmClient {
            override suspend fun complete(request: LlmRequest): LlmResponse {
                delay(100_000)
                return LlmResponse("", toolCalls = listOf(ToolCall(CyclePlanDirector.TOOL_NAME, ScriptedLlm.planOf(read("Read A")))), latencyMs = 100_000, retryCount = 0)
            }
        }
        val p = CycleProcessor(
            api, set, CyclePlanDirector(slow), traces, "arx-1", { base.plusMillis(currentTime) },
            leaseDuration = Duration.ofMinutes(2), heartbeatInterval = Duration.ofSeconds(40), sleep = {},
        )

        var results: List<TickResult> = emptyList()
        val job = launch { results = p.runOnce() }
        advanceTimeBy(10_000); runCurrent()
        val firstLease = Instant.parse(cycleRow(c.id).date("Lease Until")!!.start!!)
        assertEquals("Running", cycleRow(c.id).select("Status"))

        advanceTimeBy(40_000); runCurrent() // 50 seconds in: one heartbeat has fired
        val afterHeartbeat = Instant.parse(cycleRow(c.id).date("Lease Until")!!.start!!)
        assertTrue(afterHeartbeat.isAfter(firstLease), "the lease was not extended: $firstLease -> $afterHeartbeat")

        advanceTimeBy(100_000); runCurrent() // well past the model call
        job.join()

        assertEquals("Done", cycleRow(c.id).select("Status"))
        assertTrue(fake.requests.count { it.operation == "updatePage" } >= 5, "claim, two heartbeats, progress and finish should all have been written")
        assertTrue(results.single() is TickResult.Finished)
    }

    // ── The trace ────────────────────────────────────────────────────────────

    @Test
    fun `the trace records what was decided, in a form that serializes`() {
        source("Memo")
        cycle(goal().id)

        run(processor(llmPlan(read("Read the memo"), ScriptedLlm.taskJson("Delete", "Check", "Delete the claim row."), rationale = "memo first")))
        val trace = traces.recent(1).single()

        assertEquals("CYCLE-1", trace.cycle)
        assertEquals("GOAL-1", trace.goal)
        assertEquals("done", trace.outcome)
        assertEquals("arx-1", trace.worker)
        assertEquals(2, trace.model!!.tasksProposed)
        assertEquals("memo first", trace.model!!.rationale)
        assertEquals(1, trace.accepted.size)
        assertEquals(1, trace.rejected.size)
        assertEquals("cycle:CYCLE-1:v1:o1", trace.writes.single().opKey)
        assertNotNull(trace.finishedAt)

        val json = Json.encodeToString(CyclePlanTrace.serializer(), trace)
        assertFalse(json.contains("Bearer") || json.contains("ntn_"))
    }

    @Test
    fun `the store keeps only the most recent traces, newest first, and hands out copies`() {
        val store = CycleTraceStore(capacity = 3)
        (1..5).forEach { store.add(CyclePlanTrace("CYCLE-$it", "p$it", null, null, "w", "t")) }
        assertEquals(listOf("CYCLE-5", "CYCLE-4", "CYCLE-3"), store.recent().map { it.cycle })
        assertEquals(2, store.recent(2).size)
        store.recent(1).single().notes += "mutated"
        assertTrue(store.recent(1).single().notes.isEmpty())
    }
}
