package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** The two model decisions after the plan: doing one task from staged content, and QC's close-out. Both are proposals; code validates them. */
class CycleTaskAndQcTest {

    private val memoUrl = "https://www.notion.so/aaaaaaaaaaaa4aaa8aaa000000000001"
    private val otherUrl = "https://www.notion.so/bbbbbbbbbbbb4bbb8bbb000000000002"

    private fun goal(doneWhen: String = "Every claim in Persona is marked.", scope: GoalScope = GoalScope.EMPTY) =
        Goal("g", "Reconcile Persona", "GOAL-1", GoalStatus.ACTIVE, doneWhen, scope, emptyList(), 10, 3, null)

    private fun cycle() = Cycle("c", "Cycle A", "CYCLE-1", CycleStatus.RUNNING, "g", 1, 6, null, "arx-1", null, null, "", Instant.EPOCH, Instant.EPOCH)

    private fun task(order: Int = 2, title: String = "Compare Persona with the Items", links: List<String> = emptyList()) = AgentTask(
        "t$order", title, "TASK-$order", TaskType.CHECK, TaskStatus.PLANNED, order, 1,
        "Compare each claim with what the Items say.", links.joinToString("\n"), links, "", "cycle:CYCLE-1:v1:o$order", listOf("c"),
    )

    private fun staged(vararg pages: StagedPage, problems: List<StagingProblem> = emptyList()) = StagedContext(pages.toList(), problems, emptyList())
    private fun page(url: String = memoUrl, title: String = "Persona", text: String = "Jacob prefers short answers.") = StagedPage(url, title, "persona", text, truncated = false)

    private fun input(staged: StagedContext = staged(page()), earlier: List<EarlierResult> = emptyList(), goal: Goal = goal(), task: AgentTask = task()) =
        TaskExecutionInput(goal, cycle(), task, taskCount = 4, staged = staged, earlier = earlier)

    private fun execute(llm: ScriptedLlm?, input: TaskExecutionInput = input()): ExecutionOutcome = runBlocking { DirectorTaskExecutor(llm).execute(input) }
    private fun answering(json: kotlinx.serialization.json.JsonObject) = ScriptedLlm.answering(json, DirectorTaskExecutor.TOOL_NAME)

    // ── The task call ────────────────────────────────────────────────────────

    @Test
    fun `one forced tool call is made, with the goal, the task, the staged pages and the earlier results in the prompt`() {
        val llm = answering(RoutedLlm.taskDone("Persona matches.", memoUrl))
        execute(llm, input(earlier = listOf(EarlierResult(1, "Read Persona", TaskStatus.DONE, "Read it: 12 claims."))))

        val request = llm.requests.single()
        assertEquals(DirectorTaskExecutor.TOOL_NAME, request.forcedToolName)
        assertEquals(listOf(DirectorTaskExecutor.TOOL_NAME), request.tools.map { it.name })
        assertEquals(0.2, request.temperature)
        assertEquals(CyclePrompts.forTask(TaskType.CHECK).text, request.systemPrompt, "a Check task gets the Check prompt")
        assertTrue(request.systemPrompt!!.contains("You can change nothing") && request.systemPrompt!!.contains("Blocking is correct; guessing is not."))
        val prompt = request.prompt
        assertTrue(prompt.contains("GOAL: Reconcile Persona (GOAL-1)"), prompt)
        assertTrue(prompt.contains("DONE WHEN: Every claim in Persona is marked."), prompt)
        assertTrue(prompt.contains("TASK 2 of 4: Compare Persona with the Items  (Check)"), prompt)
        assertTrue(prompt.contains("WHAT TO DO: Compare each claim"), prompt)
        assertTrue(prompt.contains("- Task 1 Read Persona [Done]: Read it: 12 claims."), prompt)
        assertTrue(prompt.contains("Jacob prefers short answers.") && !prompt.contains(memoUrl), "a page is shown without its id: $prompt")
    }

