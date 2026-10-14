package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.pipeline.agentwork.CycleProcessor.TickResult
import app.alfrd.engram.notion.FakeBlock
import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionApiException
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The acceptance cases for a whole cycle: a Queued cycle on an Active goal is planned, executed
 * report-only, QC'd and closed in one run; a goal with no mentions fails clearly; links and reads outside
 * Scope are rejected in code; a restart mid-cycle reclaims the lease, makes no duplicate rows and never
 * re-runs a task that is already settled. Everything goes through the real processor, the real guard and
 * the real configuration, over the in-memory Notion.
 */
class CycleExecutionTest {

    private val f = AgentWorkFixture()
    private val t = f.template
    private val traces = CycleTraceStore()
    private var now: Instant = f.base

    private val itemsDatabaseId = f.tableSet.table(AgentWorkTables.MEMORY_ITEMS).databaseId!!
    private val personaUrl get() = notionPageUrl(t.personaPageId)
    private val itemsUrl get() = notionPageUrl(itemsDatabaseId)

    private fun processor(llm: RoutedLlm, worker: String = "arx-1", over: NotionApi = f.guarded(), maxAttempts: Int = 3) = CycleProcessor(
        over, f.tableSet, CyclePlanDirector(llm), DirectorTaskExecutor(llm), CycleQcDirector(llm), traces, worker, { now },
        leaseDuration = Duration.ofMinutes(2), maxAttemptsPerCycle = maxAttempts, sleep = {},
    )

    private fun run(p: CycleProcessor): List<TickResult> = runBlocking { p.runOnce() }
    private fun tick() { f.fake.now = f.fake.now.plusSeconds(1) }

    private fun item(title: String, said: String = "said: $title"): NotionPage = runBlocking {
        f.fake.createPage(t.items, mapOf("Item" to NotionValue.Title(title), "Said" to NotionValue.RichText(said), "Status" to NotionValue.Select("Open"))).also { tick() }
    }

    private fun personaAndItemsGoal(title: String = "Reconcile Persona with what alfrd currently knows"): NotionPage =
        f.goal(title = title, scope = scopeOf("", pageMention(t.personaPageId, "Persona"), databaseMention(itemsDatabaseId, "Items")))

    private fun read(title: String, vararg links: String) = ScriptedLlm.taskJson(title, "Read", "Read it and report what it says.", *links)
    private fun check(title: String, vararg links: String) = ScriptedLlm.taskJson(title, "Check", "Compare and report differences.", *links)
    private fun consolidate(title: String) = ScriptedLlm.taskJson(title, "Consolidate", "Put the earlier findings together.")

    private fun cycleRow(id: String) = f.fake.page(id)
    private fun statusesOf(cycleId: String) = f.tasksFor(cycleId).map { it.select("Status") }

    /** The task handler that records what each task was given, answers done, and cites every staged page. */
    private class Recorder {
        val prompts = ArrayList<String>()
        val handler: (app.alfrd.engram.cognitive.providers.LlmRequest) -> JsonObject = { req ->
            prompts += req.prompt
            RoutedLlm.doingEveryTask(req)
        }
    }

    // ── Acceptance: planned, executed and QC'd in one run ────────────────────

