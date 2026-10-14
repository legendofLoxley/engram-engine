package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionBlock
import app.alfrd.engram.notion.NotionBlockRead
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The report written into a Cycle's own page body when the cycle finishes. */
class CycleReportTest {

    private val f = AgentWorkFixture()
    private val t = f.template
    private val personaUrl get() = notionPageUrl(t.personaPageId)

    private val consolidated = "Item A: the first thing.\nItem B: the second thing.\n" + "x".repeat(2600) + "\nitems found earlier: 2, items reported: 2"
    private val longFinding = "finding-" + "y".repeat(892) // 900 chars

    /** Gives every answer an endpoint and a model, the way the local client does. */
    private fun serving(inner: LlmClient) = object : LlmClient {
        override suspend fun complete(request: LlmRequest): LlmResponse =
            inner.complete(request).copy(endpoint = "http://127.0.0.1:8081/v1", modelName = "qwen-test", latencyMs = 1500)
    }

    private fun model(withConsolidate: Boolean = true, qc: (LlmRequest) -> kotlinx.serialization.json.JsonObject = { RoutedLlm.qcOf(summary = "QC SUMMARY TEXT", looksMet = "partly", feedback = "FEEDBACK TEXT", rewriteField = "Done when", rewriteText = "REWRITE TEXT") }) = RoutedLlm(
        plan = RoutedLlm.plan(
            *listOfNotNull(
                ScriptedLlm.taskJson("Read the page", "Read", "Read it.", personaUrl),
                ScriptedLlm.taskJson("Check the page", "Check", "Check it."),
                if (withConsolidate) ScriptedLlm.taskJson("Bring it together", "Consolidate", "Consolidate.") else null,
            ).toTypedArray(),
            rationale = "RATIONALE: read, check, then consolidate.",
        ),
        task = { r ->
            when {
                r.systemPrompt!!.contains("Your task type is Consolidate") -> RoutedLlm.taskDone(consolidated)
                r.systemPrompt!!.contains("Your task type is Check") -> RoutedLlm.taskDone("checked ok")
                else -> RoutedLlm.taskDone(longFinding, personaUrl)
            }
        },
        qc = qc,
    )

    private fun cycleWith(llm: LlmClient, scope: Boolean = true): Triple<String, CycleTraceStore, NotionApi> {
        val traces = CycleTraceStore()
        val goal = f.goal(scope = if (scope) scopeOf("", pageMention(t.personaPageId, "Persona")) else scopeOf("no mentions here"))
        val c = f.cycle(goal.id)
        runBlocking { f.processor(llm, traces).runOnce() }
        return Triple(c.id, traces, f.fake)
    }

    /** A fresh guard that has seen the Cycle page, as the processor's has. */
    private fun known(id: String): NotionApi = f.guarded().also { runBlocking { it.retrievePage(id) } }

    private fun blocks(id: String): List<NotionBlockRead> = runBlocking {
        val out = ArrayList<NotionBlockRead>(); var cursor: String? = null
        do { val p = f.fake.listBlocks(id, cursor, 100); out += p.blocks; cursor = p.nextCursor } while (cursor != null)
        out
    }

    private fun section(blocks: List<NotionBlockRead>, heading: String): List<NotionBlockRead> {
        val start = blocks.indexOfFirst { it.type == "heading_2" && it.plainText == heading }
        require(start >= 0) { "no heading '$heading' in ${blocks.map { it.plainText.take(30) }}" }
        val end = blocks.drop(start + 1).indexOfFirst { it.type == "heading_2" }.let { if (it < 0) blocks.size else start + 1 + it }
        return blocks.subList(start + 1, end)
    }

    // ── Content ──────────────────────────────────────────────────────────────

    @Test
    fun `a finished cycle gets the six plain headings, in order, after a marker line`() {
        val (id, _, _) = cycleWith(serving(model()))
        val b = blocks(id)
        assertEquals(CycleReport.marker(id), b.first().plainText)
        assertEquals(listOf("Verdict", "Result", "QC", "Plan", "What each task found", "Notes"), b.filter { it.type == "heading_2" }.map { it.plainText })
        assertEquals(CycleReport.END_LINE, b.last().plainText)
    }