    @Test
    fun `the prompt names no retired goal field and no token`() {
        val prompt = TaskPromptBuilder.build(input())
        for (retired in listOf("Intent", "Metric", "Done Criteria", "Target Links", "Target Space", "Goal Type")) assertFalse(prompt.contains(retired), retired)
        assertFalse(prompt.contains("ntn_") || prompt.contains("Bearer") || prompt.contains("notion-worker-token"))
    }

    @Test
    fun `a task that names no page says so instead of showing an empty list`() {
        val prompt = TaskPromptBuilder.build(input(staged = staged()))
        assertTrue(prompt.contains("SOURCE MATERIAL: none was given for this task"), prompt)
    }

    @Test
    fun `a done answer is returned with only the citations that were staged`() {
        val outcome = execute(answering(RoutedLlm.taskDone("Persona matches.", memoUrl, otherUrl, "https://example.org/made-up")), input(staged = staged(page())))
        val done = outcome.execution as TaskExecution.Done
        assertEquals("Persona matches.", done.result)
        assertEquals(listOf(memoUrl), done.pagesUsed, "a page that was not staged cannot be cited")
        assertEquals(2, done.droppedCitations)
        assertTrue(outcome.modelCalled)
        assertEquals("qwen-test", outcome.model)
    }

    @Test
    fun `a citation is matched by page id, not by spelling`() {
        val done = execute(answering(RoutedLlm.taskDone("ok", "https://app.notion.com/p/aaaaaaaaaaaa4aaa8aaa000000000001?pvs=204"))).execution as TaskExecution.Done
        assertEquals(listOf(memoUrl), done.pagesUsed, "the Result cites the staged page's own URL, never the model's spelling of it")
        assertEquals(0, done.droppedCitations)
    }

    @Test
    fun `a citation whose id sits outside the path of the url does not cite the staged page`() {
        val id = memoUrl.substringAfterLast("/")
        val done = execute(answering(RoutedLlm.taskDone("ok", "https://$id@www.notion.so/unrelated", "https://www.notion.so/unrelated#$id"))).execution as TaskExecution.Done
        assertTrue(done.pagesUsed.isEmpty(), done.pagesUsed.toString())
        assertEquals(2, done.droppedCitations)
    }

    @Test
    fun `cancellation is not a model failure in the planner, the worker or QC, and propagates so the cycle is left Running for a later reclaim`() {
        val cancelling = ScriptedLlm { throw kotlinx.coroutines.CancellationException("the poll was cancelled") }
        for ((name, call) in listOf<Pair<String, () -> Any?>>(
            "worker" to { execute(cancelling) },
            "QC" to { qc(cancelling) },
        )) {
            val thrown = runCatching { call() }.exceptionOrNull()
            assertTrue(thrown is kotlinx.coroutines.CancellationException, "$name: expected cancellation to propagate, got $thrown")
        }
    }

    @Test
    fun `a blocked answer carries its reason, falling back to the result text and then to a stock sentence`() {
        assertEquals("Items table was not in scope.", (execute(answering(RoutedLlm.taskBlocked("Items table was not in scope."))).execution as TaskExecution.Blocked).reason)
        val viaResult = buildJsonObject { put("outcome", "blocked"); put("result", "no items staged") }
        assertEquals("no items staged", (execute(answering(viaResult)).execution as TaskExecution.Blocked).reason)
        val bare = buildJsonObject { put("outcome", "blocked") }
        assertEquals("the staged material was not enough", (execute(answering(bare)).execution as TaskExecution.Blocked).reason)
    }

    @Test
    fun `a reason or a result is capped`() {
        val long = "x".repeat(20_000)
        assertEquals(DirectorTaskExecutor.MAX_RESULT_CHARS, ((execute(answering(RoutedLlm.taskDone(long))).execution) as TaskExecution.Done).result.length)
        assertEquals(DirectorTaskExecutor.MAX_REASON_CHARS, ((execute(answering(RoutedLlm.taskBlocked(long))).execution) as TaskExecution.Blocked).reason.length)
    }

