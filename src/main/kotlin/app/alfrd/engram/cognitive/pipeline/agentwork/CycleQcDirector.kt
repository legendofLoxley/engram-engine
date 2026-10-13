package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/** One task as QC sees it: what it was, how it ended, and the start of what it found. */
data class QcTaskLine(val order: Int, val title: String, val type: String, val status: TaskStatus, val excerpt: String)

data class QcInput(
    val goal: Goal,
    val cycle: Cycle,
    /** The titles of what Scope mentions, with a word on what became of each (read, listed, or skipped and why). */
    val scopeLines: List<String>,
    val tasks: List<QcTaskLine>,
    /** Tasks the model proposed that code rejected before anything was written. */
    val rejectedAtPlanning: Int,
    /** The goal's in-scope rows and pages, staged within bounds. QC judges Done when against these, not only against the task results. */
    val sources: StagedContext = StagedContext.EMPTY,
)

/** QC's call on the cycle. [goalLooksMet] is one of [LOOKS_MET_VALUES]. The goal's Status is Jacob's to change and QC never does. */
data class QcDecision(
    val summary: String,
    val goalLooksMet: String,
    val goalFeedback: String,
    /** `Goal`, `Done when` or `Scope`: the only fields QC may suggest rewriting. */
    val suggestedField: String?,
    val suggestedText: String?,
)

/** What came back. [decision] is null when the model gave nothing usable, and [rejectedReason] then says why, as a code. */
data class QcResult(
    val decision: QcDecision?,
    val modelCalled: Boolean,
    val latencyMs: Long,
    val rejectedReason: String? = null,
    val providerName: String? = null,
    val modelName: String? = null,
    val answer: AnswerTrace? = null,
    val given: GivenTrace? = null,
)

val LOOKS_MET_VALUES: List<String> = listOf("yes", "partly", "no", "unclear")

/**
 * The Director's QC decision for a cycle that has run its tasks: **one structured model call** that writes
 * two things, and code that validates both before anything is written.
 *
 * 1. What was done, measured against *Done when*, and whether the goal looks met.
 * 2. **Goal feedback**: what the goal left unclear, what alfrd had to guess, and (only if it would have
 *    helped) one suggested rewrite of Goal, Done when or Scope.
 *
 * Modeled on [CyclePlanDirector]: a forced tool call, then validation. The model cannot change the goal's
 * Status (goals are read-only at the guard) and is told not to claim to.
 */
open class CycleQcDirector(private val llmClient: LlmClient?) {

    private val logger = LoggerFactory.getLogger(CycleQcDirector::class.java)

    open suspend fun decide(input: QcInput): QcResult {
        val client = llmClient ?: return QcResult(null, modelCalled = false, latencyMs = 0, rejectedReason = "no_llm_client")
        val startMs = System.currentTimeMillis()
        val prompt = QcPromptBuilder.build(input)
        val given = GivenTrace.of(CyclePrompts.QC, prompt.length, input.sources, QcPromptBuilder.shownTasks(input).map { it.order })
        val response = try {
            client.complete(
                LlmRequest(
                    prompt = prompt,
                    systemPrompt = CyclePrompts.QC.text,
                    model = LlmModel.CLAUDE_SONNET_4_5, // label only: the local client ignores it
                    maxTokens = MAX_TOKENS,
                    timeoutMs = TIMEOUT_MS,
                    tools = listOf(QC_TOOL),
                    forcedToolName = TOOL_NAME,
                    temperature = 0.2,
                    reasoning = true,
                ),
            )
        } catch (e: CancellationException) {
            // Shutdown or a cancelled poll is not a model failure: let it propagate so the cycle is left Running for a later reclaim.
            throw e
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startMs
            logger.warn("cycle-qc: LLM call failed: {}", e::class.java.simpleName)
            return QcResult(null, modelCalled = true, latencyMs = latency, rejectedReason = "llm_call_failed: ${e::class.java.simpleName}", given = given)
        }
        val latency = System.currentTimeMillis() - startMs
        val answer = AnswerTrace.of(response)
        fun rejected(code: String) = QcResult(null, true, latency, code, response.providerName, response.modelName, answer, given)

        val call = response.toolCalls.firstOrNull { it.name == TOOL_NAME } ?: return rejected("no_tool_call")
        val decision = parse(call.input) ?: return rejected("malformed_tool_input")
        return QcResult(decision, true, latency, null, response.providerName, response.modelName, answer, given)
    }

