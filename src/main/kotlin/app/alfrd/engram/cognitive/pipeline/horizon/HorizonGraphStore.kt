package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID

/** One entry in an `ASSERTS` edge's append-only `statusHistory` audit trail. */
@Serializable
data class StatusHistoryEntry(val state: String, val at: Long)

/**
 * Write-side interface through which propagation (a future task) updates Context Horizon graph
 * state. Standalone — deliberately not part of [app.alfrd.engram.cognitive.pipeline.memory.EngramClient],
 * so [app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient]/`HttpEngramClient` aren't
 * forced to implement methods nothing calls yet.
 *
 * Every mutator takes [String] `userEmail` and verifies every phrase uid it touches actually
 * belongs to that user (reachable via their own `TRUSTS` edges) before writing anything — a failed
 * check is a rejected, logged no-op, returning `false` rather than throwing, so a caller can
 * observe rejection. Mutations run inside a single, synchronous, suspend-and-block-until-committed
 * transaction (never `scope.launch`) — a caller that awaits a write is guaranteed a subsequent
 * [HorizonAssembler.assemble] call on the same `Database` observes it (ordinary single-process ACID
 * read-after-write; no version counter needed).
 *
 * [cycleSeq] is a caller-supplied, per-user monotonic cycle number — identity, never a wall-clock
 * timestamp. This store does not own or persist the counter itself, only accepts and stamps it.
 */
interface HorizonGraphStore {

    /** Ingests one environment-sourced phrase for [userEmail], creating/reusing an `environment_signal` Source named [sourceName]. Returns the new Phrase uid, or null on failure/unknown user. */
    suspend fun ingestEnvironmentSignal(
        userEmail: String,
        cycleSeq: Long,
        sourceName: String,
        text: String,
        metadata: String = "{}",
    ): String?

    /** Appends a status transition to every `ASSERTS` edge asserting [phraseUid] that [userEmail] owns. Returns false if the phrase isn't owned by [userEmail] or doesn't exist. */
    suspend fun markAssertionStatus(userEmail: String, cycleSeq: Long, phraseUid: String, status: AssertionStatus): Boolean

    /** Creates a `relevant_to` edge from [fromPhraseUid] to [toPhraseUid]. Both must be owned by [userEmail]; returns false otherwise. */
    suspend fun markRelevant(userEmail: String, cycleSeq: Long, fromPhraseUid: String, toPhraseUid: String, strength: Double = 1.0): Boolean

    /** Creates a `supersedes` edge from [newerPhraseUid] to [olderPhraseUid]. Both must be owned by [userEmail]; returns false otherwise. Representation only — no live code path calls this yet. */
    suspend fun markSuperseded(userEmail: String, cycleSeq: Long, newerPhraseUid: String, olderPhraseUid: String): Boolean
}

class ArcadeHorizonGraphStore(private val db: Database) : HorizonGraphStore {

    private val logger = LoggerFactory.getLogger(ArcadeHorizonGraphStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val MAX_STATUS_HISTORY_ENTRIES = 20
    }

    override suspend fun ingestEnvironmentSignal(
        userEmail: String,
        cycleSeq: Long,
        sourceName: String,
        text: String,
        metadata: String,
    ): String? = withContext(Dispatchers.IO) {
        if (userEmail.isBlank() || text.isBlank() || sourceName.isBlank()) return@withContext null
        try {
            var newPhraseUid: String? = null
            db.transaction {
                val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: run {
                    logger.warn("ingestEnvironmentSignal: no User vertex for email=$userEmail — skipping")
                    return@transaction
                }
                val sourceVertex = findOrCreateEnvironmentSource(userVertex, sourceName, metadata)
                val now = System.currentTimeMillis()
                val uid = UUID.randomUUID().toString()
                val phraseVertex = db.newVertex("Phrase").apply {
                    set("uid", uid)
                    set("text", text)
                    set("hash", sha256(text))
                    set("visibility", "private")
                    set("createdAt", now)
                    set("updatedAt", now)
                    save()
                }
                sourceVertex.newEdge("ASSERTS", phraseVertex, false).apply {
                    set("context", ENVIRONMENT_SOURCE_TYPE)
                    set("timestamp", now)
                    set("scores", "[]")
                    set("cycleSeq", cycleSeq)
                    save()
                }
                newPhraseUid = uid
            }
            newPhraseUid
        } catch (e: Exception) {
            logger.warn("ingestEnvironmentSignal failed for userEmail=$userEmail sourceName=$sourceName: ${e.message}")
            null
        }
    }

