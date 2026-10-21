package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.ToolCall
import app.alfrd.engram.notion.NotionValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How the three cycle calls (plan, task, QC) ask the local model, and what the trace keeps of how each answer
 * ended. Added after CYC-1: the local model, asked to call a tool with thinking switched off and a 3,000-token
 * budget, wrote its reasoning as visible text and never made the call, and nothing recorded why.
 */
class CycleModelCallTest {

    private val f = AgentWorkFixture()
    private val t = f.template
    private val personaUrl get() = notionPageUrl(t.personaPageId)

    /** A model that records every request and answers each stage with the response the test builds for it. */
    private class Recording(private val answer: (LlmRequest) -> LlmResponse) : LlmClient {
        val requests = ArrayList<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { requests += request; return answer(request) }
    }

    private fun reply(request: LlmRequest, input: JsonObject, finish: String? = "tool_calls", tokens: Int? = 1234, content: String = "") =
        LlmResponse(content, toolCalls = listOf(ToolCall(request.forcedToolName!!, input)), latencyMs = 5, retryCount = 0, providerName = "local", modelName = "qwen-test", finishReason = finish, completionTokens = tokens)

    private fun happyModel() = Recording { r ->
        when (r.forcedToolName) {
            CyclePlanDirector.TOOL_NAME -> reply(r, ScriptedLlm.planOf(ScriptedLlm.taskJson("Read Persona", "Read", "Read it.", personaUrl)))
            DirectorTaskExecutor.TOOL_NAME -> reply(r, RoutedLlm.taskDone("Persona read.", personaUrl), tokens = 400)
            else -> reply(r, RoutedLlm.qcOf(), tokens = 600)
        }
    }

    private fun runCycle(model: LlmClient): Pair<CycleTraceStore, String> {
        val traces = CycleTraceStore()
        val goal = f.goal(scope = scopeOf("", pageMention(t.personaPageId, "Persona")))
        val cycle = f.cycle(goal.id)
        runBlocking { f.processor(model, traces).runOnce() }
        return traces to cycle.id
    }

    // ── The flag and the budgets ─────────────────────────────────────────────

    @Test
    fun `plan, task and QC each ask for reasoning, an 8000-token budget and the one forced tool`() {
        val model = happyModel()
        runCycle(model)

        val byTool = model.requests.associateBy { it.forcedToolName }
        assertEquals(setOf(CyclePlanDirector.TOOL_NAME, DirectorTaskExecutor.TOOL_NAME, CycleQcDirector.TOOL_NAME), byTool.keys)
        for ((tool, request) in byTool) {
            assertTrue(request.reasoning, "$tool must opt in to reasoning")
            assertEquals(8_000, request.maxTokens, tool)
            assertEquals(listOf(tool), request.tools.map { it.name }, "$tool offers exactly the tool it forces")
        }
    }

    @Test
    fun `the budgets are one constant per call, and none is below what the local model needed to think and then call`() {
        assertEquals(8_000, CyclePlanDirector.MAX_TOKENS)
        assertEquals(8_000, DirectorTaskExecutor.MAX_TOKENS)
        assertEquals(8_000, CycleQcDirector.MAX_TOKENS)
        // The worst case at the measured ~80 tokens/s must stay inside the call's own timeout.
        assertTrue(CyclePlanDirector.MAX_TOKENS / 80.0 * 1000 < CyclePlanDirector.TIMEOUT_MS)
    }

    @Test
    fun `nothing else in this package opts in to reasoning, and the default request does not`() {
        assertFalse(LlmRequest(prompt = "x").reasoning)
        val cycleSources = java.io.File("src/main/kotlin/app/alfrd/engram/cognitive/pipeline/agentwork").listFiles { f -> f.name.endsWith(".kt") }!!
        val opting = cycleSources.filter { it.readText().contains("reasoning = true") }.map { it.name }.sorted()
        assertEquals(listOf("CyclePlanDirector.kt", "CycleQcDirector.kt", "CycleTaskExecutor.kt"), opting)
        val others = java.io.File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" && it.path.contains("/agentwork/").not() }
            .filter { it.readText().contains("reasoning = true") }.map { it.name }.toList()
        // The claim extraction's forced-tool passes are the one caller outside agentwork that opts in, for the same reason (a long prompt and a forced tool).
        assertEquals(emptyList<String>(), others - "LocalClaimModel.kt", "chat, the Interpreter and the other directors must keep thinking off")
    }

    // ── The trace ────────────────────────────────────────────────────────────

    @Test
    fun `the trace keeps finish reason, tokens, content length and tool-call count for the plan, each task and QC`() {
        val (traces, _) = runCycle(happyModel())
        val trace = traces.recent(1).single()

        assertEquals(AnswerTrace("tool_calls", 1234, 0, 1, null, "qwen-test"), trace.model!!.answer)
        assertEquals(AnswerTrace("tool_calls", 400, 0, 1, null, "qwen-test"), trace.execution.single().answer)
        assertEquals(AnswerTrace("tool_calls", 600, 0, 1, null, "qwen-test"), trace.qc!!.answer)
    }