    @Test
    fun `a queued cycle on an active goal is planned, executed in order and QC'd in one run, with a Result on every task and Summary plus Goal Feedback on the cycle`() {
        f.fake.setBlocks(t.personaPageId, listOf(FakeBlock("heading_1", "Voice"), FakeBlock("paragraph", "Jacob prefers short answers."), FakeBlock("toggle", "Rules", children = listOf(FakeBlock("paragraph", "Never loud.")))))
        item("Wants brevity", said = "I like short answers")
        item("Hates puns")
        val goal = personaAndItemsGoal()
        val c = f.cycle(goal.id)
        val recorder = Recorder()
        val llm = RoutedLlm(
            RoutedLlm.plan(read("Read Persona", personaUrl), check("Compare Persona with the Items", personaUrl, itemsUrl), consolidate("Gather the differences")),
            recorder.handler,
        ) { RoutedLlm.qcOf(summary = "Persona was compared with 2 Items.", looksMet = "partly", feedback = "Done when did not say what supported means.", rewriteField = "Done when", rewriteText = "Done when: every claim is supported or not.") }

        val results = run(processor(llm))

        val finished = results.single() as TickResult.Finished
        assertEquals(CycleStatus.DONE, finished.status)
        val row = cycleRow(c.id)
        assertEquals("Done", row.select("Status"))
        assertEquals(3.0, row.number("Tasks Done"))
        assertNotNull(row.date("Finished"))
        assertNull(row.date("Lease Until"), "the lease is released")
        assertEquals(
            "Executed 3 tasks: 3 done, 0 blocked, 0 failed. Persona was compared with 2 Items. " +
                "Goal looks met: partly (the goal's Status is yours to change; it was not touched).",
            row.text("Summary"),
        )
        assertEquals("Done when did not say what supported means.\n\nSuggested rewrite of Done when: Done when: every claim is supported or not.", row.text("Goal Feedback"))

        val tasks = f.tasksFor(c.id)
        assertEquals(listOf("Done", "Done", "Done"), tasks.map { it.select("Status") })
        assertTrue(tasks.all { it.text("Result").isNotBlank() && it.date("Started") != null && it.date("Finished") != null })
        assertTrue(tasks[0].text("Result").contains("Pages used:") && tasks[0].text("Result").contains(personaUrl), tasks[0].text("Result"))
        assertTrue(tasks[1].text("Result").contains(personaUrl) && tasks[1].text("Result").contains("Wants brevity"), "the second task cites the Persona page and the Item rows: ${tasks[1].text("Result")}")
        assertTrue(tasks[2].text("Result").contains("Wants brevity") && tasks[2].text("Result").contains("Hates puns") && tasks[2].text("Result").contains(personaUrl), "the Consolidate task, which linked nothing, was still given and cites the source: ${tasks[2].text("Result")}")

        // What each task was actually given.
        assertEquals(3, recorder.prompts.size)
        assertTrue(recorder.prompts[0].contains("Jacob prefers short answers.") && recorder.prompts[0].contains("  Never loud."), "Persona's blocks, one level of children included, reached the worker")
        assertTrue(recorder.prompts[1].contains("Wants brevity") && recorder.prompts[1].contains("Hates puns"), "the Items rows reached the worker")
        assertTrue(recorder.prompts[2].contains("Wants brevity") && recorder.prompts[2].contains("I like short answers") && recorder.prompts[2].contains("Jacob prefers short answers."), "the Consolidate task is given the source rows and the Persona page, not only earlier results")
        assertTrue(recorder.prompts[2].contains("EARLIER WORK BY OTHER TASKS OF THIS CYCLE (notes only"), "and earlier results are labelled as notes")
        assertTrue(recorder.prompts[2].contains("- Task 1 Read Persona [Done]") && recorder.prompts[2].contains("- Task 2 Compare Persona with the Items [Done]"), "earlier results reach later tasks")
        assertEquals(1, llm.planRequests.size)
        assertEquals(1, llm.qcRequests.size)
        assertTrue(llm.qcRequests.single().prompt.contains("Task 1 [Done] (Read) Read Persona"), llm.qcRequests.single().prompt)
    }

    @Test
    fun `tasks run strictly in order, each marked Running before its work and settled in one write after it`() {
        personaAndItemsGoal()
        val c = f.cycle(f.fake.rows(t.goals).single().id)
        run(processor(RoutedLlm.happy(read("A", personaUrl), read("B", personaUrl), read("C", personaUrl))))

        val taskIds = f.tasksFor(c.id).map { it.id }
        val taskWrites = f.fake.requests.filter { it.operation == "updatePage" && it.target in taskIds }.map { taskIds.indexOf(it.target) + 1 }
        assertEquals(listOf(1, 1, 2, 2, 3, 3), taskWrites, "start, then one settling write, task by task")
    }

    @Test
    fun `nothing is written anywhere but the cycle and its tasks, the goal is untouched, and nothing in memory or PPS changes`() {
        item("an item")
        f.source("a source")
        val goal = personaAndItemsGoal()
        val c = f.cycle(goal.id) // creating the cycle fills the goal's Cycles relation; the snapshot is taken after it
        val before = f.fake.page(goal.id)
        val rowsBefore = AgentWorkTables.ALL.filter { it !in AgentWorkTables.WRITABLE }.associateWith { name -> f.fake.rows(f.tableSet.table(name).dataSourceId).map { it.id to it.lastEditedTime } }
        val mark = f.fake.requests.size

        run(processor(RoutedLlm.happy(read("Read Persona", personaUrl), check("Check items", itemsUrl))))

        val taskIds = f.tasksFor(c.id).map { it.id }.toSet()
        val writes = f.fake.requests.drop(mark).filter { it.operation in setOf("createPage", "updatePage", "appendBlocks") }
        assertTrue(writes.isNotEmpty())
        assertTrue(writes.all { (it.operation == "createPage" && it.target == t.tasks) || (it.operation == "updatePage" && (it.target == c.id || it.target in taskIds)) || (it.operation == "appendBlocks" && it.target == c.id) }, writes.toString())
        assertTrue(writes.filter { it.operation == "appendBlocks" }.all { it.target == c.id }, "the only body append is the cycle report, on the Cycle's own page")
        assertEquals(before.properties, f.fake.page(goal.id).properties, "the goal row changed")
        assertEquals("Active", f.fake.page(goal.id).select("Status"))
        for ((name, rows) in rowsBefore) assertEquals(rows, f.fake.rows(f.tableSet.table(name).dataSourceId).map { it.id to it.lastEditedTime }, "$name changed")
    }

