package app.alfrd.engram.cognitive.pipeline.agentwork

import kotlinx.serialization.Serializable
import java.util.ArrayDeque

/**
 * A record of one cycle's run, for the debug surface (`GET /debug/notion-cycles`): the goal's scope and what
 * became of each mention, the plan the Director wrote, what each task read and did, and QC. It holds ids,
 * titles, types, links, counts, reason codes, short result excerpts and the plan. It never holds a token,
 * and nothing in it is read back to make a decision.
 */
@Serializable
data class CyclePlanTrace(
    val cycle: String?,
    val cyclePageId: String,
    var goal: String?,
    var goalTitle: String?,
    val worker: String,
    val startedAt: String,
    var finishedAt: String? = null,
    /** True when this run took over a Running cycle whose lease had expired. */
    val reclaimed: Boolean = false,
    /** `done`, `failed`, `aborted` (left Running for a later reclaim) or `lost_claim`. */
    var outcome: String = "running",
    var summary: String? = null,
    val links: MutableList<LinkTrace> = mutableListOf(),
    var contextRows: Int = 0,
    var model: ModelTrace? = null,
    val accepted: MutableList<TaskTrace> = mutableListOf(),
    val rejected: MutableList<RejectionTrace> = mutableListOf(),
    val writes: MutableList<WriteTrace> = mutableListOf(),
    val notes: MutableList<String> = mutableListOf(),
    var scope: ScopeTrace? = null,
    /** What each task was given to read, within the content limits. */
    val content: MutableList<ContentTrace> = mutableListOf(),
    /** One entry per task the run looked at, in order: run, skipped because already settled, blocked, or failed. */
    val execution: MutableList<TaskRunTrace> = mutableListOf(),
    var qc: QcTrace? = null,
)

/** The goal's scope as the Director read it. Prose that was ignored is reported here, as the converged design requires. */
@Serializable
data class ScopeTrace(
    val mentions: Int,
    val readable: Int,
    val skipped: List<SkippedMentionTrace>,
    val proseChars: Int,
    val proseExcerpt: String?,
    val ignoredLinks: List<String>,
    val serves: Int,
    val allowedPages: Int,
    val allowedDatabases: Int,
    val allowedRows: Int,
    val truncated: Boolean,
)

@Serializable
data class SkippedMentionTrace(val title: String, val kind: String, val reason: String, val detail: String?)

/** One page, or one database's rows, staged for a task: how much of it, and whether a limit cut it. */
@Serializable
data class ContentTrace(val url: String, val title: String, val chars: Int, val truncated: Boolean, val note: String? = null)

/**
 * How a model call ended, as counts only. Never the model's words: this is what lets a failed call be told
 * apart (cut off by the token cap, stopped on its own, answered in prose with no tool call) from the journal
 * or `/debug/notion-cycles`, without the reply being stored anywhere.
 */
@Serializable
data class AnswerTrace(
    /** The provider's stop reason (`stop`, `tool_calls`, `length`), or null when it reported none. */
    val finishReason: String?,
    /** Tokens generated, reasoning included, or null when the provider reported none. */
    val completionTokens: Int?,
    /** Characters of visible content that came back. Zero means the answer was only a tool call. */
    val contentChars: Int,
    /** How many tool calls came back. */
    val toolCalls: Int,
) {
    companion object {
        fun of(response: app.alfrd.engram.cognitive.providers.LlmResponse) =
            AnswerTrace(response.finishReason, response.completionTokens, response.text.length, response.toolCalls.size)
    }
}

/**
 * What one model call was given, as **ids and counts only, never text**: which system prompt (name, version,
 * fingerprint of the exact text), how big the per-call prompt was, which pages and rows of the goal's Scope it
 * contained, which earlier tasks' results it was shown, and which items were cut or left out by a bound.
 * Together with [AnswerTrace] this is what lets a bad answer be traced to what the model was and was not given.
 */