    @Test
    fun `visible content is measured and never stored, in the trace or its JSON`() {
        val prose = "SECRET-REASONING-TEXT ".repeat(20)
        val model = Recording { r ->
            when (r.forcedToolName) {
                CyclePlanDirector.TOOL_NAME -> reply(r, ScriptedLlm.planOf(ScriptedLlm.taskJson("Read Persona", "Read", "Read it.", personaUrl)), content = prose)
                DirectorTaskExecutor.TOOL_NAME -> reply(r, RoutedLlm.taskDone("ok", personaUrl))
                else -> reply(r, RoutedLlm.qcOf())
            }
        }
        val (traces, _) = runCycle(model)
        val trace = traces.recent(1).single()

        assertEquals(prose.length, trace.model!!.answer!!.contentChars)
        val json = Json.encodeToString(CyclePlanTrace.serializer(), trace)
        assertFalse(json.contains("SECRET-REASONING-TEXT"), "the model's words must not reach the trace")
        assertTrue(json.contains("\"finishReason\":\"tool_calls\"") && json.contains("\"completionTokens\":1234"))
    }

    @Test
    fun `an answer in prose with no tool call fails the cycle with the new wording and a trace that says what happened`() {
        val prose = "I will now generate the tool call."
        val model = Recording { r -> LlmResponse(prose, latencyMs = 5, retryCount = 0, providerName = "local", modelName = "qwen-test", finishReason = "stop", completionTokens = 2021) }
        val (traces, cycleId) = runCycle(model)

        val row = f.fake.page(cycleId)
        assertEquals("Failed", row.select("Status"))
        assertEquals("Cycle failed: the model answered without the required tool call. Nothing was written.", row.text("Summary"))
        assertFalse(row.text("Summary").contains("did not return an answer"))
        val trace = traces.recent(1).single()
        assertEquals("no_tool_call", trace.model!!.rejectedReason)
        assertEquals(AnswerTrace("stop", 2021, prose.length, 0, null, "qwen-test"), trace.model!!.answer)
        assertTrue(f.tasksFor(cycleId).isEmpty())
    }

    @Test
    fun `a cut-off answer is told apart from one that stopped by itself`() {
        val model = Recording { r -> LlmResponse("partial", toolCalls = emptyList(), latencyMs = 5, retryCount = 0, finishReason = "length", completionTokens = 8000) }
        val (traces, _) = runCycle(model)
        assertEquals("length", traces.recent(1).single().model!!.answer!!.finishReason)
        assertEquals(8000, traces.recent(1).single().model!!.answer!!.completionTokens)
    }

    @Test
    fun `a failing task and a failing QC keep their counts too, and a provider that reports nothing gives nulls`() {
        val model = Recording { r ->
            when (r.forcedToolName) {
                CyclePlanDirector.TOOL_NAME -> reply(r, ScriptedLlm.planOf(ScriptedLlm.taskJson("Read Persona", "Read", "Read it.", personaUrl)), finish = null, tokens = null)
                DirectorTaskExecutor.TOOL_NAME -> LlmResponse("prose only", latencyMs = 5, retryCount = 0, finishReason = "stop", completionTokens = 90)
                else -> LlmResponse("", latencyMs = 5, retryCount = 0, toolCalls = listOf(ToolCall("some_other_tool", JsonObject(emptyMap()))), finishReason = "tool_calls", completionTokens = 12)
            }
        }
        val (traces, cycleId) = runCycle(model)
        val trace = traces.recent(1).single()

        assertEquals(AnswerTrace(null, null, 0, 1, null, "qwen-test"), trace.model!!.answer)
        assertEquals("failed", trace.execution.single().outcome)
        assertEquals(AnswerTrace("stop", 90, 10, 0, null, null), trace.execution.single().answer)
        assertEquals("no_tool_call", trace.qc!!.rejectedReason)
        assertEquals(AnswerTrace("tool_calls", 12, 0, 1, null, null), trace.qc!!.answer)
        assertEquals("Not executed: the model answered without the required tool call. The task was not retried.", f.tasksFor(cycleId).single().text("Result"))
    }

    @Test
    fun `when validation drops the whole plan only the plan call is made, and there is no task or QC answer to record`() {
        val goal = f.goal(scope = scopeOf("", pageMention(t.personaPageId, "Persona")))
        f.cycle(goal.id)
        val traces = CycleTraceStore()
        // The plan links a page outside Scope, so validation drops it before any task exists.
        val model = Recording { r -> reply(r, ScriptedLlm.planOf(ScriptedLlm.taskJson("Read elsewhere", "Read", "Read it.", "https://example.org/x"))) }
        runBlocking { f.processor(model, traces).runOnce() }

        val trace = traces.recent(1).single()
        assertEquals(1, model.requests.size)
        assertTrue(trace.execution.isEmpty())
        assertNull(trace.qc)
        assertEquals(1234, trace.model!!.answer!!.completionTokens, "the plan call itself is still recorded")
    }
}