    @Test
    fun `no model, an unusable answer or a throwing model each fail the task with a code and never repeat the model's words`() {
        assertEquals("no_llm_client", (execute(null).execution as TaskExecution.Failed).reason)
        assertFalse(execute(null).modelCalled)

        val thrown = execute(ScriptedLlm { throw IllegalStateException("the prompt said SECRET-PROMPT-TEXT ntn_SECRET") })
        assertEquals("llm_call_failed: IllegalStateException", (thrown.execution as TaskExecution.Failed).reason)
        assertTrue(thrown.modelCalled)

        assertEquals("no_tool_call", (execute(ScriptedLlm { LlmResponse("I did it", latencyMs = 1, retryCount = 0) }).execution as TaskExecution.Failed).reason)
        assertEquals("no_tool_call", (execute(ScriptedLlm.answering(RoutedLlm.taskDone("x"), "some_other_tool")).execution as TaskExecution.Failed).reason)
        assertEquals("empty_result", (execute(answering(buildJsonObject { put("outcome", "done"); put("result", "   ") })).execution as TaskExecution.Failed).reason)
        assertEquals("unknown_outcome", (execute(answering(buildJsonObject { put("outcome", "maybe"); put("result", "x") })).execution as TaskExecution.Failed).reason)
        assertEquals("unknown_outcome", (execute(answering(buildJsonObject { put("result", "x") })).execution as TaskExecution.Failed).reason)
    }

    @Test
    fun `a second tool call in the answer is ignored`() {
        val llm = ScriptedLlm {
            LlmResponse(
                "", toolCalls = listOf(ToolCall(DirectorTaskExecutor.TOOL_NAME, RoutedLlm.taskDone("first")), ToolCall(DirectorTaskExecutor.TOOL_NAME, RoutedLlm.taskDone("second"))),
                latencyMs = 1, retryCount = 0,
            )
        }
        assertEquals("first", (execute(llm).execution as TaskExecution.Done).result)
    }

    // ── QC ───────────────────────────────────────────────────────────────────

    private fun qcInput(
        lines: List<QcTaskLine> = listOf(
            QcTaskLine(1, "Read Persona", "Read", TaskStatus.DONE, "12 claims, 9 supported."),
            QcTaskLine(2, "Check against Items", "Check", TaskStatus.BLOCKED, "Blocked: the Items table was not staged."),
            QcTaskLine(3, "Consolidate", "Consolidate", TaskStatus.FAILED, "Not executed: the model call failed."),
        ),
        goal: Goal = goal(),
    ) = QcInput(goal, cycle(), listOf("page Persona (persona): read", "database Items: 40 row(s) read"), lines, rejectedAtPlanning = 2)

    private fun qc(llm: ScriptedLlm?, input: QcInput = qcInput()): QcResult = runBlocking { CycleQcDirector(llm).decide(input) }
    private fun qcAnswering(json: kotlinx.serialization.json.JsonObject) = ScriptedLlm.answering(json, CycleQcDirector.TOOL_NAME)

    @Test
    fun `one forced call writes the close-out, from the goal, Done when, scope and every task's outcome`() {
        val llm = qcAnswering(RoutedLlm.qcOf())
        qc(llm)

        val request = llm.requests.single()
        assertEquals(CycleQcDirector.TOOL_NAME, request.forcedToolName)
        assertTrue(request.systemPrompt!!.contains("You cannot and do not change the goal's Status"))
        val prompt = request.prompt
        assertTrue(prompt.contains("DONE WHEN: Every claim in Persona is marked."), prompt)
        assertTrue(prompt.contains("- page Persona (persona): read") && prompt.contains("- database Items: 40 row(s) read"), prompt)
        assertTrue(prompt.contains("3 planned; done 1, blocked 1, failed 1; 2 more proposed task(s) were rejected by code"), prompt)
        assertTrue(prompt.contains("Task 2 [Blocked] (Check) Check against Items") && prompt.contains("the Items table was not staged"), prompt)
    }

    @Test
    fun `scope prose that code ignored, and links that were not mentions, are told to QC`() {
        val scope = GoalScope(emptyList(), proseChars = 40, proseExcerpt = "only the live ones please", ignoredLinks = listOf("https://example.org/x"))
        val llm = qcAnswering(RoutedLlm.qcOf())
        qc(llm, qcInput(goal = goal(scope = scope)))
        val prompt = llm.requests.single().prompt
        assertTrue(prompt.contains("Scope ALSO held 40 letters and digits of prose") && prompt.contains("only the live ones please"), prompt)
        assertTrue(prompt.contains("1 link(s) that are not mentions"), prompt)
    }