@Serializable
data class GivenTrace(
    val prompt: PromptRef,
    /** Characters of the per-call (user) prompt. */
    val promptChars: Int,
    /** Pages and row blocks given as source. */
    val sourceBlocks: Int,
    /** Pages and rows given as source, in total. [sourceItemIds] lists up to [MAX_IDS] of them. */
    val sourceItems: Int,
    val sourceItemIds: List<String>,
    /** Orders of the earlier tasks whose results were shown, labelled as earlier work. */
    val earlierTasks: List<Int>,
    /** Items given only in part, by id (a property or a page body was cut, with a marker). */
    val cutItemIds: List<String>,
    /** Items that did not fit and were not given, by id. */
    val leftOutItemIds: List<String>,
    /** How many items were left out in all; may exceed the ids listed. */
    val leftOutCount: Int,
    /** Lines under NOT GIVEN TO YOU that the model was shown. */
    val notGivenLines: Int,
) {
    companion object {
        const val MAX_IDS = 100

        fun of(prompt: SystemPrompt, promptChars: Int, staged: StagedContext?, earlierTasks: List<Int> = emptyList()): GivenTrace {
            val items = staged?.items.orEmpty()
            val left = staged?.leftOutItems.orEmpty()
            return GivenTrace(
                prompt.ref, promptChars, staged?.pages?.size ?: 0, items.size, items.map { it.id }.take(MAX_IDS), earlierTasks,
                staged?.cutItems.orEmpty().map { it.id }.take(MAX_IDS), left.map { it.id }.take(MAX_IDS),
                left.size + (staged?.problems?.count { it is StagingProblem.OverBudget } ?: 0), staged?.notGiven()?.size ?: 0,
            )
        }
    }
}

@Serializable
data class TaskRunTrace(
    val order: Int,
    val title: String,
    /** `done`, `blocked`, `failed`, or `already_settled` (a restart found it finished and did not run it again). */
    val outcome: String,
    /** A reason code or short reason; never the model's own words. */
    val reason: String?,
    val pagesStaged: Int,
    val pagesCited: Int,
    val resultChars: Int,
    val latencyMs: Long?,
    val problems: List<String> = emptyList(),
    val answer: AnswerTrace? = null,
    val given: GivenTrace? = null,
)

@Serializable
data class QcTrace(
    val called: Boolean,
    val latencyMs: Long,
    val rejectedReason: String?,
    val goalLooksMet: String?,
    val provider: String?,
    val model: String?,
    val answer: AnswerTrace? = null,
    val given: GivenTrace? = null,
)

@Serializable
data class LinkTrace(val link: String, val kind: String, val detail: String? = null)

@Serializable
data class ModelTrace(
    val called: Boolean,
    val latencyMs: Long,
    val provider: String?,
    val model: String?,
    val rejectedReason: String?,
    val tasksProposed: Int,
    val rationale: String?,
    val answer: AnswerTrace? = null,
    val given: GivenTrace? = null,
)

@Serializable
data class TaskTrace(val order: Int, val title: String, val type: String, val links: List<String>, val instructions: String)

@Serializable
data class RejectionTrace(val index: Int, val title: String?, val reason: String, val detail: String?)

@Serializable
data class WriteTrace(val opKey: String, val outcome: String)

/** The most recent traces, newest first, bounded. Safe to read while the processor writes. */
class CycleTraceStore(private val capacity: Int = 50) {
    private val traces = ArrayDeque<CyclePlanTrace>()

    @Synchronized
    fun add(trace: CyclePlanTrace) {
        traces.addFirst(trace)
        while (traces.size > capacity) traces.removeLast()
    }

    /** A copy of each trace as it is now. */
    @Synchronized
    fun recent(limit: Int = capacity): List<CyclePlanTrace> = traces.take(limit).map { it.copy(
        links = it.links.toMutableList(), accepted = it.accepted.toMutableList(), rejected = it.rejected.toMutableList(), writes = it.writes.toMutableList(),
        notes = it.notes.toMutableList(), content = it.content.toMutableList(), execution = it.execution.toMutableList(),
    ) }
}