    @Test
    fun `Verdict has the goal-looks-met value and one line`() {
        val (id, _, _) = cycleWith(serving(model()))
        val v = section(blocks(id), "Verdict").map { it.plainText }
        assertEquals("Goal looks met: partly", v[0])
        assertEquals(2, v.size)
        assertTrue(v[1].contains("3 tasks (3 done, 0 blocked, 0 failed)"), v[1])
    }

    @Test
    fun `Result is the final Consolidate result in full, even past one text segment`() {
        val (id, _, _) = cycleWith(serving(model()))
        val r = section(blocks(id), "Result")
        assertTrue(r.first().plainText.contains("Consolidate: Bring it together"))
        val text = r.drop(1).joinToString("\n") { it.plainText }
        val stored = f.tasksFor(id).last().text("Result")
        assertTrue(stored.startsWith(consolidated), "the stored result carries the worker's trailer after the text")
        assertEquals(stored.lines().filter { it.isNotBlank() }.joinToString("\n"), text, "the whole stored result, nothing shortened (blank lines apart)")
        assertTrue(r.any { it.segments.size > 1 }, "the 2,600-character line spans two segments")
    }

    @Test
    fun `without a Consolidate task, Result is the last task's result`() {
        val (id, _, _) = cycleWith(serving(model(withConsolidate = false)))
        val r = section(blocks(id), "Result")
        assertTrue(r.first().plainText.contains("Check: Check the page"), r.first().plainText)
        assertEquals("checked ok", r[1].plainText)
    }

    @Test
    fun `QC has Summary, Goal Feedback and the suggested rewrite, in full`() {
        val (id, _, _) = cycleWith(serving(model()))
        val q = section(blocks(id), "QC").map { it.plainText }
        assertTrue("QC SUMMARY TEXT" in q && "FEEDBACK TEXT" in q && "REWRITE TEXT" in q, q.toString())
        assertTrue(q.any { it == "Suggested rewrite of Done when" })
        // No rewrite suggested: no rewrite section.
        val f2 = AgentWorkFixture()
        val goal = f2.goal(scope = scopeOf("", pageMention(f2.template.personaPageId, "Persona")))
        val c = f2.cycle(goal.id)
        runBlocking { f2.processor(serving(RoutedLlm.happy(ScriptedLlm.taskJson("Only", "Read", "Read it.", notionPageUrl(f2.template.personaPageId)))), CycleTraceStore()).runOnce() }
        val q2 = runBlocking { f2.fake.listBlocks(c.id).blocks }.map { it.plainText }
        assertFalse(q2.any { it.startsWith("Suggested rewrite") }, q2.toString())
    }

    @Test
    fun `Plan has the rationale, then each task as type and title linked to its task page`() {
        val (id, _, _) = cycleWith(serving(model()))
        val plan = section(blocks(id), "Plan")
        assertEquals("RATIONALE: read, check, then consolidate.", plan.first().plainText)
        assertEquals(listOf("Read: Read the page", "Check: Check the page", "Consolidate: Bring it together"), plan.drop(1).map { it.plainText })
        val taskPages = f.tasksFor(id).map { notionPageUrl(it.id) }
        assertEquals(taskPages, plan.drop(1).map { it.segments.single().link })
    }

    @Test
    fun `What each task found keeps 400 characters and says how many were cut`() {
        val (id, _, _) = cycleWith(serving(model()))
        val found = section(blocks(id), "What each task found").map { it.plainText }
        assertEquals("1. Read: Read the page (Done)", found[0])
        val stored = f.tasksFor(id).first().text("Result").trim()
        assertTrue(stored.length > 900 && stored.startsWith(longFinding))
        assertEquals(stored.take(400) + " [CUT ${stored.length - 400} chars]", found[1])
        assertTrue(found[3].startsWith("checked ok") && !found[3].contains("[CUT"), "a short result is shown whole, without a marker")
    }

    @Test
    fun `Notes name the endpoint, model and duration of every call, and say when nothing was cut`() {
        val (id, _, _) = cycleWith(serving(model()))
        val n = section(blocks(id), "Notes").map { it.plainText }
        for (call in listOf("Plan call", "Task 1 call", "Task 2 call", "Task 3 call", "QC call")) {
            assertTrue(n.any { it.startsWith("$call: http://127.0.0.1:8081/v1 · qwen-test · ") && it.endsWith(" s") }, "$call in $n")
        }
        assertTrue(n.any { it == "Cuts and limits: none applied." }, n.toString())
        assertTrue(n.any { it == "Rejected tasks: none." })
        assertTrue(n.any { it.startsWith("Plan: served by the endpoint it was sent to") })
    }