    // ── Acceptance: a goal with no mentions fails clearly ────────────────────

    @Test
    fun `a goal whose Scope has no mentions produces a clear Failed cycle with the reason, a suggested Scope rewrite, no model call and no task`() {
        val goal = f.goal(scope = NotionValue.RichText("The 10 listed primary documents and nothing about locking."))
        val c = f.cycle(goal.id)
        val llm = RoutedLlm() // any model call fails the test

        val result = run(processor(llm)).single() as TickResult.Finished

        assertEquals(CycleStatus.FAILED, result.status)
        assertTrue(llm.requests.isEmpty())
        assertTrue(f.tasksFor(c.id).isEmpty())
        val row = cycleRow(c.id)
        assertEquals("Failed", row.select("Status"))
        assertTrue(row.text("Summary").startsWith("Cycle failed: the goal's Scope has no page or database mentions, so there is nothing the Director may read."), row.text("Summary"))
        assertTrue(row.text("Summary").contains("Only @-mentions count; words and links in Scope are ignored."))
        assertTrue(row.text("Summary").contains("The 10 listed primary documents"), "the prose that was ignored is reported: ${row.text("Summary")}")
        assertTrue(row.text("Goal Feedback").contains("Suggested rewrite of Scope: @-mention each page and database"), row.text("Goal Feedback"))
        assertEquals(0.0, row.number("Tasks Done"))
        assertNotNull(row.date("Finished"))
        val trace = traces.recent(1).single()
        assertEquals("failed", trace.outcome)
        assertEquals(0, trace.scope!!.mentions)
        assertTrue(trace.scope!!.proseChars > 0 && trace.scope!!.proseExcerpt!!.startsWith("The 10 listed"))
    }

    @Test
    fun `a goal with an empty Scope, or a Scope of only links, is the same failure and the links are reported as ignored`() {
        val empty = f.cycle(f.goal(title = "empty", scope = NotionValue.RichText("")).id)
        val linksOnly = f.cycle(
            f.goal(
                title = "links",
                scope = NotionValue.RichText("see https://example.org/spec", links = listOf("https://example.org/spec", personaUrl)),
            ).id,
        )
        val llm = RoutedLlm()

        val results = run(processor(llm))

        assertEquals(2, results.size)
        for (c in listOf(empty, linksOnly)) {
            assertEquals("Failed", cycleRow(c.id).select("Status"))
            assertTrue(cycleRow(c.id).text("Summary").contains("no page or database mentions"))
        }
        assertTrue(llm.requests.isEmpty())
        val scope = traces.recent(2).first { it.goalTitle == "links" }.scope!!
        assertEquals(0, scope.mentions)
        assertTrue("https://example.org/spec" in scope.ignoredLinks && personaUrl in scope.ignoredLinks, "a Notion link that is not a mention is not scope: ${scope.ignoredLinks}")
    }

    @Test
    fun `mentions that none of can be read fail the cycle with the reasons, before any planning`() {
        val stranger = f.fake.createPlainPage(listOf("private"))
        val goal = f.goal(scope = scopeOf("", pageMention(stranger, "Private notes"), databaseMention("99999999-9999-4999-8999-999999999999", "Somebody's database")))
        val c = f.cycle(goal.id)
        val llm = RoutedLlm()

        run(processor(llm))

        val row = cycleRow(c.id)
        assertEquals("Failed", row.select("Status"))
        assertTrue(row.text("Summary").contains("none of the 2 page or database mentions in the goal's Scope could be read"), row.text("Summary"))
        assertTrue(row.text("Summary").contains("Private notes: not readable") && row.text("Summary").contains("Somebody's database: database not configured"), row.text("Summary"))
        assertTrue(row.text("Goal Feedback").contains("Suggested rewrite of Scope"))
        assertTrue(row.text("Goal Feedback").contains("readablePages allowlist"), "the error names the allowlist: ${row.text("Goal Feedback")}")
        assertTrue(llm.requests.isEmpty())
        assertTrue(f.fake.requests.none { it.operation == "listBlocks" && it.target == stranger }, "the private page was never read")
    }

