package app.alfrd.engram.cognitive.pipeline.hermes

import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventMetadata
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonGraphStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * One durably-committed, honestly-labeled Hermes document-summary outcome, shown independently of
 * any conversational reply. [summary] is a short fixed-shape label plus the target filename — never
 * the delivered reply text itself (that stays owned by [HermesCompletionDirector]/the Director's
 * own reply path; this is a *different*, asynchronous output, not a copy of that one).
 */
data class HermesActivityItem(
    val eventId: String,
    val assignmentId: String,
    /** Null only for a `"running"` item — nothing has been committed to the graph yet, so there is
     *  no cycle to report. Always non-null for a terminal item. */
    val cycleSeq: Long?,
    val targetFilename: String,
    /** `"running"` | `"completed"` | `"failed"` | `"cancelled"` — see [ActorEventMetadata.executionOutcome]
     *  for the three terminal values; `"running"` is synthesized from [HermesActiveAssignmentRegistry], never
     *  read from the graph. */
    val state: String,
    val summary: String,
    /** `occurredAt` (graph commit time) for a terminal item; `issuedAt` (dispatch time) for a running one. */
    val occurredAt: Long,
)

/** Mirrors [HorizonGraphStore.ActorEventListResult]'s Ok/Failed distinction one level up, after this feed's own filtering/labeling — a caller must still be able to tell "no eligible items" from "the read itself failed" apart. */
sealed interface HermesActivityFeedResult {
    data class Ok(val items: List<HermesActivityItem>) : HermesActivityFeedResult
    data class Failed(val reason: String) : HermesActivityFeedResult
}

/**
 * Builds the "selected graph updates independently of conversation" activity list for one user —
 * entirely from durable graph state ([HorizonGraphStore.listRecentActorEvents]), **never** from
 * [HermesAssignmentCompletionStore] (in-memory, scoped to this process's own lifetime — see that
 * store's own doc) and never from the delivered reply text itself. This is what makes the list
 * survive a backend restart: every fact this feed needs (which assignment kind, which target
 * filename, what actually happened) was written durably onto the `ASSERTS` edge at ingestion time
 * (see [HermesDelegationDispatcher]'s `assignmentKind`/`targetFilename`/`executionOutcome` fields),
 * not kept only in a process-local map.
 *
 * A **bounded snapshot**, not a cursor: [list] always returns "what's eligible right now," capped at
 * [MAX_ITEMS]. A caller reconciles against whatever it last rendered by [HermesActivityItem.eventId]
 * — never by trusting a saved position, which could otherwise permanently hide an event whose
 * earlier read happened to fail, or miss items after a reload. Retention is a display boundary only:
 * an item older than the newest [MAX_ITEMS] simply falls out of this view; its underlying graph
 * evidence is never touched, let alone deleted, by this class.
 *
 * Scoped to exactly one event family this increment supports —
 * [HermesAssignmentKind.DocumentSummary] outcomes — via [ActorEventMetadata.assignmentKind]; a
 * [HermesAssignmentKind.MarkerCheck] event (or anything with unrecognized/missing metadata) is
 * silently excluded, never guessed into a wrong label.
 */
object HermesActivityFeed {
    /** The Source name [HermesDelegationDispatcher] ingests every assignment outcome under. */
    const val HERMES_SOURCE_NAME = "hermes"

    /** The bounded-snapshot size — see this class's own doc for why this is a display cap, not a graph limit. */
    const val MAX_ITEMS = 50

    private val logger = LoggerFactory.getLogger(HermesActivityFeed::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** The one [HermesAssignmentKind] label this feed (terminal and running alike) supports. */
    private const val DOCUMENT_SUMMARY_KIND = "document_summary"

    /**
     * [activeAssignments] is optional — omitted (the default), this behaves exactly as before:
     * terminal graph history only. Passed a real registry, currently-running
     * [HermesAssignmentKind.DocumentSummary] assignments for [userEmail] are included too, most
     * recently issued first, ahead of the terminal history.
     *
     * De-duplication handles the one genuine race: [HermesDelegationDispatcher] unregisters from
     * the active registry only in a `finally` block *after* both graph ingestion and completion
     * recording, so an assignment can very briefly be visible in both the just-fetched terminal
     * list and the registry's still-active list. Terminal wins — it is the more complete, durable
     * fact — so any running entry whose [HermesActiveAssignmentSummary.assignmentId] already
     * appears among the terminal items is dropped rather than shown twice.
     *
     * Running items are never subject to [MAX_ITEMS]: that cap bounds the terminal *history* query
     * only. There are only ever a handful of assignments genuinely in flight at once, and hiding
     * one of them because of an unrelated history cap would misrepresent what is actually running.
     */
    suspend fun list(
        userEmail: String,
        horizonGraphStore: HorizonGraphStore,
        activeAssignments: HermesActiveAssignmentRegistry? = null,
    ): HermesActivityFeedResult =
        when (val result = horizonGraphStore.listRecentActorEvents(userEmail, HERMES_SOURCE_NAME, MAX_ITEMS)) {
            is HorizonGraphStore.ActorEventListResult.Failed -> HermesActivityFeedResult.Failed(result.reason)
            is HorizonGraphStore.ActorEventListResult.Ok -> {
                val terminalItems = result.events.mapNotNull(::toActivityItem)
                val terminalAssignmentIds = terminalItems.map { it.assignmentId }.toSet()
                val runningItems = activeAssignments
                    ?.listActive(userEmail, DOCUMENT_SUMMARY_KIND)
                    .orEmpty()
                    .filter { it.assignmentId !in terminalAssignmentIds }
                    .sortedByDescending { it.issuedAt }
                    .map { toRunningActivityItem(it) }
                HermesActivityFeedResult.Ok(runningItems + terminalItems)
            }
        }

    private fun toRunningActivityItem(summary: HermesActiveAssignmentSummary) = HermesActivityItem(
        eventId = "hermes-assignment-${summary.assignmentId}",
        assignmentId = summary.assignmentId,
        cycleSeq = null,
        targetFilename = summary.targetFilename,
        state = "running",
        summary = "Summarizing ${summary.targetFilename}…",
        occurredAt = summary.issuedAt,
    )

    private fun toActivityItem(event: HorizonGraphStore.ActorEventSummary): HermesActivityItem? {
        val metadata = decodeMetadata(event.kindMetadata) ?: return null
        if (metadata.assignmentKind != DOCUMENT_SUMMARY_KIND) return null
        val targetFilename = metadata.targetFilename?.takeIf { it.isNotBlank() } ?: return null
        val state = metadata.executionOutcome ?: return null
        val assignmentId = event.assignmentId?.takeIf { it.isNotBlank() } ?: return null
        val summary = when (state) {
            "completed" -> "Summarized $targetFilename"
            "failed" -> "Could not summarize $targetFilename"
            "cancelled" -> "Summary of $targetFilename was cancelled"
            // An unrecognized state is never guessed into one of the three known labels above —
            // excluded instead, the same "don't fabricate a label" convention as everywhere else here.
            else -> return null
        }
        return HermesActivityItem(
            eventId = event.eventId,
            assignmentId = assignmentId,
            cycleSeq = event.cycleSeq,
            targetFilename = targetFilename,
            state = state,
            summary = summary,
            occurredAt = event.occurredAt,
        )
    }

    private fun decodeMetadata(raw: String): ActorEventMetadata? = try {
        json.decodeFromString<ActorEventMetadata>(raw)
    } catch (e: Exception) {
        logger.warn("HermesActivityFeed: unparsable kindMetadata, excluding event: ${e.message}")
        null
    }
}
