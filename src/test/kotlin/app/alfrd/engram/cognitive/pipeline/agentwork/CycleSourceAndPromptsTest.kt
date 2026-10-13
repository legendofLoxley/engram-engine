package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The principle: **system prompts say how to work; the per-call prompt carries what to work on.**
 * Added after CYC-2, where a Consolidate task and QC were given only earlier tasks' Results and a digest of the
 * rows with long text cut, and so wrote conclusions about rows they had not seen ("truncated in source").
 */
class CycleSourceAndPromptsTest {

    private val f = AgentWorkFixture()
    private val t = f.template
    private val traces = CycleTraceStore()
    private val personaUrl get() = notionPageUrl(t.personaPageId)
    private val itemsUrl get() = notionPageUrl(f.tableSet.table(AgentWorkTables.MEMORY_ITEMS).databaseId!!)

    private fun item(title: String, said: String = "said: $title", extra: Map<String, NotionValue> = emptyMap()): NotionPage = runBlocking {
        f.fake.createPage(t.items, mapOf("Item" to NotionValue.Title(title), "Said" to NotionValue.RichText(said), "Status" to NotionValue.Select("Open")) + extra)
            .also { f.fake.now = f.fake.now.plusSeconds(1) }
    }

    private fun goalOverItems() = f.goal(title = "Check what alfrd holds", scope = scopeOf("", databaseMention(f.tableSet.table(AgentWorkTables.MEMORY_ITEMS).databaseId!!, "Items")))

    /** Records every request, and answers each stage the way a cooperative worker would. */
    private class Recording : app.alfrd.engram.cognitive.providers.LlmClient {
        val requests = ArrayList<LlmRequest>()
        var plan: JsonObject = ScriptedLlm.planOf()
        override suspend fun complete(request: LlmRequest): app.alfrd.engram.cognitive.providers.LlmResponse {
            requests += request
            val input = when (request.forcedToolName) {
                CyclePlanDirector.TOOL_NAME -> plan
                DirectorTaskExecutor.TOOL_NAME -> RoutedLlm.taskDone("done", *RoutedLlm.stagedUrls(request.prompt).toTypedArray())
                else -> RoutedLlm.qcOf()
            }
            return app.alfrd.engram.cognitive.providers.LlmResponse("", toolCalls = listOf(app.alfrd.engram.cognitive.providers.ToolCall(request.forcedToolName!!, input)), latencyMs = 5, retryCount = 0, providerName = "local", modelName = "qwen-test", finishReason = "tool_calls", completionTokens = 100)
        }
        fun taskPrompts() = requests.filter { it.forcedToolName == DirectorTaskExecutor.TOOL_NAME }
        fun qcPrompt() = requests.single { it.forcedToolName == CycleQcDirector.TOOL_NAME }
    }

    private fun run(model: Recording, limits: ContentLimits = ContentLimits()): Pair<CyclePlanTrace, String> {
        val cycle = f.cycle(f.fake.rows(t.goals).single().id)
        runBlocking {
            CycleProcessor(f.guarded(), f.tableSet, CyclePlanDirector(model), DirectorTaskExecutor(model), CycleQcDirector(model), traces, "arx-1", { f.base }, limits = limits, sleep = {}).runOnce()
        }
        return traces.recent(1).single() to cycle.id
    }

    private fun tasks(vararg specs: Pair<String, String>) = ScriptedLlm.planOf(*specs.map { (title, type) -> ScriptedLlm.taskJson(title, type, "Do it.") }.toTypedArray())

    // ── 1. What to work on: the actual rows and pages ────────────────────────

    @Test
    fun `a Check and a Consolidate task that linked nothing are given the in-scope rows, with every property in full`() {
        val long = "Jacob said this at length. " + "More of what was said. ".repeat(60) + "THE-LAST-SENTENCE."
        item("Long one", said = long)
        item("Short one")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Verify the claims" to "Check", "Gather it" to "Consolidate") }
        run(model)

        assertTrue(long.length > 1_000)
        for (prompt in model.taskPrompts().map { it.prompt }) {
            assertTrue(prompt.contains("SOURCE MATERIAL"), prompt)
            assertTrue(prompt.contains(long), "the long property reached the worker in full")
            assertTrue(prompt.contains("THE-LAST-SENTENCE."))
            assertTrue(prompt.contains("Short one") && prompt.contains("said: Short one"))
            assertFalse(prompt.contains("[CUT") || prompt.contains("…"), "nothing was cut, so nothing says so")
            assertFalse(prompt.contains("NOT GIVEN TO YOU"))
        }
    }

