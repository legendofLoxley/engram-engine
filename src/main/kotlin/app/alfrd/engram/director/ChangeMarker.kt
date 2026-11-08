package app.alfrd.engram.director

import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOwnership
import com.arcadedb.database.Database
import kotlinx.serialization.Serializable

/**
 * A cheap fingerprint of one identity's graph, read without taking any lock and without writing: the cycle counter, a count per
 * type the identity owns or trusts, and the newest timestamps that show a source or an extraction run arriving or finishing.
 * Two markers that are equal mean nothing the Director would want to look at again was added; unequal markers say which part moved.
 *
 * What the counts are, exactly:
 * - `Source`: Sources the identity trusts (`User -TRUSTS-> Source`) or owns (`ownerEmail`), each once.
 * - `Chunk`, `Project`, `Domain`, `ExtractionRun`: vertices whose `ownerEmail` is the identity.
 * - `Phrase`: Phrase vertices with that `ownerEmail`. Polymorphic, so it includes Tasks, Questions and Events.
 * - `Task`, `Question`, `Event`: the typed items with that `ownerEmail`.
 * - `Asserted`: `ASSERTS` edges out of the Sources the identity trusts. A conversational fact has no `ownerEmail`, so this is what shows one arriving.
 *
 * The timestamps: Source has no `createdAt`, so [newestSourceReadAt] is the largest `readAt` among its Sources, and ExtractionRun has
 * none either, so [newestRunStartedAt] and [newestRunFinishedAt] are the largest `startedAt` and `finishedAt` (a finishing run is a change
 * even when no count moved yet).
 */
@Serializable
data class ChangeMarker(
    val lastCycleSeq: Long,
    val counts: Map<String, Long>,
    val newestSourceReadAt: Long? = null,
    val newestRunStartedAt: Long? = null,
    val newestRunFinishedAt: Long? = null,
) {
    /** One part that moved: its name and what it was and is. [before] is null for the first marker ever compared. */
    @Serializable
    data class Change(val part: String, val before: String?, val after: String) {
        override fun toString() = if (before == null) "$part $after" else "$part $before -> $after"
    }

    /** The parts that differ from [previous]; empty when nothing moved. A null [previous] (the first tick) reports every part as new. */
    fun changesSince(previous: ChangeMarker?): List<Change> {
        val out = mutableListOf<Change>()
        fun part(name: String, before: Any?, after: Any?) {
            if (previous == null) out += Change(name, null, after?.toString() ?: "none")
            else if (before != after) out += Change(name, before?.toString() ?: "none", after?.toString() ?: "none")
        }
        part("lastCycleSeq", previous?.lastCycleSeq, lastCycleSeq)
        for (key in KEYS) part(key, previous?.counts?.get(key), counts[key])
        part("newestSourceReadAt", previous?.newestSourceReadAt, newestSourceReadAt)
        part("newestRunStartedAt", previous?.newestRunStartedAt, newestRunStartedAt)
        part("newestRunFinishedAt", previous?.newestRunFinishedAt, newestRunFinishedAt)
        return out
    }

    companion object {
        /** Count names in the order they are shown. */
        val KEYS = listOf("Source", "Chunk", "Phrase", "Task", "Question", "Event", "Project", "Domain", "ExtractionRun", "Asserted")
    }
}

/** Reads a [ChangeMarker]. Queries only: nothing here opens a write transaction. */
class ChangeMarkerReader(private val db: Database) {

    /** The marker for [email], or null when there is no such User (it is never created here). */
    fun read(email: String): ChangeMarker? {
        val user = HorizonOwnership.findUserVertex(db, email) ?: return null
        val lastCycleSeq = (user.get("lastCycleSeq") as? Number)?.toLong() ?: 0L
        val params = mapOf("e" to email)

        val trusted = HorizonOwnership.trustedSourceUids(db, email)
        val owned = db.query("sql", "SELECT uid FROM Source WHERE ownerEmail = :e", params).use { rs ->
            val uids = mutableListOf<String>()
            while (rs.hasNext()) rs.next().getProperty<String>("uid")?.let(uids::add)
            uids
        }
        val sourceUids = (trusted + owned).distinct()

        // Distinct vertices, not rows: the owner-indexed scan of a Phrase subtype returns an item once per index entry it has had, so
        // count(*) over it reported 56 for 11 real tasks and moved when an item was merely updated. A uid is one vertex.
        fun count(type: String): Long =
            db.query("sql", "SELECT uid FROM $type WHERE ownerEmail = :e", params).use { rs ->
                val uids = HashSet<String>()
                while (rs.hasNext()) rs.next().getProperty<String>("uid")?.let(uids::add)
                uids.size.toLong()
            }
        fun max(type: String, field: String): Long? =
            db.query("sql", "SELECT max($field) AS m FROM $type WHERE ownerEmail = :e", params).use { rs -> if (rs.hasNext()) rs.next().getProperty<Number?>("m")?.toLong() else null }

        val newestRead = if (sourceUids.isEmpty()) null else db.query("sql", "SELECT max(readAt) AS m FROM Source WHERE uid IN :uids", mapOf("uids" to sourceUids)).use { rs ->
            if (rs.hasNext()) rs.next().getProperty<Number?>("m")?.toLong() else null
        }
        val asserted = if (trusted.isEmpty()) 0L else db.query("sql", "SELECT count(*) AS n FROM ASSERTS WHERE @out.uid IN :uids", mapOf("uids" to trusted)).use { rs ->
            if (rs.hasNext()) rs.next().getProperty<Number>("n").toLong() else 0L
        }

        val counts = linkedMapOf(
            "Source" to sourceUids.size.toLong(),
            "Chunk" to count("Chunk"),
            "Phrase" to count("Phrase"),
            "Task" to count("Task"),
            "Question" to count("Question"),
            "Event" to count("Event"),
            "Project" to count("Project"),
            "Domain" to count("Domain"),
            "ExtractionRun" to count("ExtractionRun"),
            "Asserted" to asserted,
        )
        return ChangeMarker(lastCycleSeq, counts, newestRead, max("ExtractionRun", "startedAt"), max("ExtractionRun", "finishedAt"))
    }
}
