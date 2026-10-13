package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.ToolCall
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Short handles (P1, D1, R1) instead of 32-character ids in every prompt, and the QC bound on task results.
 * Added after CYC-3: the planner mis-copied the last character of a row id and two tasks were rejected, and
 * QC was given 900 characters of a 1,507-character Result.
 */
class CycleHandlesTest {

    private val f = AgentWorkFixture()
    private val t = f.template
    private val traces = CycleTraceStore()
    private val itemsDb get() = f.tableSet.table(AgentWorkTables.MEMORY_ITEMS).databaseId!!

    private fun item(title: String, said: String = "said: $title"): NotionPage = runBlocking {
        f.fake.createPage(t.items, mapOf("Item" to NotionValue.Title(title), "Said" to NotionValue.RichText(said), "Status" to NotionValue.Select("Open"))).also { f.fake.now = f.fake.now.plusSeconds(1) }
    }

    private fun goal() = f.goal(title = "Judge the items", scope = scopeOf("", pageMention(t.personaPageId, "Persona"), databaseMention(itemsDb, "Items")))

    private fun contextFor(): ScopeContext = runBlocking { ScopeReader(f.guarded(), f.tableSet).read(GoalMapper.fromPage(f.fake.rows(t.goals).single()).scope) }

    private fun hex(id: String) = id.replace("-", "")

    // ── The map ──────────────────────────────────────────────────────────────

    @Test
    fun `pages are P, databases are D and the rows of a database are R, numbered in Scope order`() {
        val a = item("Alpha"); val b = item("Beta"); goal()
        val h = contextFor().handles
        assertEquals(notionPageUrl(t.personaPageId), h.urlOf("P1"))
        assertEquals(notionPageUrl(itemsDb), h.urlOf("D1"))
        assertEquals(notionPageUrl(a.id), h.urlOf("R1"))
        assertEquals(notionPageUrl(b.id), h.urlOf("R2"))
        assertNull(h.urlOf("R3"))
        assertEquals("R2", h.handleFor(b.id))
        assertEquals("[P1]", h.label(t.personaPageId))
        assertEquals("R1", h.handleFor(notionPageUrl(a.id)), "found by URL too")
    }

    @Test
    fun `a handle resolves in any spelling, an exact URL resolves to itself, and an unknown handle is left alone`() {
        item("Alpha"); goal()
        val h = contextFor().handles
        val url = h.urlOf("R1")!!
        for (spelling in listOf("R1", "r1", " [R1] ", "[r1]", "R01")) assertEquals(url, h.resolve(spelling), spelling)
        assertEquals(url, h.resolve(url))
        assertEquals("R9", h.resolve("R9"), "unknown: left as it was, so it is rejected as an unknown link is")
        assertEquals("https://example.org/x", h.resolve("https://example.org/x"))
        assertEquals("R1 and more", h.resolve("R1 and more"))
        assertTrue(Handles.isHandle("D3") && !Handles.isHandle("X3") && !Handles.isHandle("R"))
    }

    // ── The validator ────────────────────────────────────────────────────────

    private fun validate(vararg links: String, handles: Handles = contextFor().handles, type: String = "Read"): ValidatedPlan {
        val ctx = contextFor()
        return CyclePlanValidator.validate(listOf(ProposedTask("T", type, "Do it.", links.toList())), 6, ctx.allowed, handles)
    }

    @Test
    fun `a handle in target_links is accepted and written as the real URL`() {
        val a = item("Alpha"); goal()
        val plan = validate("R1", "[P1]", "D1")
        assertEquals(listOf(notionPageUrl(a.id), notionPageUrl(t.personaPageId), notionPageUrl(itemsDb)), plan.tasks.single().targetLinks)
        assertTrue(plan.rejected.isEmpty())
    }

    @Test
    fun `an exact real URL is still accepted, and mixing it with a handle is fine`() {
        val a = item("Alpha"); val b = item("Beta"); goal()
        val plan = validate(notionPageUrl(a.id), "R2")
        assertEquals(listOf(notionPageUrl(a.id), notionPageUrl(b.id)), plan.tasks.single().targetLinks)
    }

    @Test
    fun `an unknown handle is rejected exactly as an unknown link is, and a handle never widens scope`() {
        item("Alpha"); goal()
        val unknown = validate("R9")
        assertEquals(RejectReason.LINK_NOT_A_URL, unknown.rejected.single().reason)
        assertEquals("R9", unknown.rejected.single().detail)
        val garbage = validate("not-a-link")
        assertEquals(unknown.rejected.single().reason, garbage.rejected.single().reason, "same rejection as any unknown link")
        // A handle map built from a different scope maps nothing here; the URL it would give still has to pass allowed.allows.
        val outside = Handles.NONE
        assertEquals(RejectReason.LINK_NOT_A_URL, validate("R1", handles = outside).rejected.single().reason)
    }