    @Test
    fun `Read, Hypothesis and Escalate tasks get only the links they were given`() {
        item("In scope row")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Just read" to "Read", "Test an idea" to "Hypothesis", "Flag it" to "Escalate") }
        run(model)

        assertEquals(3, model.taskPrompts().size)
        for (request in model.taskPrompts()) {
            assertTrue(request.prompt.contains("SOURCE MATERIAL: none was given for this task"), request.prompt)
            assertFalse(request.prompt.contains("In scope row"))
        }
    }

    @Test
    fun `the Check task's own links come first, and nothing is given twice`() {
        val a = item("Alpha")
        item("Beta")
        goalOverItems()
        val model = Recording().apply { plan = ScriptedLlm.planOf(ScriptedLlm.taskJson("Verify", "Check", "Do it.", itemsUrl)) }
        val (trace, _) = run(model)

        val prompt = model.taskPrompts().single().prompt
        assertEquals(1, Regex("### Alpha ").findAll(prompt).count(), "a row linked and also in scope appears once")
        assertEquals(1, Regex("### Beta ").findAll(prompt).count())
        assertEquals(1, trace.execution.single().pagesStaged, "the database was linked, so the scope pass added nothing")
        assertTrue(a.id in trace.execution.single().given!!.sourceItemIds)
    }

    @Test
    fun `earlier results are included too, after the source and labelled as notes that may be wrong`() {
        item("Alpha")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Read first" to "Read", "Check second" to "Check") }
        run(model)

        val prompt = model.taskPrompts()[1].prompt
        val source = prompt.indexOf("SOURCE MATERIAL (")
        val earlier = prompt.indexOf("EARLIER WORK BY OTHER TASKS OF THIS CYCLE")
        assertTrue(source in 0 until earlier, "the source comes before the earlier work")
        assertTrue(prompt.contains("it may be wrong or incomplete, and it is not evidence"), prompt)
        assertTrue(prompt.contains("- Task 1 Read first [Done]"))
        assertFalse(model.taskPrompts()[0].prompt.contains("EARLIER WORK"), "the first task has none")
    }

    @Test
    fun `QC is given the source rows, with the task results labelled as earlier work to confirm`() {
        item("Alpha", said = "Alpha said a long thing. ".repeat(30) + "ALPHA-END.")
        item("Beta")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Read it" to "Read") }
        val (trace, _) = run(model)

        val prompt = model.qcPrompt().prompt
        assertTrue(prompt.contains("SOURCE MATERIAL (pages and rows from the goal's Scope; this is the evidence"), prompt)
        assertTrue(prompt.contains("ALPHA-END.") && prompt.contains("said: Beta"))
        assertTrue(prompt.indexOf("SOURCE MATERIAL (") < prompt.indexOf("TASK RESULTS (earlier work"), "source first, results after")
        assertTrue(prompt.contains("confirm it against the SOURCE MATERIAL before relying on it"))
        assertTrue(prompt.endsWith("Write the close-out now."), "the instruction at the end is never cut off")
        assertEquals(2, trace.qc!!.given!!.sourceItems)
    }

    @Test
    fun `a property the client cannot read is named as not shown, never dropped silently`() {
        val container = runBlocking { f.fake.createPage(t.containers, mapOf("Name" to NotionValue.Title("A container"))) }
        item("Has hidden", extra = mapOf("Container" to NotionValue.Relation(listOf(container.id))))
        item("Has none")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Check it" to "Check") }
        run(model)
        val prompt = model.taskPrompts().single().prompt
        assertEquals(1, Regex("properties this client cannot read, so not shown: Container").findAll(prompt).count(), "named once, for the row that has one")
        assertTrue(prompt.contains("### Has hidden") && prompt.contains("### Has none"))
    }

    // ── Bounds: nothing is cut without saying which item ─────────────────────

    private val tight = ContentLimits(perPageChars = 2_000, perTaskChars = 5_000, perCycleChars = 60_000)