    // ── Acceptance: links and reads outside Scope are rejected in code ───────

    @Test
    fun `a plan that links outside Scope has those tasks rejected before anything is written`() {
        val outside = runBlocking { f.fake.createPage(t.findings, mapOf("Claim" to NotionValue.Title("a finding the goal never mentioned"))) }
        val c = f.cycle(personaAndItemsGoal().id)
        val llm = RoutedLlm.happy(
            read("Read Persona", personaUrl),
            read("Read the finding", notionPageUrl(outside.id)),
            read("Read a site", "https://example.org/spec"),
        )

        run(processor(llm))

        assertEquals(listOf("Read Persona"), f.tasksFor(c.id).map { it.title("Task") })
        assertEquals(listOf("LINK_NOT_ALLOWED", "LINK_NOT_ALLOWED"), traces.recent(1).single().rejected.map { it.reason })
        assertTrue(cycleRow(c.id).text("Summary").contains("2 proposed tasks were rejected by validation"))
        // The cycle's own page is listed once, to look for its report; apart from that only the one accepted task read a page's blocks.
        assertEquals(0, f.fake.requests.count { it.operation == "listBlocks" && it.target != c.id } - 1, "only the one accepted task read a page's blocks")
    }

    @Test
    fun `a task edited in Notion to point outside Scope is Blocked at execution, nothing outside is read, and the cycle goes on`() {
        val secret = runBlocking { f.fake.createPage(t.findings, mapOf("Claim" to NotionValue.Title("SECRET FINDING TITLE"))) }
        f.fake.setBlocks(secret.id, listOf(FakeBlock("paragraph", "SECRET FINDING BODY")))
        val goal = personaAndItemsGoal()
        val c = f.cycle(goal.id, "Running")
        runBlocking {
            f.fake.updatePage(c.id, mapOf("Worker" to NotionValue.RichText("dead"), "Lease Until" to NotionValue.DateValue("2026-10-12T09:00:00.000+00:00"), "Summary" to NotionValue.RichText("Cycle in progress: writing 2 planned tasks.")))
            for ((order, links) in listOf(1 to notionPageUrl(secret.id), 2 to personaUrl)) {
                f.fake.createPage(
                    t.tasks,
                    mapOf(
                        "Task" to NotionValue.Title("Task $order"), "Type" to NotionValue.Select("Read"), "Order" to NotionValue.NumberValue(order.toDouble()),
                        "Status" to NotionValue.Select("Planned"), "Plan Version" to NotionValue.NumberValue(1.0), "Instructions" to NotionValue.RichText("Read it."),
                        "Target Links" to NotionValue.RichText(links), "Op key" to NotionValue.RichText("cycle:CYCLE-1:v1:o$order"), "Cycle" to NotionValue.Relation(listOf(c.id)),
                    ),
                )
            }
        }
        val recorder = Recorder()
        val llm = RoutedLlm(task = recorder.handler) { RoutedLlm.qcOf() }

        run(processor(llm, worker = "arx-2"))

        val tasks = f.tasksFor(c.id)
        assertEquals(listOf("Blocked", "Done"), tasks.map { it.select("Status") })
        assertTrue(tasks[0].text("Result").startsWith("Blocked: this task names 1 page outside the goal's Scope"), tasks[0].text("Result"))
        assertEquals(1, recorder.prompts.size, "the blocked task never reached the worker")
        assertFalse(llm.everythingSent.contains("SECRET FINDING"), "something outside Scope reached the model")
        assertTrue(f.fake.requests.none { it.operation == "listBlocks" && it.target == secret.id }, "the page outside Scope was read")
        assertEquals(1.0, cycleRow(c.id).number("Tasks Done"))
        assertEquals("Done", cycleRow(c.id).select("Status"))
        assertTrue(cycleRow(c.id).text("Summary").startsWith("Executed 2 tasks: 1 done, 1 blocked, 0 failed."), cycleRow(c.id).text("Summary"))
    }

    // ── A task that cannot be done is Blocked; one whose model call fails is Failed; the cycle goes on ──