    @Test
    fun `Notes carry the plan fallback line, rejected tasks and cut markers`() {
        val failing = object : LlmClient { override suspend fun complete(request: LlmRequest): LlmResponse = throw java.io.IOException("down") }
        val inner = serving(model { RoutedLlm.qcOf(summary = "S [CUT 12 chars]", feedback = "F") })
        val goal = f.goal(scope = scopeOf("", pageMention(t.personaPageId, "Persona")))
        val c = f.cycle(goal.id)
        val traces = CycleTraceStore()
        // A plan with one valid task and one the validator rejects (it links outside the Scope).
        val llm = serving(RoutedLlm(
            plan = RoutedLlm.plan(ScriptedLlm.taskJson("Fine", "Read", "Read it.", personaUrl), ScriptedLlm.taskJson("Wanders", "Read", "Read.", "https://www.notion.so/99999999999949998999999999999999")),
            task = RoutedLlm.doingEveryTask, qc = { RoutedLlm.qcOf(summary = "S [CUT 12 chars]", feedback = "F") },
        ))
        runBlocking { f.processor(llm, traces, planRoute = PlanRoute(failing, "http://127.0.0.1:8083/v1")).runOnce() }
        val n = section(blocks(c.id), "Notes").map { it.plainText }
        assertTrue(n.any { it.startsWith("Plan: Plan served by fallback endpoint") && it.contains("IOException") }, n.toString())
        assertTrue(n.any { it.startsWith("Rejected task 2 \"Wanders\"") }, n.toString())
        assertTrue(n.any { it.startsWith("Cuts and limits:") && it.contains("QC Summary: 1 cut marker") }, n.toString())
        inner.hashCode()
    }

    @Test
    fun `a failed cycle gets a report too, with the verdict not assessed`() {
        val (id, _, _) = cycleWith(serving(model()), scope = false)
        val b = blocks(id)
        assertEquals("Failed", f.fake.page(id).select("Status"))
        val v = section(b, "Verdict").map { it.plainText }
        assertEquals("Goal looks met: not assessed", v[0])
        assertTrue(v[1].startsWith("Cycle failed: Cycle failed:") || v[1].startsWith("Cycle failed:"), v[1])
        assertEquals(CycleReport.END_LINE, b.last().plainText)
    }

    // ── Idempotence ──────────────────────────────────────────────────────────

    @Test
    fun `a second write of the report appends nothing`() {
        val (id, traces, _) = cycleWith(serving(model()))
        val before = blocks(id).size
        val appends = f.fake.requestCount("appendBlocks")
        val again = runBlocking { CycleReportWriter(known(id)).write(id, listOf(NotionBlock.Paragraph(listOf(app.alfrd.engram.notion.RichSegment("other"))))) }
        assertEquals(CycleReportWriter.Outcome.ALREADY_PRESENT, again)
        assertEquals(before, blocks(id).size)
        assertEquals(appends, f.fake.requestCount("appendBlocks"))
        assertTrue(traces.recent(1).single().notes.contains("cycle report written (${before} blocks)"), traces.recent(1).single().notes.toString())
    }

    @Test
    fun `a report whose write landed but whose reply was lost is not written again`() {
        f.fake.failAfterApplying("appendBlocks", java.io.IOException("reply lost"))
        val (id, traces, _) = cycleWith(serving(model()))
        val first = blocks(id)
        assertEquals(CycleReport.marker(id), first.first().plainText)
        assertTrue(traces.recent(1).single().notes.any { it == "cycle report not written: IOException" }, traces.recent(1).single().notes.toString())
        val retry = runBlocking { CycleReportWriter(known(id)).write(id, listOf(NotionBlock.Paragraph(listOf(app.alfrd.engram.notion.RichSegment("again"))))) }
        assertEquals(CycleReportWriter.Outcome.ALREADY_PRESENT, retry)
        assertEquals(first.size, blocks(id).size)
    }