    @Test
    fun `rows that do not fit are left out whole and named, in the prompt and in the trace, and the model is not given their text`() {
        val rows = (1..6).map { n -> item("Row $n", said = "R$n " + "x".repeat(1_400)) }
        goalOverItems()
        val model = Recording().apply { plan = tasks("Consolidate them" to "Consolidate") }
        val (trace, _) = run(model, tight)

        val prompt = model.taskPrompts().single().prompt
        val given = rows.filter { prompt.contains("### ${it.properties.values.filterIsInstance<NotionValue.Title>().first().text} ") }
        val left = rows - given.toSet()
        assertTrue(given.isNotEmpty() && left.isNotEmpty(), "the bound should bite: given ${given.size} of ${rows.size}")
        assertTrue(prompt.contains("NOT GIVEN TO YOU (limits of this call, not facts about the sources):"), prompt)
        for (row in left) {
            val title = row.properties.values.filterIsInstance<NotionValue.Title>().first().text
            assertTrue(prompt.contains("LEFT OUT: \"$title\" <${notionPageUrl(row.id)}>"), "left-out row '$title' is named")
            assertFalse(prompt.contains("R${title.removePrefix("Row ")} x"), "its text was not given")
        }
        val g = trace.execution.single().given!!
        assertEquals(left.map { it.id }.toSet(), g.leftOutItemIds.toSet())
        assertEquals(left.size, g.leftOutCount)
        assertEquals(given.size, g.sourceItems)
        assertTrue(trace.execution.single().given!!.notGivenLines >= left.size)
        assertTrue(trace.content.any { it.truncated && it.note!!.contains("left out") })
    }

    @Test
    fun `one property longer than the per-row bound is cut with a marker that says how much, and the row is named as cut`() {
        val huge = "H".repeat(5_000)
        val row = item("Huge row", said = huge)
        item("Normal row")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Check them" to "Check") }
        val (trace, _) = run(model, ContentLimits(perPageChars = 2_000, perTaskChars = 14_000))

        val prompt = model.taskPrompts().single().prompt
        assertTrue(Regex("""\[CUT: showing \d+ of 5000 characters of this property""").containsMatchIn(prompt), prompt.take(3_000))
        assertTrue(prompt.contains("CUT: \"Huge row\" <${notionPageUrl(row.id)}> was given only in part"), "the cut row is named")
        assertTrue(prompt.contains("said: Normal row"), "the other row is untouched")
        assertEquals(listOf(row.id), trace.execution.single().given!!.cutItemIds)
    }

    @Test
    fun `the source for a task never exceeds the per-task bound, and QC's never exceeds its own`() {
        (1..12).forEach { n -> item("Bulk $n", said = "B$n " + "y".repeat(900)) }
        goalOverItems()
        val model = Recording().apply { plan = tasks("Consolidate" to "Consolidate") }
        val limits = ContentLimits(perPageChars = 3_000, perTaskChars = 4_000)
        run(model, limits)

        fun sourceChars(prompt: String) = prompt.substringAfter("SOURCE MATERIAL (").substringBefore("NOT GIVEN TO YOU").length
        assertTrue(sourceChars(model.taskPrompts().single().prompt) <= 4_000 + 400, "task source: ${sourceChars(model.taskPrompts().single().prompt)}")
        assertTrue(sourceChars(model.qcPrompt().prompt) <= 4_000 + 400, "QC source: ${sourceChars(model.qcPrompt().prompt)}")
    }

    @Test
    fun `a page whose text is cut by the bound says which page and how much`() {
        f.fake.setBlocks(t.personaPageId, (1..400).map { app.alfrd.engram.notion.FakeBlock("paragraph", "Persona line $it with enough filler text to be long") })
        f.goal(title = "Read the persona", scope = scopeOf("", pageMention(t.personaPageId, "Persona")))
        val model = Recording().apply { plan = tasks("Check it" to "Check") }
        val (trace, _) = run(model, ContentLimits(perPageChars = 3_000, perTaskChars = 14_000))

        val prompt = model.taskPrompts().single().prompt
        assertTrue(prompt.contains("[CUT: this page has more text than the size limit allowed; the rest was not read]"), prompt.takeLast(1_500))
        assertTrue(prompt.contains("CUT: \"Persona\" <$personaUrl> was given only in part"))
        assertEquals(1, trace.execution.single().given!!.cutItemIds.size)
    }

