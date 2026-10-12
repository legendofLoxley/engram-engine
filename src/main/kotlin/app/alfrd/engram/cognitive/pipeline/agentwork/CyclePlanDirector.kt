package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/** The resolved (or open) escalations of a goal, as the Director reads them before it plans. */
data class EscalationNote(val kind: String?, val status: String?, val ask: String, val resolution: String)

/** Everything one planning decision is given. */
data class PlanningInput(
    val goal: Goal,
    val cycle: Cycle,
    val taskBudget: Int,
    val escalations: List<EscalationNote>,
    val previousCycleLabel: String?,
    val previousSummary: String?,
    val context: LinkedContext,
)

/** What the model proposed. [tasks] is untrusted: [CyclePlanValidator] decides what survives. */
data class PlanProposal(
    val tasks: List<ProposedTask>,
    val rationale: String?,
    val modelCalled: Boolean,
    val latencyMs: Long,
    /** Null when the model returned a usable proposal. Otherwise a short code, never the model's text. */
    val rejectedReason: String? = null,
    val providerName: String? = null,
    val modelName: String? = null,
)

/**
 * The Director's Orchestrate decision for a Notion-queued cycle: **one structured model call that
 * proposes up to Task Budget tasks**, and nothing else. Modeled on [app.alfrd.engram.cognitive.pipeline.hermes.HermesDocumentIntentDirector]
 * and [app.alfrd.engram.cognitive.pipeline.Interpreter]: a forced tool call against the Director's own
 * configured model, then independent code-side validation ([CyclePlanValidator]) before anything is
 * written. The model's output is never a permission.
 *
 * It cannot widen its own reach: the prompt lists the only links it may use and the only task types it
 * may propose, and the validator enforces both whatever the prompt said.
 */
open class CyclePlanDirector(private val llmClient: LlmClient?) {

    private val logger = LoggerFactory.getLogger(CyclePlanDirector::class.java)

    open suspend fun propose(input: PlanningInput): PlanProposal {
        val client = llmClient ?: return PlanProposal(emptyList(), null, modelCalled = false, latencyMs = 0, rejectedReason = "no_llm_client")

        val startMs = System.currentTimeMillis()
        val response = try {
            client.complete(
                LlmRequest(
                    prompt = buildPrompt(input),
                    systemPrompt = SYSTEM_PROMPT,
                    model = LlmModel.CLAUDE_SONNET_4_5, // label only: LocalLlmClient ignores it and uses its one configured local model
                    maxTokens = 3_000,
                    timeoutMs = TIMEOUT_MS,
                    tools = listOf(PROPOSE_PLAN_TOOL),
                    forcedToolName = TOOL_NAME,
                    temperature = 0.2,
                ),
            )
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startMs
            // Class name only: an exception message from a provider could echo a prompt.
            logger.warn("cycle-plan: LLM call failed: {}", e::class.java.simpleName)
            return PlanProposal(emptyList(), null, modelCalled = true, latencyMs = latencyMs, rejectedReason = "llm_call_failed: ${e::class.java.simpleName}")
        }
        val latencyMs = System.currentTimeMillis() - startMs

        val calls = response.toolCalls.filter { it.name == TOOL_NAME }
        if (calls.size > 1) logger.warn("cycle-plan: response carried {} '{}' calls, honoring only the first", calls.size, TOOL_NAME)
        val call = calls.firstOrNull()
            ?: return PlanProposal(emptyList(), null, true, latencyMs, "no_tool_call", response.providerName, response.modelName)

        val tasks = (call.input["tasks"] as? JsonArray)
            ?: return PlanProposal(emptyList(), null, true, latencyMs, "malformed_tool_input", response.providerName, response.modelName)

        return PlanProposal(
            tasks = tasks.mapNotNull { (it as? JsonObject)?.let(::parseTask) },
            rationale = (call.input["rationale"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            modelCalled = true,
            latencyMs = latencyMs,
            providerName = response.providerName,
            modelName = response.modelName,
        )
    }

    private fun parseTask(obj: JsonObject): ProposedTask {
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val links = when (val raw = obj["target_links"]) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            is JsonPrimitive -> listOfNotNull(raw.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() })
            else -> emptyList()
        }
        return ProposedTask(title = str("title"), type = str("type"), instructions = str("instructions"), targetLinks = links)
    }

    internal fun buildPrompt(input: PlanningInput): String = PlanPromptBuilder.build(input)