    @Test
    fun `a task the worker cannot do from what Scope allows is Blocked with its reason, and the next task still runs`() {
        val c = f.cycle(personaAndItemsGoal().id)
        var calls = 0
        val llm = RoutedLlm(
            RoutedLlm.plan(read("Read Persona", personaUrl), check("Compare with sessions", personaUrl), consolidate("Gather")),
            { req -> if (++calls == 2) RoutedLlm.taskBlocked("The Sessions table is not in Scope, so there is nothing to compare Persona with.") else RoutedLlm.doingEveryTask(req) },
        ) { RoutedLlm.qcOf() }

        run(processor(llm))

        val tasks = f.tasksFor(c.id)
        assertEquals(listOf("Done", "Blocked", "Done"), tasks.map { it.select("Status") })
        assertEquals("Blocked: The Sessions table is not in Scope, so there is nothing to compare Persona with.", tasks[1].text("Result"))
        assertEquals(2.0, cycleRow(c.id).number("Tasks Done"), "only Done tasks count")
        assertEquals("Done", cycleRow(c.id).select("Status"))
        assertTrue(cycleRow(c.id).text("Summary").startsWith("Executed 3 tasks: 2 done, 1 blocked, 0 failed."))
    }

    @Test
    fun `a task whose model call fails is marked Failed with a code, not retried, and the cycle goes on and is QC'd`() {
        val c = f.cycle(personaAndItemsGoal().id)
        var calls = 0
        val llm = RoutedLlm(
            RoutedLlm.plan(read("A", personaUrl), read("B", personaUrl), read("C", personaUrl)),
            { req -> if (++calls == 2) throw IllegalStateException("boom with SECRET-PROMPT-TEXT") else RoutedLlm.doingEveryTask(req) },
        ) { RoutedLlm.qcOf() }

        run(processor(llm))

        assertEquals(listOf("Done", "Failed", "Done"), statusesOf(c.id))
        val failed = f.tasksFor(c.id)[1].text("Result")
        assertEquals("Not executed: the model call failed (IllegalStateException). The task was not retried.", failed)
        assertFalse(failed.contains("SECRET"))
        assertEquals(3, calls, "the failed task was not asked again")
        assertEquals("Done", cycleRow(c.id).select("Status"))
        assertTrue(cycleRow(c.id).text("Summary").startsWith("Executed 3 tasks: 2 done, 0 blocked, 1 failed."))
    }

    @Test
    fun `a page the worker cites that was never staged is dropped from the Result`() {
        val c = f.cycle(personaAndItemsGoal().id)
        val llm = RoutedLlm(
            RoutedLlm.plan(read("Read Persona", personaUrl)),
            { RoutedLlm.taskDone("Persona read.", personaUrl, "https://example.org/made-up", itemsUrl) },
        ) { RoutedLlm.qcOf() }

        run(processor(llm))

        val result = f.tasksFor(c.id).single().text("Result")
        assertTrue(result.contains(personaUrl))
        assertFalse(result.contains("made-up") || result.contains(itemsUrl), "an unstaged page was cited: $result")
    }

    @Test
    fun `when QC cannot produce a close-out the cycle fails honestly, every Result stays, and no feedback is invented`() {
        val c = f.cycle(personaAndItemsGoal().id)
        val llm = RoutedLlm(RoutedLlm.plan(read("A", personaUrl)), RoutedLlm.doingEveryTask) { throw IllegalStateException("qc down") }

        run(processor(llm))

        val row = cycleRow(c.id)
        assertEquals("Failed", row.select("Status"))
        assertTrue(row.text("Summary").startsWith("Cycle failed at QC: the model call failed (IllegalStateException)."), row.text("Summary"))
        assertTrue(row.text("Summary").contains("1 task ran (1 done, 0 blocked, 0 failed) and their Results are on the Agent Tasks"), row.text("Summary"))
        assertEquals("", row.text("Goal Feedback"))
        assertEquals("Done", f.tasksFor(c.id).single().select("Status"))
        assertEquals(1.0, row.number("Tasks Done"))
    }

    @Test
    fun `with no model at all the cycle fails at planning, writes nothing but its own failure, and runs no task`() {
        val c = f.cycle(personaAndItemsGoal().id)
        run(processor(RoutedLlm(), over = f.guarded()).let { CycleProcessor(f.guarded(), f.tableSet, CyclePlanDirector(null), DirectorTaskExecutor(null), CycleQcDirector(null), traces, "arx-1", { now }, sleep = {}) })
        assertEquals("Failed", cycleRow(c.id).select("Status"))
        assertTrue(cycleRow(c.id).text("Summary").contains("no local model is available"))
        assertTrue(f.tasksFor(c.id).isEmpty())
    }

    // ── Acceptance: a restart mid-cycle ──────────────────────────────────────