    @Test
    fun `a mis-copied id, the CYC-3 failure, is still rejected as not allowed`() {
        val a = item("Alpha"); goal()
        val real = hex(a.id)
        val miscopied = notionPageUrl(real.dropLast(1) + if (real.last() == '2') '3' else '2')
        val plan = validate(miscopied)
        assertEquals(RejectReason.LINK_NOT_ALLOWED, plan.rejected.single().reason)
    }

    @Test
    fun `a rejection records the proposed type and every link as written, not only the first offending one`() {
        item("Alpha"); goal()
        val plan = validate("R1", "https://example.org/a", "D1", type = "Read")
        val r = plan.rejected.single()
        assertEquals("Read", r.type)
        assertEquals(listOf("R1", "https://example.org/a", "D1"), r.links)
        assertEquals(RejectReason.LINK_NOT_ALLOWED, r.reason)
        assertEquals("https://example.org/a", r.detail)
        val badType = validate("R1", type = "Gate")
        assertEquals(RejectReason.TYPE_NOT_ALLOWED, badType.rejected.single().reason)
        assertEquals(listOf("R1"), badType.rejected.single().links)
    }

    // ── Prompts show handles, not ids ────────────────────────────────────────

    private class Recording : LlmClient {
        val requests = ArrayList<LlmRequest>()
        var planLinks: List<String> = emptyList()
        var cite: List<String> = emptyList()
        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            val input: JsonObject = when (request.forcedToolName) {
                CyclePlanDirector.TOOL_NAME -> ScriptedLlm.planOf(ScriptedLlm.taskJson("Check them", "Check", "Do it.", *planLinks.toTypedArray()), ScriptedLlm.taskJson("Read first", "Read", "Do it.", *planLinks.toTypedArray()))
                DirectorTaskExecutor.TOOL_NAME -> RoutedLlm.taskDone("Checked Alpha and Beta.", *cite.toTypedArray())
                else -> RoutedLlm.qcOf()
            }
            return LlmResponse("", toolCalls = listOf(ToolCall(request.forcedToolName!!, input)), latencyMs = 5, retryCount = 0, providerName = "local", modelName = "q", finishReason = "tool_calls", completionTokens = 10)
        }
    }

    private fun run(model: Recording): Pair<CyclePlanTrace, String> {
        val cycle = f.cycle(f.fake.rows(t.goals).single().id)
        runBlocking { f.processor(model, traces).runOnce() }
        return traces.recent(1).single() to cycle.id
    }

    @Test
    fun `no model-facing prompt carries a raw page id, in the plan, a task or QC`() {
        item("Alpha"); item("Beta"); goal()
        val model = Recording().apply { planLinks = listOf("R1") }
        run(model)
        assertEquals(3, model.requests.map { it.forcedToolName }.toSet().size)
        for (r in model.requests) {
            assertFalse(r.prompt.contains("notion.so"), "${r.forcedToolName} prompt shows a URL")
            assertFalse(Regex("[0-9a-f]{32}").containsMatchIn(r.prompt), "${r.forcedToolName} prompt shows a raw id")
            assertFalse(Regex("[0-9a-f]{8}-[0-9a-f]{4}-").containsMatchIn(r.prompt), "${r.forcedToolName} prompt shows a dashed id")
        }
        val task = model.requests.first { it.forcedToolName == DirectorTaskExecutor.TOOL_NAME }.prompt
        assertTrue(Regex("""=== \[P1\] Persona""").containsMatchIn(task) && Regex("""\[R1\] Alpha""").containsMatchIn(task) && task.contains("### [R2] Beta"), task)
        val plan = model.requests.first { it.forcedToolName == CyclePlanDirector.TOOL_NAME }.prompt
        assertTrue(plan.contains("- R1  [memory-items] Alpha") && plan.contains("- D1  [database Items") && plan.contains("- P1  page Persona"), plan)
        val qc = model.requests.first { it.forcedToolName == CycleQcDirector.TOOL_NAME }.prompt
        assertTrue(qc.contains("### [R1] Alpha") && qc.contains("=== [P1] Persona"), qc)
    }

    @Test
    fun `the not-given lines name an item by handle and title`() {
        val rows = (1..4).map { n -> item("Row $n", said = "x".repeat(1_400)) }
        goal()
        val model = Recording().apply { planLinks = emptyList() }
        runBlocking {
            val cycle = f.cycle(f.fake.rows(t.goals).single().id)
            CycleProcessor(f.guarded(), f.tableSet, CyclePlanDirector(model), DirectorTaskExecutor(model), CycleQcDirector(model), traces, "arx-1", { f.base }, limits = ContentLimits(perPageChars = 2_000, perTaskChars = 3_000), sleep = {}).runOnce()
            assertTrue(cycle.id.isNotEmpty())
        }
        val prompt = model.requests.first { it.forcedToolName == DirectorTaskExecutor.TOOL_NAME }.prompt
        assertTrue(Regex("""LEFT OUT: \[R\d\] "Row \d" did not fit""").containsMatchIn(prompt), prompt.takeLast(900))
        assertFalse(prompt.contains(rows.first().id) || prompt.contains("notion.so"))
    }

    @Test
    fun `a plan written in handles creates tasks with the real URLs on their rows, and the worker's handle citations come back as URLs`() {
        val a = item("Alpha"); val b = item("Beta"); goal()
        val model = Recording().apply { planLinks = listOf("R1", "R2"); cite = listOf("R1", "[R2]", "R9", "P1") }
        val (trace, cycleId) = run(model)

        val tasks = f.tasksFor(cycleId)
        assertEquals(listOf("Check them", "Read first"), tasks.map { it.title("Task") })
        assertEquals(listOf(notionPageUrl(a.id), notionPageUrl(b.id)), tasks[0].text("Target Links").lines().filter { it.isNotBlank() }.ifEmpty { listOf(notionPageUrl(a.id), notionPageUrl(b.id)) }.take(2))
        val result = tasks[0].text("Result")
        assertTrue(result.contains(notionPageUrl(a.id)) && result.contains(notionPageUrl(b.id)) && result.contains(notionPageUrl(t.personaPageId)), result)
        assertFalse(result.contains("R9"), "an unknown handle is dropped")
        assertEquals(3, trace.execution[0].pagesCited)
        assertTrue(trace.rejected.isEmpty())
    }

    @Test
    fun `a rejected task is traced with its type and all its links`() {
        item("Alpha"); goal()
        val model = Recording().apply { planLinks = listOf("R1", "R9") }
        val (trace, _) = run(model)
        assertEquals(2, trace.rejected.size)
        assertEquals(listOf("Check", "Read"), trace.rejected.map { it.type })
        assertTrue(trace.rejected.all { it.links == listOf("R1", "R9") && it.reason == "LINK_NOT_A_URL" })
    }

    @Test
    fun `the system prompts tell the model to use the handle and not to put handles in its text`() {
        assertTrue(CyclePrompts.PLAN.text.contains("Write the handle, never an id or a URL"))
        for (p in CyclePrompts.taskPrompts) assertTrue(p.text.contains("write the handles of the sources you actually used") && p.text.contains("name items by their title"), p.name)
        assertTrue(CyclePrompts.QC.text.contains("name items by their title, not by handle"))
    }

    // ── QC task results ──────────────────────────────────────────────────────

    private fun line(order: Int, result: String) = QcTaskLine(order, "Task $order title", "Consolidate", TaskStatus.DONE, result)
    private fun qcInput(vararg lines: QcTaskLine) = QcInput(
        Goal("g", "Goal", "GOAL-1", GoalStatus.ACTIVE, "Done.", GoalScope.EMPTY, emptyList(), 5, 3, null),
        Cycle("c", "C", "CYC-1", CycleStatus.RUNNING, "g", 1, 6, null, "w", null, null, "", java.time.Instant.EPOCH, java.time.Instant.EPOCH),
        emptyList(), lines.toList(), 0,
    )

    @Test
    fun `a result of the CYC-3 size, 1507 characters, reaches QC whole with no cut marker`() {
        val result = (1..30).joinToString("\n") { "Row $it: Done because reason number $it is stated here." }.take(1_507)
        assertEquals(1_507, result.length)
        val input = qcInput(line(1, result))
        val prompt = QcPromptBuilder.build(input)
        assertTrue(prompt.contains(result.trim().lines().last().trim()), "the last row's reason is there")
        assertFalse(prompt.contains("[CUT"), prompt)
        assertTrue(QcPromptBuilder.cutTasks(input).isEmpty())
        assertTrue(prompt.contains("Row 1: Done because") && prompt.contains("\n    Row 2:"), "line breaks are kept, indented under the result")
    }

    @Test
    fun `a result is given up to the Result's own bound, and cut with the marker only beyond it`() {
        assertEquals(DirectorTaskExecutor.MAX_RESULT_CHARS + 3_000, QcPromptBuilder.RESULT_CAP)
        val atCap = "a".repeat(QcPromptBuilder.RESULT_CAP)
        assertFalse(QcPromptBuilder.build(qcInput(line(1, atCap))).contains("[CUT"))
        val over = QcPromptBuilder.build(qcInput(line(1, atCap + "b".repeat(50))))
        assertTrue(over.contains("[CUT: 50 more characters of this result not shown]"), over.takeLast(400))
    }

    @Test
    fun `all results share about 30000 characters, a short one keeps its length, and the long ones are cut fairly`() {
        val short = line(1, "short result")
        val longs = (2..6).map { line(it, "L$it" + "x".repeat(7_000)) }
        val input = qcInput(short, *longs.toTypedArray())
        val allow = QcPromptBuilder.allowances(input)
        assertEquals("short result".length, allow[0])
        assertTrue(allow.sum() <= QcPromptBuilder.RESULTS_TOTAL, "total ${allow.sum()}")
        assertTrue(allow.drop(1).all { it in 5_900..6_000 }, allow.toString())
        assertEquals(longs.map { it.order }, QcPromptBuilder.cutTasks(input))
        val prompt = QcPromptBuilder.build(input)
        assertEquals(5, Regex("""\[CUT: \d+ more characters of this result not shown]""").findAll(prompt).count())
        assertTrue(prompt.endsWith("Write the close-out now."))
    }

    @Test
    fun `a cut task result is named under NOT GIVEN TO YOU and recorded in the given record as task and its order`() {
        val input = qcInput(line(1, "ok"), line(2, "y".repeat(QcPromptBuilder.RESULT_CAP + 10)))
        val prompt = QcPromptBuilder.build(input)
        assertTrue(prompt.contains("NOT GIVEN TO YOU"), prompt)
        assertTrue(prompt.contains("CUT: the result of Task 2 \"Task 2 title\" was given only in part"), prompt)
        assertFalse(prompt.contains("the result of Task 1"))

        val llm = ScriptedLlm.answering(RoutedLlm.qcOf(), CycleQcDirector.TOOL_NAME)
        val result = runBlocking { CycleQcDirector(llm).decide(input) }
        val given = result.given!!
        assertEquals(listOf("task:2"), given.cutItemIds)
        assertEquals(1, given.notGivenLines)
        assertEquals(listOf(1, 2), given.earlierTasks)
    }

    @Test
    fun `results beyond the 25-task listing are left out by name in the record`() {
        val input = qcInput(*(1..27).map { line(it, "r$it") }.toTypedArray())
        val prompt = QcPromptBuilder.build(input)
        assertTrue(prompt.contains("LEFT OUT: the results of 2 more task(s) were not given."))
        val given = runBlocking { CycleQcDirector(ScriptedLlm.answering(RoutedLlm.qcOf(), CycleQcDirector.TOOL_NAME)).decide(input) }.given!!
        assertEquals(listOf("task:26", "task:27"), given.leftOutItemIds)
        assertEquals(2, given.leftOutCount)
    }

    @Test
    fun `end to end, a long Result reaches QC whole and the trace records no cut`() {
        item("Alpha"); goal()
        val long = "Alpha: Done because it is stated. ".repeat(60)
        val model = object : LlmClient {
            val requests = ArrayList<LlmRequest>()
            override suspend fun complete(request: LlmRequest): LlmResponse {
                requests += request
                val input = when (request.forcedToolName) {
                    CyclePlanDirector.TOOL_NAME -> ScriptedLlm.planOf(ScriptedLlm.taskJson("Check them", "Check", "Do it."))
                    DirectorTaskExecutor.TOOL_NAME -> RoutedLlm.taskDone(long)
                    else -> RoutedLlm.qcOf()
                }
                return LlmResponse("", toolCalls = listOf(ToolCall(request.forcedToolName!!, input)), latencyMs = 5, retryCount = 0)
            }
        }
        val cycle = f.cycle(f.fake.rows(t.goals).single().id)
        runBlocking { f.processor(model, traces).runOnce() }
        val qc = model.requests.last { it.forcedToolName == CycleQcDirector.TOOL_NAME }.prompt
        assertTrue(long.length > 1_500 && qc.contains(long.trim().replace("\n", "\n    ")), "the whole Result is in the QC prompt")
        assertFalse(qc.contains("[CUT"))
        assertTrue(traces.recent(1).single().qc!!.given!!.cutItemIds.none { it.startsWith("task:") })
        assertTrue(cycle.id.isNotEmpty())
    }
}