    @Test
    fun `a page with the marker but no end line is left alone and reported as unfinished`() {
        val (id, _, _) = cycleWith(serving(model()))
        val trimmed = AgentWorkFixture()
        val g = trimmed.goal(scope = scopeOf("", pageMention(trimmed.template.personaPageId, "Persona")))
        val c = trimmed.cycle(g.id)
        runBlocking { trimmed.fake.appendBlocks(c.id, listOf(NotionBlock.Paragraph(listOf(app.alfrd.engram.notion.RichSegment(CycleReport.marker(c.id)))))) }
        val outcome = runBlocking { CycleReportWriter(trimmed.fake).write(c.id, listOf(NotionBlock.Paragraph(listOf(app.alfrd.engram.notion.RichSegment("x"))))) }
        assertEquals(CycleReportWriter.Outcome.ALREADY_PRESENT_INCOMPLETE, outcome)
        assertEquals(1, runBlocking { trimmed.fake.listBlocks(c.id).blocks }.size)
        assertTrue(id.isNotEmpty())
    }

    // ── Failure and the guard ────────────────────────────────────────────────

    @Test
    fun `a report that cannot be written is a note on the trace and never fails the cycle`() {
        f.fake.failNext("appendBlocks", RuntimeException("notion is down"))
        val (id, traces, _) = cycleWith(serving(model()))
        val row = f.fake.page(id)
        assertEquals("Done", row.select("Status"))
        assertTrue(row.text("Summary").isNotBlank())
        assertEquals(3, f.tasksFor(id).count { it.select("Status") == "Done" })
        val trace = traces.recent(1).single()
        assertEquals("done", trace.outcome)
        assertTrue(trace.notes.contains("cycle report not written: RuntimeException"), trace.notes.toString())
        assertFalse(trace.notes.any { it.contains("notion is down") }, "only the class name, never the message")
    }

    @Test
    fun `the report goes to the Cycle's own page and nowhere else`() {
        val (id, _, _) = cycleWith(serving(model()))
        val appended = f.fake.requests.filter { it.operation == "appendBlocks" }.map { it.target }.toSet()
        assertEquals(setOf(id), appended)
    }

    @Test
    fun `only the Cycles table may take page-body appends`() {
        val set = AgentWorkConfig.load("deploy/config/notion-agent-worker.json").tableSet
        assertEquals(listOf("cycles"), set.tables.filter { it.pageBodyAppend }.map { it.name })
        for (other in listOf("agent-tasks")) {
            val bad = set.copy(tables = set.tables.map { if (it.name == other) it.copy(pageBodyAppend = true) else it })
            val e = runCatching { AgentWorkTables.requireShape(bad) }.exceptionOrNull()
            assertTrue(e is app.alfrd.engram.notion.NotionConfigException && e.message!!.contains("may not accept page-body appends"), e?.message)
        }
        // Read-only tables cannot be given the flag at all (NotionTable refuses it).
        assertTrue(runCatching { set.table("goals").copy(pageBodyAppend = true) }.isFailure)
    }

    // ── task.consolidate v3 ──────────────────────────────────────────────────

    @Test
    fun `Consolidate carries every earlier item forward, accounts for changes and conflicts, and ends with a count`() {
        val p = CyclePrompts.forTask(TaskType.CONSOLIDATE)
        assertEquals("task.consolidate", p.name)
        assertEquals("3", p.version)
        for (rule in listOf(
            "Carry forward every item that the earlier tasks of this cycle produced",
            "List all of them, one by one",
            "If you change, merge or drop an earlier item, say which item it was and why.",
            "If two earlier results conflict, report both and say that they conflict. Do not choose one silently.",
            "\"items found earlier: N, items reported: N\"",
        )) assertTrue(p.text.contains(rule), "missing: $rule")
        // General wording only: nothing names a goal, a table or a page.
        for (word in listOf("Persona", "Template", "GOAL", "Done when", "R1", "memory")) assertFalse(p.text.contains(word), "goal-specific term: $word")
        // The other task types are untouched.
        assertEquals("3", CyclePrompts.forTask(TaskType.CHECK).version)
        assertEquals("2", CyclePrompts.forTask(TaskType.READ).version)
    }
}