    /** Fails, for good (a non-retryable 400), the nth write of a given kind. The wrapper sits below the guard and the limiter, as a dropped connection would. */
    private inner class Crasher(private val isTarget: (String, Map<String, NotionValue>) -> Boolean, private val nth: Int) : NotionApi by f.fake {
        private var seen = 0
        var armed = true
        override suspend fun updatePage(pageId: String, properties: Map<String, NotionValue>): NotionPage {
            if (armed && isTarget(pageId, properties) && ++seen == nth) throw NotionApiException(400, "validation_error", "simulated failure")
            return f.fake.updatePage(pageId, properties)
        }
    }

    private fun isTaskStart(props: Map<String, NotionValue>) = props["Status"] == NotionValue.Select("Running") && !props.containsKey("Worker")
    private fun isTaskSettle(props: Map<String, NotionValue>) = props.containsKey("Result")

    @Test
    fun `a restart after a task is Done reclaims the lease, makes no duplicate rows, and never runs the Done task again`() {
        val c = f.cycle(personaAndItemsGoal().id)
        val plan = RoutedLlm.plan(read("A", personaUrl), read("B", personaUrl), read("C", personaUrl))
        val first = Recorder()
        val llmA = RoutedLlm(plan, first.handler) { RoutedLlm.qcOf() }

        // The second task's start write never lands: the process "dies" there.
        val crash = Crasher({ _, props -> isTaskStart(props) }, nth = 2)
        val resultA = run(processor(llmA, worker = "arx-1", over = f.guarded(crash))).single()
        assertTrue(resultA is TickResult.Aborted, "got $resultA")
        assertEquals("Running", cycleRow(c.id).select("Status"), "left for a takeover")
        assertEquals(listOf("Done", "Planned", "Planned"), statusesOf(c.id))
        val taskOneBefore = f.tasksFor(c.id)[0]
        val idsBefore = f.tasksFor(c.id).map { it.id }

        now = f.base.plusSeconds(600) // past the lease
        val second = Recorder()
        val llmB = RoutedLlm(task = second.handler) { RoutedLlm.qcOf() } // no plan handler: planning again fails the test
        val resultB = run(processor(llmB, worker = "arx-2")).single() as TickResult.Finished

        assertEquals(CycleStatus.DONE, resultB.status)
        assertTrue(llmB.planRequests.isEmpty(), "the plan was made again")
        assertEquals(2, second.prompts.size, "only the two unfinished tasks were run")
        assertTrue(second.prompts.none { p -> p.lineSequence().any { it.startsWith("TASK 1 of 3") } }, "the Done task was run again")
        assertTrue(second.prompts.map { p -> p.lineSequence().first { it.startsWith("TASK ") }.substringBefore(":") } == listOf("TASK 2 of 3", "TASK 3 of 3"))
        assertEquals(idsBefore, f.tasksFor(c.id).map { it.id }, "rows were added or removed")
        assertEquals(listOf("cycle:CYCLE-1:v1:o1", "cycle:CYCLE-1:v1:o2", "cycle:CYCLE-1:v1:o3"), f.tasksFor(c.id).map { it.text("Op key") })
        val taskOneAfter = f.tasksFor(c.id)[0]
        assertEquals(taskOneBefore.text("Result"), taskOneAfter.text("Result"), "a Done task is never edited")
        assertEquals(taskOneBefore.date("Finished"), taskOneAfter.date("Finished"))
        assertEquals(listOf("Done", "Done", "Done"), statusesOf(c.id))
        assertEquals(3.0, cycleRow(c.id).number("Tasks Done"))
        assertEquals("arx-2", cycleRow(c.id).text("Worker"))
        assertTrue(traces.recent(1).single().reclaimed)
        assertEquals(listOf("already_settled", "done", "done"), traces.recent(1).single().execution.map { it.outcome })
        assertTrue(second.prompts[0].contains("- Task 1 A [Done]"), "the Done task's result is still shown to later tasks")
    }