    @Test
    fun `a missing Done when is said plainly`() {
        val llm = qcAnswering(RoutedLlm.qcOf())
        qc(llm, qcInput(goal = goal(doneWhen = "")))
        assertTrue(llm.requests.single().prompt.contains("(Jacob wrote no Done when)"))
    }

    @Test
    fun `a valid answer becomes a decision, with a suggested rewrite only for Goal, Done when or Scope`() {
        val ok = qc(qcAnswering(RoutedLlm.qcOf(summary = "S", looksMet = "yes", feedback = "F", rewriteField = "Done when", rewriteText = "Every claim has a status."))).decision!!
        assertEquals(QcDecision("S", "yes", "F", "Done when", "Every claim has a status."), ok)

        for (field in listOf("Goal", "Done when", "Scope")) {
            assertEquals(field, qc(qcAnswering(RoutedLlm.qcOf(rewriteField = field))).decision!!.suggestedField)
        }
        val bad = qc(qcAnswering(RoutedLlm.qcOf(rewriteField = "Status", rewriteText = "Met"))).decision!!
        assertNull(bad.suggestedField, "QC may not suggest changing the goal's Status")
        assertNull(bad.suggestedText)
        val noText = qc(qcAnswering(RoutedLlm.qcOf(rewriteField = "Scope", rewriteText = "  "))).decision!!
        assertNull(noText.suggestedField)
    }

    @Test
    fun `an unknown looks-met value reads as unclear, and long text is capped`() {
        assertEquals("unclear", qc(qcAnswering(RoutedLlm.qcOf(looksMet = "definitely"))).decision!!.goalLooksMet)
        val capped = qc(qcAnswering(RoutedLlm.qcOf(summary = "s".repeat(9_000), feedback = "f".repeat(9_000)))).decision!!
        assertEquals(CycleQcDirector.MAX_TEXT_CHARS, capped.summary.length)
        assertEquals(CycleQcDirector.MAX_TEXT_CHARS, capped.goalFeedback.length)
    }

    @Test
    fun `an answer with no summary or no feedback is not a close-out`() {
        assertEquals("malformed_tool_input", qc(qcAnswering(RoutedLlm.qcOf(summary = ""))).rejectedReason)
        assertEquals("malformed_tool_input", qc(qcAnswering(RoutedLlm.qcOf(feedback = " "))).rejectedReason)
        assertNull(qc(qcAnswering(RoutedLlm.qcOf(summary = ""))).decision)
    }

    @Test
    fun `no model, a throwing model or no tool call are reported by code`() {
        assertEquals("no_llm_client", qc(null).rejectedReason)
        assertEquals("llm_call_failed: IllegalStateException", qc(ScriptedLlm { throw IllegalStateException("SECRET") }).rejectedReason)
        assertEquals("no_tool_call", qc(ScriptedLlm { LlmResponse("all good", latencyMs = 1, retryCount = 0) }).rejectedReason)
    }

    @Test
    fun `the summary and the feedback are composed with the Director's own counts and a note that the goal's Status was not touched`() {
        val decision = QcDecision("Two tasks produced results.", "partly", "Done when named no number.", "Done when", "Every claim is marked.")
        val summary = QcComposer.summary(done = 2, blocked = 1, failed = 0, total = 3, decision = decision)
        assertEquals(
            "Executed 3 tasks: 2 done, 1 blocked, 0 failed. Two tasks produced results. Goal looks met: partly (the goal's Status is yours to change; it was not touched).",
            summary,
        )
        assertEquals("Done when named no number.\n\nSuggested rewrite of Done when: Every claim is marked.", QcComposer.feedback(decision))
        assertEquals("Done when named no number.", QcComposer.feedback(decision.copy(suggestedField = null, suggestedText = null)))
        assertTrue(QcComposer.summary(1, 0, 0, 1, decision).startsWith("Executed 1 task: "))
    }
}