    companion object {
        const val TOOL_NAME = "propose_cycle_plan"
        const val TIMEOUT_MS = 180_000L

        private val SYSTEM_PROMPT: String = """
            You are the Director's planner for alfrd. Jacob has queued one cycle against one goal. Your only job is to propose a dry-run plan: an ordered list of tasks that a later step could carry out toward the goal. Nothing is executed or written now.

            Call propose_cycle_plan exactly once.

            Rules you must follow. Code checks every one of them afterwards and discards any task that breaks one:
            - Propose at most the task budget given. Fewer, sharper tasks beat many vague ones. Skip work the target already covers.
            - Each task has a short title, a type, and instructions that say what the task would do and what it would look at.
            - Types you may use: ${TaskType.PROPOSABLE.joinToString(", ") { it.label }}. Do not use Orchestrate, Gate or QC; the Director does those itself.
            - target_links may contain only links copied exactly from the "Links you may use" list. Never invent, shorten or guess a link. A task may have none.
            - Never propose a task that locks, unlocks, finalizes or changes an answer standard. Only people lock answer standards.
            - Never propose a task that deletes, removes, overwrites or replaces an existing row. A correction is a new row that supersedes the old one, and that is a later step's business.
            - Stay inside the goal's Scope. If you notice something outside it, you may propose an Escalate task to note it for a later goal, but do not plan work on it.
            - Read what Jacob resolved on earlier escalations and what the last cycle summarized, and plan from them.
        """.trimIndent()

        private val PROPOSE_PLAN_TOOL = ToolDefinition(
            name = TOOL_NAME,
            description = "Propose the dry-run plan for this cycle: an ordered list of tasks, at most the task budget.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("tasks") {
                        put("type", "array")
                        put("description", "The tasks, in the order they would run.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("title") { put("type", "string"); put("description", "A short imperative title.") }
                                putJsonObject("type") {
                                    put("type", "string")
                                    putJsonArray("enum") { TaskType.PROPOSABLE.forEach { add(it.label) } }
                                    put("description", "What kind of task this is.")
                                }
                                putJsonObject("instructions") { put("type", "string"); put("description", "What the task would do and what it would look at.") }
                                putJsonObject("target_links") {
                                    put("type", "array")
                                    putJsonObject("items") { put("type", "string") }
                                    put("description", "Links from the allowed list that this task reads. May be empty.")
                                }
                            }
                            putJsonArray("required") { add("title"); add("type"); add("instructions") }
                        }
                    }
                    putJsonObject("rationale") { put("type", "string"); put("description", "One or two sentences on why this plan.") }
                }
                putJsonArray("required") { add("tasks") }
            },
        )
    }
}

/** Builds the user-side prompt: the goal, what Jacob has said since, and the only links a task may use. Pure, so it can be read in a test. */
object PlanPromptBuilder {
    const val MAX_CHARS = 28_000

    fun build(input: PlanningInput): String {
        val goal = input.goal
        return buildString {
            appendLine("GOAL: ${goal.title}${goal.displayId?.let { " ($it)" }.orEmpty()}")
            appendLine("Type: ${goal.type?.label ?: "unknown"}")
            appendLine("Deadline: ${goal.deadline ?: "none"}")
            appendLine()
            section("Intent", goal.intent)
            section("Scope", goal.scope, note = "what this goal may touch; stay inside it")
            section("Metric", goal.metric)
            section("Done criteria", goal.doneCriteria)
            appendLine("TASK BUDGET: ${input.taskBudget} (propose at most this many)")
            appendLine()

            val resolved = input.escalations.filter { it.resolution.isNotBlank() }
            if (resolved.isNotEmpty()) {
                appendLine("WHAT JACOB RESOLVED ON EARLIER ESCALATIONS (read before planning):")
                resolved.forEach { appendLine("- ${it.kind ?: "Escalation"} [${it.status ?: "?"}]: ${it.ask.oneLine(300)} => ${it.resolution.oneLine(600)}") }
                appendLine()
            }
            if (!input.previousSummary.isNullOrBlank()) {
                appendLine("LAST CYCLE${input.previousCycleLabel?.let { " ($it)" }.orEmpty()} SUMMARY:")
                appendLine(input.previousSummary.oneLine(900))
                appendLine()
            }

            val context = input.context
            appendLine("LINKS YOU MAY USE (target_links must be copied exactly from this list):")
            val budgetForRows = MAX_CHARS - length - 1_500
            var used = 0
            var shown = 0
            for (row in context.rows) {
                val line = buildString {
                    append("- ${row.pageUrl}  [${row.table}] ${row.title.oneLine(200)}")
                    if (row.facts.isNotEmpty()) append("  | ${row.facts.joinToString("; ")}")
                    row.externalUrl?.let { append("  | external: $it") }
                }
                if (used + line.length > budgetForRows) break
                appendLine(line)
                used += line.length + 1
                shown++
            }
            if (shown < context.rows.size) appendLine("(${context.rows.size - shown} more rows not shown: out of room)")
            if (context.rows.isEmpty()) appendLine("(none: this goal links to no readable page, so tasks have no links)")
            appendLine()

            val skipped = context.skipped
            if (skipped.isNotEmpty()) {
                appendLine("LINKS ON THE GOAL THAT COULD NOT BE USED (do not use them):")
                skipped.forEach { appendLine("- ${it.link}: ${it.reason.name.lowercase().replace('_', ' ')}") }
                appendLine()
            }
            if (goal.targetLinksText.isNotBlank()) {
                appendLine("TARGET LINKS AS WRITTEN ON THE GOAL: ${goal.targetLinksText.oneLine(600)}")
                appendLine()
            }
            append("Propose the dry-run plan now.")
        }.take(MAX_CHARS)
    }

    private fun StringBuilder.section(label: String, text: String, note: String? = null) {
        if (text.isBlank()) return
        appendLine("${label.uppercase()}${note?.let { " ($it)" }.orEmpty()}: ${text.oneLine(1_500)}")
        appendLine()
    }

    private fun String.oneLine(max: Int): String {
        val line = replace(Regex("\\s+"), " ").trim()
        return if (line.length <= max) line else line.take(max - 1) + "…"
    }
}
