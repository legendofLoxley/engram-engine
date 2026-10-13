package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A model stand-in for a whole cycle: one handler per forced tool (plan, task, QC), recording every
 * request so a test can read exactly what each stage was given. A stage with no handler fails the test
 * loudly when it is called, which is how a test proves a stage was *not* reached.
 */
class RoutedLlm(
    private val plan: ((LlmRequest) -> JsonObject)? = null,
    private val task: ((LlmRequest) -> JsonObject)? = null,
    private val qc: ((LlmRequest) -> JsonObject)? = null,
) : LlmClient {
    val requests = ArrayList<LlmRequest>()

    fun requestsFor(tool: String): List<LlmRequest> = requests.filter { it.forcedToolName == tool }
    val planRequests: List<LlmRequest> get() = requestsFor(CyclePlanDirector.TOOL_NAME)
    val taskRequests: List<LlmRequest> get() = requestsFor(DirectorTaskExecutor.TOOL_NAME)
    val qcRequests: List<LlmRequest> get() = requestsFor(CycleQcDirector.TOOL_NAME)

    /** Everything any stage was sent: the prompts and the system prompts. */
    val everythingSent: String get() = requests.joinToString("\n") { it.prompt + "\n" + (it.systemPrompt ?: "") }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        val handler = when (request.forcedToolName) {
            CyclePlanDirector.TOOL_NAME -> plan
            DirectorTaskExecutor.TOOL_NAME -> task
            CycleQcDirector.TOOL_NAME -> qc
            else -> null
        } ?: throw AssertionError("the model was asked for '${request.forcedToolName}', which this test did not expect")
        val input = handler(request)
        return LlmResponse(text = "", toolCalls = listOf(ToolCall(request.forcedToolName!!, input)), latencyMs = 5, retryCount = 0, providerName = "local", modelName = "qwen-test")
    }

    companion object {
        private val STAGED_URL = Regex("""<(https://www\.notion\.so/[0-9a-f]{32})>""")

        /** The URLs of the staged pages in a task prompt. */
        fun stagedUrls(prompt: String): List<String> = STAGED_URL.findAll(prompt).map { it.groupValues[1] }.distinct().toList()

        fun plan(vararg tasks: JsonObject, rationale: String? = "because"): (LlmRequest) -> JsonObject = { ScriptedLlm.planOf(*tasks, rationale = rationale) }

        /** A worker that does every task from what it was given and cites every staged page. */
        val doingEveryTask: (LlmRequest) -> JsonObject = { req -> taskDone("Findings: ${req.prompt.lineSequence().first { it.startsWith("TASK ") }.take(80)}", *stagedUrls(req.prompt).toTypedArray()) }

        fun taskDone(result: String, vararg pages: String): JsonObject = buildJsonObject {
            put("outcome", "done"); put("result", result)
            putJsonArray("pages_used") { pages.forEach { add(it) } }
        }

        fun taskBlocked(reason: String): JsonObject = buildJsonObject { put("outcome", "blocked"); put("blocked_reason", reason) }

        fun qcOf(
            summary: String = "The tasks produced results against Done when.",
            looksMet: String = "partly",
            feedback: String = "Done when named no number, so alfrd guessed that all of them was meant.",
            rewriteField: String? = null,
            rewriteText: String = "Done when: every item is classified.",
        ): JsonObject = buildJsonObject {
            put("summary", summary); put("goal_looks_met", looksMet); put("goal_feedback", feedback)
            if (rewriteField != null) putJsonObject("suggested_rewrite") { put("field", rewriteField); put("text", rewriteText) }
        }

        /** The common case: this plan, every task done, a QC close-out. */
        fun happy(vararg tasks: JsonObject, qc: JsonObject = qcOf()) = RoutedLlm(plan(*tasks), doingEveryTask) { qc }
    }
}