    @Test
    fun `no cut is ever silent in what a worker is given, and the old digest ellipsis is gone from it`() {
        item("Long", said = "w ".repeat(2_000))
        goalOverItems()
        val model = Recording().apply { plan = tasks("Check" to "Check") }
        run(model, ContentLimits(perPageChars = 6_000, perTaskChars = 14_000))
        val prompt = model.taskPrompts().single().prompt
        assertFalse(prompt.contains("…"), "a worker is never given text ending in a bare ellipsis")
    }

    @Test
    fun `the planner's digest marks its own cuts, and says how many properties it left off`() {
        item("Many props", said = "N".repeat(900))
        goalOverItems()
        val goal = GoalMapper.fromPage(f.fake.rows(t.goals).single())
        val context = runBlocking { ScopeReader(f.guarded(), f.tableSet, maxFactsPerRow = 1, maxFactChars = 200).read(goal.scope) }
        val cycle = Cycle("c", "Cycle A", "CYCLE-1", CycleStatus.RUNNING, "g", 1, 6, null, "arx-1", null, null, "", Instant.EPOCH, Instant.EPOCH)
        val prompt = PlanPromptBuilder.build(PlanningInput(goal, cycle, 6, emptyList(), null, null, context))
        assertTrue(context.rows.single().factsOmitted > 0)
        assertTrue(prompt.contains("more properties not listed here)"), prompt)
        val cutRow = runBlocking { ScopeReader(f.guarded(), f.tableSet, maxFactChars = 200).read(goal.scope) }.rows.single()
        assertTrue(cutRow.facts.any { Regex("""\[cut: showing \d+ of 900 characters\]""").containsMatchIn(it) }, cutRow.facts.toString())
        assertFalse(prompt.contains("…"))
        assertEquals(900, cutRow.fields.first { it.name == "Said" }.text.length, "the full text is kept on the row for the workers")
    }

    // ── 2. How to work: one system prompt per task type ──────────────────────

    @Test
    fun `each task type gets its own system prompt, by name, and an unknown type gets the conservative Read one`() {
        val names = TaskType.PROPOSABLE.associateWith { CyclePrompts.forTask(it).name }
        assertEquals(mapOf(TaskType.READ to "task.read", TaskType.CHECK to "task.check", TaskType.HYPOTHESIS to "task.hypothesis", TaskType.CONSOLIDATE to "task.consolidate", TaskType.ESCALATE to "task.escalate"), names)
        assertEquals(TaskType.PROPOSABLE.size, TaskType.PROPOSABLE.map { CyclePrompts.forTask(it).text }.toSet().size, "no two types share a text")
        assertEquals("task.read", CyclePrompts.forTask(null).name)
        assertEquals("task.read", CyclePrompts.forTask(TaskType.GATE).name)
    }

    @Test
    fun `the executor sends the prompt of the task's own type`() {
        item("Row")
        goalOverItems()
        val model = Recording().apply { plan = tasks("a" to "Read", "b" to "Check", "c" to "Consolidate", "d" to "Hypothesis", "e" to "Escalate") }
        run(model)
        assertEquals(
            listOf("task.read", "task.check", "task.consolidate", "task.hypothesis", "task.escalate"),
            model.taskPrompts().map { req -> CyclePrompts.taskPrompts.single { it.text == req.systemPrompt }.name },
        )
    }

    @Test
    fun `Check verifies against the source, assumes earlier work may be wrong, and reports discrepancies`() {
        val text = CyclePrompts.forTask(TaskType.CHECK).text
        for (rule in listOf("Assume the earlier work may be wrong", "find the evidence in the SOURCE MATERIAL yourself", "confirmed, contradicted, or not found", "Report specific discrepancies", "do not repeat any claim as true because an earlier task said it")) {
            assertTrue(text.contains(rule), "missing: $rule")
        }
    }

    @Test
    fun `Consolidate works from the source rows and uses earlier results only as notes`() {
        val text = CyclePrompts.forTask(TaskType.CONSOLIDATE).text
        for (rule in listOf("Work from the SOURCE MATERIAL", "Use EARLIER WORK only as notes", "the source wins", "Do not drop items", "how many items you were given")) assertTrue(text.contains(rule), "missing: $rule")
    }

    @Test
    fun `QC judges Done when against the source rows and does not repeat an unconfirmed conclusion`() {
        val text = CyclePrompts.QC.text
        for (rule in listOf("Judge Done when against the SOURCE MATERIAL, not only against the task results", "Do not repeat a task's conclusion unless you confirmed it in the sources", "unconfirmed", "contradicts the sources")) assertTrue(text.contains(rule), "missing: $rule")
    }

