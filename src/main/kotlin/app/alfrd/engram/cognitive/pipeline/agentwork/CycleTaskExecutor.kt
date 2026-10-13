package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.ToolDefinition
import app.alfrd.engram.notion.normalizeNotionId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/** What an earlier task of the same cycle produced, shown to the next one. */
data class EarlierResult(val order: Int, val title: String, val status: TaskStatus, val excerpt: String)

data class TaskExecutionInput(
    val goal: Goal,
    val cycle: Cycle,
    val task: AgentTask,
    val taskCount: Int,
    val staged: StagedContext,
    val earlier: List<EarlierResult>,
    /** Short names for the goal's in-scope pages and rows, shown to the model and mapped back to URLs here. */
    val handles: Handles = Handles.NONE,
)

/** What a task came to. Reasons are codes or short statements the Director wrote; a model's own words appear only in [Done.result] and [Blocked.reason]. */
sealed interface TaskExecution {
    /** [result] is the work's plain-text report; [pagesUsed] are staged page URLs the model cited, already checked against what was staged. */
    data class Done(val result: String, val pagesUsed: List<String>, val droppedCitations: Int = 0) : TaskExecution

    /** The staged material was not enough to do the task. Not a failure: the task is marked Blocked with this reason and the cycle goes on. */
    data class Blocked(val reason: String) : TaskExecution

    /** The Director could not get a usable answer (a code such as `llm_call_failed: IOException`). */
    data class Failed(val reason: String) : TaskExecution
}

data class ExecutionOutcome(
    val execution: TaskExecution,
    val modelCalled: Boolean,
    val latencyMs: Long,
    val provider: String? = null,
    val model: String? = null,
    val answer: AnswerTrace? = null,
    val given: GivenTrace? = null,
)

/**
 * How one task gets done. **This increment's implementation is [DirectorTaskExecutor]**, which asks the
 * Director's own local model; see its note on why that is a deliberate deviation from the contract's
 * "tasks reach Hermes as ordinary scoped assignments". The interface is the seam a Hermes-backed executor
 * would plug into, with the same staged input and the same three outcomes.
 */
fun interface CycleTaskExecutor {
    suspend fun execute(input: TaskExecutionInput): ExecutionOutcome
}

/**
 * Runs a task in the Director: **one forced tool call to the Director's local model** with the staged,
 * in-scope content, then code-side validation of the answer before anything is written. The model's output
 * is a proposal and never a permission.
 *
 * ### Contract deviation (flagged for review)
 * The Director–Hermes contract's October 11 amendment says tasks reach Hermes as ordinary scoped
 * assignments (bounded context, a read-only staged workspace, no service credentials, Director-owned
 * completion). This increment does not do that, because the existing Hermes path is not a small adapter
 * away: it has a closed set of two assignment kinds with hard-coded prompts, a single shared workspace
 * directory, a fire-and-forget dispatcher whose completion is polled by the WebUI's conversation, and it
 * records every result as graph evidence (out of scope here). What this keeps from the contract: the
 * model only ever sees staged, in-scope content; no credential reaches it (the Notion token never leaves
 * the HTTP client); the Director decides what is written (code validates the answer); the model is the
 * same local Qwen Hermes uses on this box. What it gives up: Hermes's tools and its isolation boundary
 * (a container with a read-only mount), and Hermes cancellation.
 */
open class DirectorTaskExecutor(private val llmClient: LlmClient?) : CycleTaskExecutor {

    private val logger = LoggerFactory.getLogger(DirectorTaskExecutor::class.java)

