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
    val cycleSeq: Long,
    val targetFilename: String,
    /** `"completed"` | `"failed"` | `"cancelled"` — see [ActorEventMetadata.executionOutcome]. */
    val state: String,
    val summary: String,
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

    suspend fun list(userEmail: String, horizonGraphStore: HorizonGraphStore): HermesActivityFeedResult =
        when (val result = horizonGraphStore.listRecentActorEvents(userEmail, HERMES_SOURCE_NAME, MAX_ITEMS)) {
            is HorizonGraphStore.ActorEventListResult.Failed -> HermesActivityFeedResult.Failed(result.reason)
            is HorizonGraphStore.ActorEventListResult.Ok -> HermesActivityFeedResult.Ok(result.events.mapNotNull(::toActivityItem))
        }

    private fun toActivityItem(event: HorizonGraphStore.ActorEventSummary): HermesActivityItem? {
        val metadata = decodeMetadata(event.kindMetadata) ?: return null
        if (metadata.assignmentKind != "document_summary") return null
        val targetFilename = metadata.targetFilename?.takeIf { it.isNotBlank() } ?: return null
        val state = metadata.executionOutcome ?: return null
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
