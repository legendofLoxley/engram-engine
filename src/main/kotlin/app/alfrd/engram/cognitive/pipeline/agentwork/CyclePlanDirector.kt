package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.ToolDefinition
import kotlinx.coroutines.CancellationException
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
    val context: ScopeContext,
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
    val answer: AnswerTrace? = null,
    val given: GivenTrace? = null,
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
        val built = PlanPromptBuilder.buildReport(input)
        val given = built.given()
        val response = try {
            client.complete(
                LlmRequest(
                    prompt = built.text,
                    systemPrompt = CyclePrompts.PLAN.text,
                    model = LlmModel.CLAUDE_SONNET_4_5, // label only: LocalLlmClient ignores it and uses its one configured local model
                    maxTokens = MAX_TOKENS,
                    timeoutMs = TIMEOUT_MS,
                    tools = listOf(PROPOSE_PLAN_TOOL),
                    forcedToolName = TOOL_NAME,
                    temperature = 0.2,
                    reasoning = true,
                ),
            )
        } catch (e: CancellationException) {
            // Shutdown or a cancelled poll is not a model failure: let it propagate so the cycle is left Running for a later reclaim.
            throw e
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startMs
            // Class name only: an exception message from a provider could echo a prompt.
            logger.warn("cycle-plan: LLM call failed: {}", e::class.java.simpleName)
            return PlanProposal(emptyList(), null, modelCalled = true, latencyMs = latencyMs, rejectedReason = "llm_call_failed: ${e::class.java.simpleName}", given = given)
        }
        val latencyMs = System.currentTimeMillis() - startMs
        val answer = AnswerTrace.of(response)

        val calls = response.toolCalls.filter { it.name == TOOL_NAME }
        if (calls.size > 1) logger.warn("cycle-plan: response carried {} '{}' calls, honoring only the first", calls.size, TOOL_NAME)
        val call = calls.firstOrNull()
            ?: return PlanProposal(emptyList(), null, true, latencyMs, "no_tool_call", response.providerName, response.modelName, answer, given)

        val tasks = (call.input["tasks"] as? JsonArray)
            ?: return PlanProposal(emptyList(), null, true, latencyMs, "malformed_tool_input", response.providerName, response.modelName, answer, given)

        return PlanProposal(
            tasks = tasks.mapNotNull { (it as? JsonObject)?.let(::parseTask) },
            rationale = (call.input["rationale"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            modelCalled = true,
            latencyMs = latencyMs,
            providerName = response.providerName,
            modelName = response.modelName,
            answer = answer,
            given = given,
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

        /** Reasoning counts against this: the local model spent 1.9k to 4.4k tokens thinking about a 2k-token prompt before the call. */
        const val MAX_TOKENS = 8_000

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
                                    put("description", "Handles (like R1, P1 or D1) from the allowed list that this task reads, copied exactly. May be empty.")
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

/** The planner's prompt and what went into it, by row id. */
class PlanPrompt(val text: String, val shownRowIds: List<String>, val omittedRowIds: List<String>, private val outcomes: Int, private val notUsable: Int) {
    fun given() = GivenTrace(
        CyclePrompts.PLAN.ref, text.length, outcomes, shownRowIds.size, shownRowIds.take(GivenTrace.MAX_IDS), emptyList(),
        emptyList(), omittedRowIds.take(GivenTrace.MAX_IDS), omittedRowIds.size, notUsable + if (omittedRowIds.isEmpty()) 0 else 1,
    )
}

/**
 * Builds the user-side prompt: the goal, what Jacob has said since, and the only links a task may use. Pure, so
 * it can be read in a test. The planner sees each row as a short digest (long text is cut, with a marker that
 * says so); the workers and QC are given the rows in full.
 */
object PlanPromptBuilder {
    const val MAX_CHARS = 28_000

    fun build(input: PlanningInput): String = buildReport(input).text

    fun buildReport(input: PlanningInput): PlanPrompt {
        val goal = input.goal
        val shownIds = ArrayList<String>()
        val text = buildString {
            appendLine("GOAL: ${goal.title}${goal.displayId?.let { " ($it)" }.orEmpty()}")
            appendLine("Deadline: ${goal.deadline ?: "none"}")
            appendLine()
            if (goal.doneWhen.isNotBlank()) {
                appendLine("DONE WHEN (a check anyone could apply; plan so it can be checked from your tasks' results): ${PromptText.bounded(goal.doneWhen, 6_000, "Done when")}")
                appendLine()
            }
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
            appendLine("SCOPE: the goal's @-mentions put these in reach (write each handle, like R1, P1 or D1, exactly as listed to link it):")
            context.outcomes.filter { it !is ScopeOutcome.Skipped }.forEach { appendLine("- " + describe(it, context.handles)) }
            appendLine()
            appendLine("LINKS YOU MAY USE (pages and rows in scope; write the handle in target_links):")
            val budgetForRows = MAX_CHARS - length - 1_500
            var used = 0
            var shown = 0
            for (row in context.rows) {
                val line = buildString {
                    append("- ${context.handles.handleFor(row.pageId) ?: row.pageUrl}  [${row.table}] ${row.title.oneLine(200)}")
                    if (row.facts.isNotEmpty()) append("  | ${row.facts.joinToString("; ")}")
                    if (row.factsOmitted > 0) append("; (+${row.factsOmitted} more properties not listed here)")
                }
                if (used + line.length > budgetForRows) break
                appendLine(line)
                used += line.length + 1
                shown++
                shownIds += row.pageId
            }
            if (shown < context.rows.size) appendLine("(${context.rows.size - shown} more rows not shown: out of room)")
            if (context.rows.isEmpty() && context.outcomes.none { it is ScopeOutcome.TableListed }) appendLine("(none: nothing in this goal's scope could be read, so tasks have no links)")
            appendLine()

            val skipped = context.skipped
            if (skipped.isNotEmpty()) {
                appendLine("MENTIONS ON THE GOAL THAT COULD NOT BE USED (do not use them):")
                skipped.forEach { appendLine("- ${it.mention.title.ifBlank { it.mention.id }}: ${it.reason.name.lowercase().replace('_', ' ')}") }
                appendLine()
            }
            append("Propose the plan now.")
        }.take(MAX_CHARS)
        val omitted = input.context.rows.map { it.pageId }.filter { it !in shownIds }
        return PlanPrompt(text, shownIds, omitted, input.context.outcomes.count { it !is ScopeOutcome.Skipped }, input.context.skipped.size)
    }

    private fun describe(outcome: ScopeOutcome, handles: Handles): String = when (outcome) {
        is ScopeOutcome.TableListed -> "${handles.handleFor(outcome.mention.id) ?: notionPageUrl(outcome.mention.id)}  [database ${outcome.mention.title.ifBlank { outcome.table }}, table ${outcome.table}: ${outcome.rows} row(s) listed below${if (outcome.truncated) ", more exist" else ""}]"
        is ScopeOutcome.PageRead -> "${handles.handleFor(outcome.pageId) ?: notionPageUrl(outcome.pageId)}  page ${outcome.mention.title.ifBlank { outcome.label }} (${outcome.label})"
        is ScopeOutcome.Skipped -> ""
    }

    private fun String.oneLine(max: Int): String = PromptText.excerpt(this, max)
}