    override suspend fun markAssertionStatus(
        userEmail: String,
        cycleSeq: Long,
        phraseUid: String,
        status: AssertionStatus,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            var success = false
            db.transaction {
                val ownedAssertsEdges = HorizonOwnership.ownedAssertsEdges(db, userEmail, phraseUid)
                if (ownedAssertsEdges.isEmpty()) return@transaction
                val now = System.currentTimeMillis()
                val stateValue = status.name.lowercase()
                for (edge in ownedAssertsEdges) {
                    val mutableEdge = edge.modify()
                    val history = parseStatusHistory(mutableEdge.get("statusHistory") as? String)
                    val updated = (history + StatusHistoryEntry(stateValue, now)).takeLast(MAX_STATUS_HISTORY_ENTRIES)
                    mutableEdge.set("status", stateValue)
                    mutableEdge.set("statusHistory", json.encodeToString(updated))
                    mutableEdge.set("cycleSeq", cycleSeq)
                    mutableEdge.save()
                }
                success = true
            }
            success
        } catch (e: Exception) {
            logger.warn("markAssertionStatus failed for userEmail=$userEmail phraseUid=$phraseUid: ${e.message}")
            false
        }
    }

    override suspend fun markRelevant(
        userEmail: String,
        cycleSeq: Long,
        fromPhraseUid: String,
        toPhraseUid: String,
        strength: Double,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            var success = false
            db.transaction {
                val from = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, fromPhraseUid) ?: return@transaction
                val to = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, toPhraseUid) ?: return@transaction
                RelatedToEdges.createRelevantTo(from.modify(), to.modify(), strength, cycleSeq, System.currentTimeMillis())
                success = true
            }
            success
        } catch (e: Exception) {
            logger.warn("markRelevant failed for userEmail=$userEmail from=$fromPhraseUid to=$toPhraseUid: ${e.message}")
            false
        }
    }

    override suspend fun markSuperseded(
        userEmail: String,
        cycleSeq: Long,
        newerPhraseUid: String,
        olderPhraseUid: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            var success = false
            db.transaction {
                val newer = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, newerPhraseUid) ?: return@transaction
                val older = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, olderPhraseUid) ?: return@transaction
                RelatedToEdges.createSupersedes(newer.modify(), older.modify(), cycleSeq, System.currentTimeMillis())
                success = true
            }
            success
        } catch (e: Exception) {
            logger.warn("markSuperseded failed for userEmail=$userEmail newer=$newerPhraseUid older=$olderPhraseUid: ${e.message}")
            false
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun findOrCreateEnvironmentSource(userVertex: Vertex, sourceName: String, metadata: String): Vertex {
        val existing = db.query("sql", "SELECT FROM Source WHERE name = :name", mapOf("name" to sourceName))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null }
        if (existing != null) return existing
        val sourceVertex = db.newVertex("Source").apply {
            set("uid", UUID.randomUUID().toString())
            set("name", sourceName)
            set("type", ENVIRONMENT_SOURCE_TYPE)
            set("metadata", metadata)
            save()
        }
        userVertex.modify().newEdge("TRUSTS", sourceVertex, false).apply {
            set("scores", "[]")
            save()
        }
        return sourceVertex
    }

    private fun parseStatusHistory(json: String?): List<StatusHistoryEntry> = try {
        if (json.isNullOrBlank()) emptyList() else this.json.decodeFromString(json)
    } catch (_: Exception) {
        emptyList()
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.trim().lowercase().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