    @Test
    fun `a task interrupted mid-run, left Running, is run again after the restart, and a Done one is not`() {
        val c = f.cycle(personaAndItemsGoal().id)
        val llmA = RoutedLlm(RoutedLlm.plan(read("A", personaUrl), read("B", personaUrl)), RoutedLlm.doingEveryTask) { RoutedLlm.qcOf() }

        // The first task's work is done but its settling write never lands: it is left Running, with no Result.
        val crash = Crasher({ _, props -> isTaskSettle(props) }, nth = 1)
        assertTrue(run(processor(llmA, worker = "arx-1", over = f.guarded(crash))).single() is TickResult.Aborted)
        assertEquals(listOf("Running", "Planned"), statusesOf(c.id))
        assertEquals("", f.tasksFor(c.id)[0].text("Result"))

        now = f.base.plusSeconds(600)
        val recorder = Recorder()
        val llmB = RoutedLlm(task = recorder.handler) { RoutedLlm.qcOf() }
        run(processor(llmB, worker = "arx-2"))

        assertEquals(2, recorder.prompts.size, "the interrupted task and the next one run")
        assertTrue(recorder.prompts[0].contains("TASK 1 of 2"))
        assertEquals(listOf("Done", "Done"), statusesOf(c.id))
        assertEquals(2, f.tasksFor(c.id).size, "no duplicate rows")
        assertEquals("Done", cycleRow(c.id).select("Status"))
    }

    @Test
    fun `a restart after every task is settled runs no task, plans nothing, and only closes the cycle with QC`() {
        val c = f.cycle(personaAndItemsGoal().id)
        val llmA = RoutedLlm(RoutedLlm.plan(read("A", personaUrl), read("B", personaUrl)), RoutedLlm.doingEveryTask) { throw IllegalStateException("qc down") }
        run(processor(llmA, worker = "arx-1")) // fails at QC and finishes Failed: put the cycle back as a crash between the last task and QC
        runBlocking {
            f.fake.updatePage(c.id, mapOf("Status" to NotionValue.Select("Running"), "Worker" to NotionValue.RichText("dead"), "Lease Until" to NotionValue.DateValue("2026-10-12T09:00:00.000+00:00"), "Summary" to NotionValue.RichText("Cycle in progress: writing 2 planned tasks.")))
        }
        now = f.base.plusSeconds(600)
        val llmB = RoutedLlm(qc = { RoutedLlm.qcOf(summary = "All settled.") })

        run(processor(llmB, worker = "arx-2"))

        assertTrue(llmB.planRequests.isEmpty() && llmB.taskRequests.isEmpty())
        assertEquals(1, llmB.qcRequests.size)
        assertEquals("Done", cycleRow(c.id).select("Status"))
        assertEquals(2.0, cycleRow(c.id).number("Tasks Done"))
    }

    @Test
    fun `if another worker takes the cycle mid-run, this one stops at the next task and writes nothing more to it`() {
        val c = f.cycle(personaAndItemsGoal().id)
        val llm = RoutedLlm(RoutedLlm.plan(read("A", personaUrl), read("B", personaUrl), read("C", personaUrl)), RoutedLlm.doingEveryTask) { RoutedLlm.qcOf() }
        // Once the first task is settled, another Director takes over the cycle.
        val taker = object : NotionApi by f.fake {
            override suspend fun updatePage(pageId: String, properties: Map<String, NotionValue>): NotionPage {
                val result = f.fake.updatePage(pageId, properties)
                if (isTaskSettle(properties)) f.fake.updatePage(c.id, mapOf("Worker" to NotionValue.RichText("other-director")))
                return result
            }
        }

        val result = run(processor(llm, over = f.guarded(taker))).single()

        assertTrue(result is TickResult.LostClaim, "got $result")
        assertEquals(listOf("Done", "Planned", "Planned"), statusesOf(c.id), "no task was started after the lease was lost")
        assertEquals(1, llm.taskRequests.size)
        assertTrue(llm.qcRequests.isEmpty())
        val row = cycleRow(c.id)
        assertEquals("Running", row.select("Status"), "the other worker's cycle is not finished by this one")
        assertEquals("other-director", row.text("Worker"))
        assertEquals("lost_claim", traces.recent(1).single().outcome)
    }

    @Test
    fun `a cycle that keeps failing to finish is abandoned after the limit, keeping the Results already written`() {
        val c = f.cycle(personaAndItemsGoal().id)
        val llm = RoutedLlm(RoutedLlm.plan(read("A", personaUrl), read("B", personaUrl)), RoutedLlm.doingEveryTask) { RoutedLlm.qcOf() }
        val crash = Crasher({ _, props -> isTaskStart(props) }, nth = 2)
        val p = processor(llm, over = f.guarded(crash), maxAttempts = 1)

        assertTrue(run(p).single() is TickResult.Aborted)
        now = f.base.plusSeconds(600)
        crash.armed = false
        val second = run(p).single() as TickResult.Finished

        assertEquals(CycleStatus.FAILED, second.status)
        assertTrue(cycleRow(c.id).text("Summary").startsWith("Cycle abandoned") && cycleRow(c.id).text("Summary").contains("keep their Results"))
        assertEquals("Done", f.tasksFor(c.id)[0].select("Status"))
    }

