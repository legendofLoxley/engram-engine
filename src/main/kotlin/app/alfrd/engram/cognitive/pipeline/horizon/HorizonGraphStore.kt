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
 *
 * **Consistency.** Every mutator acquires [HorizonConsistencyLock]'s write lock (via
 * [withHorizonWriteLock], bounded — see that lock's doc for why a hand-rolled lock rather than an
 * ArcadeDB-native isolation level or lock) for the duration of its `db.transaction { }` call,
 * excluding both other writers and any concurrent [HorizonAssembler.assemble] read. If the lock
 * cannot be acquired within the bound, the mutation is rejected (returns `false`/`null`, logged) —
 * it never proceeds unguarded.
 *
 * **Caller sequence-continuity obligations.** [cycleSeq] must be non-decreasing across calls for
 * the same user: a mutation stamped with a lower `cycleSeq` than one already committed for that
 * user represents evidence "from the past" relative to state the caller has already advanced
 * beyond, and [HorizonAssembler] treats anything with a `cycleSeq`/`statusCycleSeq` greater than
 * the `currentCycleSeq` it is asked about as **future evidence, and excludes it** rather than
 * surfacing or misclassifying it. [markAssertionStatus] additionally rejects (per-edge, logged) a
 * `cycleSeq` older than the phrase's own original assertion — a status cannot predate the claim it
 * describes. Enforcement beyond these two checks (e.g. a global monotonic counter) is the caller's
 * responsibility; this store does not track "the last cycleSeq seen for this user" itself.
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

    /**
     * Creates a `relevant_to` edge from [fromPhraseUid] to [toPhraseUid]. Both must be owned by
     * [userEmail]; returns false otherwise. Idempotent: if a `relevant_to` edge already exists for
     * this exact (from, to) pair, no duplicate is created and this returns true — a repeated
     * reactivation of the same target by the *same* new evidence (e.g. a resumed, previously
     * interrupted propagation pass) is a safe no-op. A *different* `fromPhraseUid` reactivating the
     * same [toPhraseUid] is genuinely new evidence and is not deduplicated against this or any
     * other existing edge.
     */
    suspend fun markRelevant(userEmail: String, cycleSeq: Long, fromPhraseUid: String, toPhraseUid: String, strength: Double = 1.0): Boolean

    /** Creates a `supersedes` edge from [newerPhraseUid] to [olderPhraseUid]. Both must be owned by [userEmail]; returns false otherwise. Representation only — no live code path calls this yet. */
    suspend fun markSuperseded(userEmail: String, cycleSeq: Long, newerPhraseUid: String, olderPhraseUid: String): Boolean

    /**
     * Stamps Horizon identity onto an `ASSERTS` edge an ordinary [app.alfrd.engram.cognitive.pipeline.memory.EngramClient.ingest]
     * call already created for [phraseUid] — it never creates a Phrase or `ASSERTS` edge itself.
     * This is the deliberate split that lets ordinary fact capture keep using `ingest()` verbatim
     * (preserving whatever Source-reuse/Concept-linking/`queryPhrases` behavior it already has)
     * while still becoming Horizon-visible: [cycleSeq] is set only if the edge has no `cycleSeq`
     * yet (this is always the initial stamp, immediately following creation); when [status] is
     * non-null, `status`/`statusHistory`/`statusCycleSeq` are set too, in the same call.
     *
     * Idempotent for a resumed, previously-interrupted attempt: calling this again with the same
     * [cycleSeq]/[status] for a phrase already stamped that way is a safe no-op (no duplicate
     * `statusHistory` entry). Calling it with a *different* [cycleSeq] than what is already
     * stamped is rejected (per-edge, logged) rather than silently overwriting an established
     * cycle identity — [markAssertionStatus] exists for a genuine *later* status transition.
     *
     * Returns false if [phraseUid] isn't owned by [userEmail] or doesn't exist.
     */
    suspend fun stampNewAssertion(userEmail: String, cycleSeq: Long, phraseUid: String, status: AssertionStatus? = null): Boolean

    /**
     * The text of [phraseUid], if it exists and is owned by [userEmail] — null otherwise. Used to
     * recover evidence text for a phrase a caller already knows the uid of but not the text (e.g.
     * [app.alfrd.engram.cognitive.pipeline.HorizonCycleCoordinator] resuming propagation after a
     * crash-interrupted write, from a [RequestLedger] checkpoint that stored only uids).
     */
    suspend fun phraseText(userEmail: String, phraseUid: String): String?

    /** [propagationStatus] is `"completed"` | `"incomplete"` | `null` (no propagate() attempt recorded yet). [incompletePropagationTargets] is the precise `(toPhraseUid, strength)` set still owed — see [ActorEventIngestionService] for how a retry uses this instead of a blind full recompute. */
    data class ActorEventRecord(
        val phraseUid: String,
        val cycleSeq: Long,
        val contentHash: String,
        val propagationStatus: String?,
        val incompletePropagationTargets: List<RelevanceEdgeSummary>,
    )

    /** The existing Actor-attributed event owned by [userEmail] with this [eventId], if any — scoped the same way [HorizonOwnership.ownedAssertsEdges] scopes every other per-user lookup. Null when never delivered (or delivered for a different user, which is never a match). */
    suspend fun findActorEventByEventId(userEmail: String, eventId: String): ActorEventRecord?

    /**
     * Creates a new Phrase + `ASSERTS` edge exactly like [ingestEnvironmentSignal], generalized to
     * an arbitrary [sourceType] and carrying the event-identity fields
     * [findActorEventByEventId] reads back — all set on the same edge, in the same transaction as
     * the Phrase/edge creation, so identity is never committed separately from the evidence it
     * identifies. [kindMetadata] is a JSON blob of event-specific fields (e.g. `toolSucceeded`) —
     * stored here, never only in the reused [findActorEventByEventId]-independent Source metadata,
     * which a second event sharing [sourceName] would not update. Returns the new Phrase uid, or
     * null on failure/unknown user — never throws, matching every other mutator in this interface.
     * Callers are responsible for checking [findActorEventByEventId] first; this method does not
     * itself check for a duplicate [eventId].
     */
    suspend fun ingestActorEvent(
        userEmail: String,
        cycleSeq: Long,
        sourceName: String,
        sourceType: String,
        text: String,
        eventId: String,
        contentHash: String,
        assignmentId: String?,
        occurredAt: Long?,
        kindMetadata: String,
    ): String?

    /** Updates the propagation-retry bookkeeping on the `ASSERTS` edge identified by [eventId] — see [ActorEventRecord]. Returns false if no such edge is owned by [userEmail]. */
    suspend fun recordActorEventPropagationStatus(
        userEmail: String,
        eventId: String,
        status: String,
        incompletePropagationTargets: List<RelevanceEdgeSummary>,
    ): Boolean
}