    override suspend fun execute(input: TaskExecutionInput): ExecutionOutcome {
        val client = llmClient ?: return ExecutionOutcome(TaskExecution.Failed("no_llm_client"), modelCalled = false, latencyMs = 0)
        val startMs = System.currentTimeMillis()
        val system = CyclePrompts.forTask(input.task.type)
        val prompt = TaskPromptBuilder.build(input)
        val given = GivenTrace.of(system, prompt.length, input.staged, TaskPromptBuilder.earlierShown(input).map { it.order })
        val response = try {
            client.complete(
                LlmRequest(
                    prompt = prompt,
                    systemPrompt = system.text,
                    model = LlmModel.CLAUDE_SONNET_4_5, // label only: the local client ignores it
                    maxTokens = MAX_TOKENS,
                    timeoutMs = TIMEOUT_MS,
                    tools = listOf(REPORT_TOOL),
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
            // Class name only: a provider's exception message could echo the prompt.
            logger.warn("cycle-task: LLM call failed: {}", e::class.java.simpleName)
            return ExecutionOutcome(TaskExecution.Failed("llm_call_failed: ${e::class.java.simpleName}"), modelCalled = true, latencyMs = latency, given = given)
        }
        val latency = System.currentTimeMillis() - startMs
        fun outcome(execution: TaskExecution) = ExecutionOutcome(execution, true, latency, response.providerName, response.modelName, AnswerTrace.of(response), given)

        val calls = response.toolCalls.filter { it.name == TOOL_NAME }
        if (calls.size > 1) logger.warn("cycle-task: response carried {} '{}' calls, honoring only the first", calls.size, TOOL_NAME)
        val call = calls.firstOrNull() ?: return outcome(TaskExecution.Failed("no_tool_call"))

        fun str(key: String) = (call.input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val kind = str("outcome").lowercase()
        return when (kind) {
            "blocked" -> {
                val reason = str("blocked_reason").ifEmpty { str("result") }.ifEmpty { "the staged material was not enough" }
                outcome(TaskExecution.Blocked(reason.take(MAX_REASON_CHARS)))
            }
            "done" -> {
                val result = str("result")
                if (result.isEmpty()) return outcome(TaskExecution.Failed("empty_result"))
                // A page, a row or a rows block: whichever was given, by its own id. A handle (R3) is mapped back to its URL first.
                val staged = (input.staged.pages.map { StagedItem(notionPageIdInPath(it.url) ?: it.url, it.url, it.title) } + input.staged.items)
                    .associateBy { notionPageIdInPath(it.url) }
                val cited = (call.input["pages_used"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
                    .filter { it.isNotEmpty() }.distinct()
                // The Result cites the staged page's own URL, never the model's spelling of it.
                val valid = cited.mapNotNull { notionPageIdInPath(input.handles.resolve(it))?.let(staged::get)?.url }.distinct()
                outcome(TaskExecution.Done(result.take(MAX_RESULT_CHARS), valid, droppedCitations = cited.size - valid.size))
            }
            else -> outcome(TaskExecution.Failed("unknown_outcome"))
        }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    companion object {
        const val TOOL_NAME = "report_task_result"
        const val TIMEOUT_MS = 180_000L
        const val MAX_TOKENS = 8_000 // see CyclePlanDirector.MAX_TOKENS
        const val MAX_RESULT_CHARS = 5_000
        const val MAX_REASON_CHARS = 600

        private val REPORT_TOOL = ToolDefinition(
            name = TOOL_NAME,
            description = "Report how this one task went: done with a result, or blocked with the reason.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("outcome") {
                        put("type", "string")
                        putJsonArray("enum") { add("done"); add("blocked") }
                        put("description", "done if you did the task from the staged material; blocked if you could not.")
                    }
                    putJsonObject("result") { put("type", "string"); put("description", "The result, in plain text. Required when done.") }
                    putJsonObject("blocked_reason") { put("type", "string"); put("description", "What was missing. Required when blocked.") }
                    putJsonObject("pages_used") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Handles (like R3 or P1) of the pages and rows you used, copied exactly from the SOURCE MATERIAL.")
                    }
                }
                putJsonArray("required") { add("outcome") }
            },
        )
    }
}

/**
 * Builds the per-call prompt for one task: **what to work on**. How to work is in the system prompt
 * ([CyclePrompts]). Pure, so a test can read exactly what the model is given.
 *
 * Order: the goal, the task, the SOURCE MATERIAL (the evidence), what was NOT GIVEN, then EARLIER WORK from
 * other tasks, labelled as notes that may be wrong. Nothing here is shortened without saying so.
 */
object TaskPromptBuilder {
    const val MAX_CHARS = 40_000

    /** Earlier results listed in a prompt: the most recent, with a note on how many were left off. */
    const val MAX_EARLIER = 12
    private const val EARLIER_EXCERPT = 700
    private const val DONE_WHEN_MAX = 6_000

    /** The earlier results that [build] lists. */
    fun earlierShown(input: TaskExecutionInput): List<EarlierResult> = input.earlier.takeLast(MAX_EARLIER)

    fun build(input: TaskExecutionInput): String {
        val goal = input.goal
        val task = input.task
        return buildString {
            appendLine("GOAL: ${goal.title}")
            if (goal.doneWhen.isNotBlank()) appendLine("DONE WHEN: ${PromptText.bounded(goal.doneWhen, DONE_WHEN_MAX, "Done when")}")
            appendLine()
            appendLine("TASK ${task.order ?: "?"} of ${input.taskCount}: ${PromptText.stripCodes(task.title)}  (${task.type?.label ?: "type unknown"})")
            appendLine("WHAT TO DO: ${PromptText.bounded(PromptText.stripCodes(task.instructions), 4_000, "the task instructions")}")
            appendLine()

            appendSources(input.staged, forTask = true, handles = input.handles)

            val shown = earlierShown(input)
            if (shown.isNotEmpty()) {
                appendLine()
                appendLine("EARLIER WORK BY OTHER TASKS OF THIS CYCLE (notes only: this is what those tasks reported, it may be wrong or incomplete, and it is not evidence; check it against the SOURCE MATERIAL):")
                if (input.earlier.size > shown.size) appendLine("(${input.earlier.size - shown.size} older task result(s) not listed)")
                shown.forEach { appendLine("- Task ${it.order} ${PromptText.stripCodes(it.title)} [${it.status.label}]: ${PromptText.excerpt(PromptText.stripCodes(PromptText.withHandles(it.excerpt, input.handles)), EARLIER_EXCERPT)}") }
            }
            appendLine()
            append("Do the task now and call report_task_result.")
        }.take(MAX_CHARS)
    }

    /** The SOURCE MATERIAL block and, after it, the list of what was not given. Shared with QC. */
    internal fun StringBuilder.appendSources(staged: StagedContext, forTask: Boolean, handles: Handles = Handles.NONE, extraNotGiven: List<String> = emptyList()) {
        if (staged.pages.isEmpty()) {
            appendLine(
                if (forTask) "SOURCE MATERIAL: none was given for this task. Work from the instructions and the earlier work only, and say so; if that is not enough, report blocked."
                else "SOURCE MATERIAL: none could be given.",
            )
        } else {
            appendLine("SOURCE MATERIAL (pages and rows from the goal's Scope; this is the evidence, and the only source you may use):")
            staged.pages.forEachIndexed { i, page ->
                appendLine()
                appendLine("=== ${handles.label(page.url, "[${i + 1}]")} ${page.title}  (${page.source})${if (page.truncated) "  [CUT: see NOT GIVEN TO YOU]" else ""}")
                appendLine(page.text)
            }
        }
        val notGiven = staged.notGiven(handles = handles) + extraNotGiven
        if (notGiven.isNotEmpty()) {
            appendLine()
            appendLine("NOT GIVEN TO YOU (limits of this call, not facts about the sources):")
            notGiven.forEach { appendLine("- $it") }
        }
    }
}

/** Text helpers for prompts. Nothing here shortens without a marker that says how much. */
object PromptText {
    /** [text] whole, or its first [max] characters followed by a marker that names [what] and the true length. */
    fun bounded(text: String, max: Int, what: String): String {
        val t = text.trim()
        return if (t.length <= max) t else t.take(max) + " [CUT: showing $max of ${t.length} characters of $what]"
    }

    private val CODE = Regex("""\s*\(\s*(?:GOAL|CYC|CYCLE|TASK)-\d+\s*\)|\b(GOAL|CYC|CYCLE|TASK)-\d+\b""")

    /**
     * [text] without goal, cycle or task codes (GOAL-3, CYC-5). A model that sees one treats it as part of the goal's
     * words and quotes it back. Applied to text the Director or a model wrote, never to the goal's own Title, Done when or Scope.
     */
    fun stripCodes(text: String): String = CODE.replace(text) { m ->
        when (m.groupValues[1]) { "GOAL" -> "the goal"; "CYC", "CYCLE" -> "an earlier cycle"; "TASK" -> "a task"; else -> "" }
    }

    private val NOTION_URL = Regex("""https://www\.notion\.so/[0-9a-fA-F]{32}""")

    /** [text] with every page URL that has a handle shown as that handle, so no long id reaches a model through an earlier Result. */
    fun withHandles(text: String, handles: Handles): String =
        if (handles.size == 0) text else NOTION_URL.replace(text) { m -> handles.handleFor(m.value)?.let { "[$it]" } ?: m.value }

    /** [text] whole, or its first [max] characters followed by `[CUT n chars]`, so a cut is never silent. */
    fun capped(text: String, max: Int): String {
        val t = text.trim()
        return if (t.length <= max) t else t.take(max) + " [CUT ${t.length - max} chars]"
    }

    /** A one-line excerpt of earlier work, with a marker saying how many characters were left off. */
    fun excerpt(text: String, max: Int): String {
        val line = text.replace(Regex("\\s+"), " ").trim()
        return if (line.length <= max) line else line.take(max) + " [CUT: ${line.length - max} more characters of this result not shown]"
    }
}
