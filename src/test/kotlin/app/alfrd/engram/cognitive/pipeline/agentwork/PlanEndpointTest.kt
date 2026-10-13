package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.LlmTimeoutError
import app.alfrd.engram.cognitive.providers.ToolCall
import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import app.alfrd.engram.notion.NotionConfigException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ConnectException

/**
 * The plan call may go to a second llama.cpp endpoint (PLAN_LLM_BASE_URL); tasks and QC never do. Off by
 * default. When the plan endpoint fails the default endpoint serves the call, and that is recorded on the
 * trace and in the cycle's Summary. Each call's trace says which base URL and model served it.
 */
class PlanEndpointTest {

    private val f = AgentWorkFixture()
    private val t = f.template
    private val personaUrl get() = notionPageUrl(t.personaPageId)

    /** One endpoint: answers each stage as a happy model would, or fails with [failure]. Records what it was asked. */
    private class Endpoint(val url: String, val model: String, val failure: (() -> Exception)? = null, val personaUrl: String) : LlmClient {
        val requests = ArrayList<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            failure?.let { throw it() }
            val input = when (request.forcedToolName) {
                CyclePlanDirector.TOOL_NAME -> ScriptedLlm.planOf(ScriptedLlm.taskJson("Read Persona", "Read", "Read it.", personaUrl))
                DirectorTaskExecutor.TOOL_NAME -> RoutedLlm.taskDone("Persona read.", personaUrl)
                else -> RoutedLlm.qcOf(looksMet = "yes")
            }
            return LlmResponse("", toolCalls = listOf(ToolCall(request.forcedToolName!!, input)), latencyMs = 7, retryCount = 0, providerName = "local", modelName = model, finishReason = "tool_calls", completionTokens = 100, endpoint = url)
        }
    }

    private fun endpoint(url: String, model: String, failure: (() -> Exception)? = null) = Endpoint(url, model, failure, personaUrl)

    private fun run(default: LlmClient, route: PlanRoute?): CyclePlanTrace {
        val traces = CycleTraceStore()
        val goal = f.goal(scope = scopeOf("", pageMention(t.personaPageId, "Persona")))
        f.cycle(goal.id)
        runBlocking { f.processor(default, traces, planRoute = route).runOnce() }
        return traces.recent().first()
    }

    private val defaultUrl = "http://127.0.0.1:8081/v1"
    private val planUrl = "http://127.0.0.1:8083/v1"

    // ── Override unset: exactly as before ────────────────────────────────────

    @Test
    fun `with no plan endpoint every call goes to the default endpoint with the budgets it always had`() {
        val default = endpoint(defaultUrl, "qwen35")
        val trace = run(default, null)

        assertEquals(3, default.requests.size, "plan, one task and QC")
        val plan = default.requests.first { it.forcedToolName == CyclePlanDirector.TOOL_NAME }
        assertEquals(CyclePlanDirector.MAX_TOKENS, plan.maxTokens)
        assertEquals(CyclePlanDirector.TIMEOUT_MS, plan.timeoutMs)
        assertEquals(8_000, plan.maxTokens)
        assertEquals(180_000L, plan.timeoutMs)
        assertNull(trace.model!!.fallback)
        assertFalse(trace.summary!!.contains("fallback", ignoreCase = true))
        assertEquals("done", trace.outcome)
    }

    // ── Override set ─────────────────────────────────────────────────────────

    @Test
    fun `with a plan endpoint only the plan call goes there, with its own timeout and token budget, and the same request otherwise`() {
        val default = endpoint(defaultUrl, "qwen35")
        val planEp = endpoint(planUrl, "qwen38")
        val trace = run(default, PlanRoute(planEp, planUrl, timeoutMs = 600_000, maxTokens = 12_000))

        assertEquals(listOf(CyclePlanDirector.TOOL_NAME), planEp.requests.map { it.forcedToolName }, "the plan endpoint is asked for the plan and nothing else")
        assertEquals(setOf(DirectorTaskExecutor.TOOL_NAME, CycleQcDirector.TOOL_NAME), default.requests.map { it.forcedToolName }.toSet(), "tasks and QC stay on the default endpoint")

        val onPlan = planEp.requests.single()
        assertEquals(600_000L, onPlan.timeoutMs)
        assertEquals(12_000, onPlan.maxTokens)
        // Same request shape as the default plan call: only the two budgets differ.
        val baseline = endpoint(defaultUrl, "qwen35").also { run(it, null) }.requests.first { it.forcedToolName == CyclePlanDirector.TOOL_NAME }
        assertEquals(baseline.copy(maxTokens = 12_000, timeoutMs = 600_000), onPlan)
        assertTrue(onPlan.reasoning)
        assertEquals(listOf(CyclePlanDirector.TOOL_NAME), onPlan.tools.map { it.name })

        assertNull(trace.model!!.fallback)
        assertEquals("done", trace.outcome)
    }

    @Test
    fun `the default budgets for a plan endpoint are 600 seconds and 12000 tokens`() {
        val route = PlanRoute(endpoint(planUrl, "m"), planUrl)
        assertEquals(600_000L, route.timeoutMs)
        assertEquals(12_000, route.maxTokens)
    }

    // ── Fallback is visible ──────────────────────────────────────────────────

    @Test
    fun `a plan endpoint that times out falls back to the default endpoint, and the trace and the Summary say so`() {
        val default = endpoint(defaultUrl, "qwen35")
        val planEp = endpoint(planUrl, "qwen38", failure = { LlmTimeoutError("timed out after 600000 ms") })
        val trace = run(default, PlanRoute(planEp, planUrl))

        val plan = default.requests.first { it.forcedToolName == CyclePlanDirector.TOOL_NAME }
        assertEquals(8_000, plan.maxTokens, "the fallback call uses the default endpoint's own budgets")
        assertEquals(180_000L, plan.timeoutMs)

        val fallback = trace.model!!.fallback!!
        assertEquals(planUrl, fallback.endpoint)
        assertEquals("LlmTimeoutError", fallback.reason)
        assertEquals(defaultUrl, trace.model!!.answer!!.endpoint, "the answer is recorded as served by the default endpoint")
        assertTrue(trace.notes.any { it.startsWith("Plan served by fallback endpoint") }, trace.notes.toString())
        assertTrue(trace.summary!!.contains("Plan served by fallback endpoint"), trace.summary)
        assertTrue(trace.summary!!.contains(planUrl))
        assertEquals("done", trace.outcome, "the cycle still completes")
    }

    @Test
    fun `an unreachable plan endpoint falls back the same way`() {
        val default = endpoint(defaultUrl, "qwen35")
        val planEp = endpoint(planUrl, "qwen38", failure = { ConnectException("refused") })
        val trace = run(default, PlanRoute(planEp, planUrl))

        assertEquals("ConnectException", trace.model!!.fallback!!.reason)
        assertTrue(trace.summary!!.contains("Plan served by fallback endpoint"))
        // The reason is a class name only: an exception message could echo a prompt.
        assertFalse(trace.summary!!.contains("refused"))
    }

    @Test
    fun `when the fallback fails too, the cycle fails and the Summary still says the plan endpoint was tried`() {
        val default = endpoint(defaultUrl, "qwen35", failure = { IOException("down") })
        val planEp = endpoint(planUrl, "qwen38", failure = { LlmTimeoutError("t") })
        val trace = run(default, PlanRoute(planEp, planUrl))

        assertEquals("failed", trace.outcome)
        assertEquals("llm_call_failed: IOException", trace.model!!.rejectedReason)
        assertNotNull(trace.model!!.fallback)
        assertTrue(trace.summary!!.contains("Plan served by fallback endpoint"), trace.summary)
    }

    // ── What each call's trace records ───────────────────────────────────────

    @Test
    fun `each call's trace records the base URL and model that served it`() {
        val default = endpoint(defaultUrl, "qwen35")
        val planEp = endpoint(planUrl, "qwen38")
        val trace = run(default, PlanRoute(planEp, planUrl))

        val plan = trace.model!!
        assertEquals(planUrl, plan.answer!!.endpoint)
        assertEquals("qwen38", plan.answer!!.model)
        assertEquals("qwen38", plan.model)
        assertTrue(plan.latencyMs >= 0)

        val task = trace.execution.single()
        assertEquals(defaultUrl, task.answer!!.endpoint)
        assertEquals("qwen35", task.answer!!.model)
        assertNotNull(task.latencyMs)

        val qc = trace.qc!!
        assertEquals(defaultUrl, qc.answer!!.endpoint)
        assertEquals("qwen35", qc.answer!!.model)
        assertEquals("qwen35", qc.model)
        assertTrue(qc.latencyMs >= 0)
    }

    @Test
    fun `the local client reports the base URL it called`() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"model":"qwen38","choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"completion_tokens":2}}""",
                ),
            )
            val base = server.url("/v1").toString().trimEnd('/')
            val response = runBlocking { LocalLlmClient(baseUrl = base, model = "local").complete(LlmRequest(prompt = "x")) }
            assertEquals(base, response.endpoint)
            assertEquals("qwen38", response.modelName)
        } finally {
            server.shutdown()
        }
    }

    // ── Configuration ────────────────────────────────────────────────────────

    private fun route(vararg env: Pair<String, String>): PlanRoute? =
        NotionCycleRuntime.planRouteFor(mapOf(*env)) { endpoint(it, "m") }

    @Test
    fun `PLAN_LLM_BASE_URL unset or blank means no plan endpoint, whatever the other two say`() {
        assertNull(route())
        assertNull(route(NotionCycleRuntime.ENV_PLAN_BASE_URL to "  "))
        assertNull(route(NotionCycleRuntime.ENV_PLAN_TIMEOUT_SECONDS to "10", NotionCycleRuntime.ENV_PLAN_MAX_TOKENS to "5"))
    }

    @Test
    fun `PLAN_LLM_BASE_URL set gives the defaults, or the timeout and token budget given`() {
        val defaults = route(NotionCycleRuntime.ENV_PLAN_BASE_URL to "http://127.0.0.1:8083/v1/")!!
        assertEquals("http://127.0.0.1:8083/v1", defaults.baseUrl)
        assertEquals(600_000L, defaults.timeoutMs)
        assertEquals(12_000, defaults.maxTokens)

        val set = route(NotionCycleRuntime.ENV_PLAN_BASE_URL to planUrl, NotionCycleRuntime.ENV_PLAN_TIMEOUT_SECONDS to "900", NotionCycleRuntime.ENV_PLAN_MAX_TOKENS to "16000")!!
        assertEquals(900_000L, set.timeoutMs)
        assertEquals(16_000, set.maxTokens)
    }

    @Test
    fun `a malformed plan endpoint setting is a configuration error, not a silent default`() {
        val url = NotionCycleRuntime.ENV_PLAN_BASE_URL to planUrl
        assertThrows<NotionConfigException> { route(NotionCycleRuntime.ENV_PLAN_BASE_URL to "127.0.0.1:8083") }
        assertThrows<NotionConfigException> { route(NotionCycleRuntime.ENV_PLAN_BASE_URL to "ftp://host/v1") }
        assertThrows<NotionConfigException> { route(url, NotionCycleRuntime.ENV_PLAN_TIMEOUT_SECONDS to "5") }
        assertThrows<NotionConfigException> { route(url, NotionCycleRuntime.ENV_PLAN_TIMEOUT_SECONDS to "soon") }
        assertThrows<NotionConfigException> { route(url, NotionCycleRuntime.ENV_PLAN_MAX_TOKENS to "50") }
        assertThrows<NotionConfigException> { route(url, NotionCycleRuntime.ENV_PLAN_MAX_TOKENS to "lots") }
    }
}