class ArcadeHorizonGraphStore(
    private val db: Database,
    private val lockTimeoutMs: Long = HorizonConsistencyLock.DEFAULT_TIMEOUT_MS,
) : HorizonGraphStore {

    private val logger = LoggerFactory.getLogger(ArcadeHorizonGraphStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun ingestEnvironmentSignal(
        userEmail: String,
        cycleSeq: Long,
        sourceName: String,
        text: String,
        metadata: String,
    ): String? = withContext(Dispatchers.IO) {
        if (userEmail.isBlank() || text.isBlank() || sourceName.isBlank()) return@withContext null
        try {
            withHorizonWriteLock(db, lockTimeoutMs) {
                var newPhraseUid: String? = null
                db.transaction {
                    val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: run {
                        logger.warn("ingestEnvironmentSignal: no User vertex for email=$userEmail — skipping")
                        return@transaction
                    }
                    val sourceVertex = findOrCreateSource(userVertex, sourceName, ENVIRONMENT_SOURCE_TYPE, metadata)
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
            } ?: run {
                logger.warn("ingestEnvironmentSignal: could not acquire the Horizon write lock within ${lockTimeoutMs}ms for userEmail=$userEmail — rejecting")
                null
            }
        } catch (e: Exception) {
            logger.warn("ingestEnvironmentSignal failed for userEmail=$userEmail sourceName=$sourceName: ${e.message}")
            null
        }
    }

    override suspend fun findActorEventByEventId(userEmail: String, eventId: String): HorizonGraphStore.ActorEventRecord? = withContext(Dispatchers.IO) {
        try {
            val sourceUids = HorizonOwnership.trustedSourceUids(db, userEmail)
            if (sourceUids.isEmpty()) return@withContext null
            val sql = """
                SELECT @in.uid as phraseUid, cycleSeq, contentHash, propagationStatus, incompletePropagationTargets
                FROM ASSERTS
                WHERE eventId = :eventId AND @out.uid IN :sourceUids
                LIMIT 1
            """.trimIndent()
            db.query("sql", sql, mapOf("eventId" to eventId, "sourceUids" to sourceUids)).use { rs ->
                if (!rs.hasNext()) return@use null
                val row = rs.next().toMap()
                HorizonGraphStore.ActorEventRecord(
                    phraseUid = row["phraseUid"] as? String ?: return@use null,
                    cycleSeq = (row["cycleSeq"] as? Number)?.toLong() ?: 0L,
                    contentHash = row["contentHash"] as? String ?: "",
                    propagationStatus = row["propagationStatus"] as? String,
                    incompletePropagationTargets = parseRelevanceEdgeSummaries(row["incompletePropagationTargets"] as? String),
                )
            }
        } catch (e: Exception) {
            logger.warn("findActorEventByEventId failed for userEmail=$userEmail eventId=$eventId: ${e.message}")
            null
        }
    }

    override suspend fun ingestActorEvent(
        userEmail: String,
        cycleSeq: Long,
        sourceName: String,
        sourceType: String,
        text: String,
        eventId: String,
        contentHash: String,
        assignmentId: String?,
        occurredAt: Long?,
        kindMetadata: String,
    ): String? = withContext(Dispatchers.IO) {
        if (userEmail.isBlank() || text.isBlank() || sourceName.isBlank() || eventId.isBlank()) return@withContext null
        try {
            withHorizonWriteLock(db, lockTimeoutMs) {
                var newPhraseUid: String? = null
                db.transaction {
                    val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: run {
                        logger.warn("ingestActorEvent: no User vertex for email=$userEmail — skipping")
                        return@transaction
                    }
                    val sourceVertex = findOrCreateSource(userVertex, sourceName, sourceType, metadata = "{}")
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
                    // Identity (eventId/contentHash/assignmentId/occurredAt) is set on the SAME edge,
                    // in the SAME transaction, as the evidence it identifies — see the interface doc.
                    sourceVertex.newEdge("ASSERTS", phraseVertex, false).apply {
                        set("context", sourceType)
                        set("timestamp", now)
                        set("scores", "[]")
                        set("cycleSeq", cycleSeq)
                        set("eventId", eventId)
                        set("contentHash", contentHash)
                        if (assignmentId != null) set("assignmentId", assignmentId)
                        set("occurredAt", occurredAt ?: now)
                        set("kindMetadata", kindMetadata)
                        save()
                    }
                    newPhraseUid = uid
                }
                newPhraseUid
            } ?: run {
                logger.warn("ingestActorEvent: could not acquire the Horizon write lock within ${lockTimeoutMs}ms for userEmail=$userEmail — rejecting")
                null
            }
        } catch (e: Exception) {
            logger.warn("ingestActorEvent failed for userEmail=$userEmail sourceName=$sourceName eventId=$eventId: ${e.message}")
            null
        }
    }

    override suspend fun recordActorEventPropagationStatus(
        userEmail: String,
        eventId: String,
        status: String,
        incompletePropagationTargets: List<RelevanceEdgeSummary>,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            withHorizonWriteLock(db, lockTimeoutMs) {
                var success = false
                db.transaction {
                    val sourceUids = HorizonOwnership.trustedSourceUids(db, userEmail)
                    if (sourceUids.isEmpty()) return@transaction
                    val sql = "SELECT FROM ASSERTS WHERE eventId = :eventId AND @out.uid IN :sourceUids LIMIT 1"
                    val edge = db.query("sql", sql, mapOf("eventId" to eventId, "sourceUids" to sourceUids)).use { rs ->
                        if (rs.hasNext()) rs.next().toElement().asEdge() else null
                    } ?: return@transaction
                    edge.modify().apply {
                        set("propagationStatus", status)
                        set("incompletePropagationTargets", json.encodeToString(incompletePropagationTargets))
                        save()
                    }
                    success = true
                }
                success
            } ?: run {
                logger.warn("recordActorEventPropagationStatus: could not acquire the Horizon write lock within ${lockTimeoutMs}ms for eventId=$eventId — rejecting")
                false
            }
        } catch (e: Exception) {
            logger.warn("recordActorEventPropagationStatus failed for userEmail=$userEmail eventId=$eventId: ${e.message}")
            false
        }
    }

    override suspend fun markAssertionStatus(
        userEmail: String,
        cycleSeq: Long,
        phraseUid: String,
        status: AssertionStatus,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            withHorizonWriteLock(db, lockTimeoutMs) {
                var success = false
                db.transaction {
                    val ownedAssertsEdges = HorizonOwnership.ownedAssertsEdges(db, userEmail, phraseUid)
                    if (ownedAssertsEdges.isEmpty()) return@transaction
                    val now = System.currentTimeMillis()
                    val stateValue = status.name.lowercase()
                    for (edge in ownedAssertsEdges) {
                        // Sequence-continuity guard: a status can never be marked for a cycle before
                        // the phrase's own original assertion — that would be evidence from before
                        // the claim existed. Skip (not corrupt) that edge rather than silently
                        // accepting it; other owned edges are still processed.
                        val originalCycleSeq = (edge.get("cycleSeq") as? Number)?.toLong()
                        if (originalCycleSeq != null && cycleSeq < originalCycleSeq) {
                            logger.warn(
                                "markAssertionStatus: cycleSeq=$cycleSeq precedes phrase's own original " +
                                    "assertion cycleSeq=$originalCycleSeq for phraseUid=$phraseUid — skipping",
                            )
                            continue
                        }
                        val mutableEdge = edge.modify()
                        // Durable audit trail — every transition is kept forever, not just the most
                        // recent N. Nothing reads this back through HorizonAssembler today (it is
                        // not part of ContextHorizon); if a future read path ever surfaces it, that
                        // path applies its own bound at read time, the same way HorizonAssembler
                        // already bounds every other inlined field — the durable graph record is
                        // never the place to lose history to a serialization-size concern.
                        val history = parseStatusHistory(mutableEdge.get("statusHistory") as? String)
                        val updated = history + StatusHistoryEntry(stateValue, now)
                        mutableEdge.set("status", stateValue)
                        mutableEdge.set("statusHistory", json.encodeToString(updated))
                        // statusCycleSeq tracks the MOST RECENT status change — deliberately distinct
                        // from `cycleSeq` (the phrase's original assertion, set once at creation and
                        // never touched here). Conflating the two previously made a phrase whose
                        // status changed in a later cycle misclassify as JustAsserted in that later
                        // cycle, even though its content wasn't newly stated then.
                        mutableEdge.set("statusCycleSeq", cycleSeq)
                        mutableEdge.save()
                        success = true
                    }
                }
                success
            } ?: run {
                logger.warn("markAssertionStatus: could not acquire the Horizon write lock within ${lockTimeoutMs}ms for phraseUid=$phraseUid — rejecting")
                false
            }
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
            withHorizonWriteLock(db, lockTimeoutMs) {
                var success = false
                db.transaction {
                    val from = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, fromPhraseUid) ?: return@transaction
                    val to = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, toPhraseUid) ?: return@transaction
                    val toUid = to.get("uid")
                    val alreadyExists = from.getEdges(Vertex.DIRECTION.OUT, "RELATED_TO").any {
                        it.get("relationType") as? String == "relevant_to" &&
                            it.getVertex(Vertex.DIRECTION.IN)?.get("uid") == toUid
                    }
                    if (alreadyExists) {
                        success = true
                        return@transaction
                    }
                    RelatedToEdges.createRelevantTo(from.modify(), to.modify(), strength, cycleSeq, System.currentTimeMillis(), ownerEmail = userEmail)
                    success = true
                }
                success
            } ?: run {
                logger.warn("markRelevant: could not acquire the Horizon write lock within ${lockTimeoutMs}ms for from=$fromPhraseUid to=$toPhraseUid — rejecting")
                false
            }
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
            withHorizonWriteLock(db, lockTimeoutMs) {
                var success = false
                db.transaction {
                    val newer = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, newerPhraseUid) ?: return@transaction
                    val older = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, olderPhraseUid) ?: return@transaction
                    RelatedToEdges.createSupersedes(newer.modify(), older.modify(), cycleSeq, System.currentTimeMillis(), ownerEmail = userEmail)
                    success = true
                }
                success
            } ?: run {
                logger.warn("markSuperseded: could not acquire the Horizon write lock within ${lockTimeoutMs}ms for newer=$newerPhraseUid older=$olderPhraseUid — rejecting")
                false
            }
        } catch (e: Exception) {
            logger.warn("markSuperseded failed for userEmail=$userEmail newer=$newerPhraseUid older=$olderPhraseUid: ${e.message}")
            false
        }
    }

    override suspend fun stampNewAssertion(
        userEmail: String,
        cycleSeq: Long,
        phraseUid: String,
        status: AssertionStatus?,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            withHorizonWriteLock(db, lockTimeoutMs) {
                var success = false
                db.transaction {
                    val ownedAssertsEdges = HorizonOwnership.ownedAssertsEdges(db, userEmail, phraseUid)
                    if (ownedAssertsEdges.isEmpty()) return@transaction
                    val now = System.currentTimeMillis()
                    for (edge in ownedAssertsEdges) {
                        val existingCycleSeq = (edge.get("cycleSeq") as? Number)?.toLong()
                        if (existingCycleSeq != null && existingCycleSeq != cycleSeq) {
                            // Already stamped under a different cycle — this method is only for the
                            // initial stamp (or an idempotent resume with the SAME cycleSeq); a
                            // mismatch means the caller is trying to re-establish identity that
                            // already exists. markAssertionStatus is for a genuine later transition.
                            logger.warn(
                                "stampNewAssertion: phraseUid=$phraseUid already stamped with " +
                                    "cycleSeq=$existingCycleSeq, cannot re-stamp with cycleSeq=$cycleSeq — skipping",
                            )
                            continue
                        }
                        val mutableEdge = edge.modify()
                        if (existingCycleSeq == null) {
                            mutableEdge.set("cycleSeq", cycleSeq)
                        }
                        if (status != null) {
                            val stateValue = status.name.lowercase()
                            val currentStatus = edge.get("status") as? String
                            val currentStatusCycleSeq = (edge.get("statusCycleSeq") as? Number)?.toLong()
                            // Idempotent under a resumed attempt: only append a new history entry
                            // if this exact (state, cycle) isn't already the recorded current one.
                            if (currentStatus != stateValue || currentStatusCycleSeq != cycleSeq) {
                                val history = parseStatusHistory(mutableEdge.get("statusHistory") as? String)
                                val updated = history + StatusHistoryEntry(stateValue, now)
                                mutableEdge.set("status", stateValue)
                                mutableEdge.set("statusHistory", json.encodeToString(updated))
                                mutableEdge.set("statusCycleSeq", cycleSeq)
                            }
                        }
                        mutableEdge.save()
                        success = true
                    }
                }
                success
            } ?: run {
                logger.warn("stampNewAssertion: could not acquire the Horizon write lock within ${lockTimeoutMs}ms for phraseUid=$phraseUid — rejecting")
                false
            }
        } catch (e: Exception) {
            logger.warn("stampNewAssertion failed for userEmail=$userEmail phraseUid=$phraseUid: ${e.message}")
            false
        }
    }

    override suspend fun phraseText(userEmail: String, phraseUid: String): String? = withContext(Dispatchers.IO) {
        try {
            HorizonOwnership.findPhraseOwnedByUser(db, userEmail, phraseUid)?.get("text") as? String
        } catch (e: Exception) {
            logger.warn("phraseText failed for userEmail=$userEmail phraseUid=$phraseUid: ${e.message}")
            null
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Reuse is scoped to a Source this *specific* [userVertex] already trusts, matched on both
     * [sourceName] and [sourceType] — never a global name lookup. A global lookup
     * (the prior implementation) would hand two different users the same Source vertex whenever
     * they happened to use the same [sourceName]: the second user's phrase would be asserted from a
     * Source the *first* user trusts (and the second user does not, since the early-return skipped
     * creating their own `TRUSTS` edge), making the second user's "private" event invisible to
     * themselves and visible to the first user's [HorizonAssembler.assemble] — a real cross-user
     * leak, not merely a naming collision. Traversal is bounded by this user's own `TRUSTS`
     * out-degree, which is small (see [HorizonOwnership.trustedSourceUids]).
     */
    private fun findOrCreateSource(userVertex: Vertex, sourceName: String, sourceType: String, metadata: String): Vertex {
        val existing = userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS")
            .firstOrNull { it.get("name") == sourceName && it.get("type") == sourceType }
            ?.modify()
        if (existing != null) return existing
        val sourceVertex = db.newVertex("Source").apply {
            set("uid", UUID.randomUUID().toString())
            set("name", sourceName)
            set("type", sourceType)
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

    private fun parseRelevanceEdgeSummaries(json: String?): List<RelevanceEdgeSummary> = try {
        if (json.isNullOrBlank()) emptyList() else this.json.decodeFromString(json)
    } catch (_: Exception) {
        emptyList()
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.trim().lowercase().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