    @Test
    fun `every prompt tells the model that a cut or left-out item is a limit of the call, not a fact about the source`() {
        for (p in CyclePrompts.taskPrompts + CyclePrompts.QC) {
            assertTrue(p.text.contains("NOT GIVEN TO YOU") && p.text.contains("[CUT"), p.name)
            assertTrue(p.text.contains("never say") && p.text.contains("truncated"), p.name)
        }
    }

    @Test
    fun `the system prompts are goal-neutral and carry no data to work on`() {
        val forbidden = listOf("Template", "Persona", "Items", "Container", "Sensitivity", "Entities", "Connections", "PPS", "Findings", "Claims", "GOAL-", "CYC-", "notion.so", "Change 1")
        for (p in CyclePrompts.all) for (w in forbidden) assertFalse(p.text.contains(w), "${p.name} mentions '$w'")
        // The per-call prompt, not the system prompt, carries the rows: a row's text is never in a system prompt.
        item("Distinctive row title 12345")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Check" to "Check") }
        run(model)
        for (r in model.requests) assertFalse(r.systemPrompt!!.contains("Distinctive row title 12345"))
        assertTrue(model.taskPrompts().single().prompt.contains("Distinctive row title 12345"))
    }

    @Test
    fun `the per-call prompts carry no working rules, which belong to the system prompts`() {
        item("Row")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Check" to "Check") }
        run(model)
        for (r in model.requests.filter { it.forcedToolName != CyclePlanDirector.TOOL_NAME }) {
            assertFalse(r.prompt.contains("How to work"), r.forcedToolName)
            assertFalse(r.prompt.contains("Assume the earlier work may be wrong"))
        }
    }

    @Test
    fun `the planner is told which types get the scope automatically, from the same flag the processor uses`() {
        val plan = CyclePrompts.PLAN.text
        assertTrue(plan.contains("Check and Consolidate tasks are also given the goal's in-scope rows and pages automatically"), plan)
        assertEquals(setOf(TaskType.CHECK, TaskType.CONSOLIDATE), TaskType.entries.filter { it.drawsOnScope }.toSet())
    }

    @Test
    fun `each prompt has a name, a version and a pinned fingerprint, so editing the text without bumping the version fails here`() {
        val pinned = mapOf(
            "plan" to ("2" to "bdbf4db2993c"), "task.read" to ("1" to "d3c22801d0aa"), "task.check" to ("1" to "80fd42edb1da"), "task.consolidate" to ("1" to "b82a372bb4df"),
            "task.hypothesis" to ("1" to "a753b8db2e9d"), "task.escalate" to ("1" to "80c30dc844be"), "qc" to ("2" to "b27602da948f"),
        )
        assertEquals(pinned.keys, CyclePrompts.all.map { it.name }.toSet())
        for (p in CyclePrompts.all) {
            assertEquals(12, p.fingerprint.length)
            assertEquals(pinned.getValue(p.name), p.version to p.fingerprint, "${p.name}: text changed, so bump its version and update this pin")
        }
        assertEquals(CyclePrompts.all.size, CyclePrompts.all.map { it.fingerprint }.toSet().size)
    }

    // ── 3. Visibility in the trace ───────────────────────────────────────────

    @Test
    fun `the trace names the system prompt each call used, and what it was given, as ids and counts`() {
        val a = item("Alpha", said = "ALPHA-SECRET-TEXT")
        val b = item("Beta")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Read it" to "Read", "Check it" to "Check", "Gather" to "Consolidate") }
        val (trace, _) = run(model)

        assertEquals(PromptRef("plan", "2", CyclePrompts.PLAN.fingerprint), trace.model!!.given!!.prompt)
        assertEquals(listOf("task.read", "task.check", "task.consolidate"), trace.execution.map { it.given!!.prompt.name })
        assertEquals(CyclePrompts.QC.ref, trace.qc!!.given!!.prompt)
        assertEquals(listOf(emptyList(), listOf(1), listOf(1, 2)), trace.execution.map { it.given!!.earlierTasks })

        val check = trace.execution[1].given!!
        assertEquals(setOf(a.id, b.id), check.sourceItemIds.toSet())
        assertEquals(2, check.sourceItems)
        assertTrue(check.promptChars > 0)
        assertEquals(0, trace.execution[0].given!!.sourceItems, "the Read task linked nothing")
        assertEquals(setOf(a.id, b.id), trace.qc!!.given!!.sourceItemIds.toSet())
        assertEquals(setOf(a.id, b.id), trace.model!!.given!!.sourceItemIds.toSet(), "the planner's shown rows")
        assertEquals(0, trace.model!!.given!!.leftOutCount)
    }