    // ── The retired goal fields are not read ─────────────────────────────────

    @Test
    fun `retired goal fields never reach a plan, a task or QC, and a page named only in them is not in scope`() {
        val retiredTarget = runBlocking { f.fake.createPage(t.findings, mapOf("Claim" to NotionValue.Title("RETIRED-LINK-TARGET"))) }
        f.fake.setBlocks(retiredTarget.id, listOf(FakeBlock("paragraph", "RETIRED-LINK-BODY")))
        val goal = runBlocking {
            f.fake.createPage(
                t.goals,
                mapOf(
                    "Goal" to NotionValue.Title("g"), "Status" to NotionValue.Select("Active"),
                    "Done when" to NotionValue.RichText("done"), "Scope" to scopeOf("", pageMention(t.personaPageId, "Persona")),
                    "Intent" to NotionValue.RichText("RETIRED-INTENT"), "Metric" to NotionValue.RichText("RETIRED-METRIC"), "Metric Note" to NotionValue.RichText("RETIRED-NOTE"),
                    "Target Links" to NotionValue.RichText("RETIRED-LINKS", links = listOf(notionPageUrl(retiredTarget.id))),
                    "Target Space" to NotionValue.Select("PPS Evidence Ledger"), "Goal Type" to NotionValue.Select("Review"),
                ),
            )
        }
        val c = f.cycle(goal.id)
        val llm = RoutedLlm(
            RoutedLlm.plan(read("Read Persona", personaUrl), read("Read the retired target", notionPageUrl(retiredTarget.id))),
            RoutedLlm.doingEveryTask,
        ) { RoutedLlm.qcOf() }

        run(processor(llm))

        assertFalse(llm.everythingSent.contains("RETIRED"), "a retired field reached the model: ${llm.everythingSent.lines().filter { it.contains("RETIRED") }}")
        assertEquals(listOf("Read Persona"), f.tasksFor(c.id).map { it.title("Task") }, "a link named only in Target Links is not in scope")
        assertTrue(f.fake.requests.none { it.operation == "listBlocks" && it.target == retiredTarget.id })
    }

    @Test
    fun `Serves is read and recorded but the Container it names is never fetched`() {
        val container = runBlocking { f.fake.createPage(t.containers, mapOf("Name" to NotionValue.Title("alfrd"))) }
        val goal = runBlocking {
            f.fake.createPage(
                t.goals,
                mapOf("Goal" to NotionValue.Title("g"), "Status" to NotionValue.Select("Active"), "Scope" to scopeOf("", pageMention(t.personaPageId, "Persona")), "Serves" to NotionValue.Relation(listOf(container.id))),
            )
        }
        f.cycle(goal.id)
        val mark = f.fake.requests.size

        run(processor(RoutedLlm.happy(read("Read Persona", personaUrl))))

        assertEquals(1, traces.recent(1).single().scope!!.serves)
        assertTrue(f.fake.requests.drop(mark).none { it.target == container.id || it.target == t.containers }, "the served Container was read though Scope does not mention it")
    }

    // ── The trace ────────────────────────────────────────────────────────────

    @Test
    fun `the trace records scope, content, each task and QC, in a form that serializes and holds no token`() {
        item("an item")
        val c = f.cycle(personaAndItemsGoal().id)
        run(processor(RoutedLlm.happy(read("Read Persona", personaUrl), check("Check Items", itemsUrl), qc = RoutedLlm.qcOf(looksMet = "yes"))))
        val trace = traces.recent(1).single()

        assertEquals("done", trace.outcome)
        assertEquals(2, trace.scope!!.mentions)
        assertEquals(2, trace.scope!!.readable)
        assertEquals(1, trace.scope!!.allowedPages)
        assertEquals(1, trace.scope!!.allowedRows)
        assertEquals(listOf("done", "done"), trace.execution.map { it.outcome })
        assertEquals(1, trace.execution[0].pagesStaged)
        assertEquals(2, trace.execution[1].pagesStaged, "the Check task is given its linked Items and the Persona page from Scope")
        assertTrue(trace.execution.all { it.pagesCited >= 1 && it.resultChars > 0 && it.latencyMs != null })
        assertTrue(trace.content.size >= 2)
        assertEquals("yes", trace.qc!!.goalLooksMet)
        assertEquals("Done", cycleRow(c.id).select("Status"))
        val json = Json.encodeToString(CyclePlanTrace.serializer(), trace)
        assertFalse(json.contains("Bearer") || json.contains("ntn_") || json.contains("notion-worker-token"))
    }
}