    private fun parse(input: JsonObject): QcDecision? {
        fun str(key: String) = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val summary = str("summary")
        val feedback = str("goal_feedback")
        if (summary.isEmpty() || feedback.isEmpty()) return null
        val looksMet = str("goal_looks_met").lowercase().takeIf { it in LOOKS_MET_VALUES } ?: "unclear"
        val rewrite = input["suggested_rewrite"] as? JsonObject
        val field = (rewrite?.get("field") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it in REWRITABLE_FIELDS }
        val text = (rewrite?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        return QcDecision(
            summary = summary.take(MAX_TEXT_CHARS),
            goalLooksMet = looksMet,
            goalFeedback = feedback.take(MAX_TEXT_CHARS),
            suggestedField = if (field != null && text != null) field else null,
            suggestedText = if (field != null && text != null) text.take(MAX_REWRITE_CHARS) else null,
        )
    }

    companion object {
        const val TOOL_NAME = "record_cycle_qc"
        const val TIMEOUT_MS = 180_000L
        const val MAX_TOKENS = 8_000 // see CyclePlanDirector.MAX_TOKENS
        const val MAX_TEXT_CHARS = 1_500
        const val MAX_REWRITE_CHARS = 600
        val REWRITABLE_FIELDS: Set<String> = setOf("Goal", "Done when", "Scope")

        private val QC_TOOL = ToolDefinition(
            name = TOOL_NAME,
            description = "Write the cycle's close-out: a summary against Done when, and feedback on the goal.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("summary") { put("type", "string"); put("description", "What was done, against Done when, and whether the goal looks met.") }
                    putJsonObject("goal_looks_met") {
                        put("type", "string")
                        putJsonArray("enum") { LOOKS_MET_VALUES.forEach { add(it) } }
                    }
                    putJsonObject("goal_feedback") { put("type", "string"); put("description", "What the goal left unclear and what alfrd had to guess.") }
                    putJsonObject("suggested_rewrite") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("field") { put("type", "string"); putJsonArray("enum") { REWRITABLE_FIELDS.forEach { add(it) } } }
                            putJsonObject("text") { put("type", "string") }
                        }
                    }
                }
                putJsonArray("required") { add("summary"); add("goal_looks_met"); add("goal_feedback") }
            },
        )
    }
}

/**
 * Builds the QC per-call prompt: **what to judge**. How to judge is in [CyclePrompts.QC]. Order: the goal and
 * Done when, the Scope, the SOURCE MATERIAL (the evidence), what was NOT GIVEN, then the TASK RESULTS, labelled
 * as earlier work to be confirmed against the sources. Pure, so a test can read exactly what the model is given.
 */
object QcPromptBuilder {
    const val MAX_CHARS = 60_000

    /** Task results listed: all of them up to this many, so a long cycle says how many it left off instead of overflowing. */
    const val MAX_TASKS = 25
    private const val RESULT_EXCERPT = 900
    private const val DONE_WHEN_MAX = 6_000

    /** The tasks that [build] lists. */
    fun shownTasks(input: QcInput): List<QcTaskLine> = input.tasks.take(MAX_TASKS)

    fun build(input: QcInput): String {
        val goal = input.goal
        return buildString {
            appendLine("GOAL: ${goal.title}${goal.displayId?.let { " ($it)" }.orEmpty()}")
            appendLine("DONE WHEN: ${goal.doneWhen.ifBlank { "(Jacob wrote no Done when)" }.let { PromptText.bounded(it, DONE_WHEN_MAX, "Done when") }}")
            appendLine()
            appendLine("SCOPE (what the goal's @-mentions put in reach):")
            if (input.scopeLines.isEmpty()) appendLine("- (nothing)") else input.scopeLines.forEach { appendLine("- ${PromptText.excerpt(it, 240)}") }
            if (goal.scope.proseChars > 0) {
                appendLine("Scope ALSO held ${goal.scope.proseChars} letters and digits of prose outside the mentions. Code ignored it: ${PromptText.excerpt(goal.scope.proseExcerpt, 200)}")
            }
            if (goal.scope.ignoredLinks.isNotEmpty()) appendLine("Scope also held ${goal.scope.ignoredLinks.size} link(s) that are not mentions. Code ignored them.")
            appendLine()

            with(TaskPromptBuilder) { appendSources(input.sources, forTask = false) }
            appendLine()

            val counts = input.tasks.groupingBy { it.status }.eachCount()
            appendLine(
                "TASK RESULTS (earlier work: what the tasks of this cycle reported. It may be wrong, incomplete or invented; confirm it against the SOURCE MATERIAL before relying on it).",
            )
            appendLine(
                "${input.tasks.size} planned" +
                    "; done ${counts[TaskStatus.DONE] ?: 0}, blocked ${counts[TaskStatus.BLOCKED] ?: 0}, failed ${counts[TaskStatus.FAILED] ?: 0}" +
                    if (input.rejectedAtPlanning > 0) "; ${input.rejectedAtPlanning} more proposed task(s) were rejected by code before they were written" else "",
            )
            shownTasks(input).forEach { task ->
                appendLine()
                appendLine("Task ${task.order} [${task.status.label}] (${task.type}) ${PromptText.excerpt(task.title, 160)}")
                appendLine("  result: ${PromptText.excerpt(task.excerpt.ifBlank { "(none)" }, RESULT_EXCERPT)}")
            }
            if (input.tasks.size > MAX_TASKS) appendLine("\n(${input.tasks.size - MAX_TASKS} more task result(s) not listed)")
            appendLine()
            append("Write the close-out now.")
        }.take(MAX_CHARS)
    }
}

/** Puts a [QcDecision] into the two texts the Cycle shows. The counts are the Director's own; the words are the model's, validated. */
object QcComposer {
    fun summary(done: Int, blocked: Int, failed: Int, total: Int, decision: QcDecision): String = buildString {
        append("Executed $total task${if (total == 1) "" else "s"}: $done done, $blocked blocked, $failed failed.")
        append(" ").append(decision.summary)
        append(" Goal looks met: ").append(decision.goalLooksMet).append(" (the goal's Status is yours to change; it was not touched).")
    }

    fun feedback(decision: QcDecision): String = buildString {
        append(decision.goalFeedback)
        if (decision.suggestedField != null && decision.suggestedText != null) {
            append("\n\nSuggested rewrite of ").append(decision.suggestedField).append(": ").append(decision.suggestedText)
        }
    }
}