    @Test
    fun `what was given is recorded without any text of it`() {
        item("Alpha", said = "ALPHA-SECRET-TEXT")
        goalOverItems()
        val model = Recording().apply { plan = tasks("Check it" to "Check") }
        val (trace, _) = run(model)
        val json = Json.encodeToString(CyclePlanTrace.serializer(), trace)
        assertFalse(json.contains("ALPHA-SECRET-TEXT"), "row text must never reach the trace")
        assertFalse(json.contains("Findings: "), "nor a task's own words")
        assertTrue(json.contains("\"name\":\"task.check\"") && json.contains("\"version\":\"1\"") && json.contains("\"fingerprint\""))
    }

    @Test
    fun `the planner trace says which rows did not fit in its prompt`() {
        (1..300).forEach { n -> item("Planner row $n", said = "s".repeat(220)) }
        goalOverItems()
        val model = Recording().apply { plan = tasks("Read" to "Read") }
        val (trace, _) = run(model)
        val g = trace.model!!.given!!
        assertTrue(g.leftOutCount > 0 && g.leftOutItemIds.isNotEmpty(), "the planner prompt is bounded, and the trace says what it left off")
        assertEquals(100, g.sourceItems + g.leftOutCount - 0 - (g.sourceItems + g.leftOutCount - 100).coerceAtLeast(0) )
        assertTrue(g.notGivenLines >= 1)
    }

    @Test
    fun `a call that fails still records what it was given`() {
        item("Alpha")
        goalOverItems()
        val model = object : app.alfrd.engram.cognitive.providers.LlmClient {
            override suspend fun complete(request: LlmRequest): app.alfrd.engram.cognitive.providers.LlmResponse {
                if (request.forcedToolName == DirectorTaskExecutor.TOOL_NAME) throw IllegalStateException("boom")
                return Recording().apply { plan = tasks("Check" to "Check") }.complete(request)
            }
        }
        val cycle = f.cycle(f.fake.rows(t.goals).single().id)
        runBlocking { CycleProcessor(f.guarded(), f.tableSet, CyclePlanDirector(model), DirectorTaskExecutor(model), CycleQcDirector(model), traces, "arx-1", { f.base }, sleep = {}).runOnce() }
        val run = traces.recent(1).single().execution.single()
        assertEquals("failed", run.outcome)
        assertEquals("task.check", run.given!!.prompt.name)
        assertEquals(1, run.given!!.sourceItems)
        assertTrue(cycle.id.isNotEmpty())
    }

    // ── The stager, directly ─────────────────────────────────────────────────

    @Test
    fun `stageSources gives the mentioned page and then the mentioned table's rows, in Scope order`() {
        item("Alpha")
        val goalScope = scopeOf("", pageMention(t.personaPageId, "Persona"), databaseMention(f.tableSet.table(AgentWorkTables.MEMORY_ITEMS).databaseId!!, "Items"))
        val context = runBlocking { ScopeReader(f.guarded(), f.tableSet).read(GoalScope.fromRichText(goalScope)) }
        val stager = TaskStager(context, PageContentReader(f.guarded()))
        val staged = runBlocking { stager.stageSources() }
        assertEquals(listOf("Persona"), staged.pages.take(1).map { it.title })
        assertTrue(staged.pages[1].title.startsWith("Rows of memory-items"))
        assertEquals(2, staged.pages.size)
        assertTrue(staged.notGiven().isEmpty())
        val tiny = runBlocking { TaskStager(context, PageContentReader(f.guarded())).stageSources(room = 200) }
        assertTrue(tiny.items.isEmpty(), "with no room nothing is half-given: ${tiny.items}")
        assertTrue(tiny.problems.isNotEmpty() && tiny.leftOutItems.size == 1, "and both what did not fit are named: ${tiny.notGiven()}")
    }

    @Test
    fun `fixed instant for the fixture clock is used`() {
        assertEquals(Instant.parse("2026-10-12T10:00:00Z"), f.base)
        assertNotEquals(CyclePrompts.QC.text, CyclePrompts.PLAN.text)
    }
}
