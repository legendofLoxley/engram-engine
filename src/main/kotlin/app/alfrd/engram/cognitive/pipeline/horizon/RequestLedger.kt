package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * A [ProcessedRequest] row, read back. See [RequestLedger] for the checkpoint state machine
 * [checkpoint] moves through.
 */
data class ProcessedRequestRecord(
    val requestId: String,
    val userEmail: String,
    val cycleSeq: Long,
    val checkpoint: String,
    val phraseUids: List<String>,
    val intentionPhraseUid: String?,
    val failureReason: String?,
)

/**
 * Bounded, durable, request-identity-based idempotency for one conversational cycle
 * ([app.alfrd.engram.cognitive.pipeline.HorizonCycleCoordinator.runCycle]) — deliberately not
 * content-based (see [CycleSequencer]'s doc for why matching on written text was rejected: it can
 * both miss a genuine retry, whose interpretation step may legitimately extract different text on
 * a second attempt, and suppress a genuine repeat, which deserves its own fresh cycle even if the
 * words are identical to something said before).
 *
 * [requestId] is a caller-supplied identity, threaded from the HTTP boundary
 * ([app.alfrd.engram.api.CognitiveRoutes.ChatRequest.requestId] etc.) through to
 * [HorizonCycleCoordinator]. A caller that does not supply one gets no retry protection for that
 * call — this ledger cannot recognize a retry it was never given a stable identity for; getting
 * the `alfrd.app` browser client to generate and resend one is a frontend change outside this
 * task's scope. This task's own verification path (`/debug/converse`, `/debug/environment-signal`)
 * supplies one explicitly, so the mechanism is fully exercised regardless.
 *
 * **Checkpoint state machine**, in order: `cycle_allocated` (a cycle number was consumed, nothing
 * written yet) → `facts_ingested` (ordinary-fact/intention Phrases exist via
 * [app.alfrd.engram.cognitive.pipeline.memory.EngramClient.ingest], not yet Horizon-stamped) →
 * `writes_committed` (every confirmed write is stamped via [HorizonGraphStore.stampNewAssertion])
 * → `completed` (propagation and assembly also ran). `failed` records a terminal, non-resumable
 * failure with [ProcessedRequestRecord.failureReason] set.
 *
 * A caller finding an existing row for `(userEmail, requestId)` resumes from exactly that
 * checkpoint rather than restarting — see [HorizonCycleCoordinator.runCycle] for the resume logic
 * this makes possible: a crash between `facts_ingested` and `writes_committed`, for example,
 * resumes from stamping the already-created uids forward, never re-running
 * `decompose()`/`ingest()` and duplicating the fact write. Because every checkpoint read-then-write
 * happens inside [PerUserCycleLock], a row found in any state other than `completed` can only ever
 * be a crash artifact from a *previous* attempt, never a live concurrent one — see that lock's doc.
 */
interface RequestLedger {
    suspend fun find(userEmail: String, requestId: String): ProcessedRequestRecord?

    /**
     * Upserts the row for `(userEmail, requestId)`. Fields left null are not cleared — each
     * checkpoint call only ever adds information as a cycle progresses (see the state machine
     * above), so a later call omitting, say, [intentionPhraseUid] does not erase one an earlier
     * call already recorded.
     */
    suspend fun checkpoint(
        userEmail: String,
        requestId: String,
        cycleSeq: Long,
        checkpoint: String,
        phraseUids: List<String>? = null,
        intentionPhraseUid: String? = null,
        failureReason: String? = null,
    )
}

/**
 * Bounded by [pruneRetentionCycles]: opportunistically deletes this user's rows more than that
 * many cycles behind the one just checkpointed, on every [checkpoint] call — no background sweep
 * needed. A retried request older than the retention window is simply treated as a new one; this
 * is an accepted, stated bound, not an attempt at unlimited historical dedup.
 */
class ArcadeRequestLedger(
    private val db: Database,
    private val lockTimeoutMs: Long = HorizonConsistencyLock.DEFAULT_TIMEOUT_MS,
    private val pruneRetentionCycles: Long = 50,
) : RequestLedger {

    private val logger = LoggerFactory.getLogger(ArcadeRequestLedger::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun find(userEmail: String, requestId: String): ProcessedRequestRecord? = withContext(Dispatchers.IO) {
        try {
            db.query(
                "sql",
                "SELECT FROM ProcessedRequest WHERE userEmail = :userEmail AND requestId = :requestId",
                mapOf("userEmail" to userEmail, "requestId" to requestId),
            ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().toRecord() else null }
        } catch (e: Exception) {
            logger.warn("find failed for userEmail=$userEmail requestId=$requestId: ${e.message}")
            null
        }
    }

    override suspend fun checkpoint(
        userEmail: String,
        requestId: String,
        cycleSeq: Long,
        checkpoint: String,
        phraseUids: List<String>?,
        intentionPhraseUid: String?,
        failureReason: String?,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val acquired = withHorizonWriteLock(db, lockTimeoutMs) {
                db.transaction {
                    val now = System.currentTimeMillis()
                    val existing = db.query(
                        "sql",
                        "SELECT FROM ProcessedRequest WHERE userEmail = :userEmail AND requestId = :requestId",
                        mapOf("userEmail" to userEmail, "requestId" to requestId),
                    ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null }

                    val vertex = existing ?: db.newVertex("ProcessedRequest").apply {
                        set("requestId", requestId)
                        set("userEmail", userEmail)
                        set("createdAt", now)
                    }
                    vertex.set("cycleSeq", cycleSeq)
                    vertex.set("checkpoint", checkpoint)
                    if (phraseUids != null) vertex.set("phraseUids", json.encodeToString(phraseUids))
                    if (intentionPhraseUid != null) vertex.set("intentionPhraseUid", intentionPhraseUid)
                    if (failureReason != null) vertex.set("failureReason", failureReason)
                    vertex.set("updatedAt", now)
                    vertex.save()

                    val threshold = cycleSeq - pruneRetentionCycles
                    if (threshold > 0) {
                        db.command(
                            "sql",
                            "DELETE FROM ProcessedRequest WHERE userEmail = :userEmail AND cycleSeq < :threshold",
                            mapOf("userEmail" to userEmail, "threshold" to threshold),
                        ).close()
                    }
                }
                true
            }
            if (acquired == null) {
                logger.warn("checkpoint: could not acquire the Horizon write lock within ${lockTimeoutMs}ms for userEmail=$userEmail requestId=$requestId")
            }
        } catch (e: Exception) {
            logger.warn("checkpoint failed for userEmail=$userEmail requestId=$requestId: ${e.message}")
        }
    }

    private fun Vertex.toRecord(): ProcessedRequestRecord {
        val uidsJson = get("phraseUids") as? String
        val uids = try {
            if (uidsJson.isNullOrBlank()) emptyList() else json.decodeFromString<List<String>>(uidsJson)
        } catch (_: Exception) {
            emptyList()
        }
        return ProcessedRequestRecord(
            requestId = get("requestId") as? String ?: "",
            userEmail = get("userEmail") as? String ?: "",
            cycleSeq = (get("cycleSeq") as? Number)?.toLong() ?: 0L,
            checkpoint = get("checkpoint") as? String ?: "",
            phraseUids = uids,
            intentionPhraseUid = get("intentionPhraseUid") as? String,
            failureReason = get("failureReason") as? String,
        )
    }
}
