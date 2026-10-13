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
)

@Serializable
data class QcTrace(
    val called: Boolean,
    val latencyMs: Long,
    val rejectedReason: String?,
    val goalLooksMet: String?,
    val provider: String?,
    val model: String?,
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
