package app.alfrd.engram.cognitive.pipeline.agentwork

import kotlinx.serialization.Serializable
import java.util.ArrayDeque

/**
 * A record of one cycle's planning decision, for the debug surface (`GET /debug/notion-cycles`).
 * It holds ids, titles, types, links, counts, reason codes and the plan the Director wrote. It never
 * holds a token, and nothing in it is read back to make a decision.
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
    fun recent(limit: Int = capacity): List<CyclePlanTrace> = traces.take(limit).map { it.copy(links = it.links.toMutableList(), accepted = it.accepted.toMutableList(), rejected = it.rejected.toMutableList(), writes = it.writes.toMutableList(), notes = it.notes.toMutableList()) }
}
