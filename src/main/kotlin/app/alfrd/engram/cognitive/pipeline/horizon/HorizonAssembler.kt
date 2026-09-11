package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * The result of [HorizonAssembler.assemble] — success carries the snapshot; every failure variant
 * is explicit and distinguishable both from each other and from "the user has no data yet". A
 * caller must not treat any failure variant as an empty [ContextHorizon] — no snapshot was read.
 */
sealed interface AssembleOutcome {
    data class Assembled(val horizon: ContextHorizon) : AssembleOutcome
    /** The [HorizonConsistencyLock] read lock could not be acquired within the bounded timeout — no snapshot was read, consistent or otherwise. */
    data class ConsistencyFailure(val reason: String) : AssembleOutcome
    /** A database/query error occurred while reading — distinguishable from a legitimately empty graph, which is [Assembled] with an empty item list, never this. */
    data class QueryFailure(val reason: String) : AssembleOutcome
    /** Even the minimum valid representation (zero items) of this user's Horizon exceeds [HorizonLimits.serializedByteBudget] — no snapshot, mixed or otherwise, is returned. */
    data class BudgetExceeded(val reason: String) : AssembleOutcome
}

/**
 * Read-side interface through which a future response-context assembly step consumes the Context
 * Horizon. Standalone and unwired — no [app.alfrd.engram.cognitive.pipeline.CognitivePipeline] call
 * site exists yet; propagation judgment is out of scope, every `relevant_to` edge assembly finds
 * was asserted directly by a caller (test or future propagation), never derived here.
 */
interface HorizonAssembler {

    /**
     * Assembles a bounded [ContextHorizon] for [userEmail] as of [currentCycleSeq] — the cycle
     * being processed right now. There is no default: identity is never inferred from clock time
     * (see [SurfacingReason]). [activeRelevanceCycles] is the primary decay mechanism for
     * [SurfacingReason.ActiveReactivation] (cycle-count based, correct at any turn spacing);
     * [activeRelevanceWallClock] is an optional, additional real-time cap, unused unless supplied.
     *
     * A [HorizonGraphStore] mutation this same caller has already `await`ed is guaranteed visible
     * (ordinary single-process ACID read-after-write) — an unrelated, still-in-flight
     * fire-and-forget writer (e.g. `MemoryWriteService.captureUtterance`) is not covered.
     *
     * **Consistency is enforced, not merely observed — and only for the writers named below, never
     * database-wide snapshot isolation.** [ArcadeHorizonAssembler] acquires
     * [HorizonConsistencyLock]'s read lock for the entire duration of its internal queries,
     * excluding every [HorizonGraphStore] writer (the complete, explicit list is in that lock's own
     * doc) for that window — it says nothing about, and provides no protection against, a write
     * through any other path (e.g. the pre-existing conversational `DatabaseEngramClient` writers,
     * which do not acquire this lock). See that lock's doc for the concrete evidence (a reproduced
     * deadlock) that ruled out an ArcadeDB-native lock/isolation level in favor of this cooperative
     * one. If the lock cannot be acquired within the bounded timeout (a single `tryLock`, never a
     * retry loop), this returns [AssembleOutcome.ConsistencyFailure] rather than a possibly-mixed
     * snapshot — no read is taken at all in that case, so there is nothing to be inconsistent.
     *
     * **A database/query error is a distinct failure, not a silently-empty result.** An exception
     * while reading (after the lock is held) returns [AssembleOutcome.QueryFailure] — a caller must
     * not confuse this with [AssembleOutcome.Assembled] carrying a genuinely empty [ContextHorizon]
     * (a user with no data yet), which is not an error at all.
     *
     * **The serialized-output byte budget is enforced here, not merely documented.** Before
     * returning [AssembleOutcome.Assembled], the real `kotlinx.serialization` JSON encoding of the
     * complete result (every field, not just item text) is measured and checked against
     * [HorizonLimits.serializedByteBudget] for this call's own `budget.maxItems` (not a fixed
     * default). If the initially selected items don't fit, the lowest-priority ones are dropped —
     * preserving their evidence reference via `omittedSample`/`omittedAtLeast`, never silently
     * discarding them — until the payload fits. If even zero items doesn't fit,
     * [AssembleOutcome.BudgetExceeded] is returned rather than an oversized snapshot.
     *
     * **Future-cycle evidence is excluded, not misclassified.** Per [HorizonGraphStore]'s
     * sequence-continuity contract, [currentCycleSeq] is assumed to be at or after every `cycleSeq`
     * already committed for [userEmail]. Any candidate whose original assertion or most recent
     * status change is nonetheless ahead of [currentCycleSeq] (a caller sequencing bug, or a write
     * that bypassed [HorizonGraphStore]) is dropped from the snapshot entirely, both by the
     * underlying query and defensively again after — never surfaced as `JustAsserted` or any other
     * classification it would otherwise get by falling through.
     *
     * **Reactivation candidacy is independently bounded, not restricted to the open/fresh pools.**
     * A `relevant_to` edge can target a phrase that never appears in either pool below (e.g. a very
     * old open item ranked below those pools' own `LIMIT`, or a resolved item being reactivated);
     * such a target is still found and admitted as a candidate, via its own independently bounded
     * query — see [ArcadeHorizonAssembler.queryActiveReactivations].
     */
    suspend fun assemble(
        userEmail: String,
        currentCycleSeq: Long,
        activeRelevanceCycles: Int = HorizonLimits.DEFAULT_RELEVANCE_CYCLES,
        activeRelevanceWallClock: Duration? = null,
        budget: HorizonBudget = HorizonBudget.DEFAULT,
    ): AssembleOutcome

    /**
     * Full, explicitly paginated enumeration beyond what [assemble] inlines — walk with [cursor]
     * (from a prior page's [CandidatePage.nextCursor]) until it comes back null. No single call
     * returns an unbounded list.
     */
    suspend fun listCandidates(
        userEmail: String,
        category: HorizonItemCategory? = null,
        status: AssertionStatus? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): CandidatePage

    /**
     * Every `status=open` candidate for [userEmail], with text, bounded by [limit] —
     * **independent of [assemble]'s own response-prompt budget** ([HorizonBudget.maxItems],
     * default 12). Propagation needs to consider a user's full (bounded) set of open intentions,
     * not just whichever ones happened to survive [assemble]'s trimming for the last response —
     * an intention ranked below that budget is still a legitimate reactivation target. Not
     * lock-protected (same precedent as [listCandidates]): a bounded enumeration, not the
     * consistency-critical read [assemble] is.
     */
    suspend fun listOpenCandidatesWithText(userEmail: String, limit: Int = 200): List<PropagationCandidate>
}

class ArcadeHorizonAssembler(
    private val db: Database,
    private val lockTimeoutMs: Long = HorizonConsistencyLock.DEFAULT_TIMEOUT_MS,
) : HorizonAssembler {

    private val logger = LoggerFactory.getLogger(ArcadeHorizonAssembler::class.java)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Test-only synchronization point, invoked once per [assemble] call inside the read
     * transaction, strictly between fetching the candidate pools and fetching reactivations.
     * No-op (`null`) in production and never set by any production code path. Exists so a test can
     * deterministically land a real, separately-committed concurrent write at that exact point and
     * observe — rather than assume — what isolation the wrapping transaction actually provides.
     * See `HorizonAssemblerTest`'s concurrency test for the empirical result this documents.
     */
    internal var testMidAssemblySync: (() -> Unit)? = null

    /**
     * Test-only override for the serialized-output byte budget [assemble] enforces — `null`
     * (default, and the only value any production code path sets) uses
     * [HorizonLimits.serializedByteBudget] scaled to the call's own `budget.maxItems`. Lets a test
     * force real trimming, or the even-zero-items-doesn't-fit path, deterministically and cheaply —
     * against a real `kotlinx.serialization`-encoded, real-byte-measured payload exactly as
     * production does — rather than needing to seed tens of kilobytes of content to organically
     * exceed the (deliberately generous) default ceiling.
     */
    internal var testByteBudgetOverride: Int? = null

    private data class RawCandidate(
        val phraseUid: String,
        val phraseText: String,
        val sourceUid: String,
        val sourceType: String,
        val assertedAt: Long,
        val cycleSeq: Long,           // ORIGINAL assertion cycle — never touched by a status change
        val statusCycleSeq: Long?,    // cycle of the most recent status change, if any — distinct from cycleSeq
        val status: String?,
        val kindMetadata: String?,    // raw ActorEventMetadata JSON, set only on Actor-attributed edges — see parseActorMetadata
    )

    override suspend fun assemble(
        userEmail: String,
        currentCycleSeq: Long,
        activeRelevanceCycles: Int,
        activeRelevanceWallClock: Duration?,
        budget: HorizonBudget,
    ): AssembleOutcome = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val empty = ContextHorizon(
            userEmail = userEmail,
            asOf = now,
            schemaVersion = HorizonLimits.SCHEMA_VERSION,
            items = emptyList(),
            budget = budget.copy(itemCount = 0, truncated = false),
            omittedSample = emptyList(),
            omittedAtLeast = 0,
            moreCandidatesAvailable = false,
        )
        // Computed up front — a pure function of budget.maxItems, no DB access needed — so it's
        // available both inside the locked/transactional section below and in the BudgetExceeded
        // message if that section signals it exceeded even at zero items.
        val byteBudget = testByteBudgetOverride ?: HorizonLimits.serializedByteBudget(budget.maxItems)
        var budgetExceeded = false
        try {
            // Held for the entire read, excluding every HorizonGraphStore writer for that window —
            // see HorizonConsistencyLock for which writers this covers (and, just as importantly,
            // which it does not) and why a hand-rolled lock rather than an ArcadeDB-native
            // isolation level/lock. A lock-acquisition timeout is an explicit
            // AssembleOutcome.ConsistencyFailure below, never a silently-returned possibly-mixed
            // snapshot. Any exception raised inside this block (including from db.transaction, which
            // rethrows after rolling back) propagates out of withHorizonReadLock uncaught — the
            // outer catch below turns it into an explicit AssembleOutcome.QueryFailure, never a
            // silently-empty result indistinguishable from a user with no data yet.
            val horizonOrNull: ContextHorizon? = withHorizonReadLock(db, lockTimeoutMs) {
                var result = empty
                db.transaction {
                    val userVertex = HorizonOwnership.findUserVertex(db, userEmail)
                    val sourceUids = userVertex?.let { trustedSourceUids(it) } ?: emptyList()

                    // A missing user or a user with no trusted sources both fall through with an
                    // empty candidate list rather than an early `return@transaction` — every path,
                    // populated or not, must reach the SAME byte-budget enforcement below. An early
                    // return here used to skip that enforcement entirely, so a budget too small even
                    // for a genuinely empty ContextHorizon would silently succeed as Assembled for
                    // these two paths while correctly failing for a populated one — inconsistent
                    // behavior depending only on whether the user happened to have data.
                    var moreCandidatesAvailable = false
                    val prioritized: List<HorizonItem>
                    if (sourceUids.isEmpty()) {
                        prioritized = emptyList()
                    } else {
                        // Bounded over-fetch pools — cost is a function of poolLimit and sourceUids
                        // (small, one per source type per user), not total graph size. The
                        // open-status branch is confirmed (by direct execution-plan inspection, not
                        // timing alone — see debugOpenPoolExecutionSteps and its test) to use the
                        // ASSERTS[status] index rather than a full type scan.
                        val poolLimit = budget.maxItems * HorizonLimits.CANDIDATE_POOL_OVERFETCH
                        val openPool = queryAsserts(sourceUids, statusFilter = "open", cycleSeqFilter = null, currentCycleSeq = currentCycleSeq, limit = poolLimit)
                        val freshPool = queryAsserts(sourceUids, statusFilter = null, cycleSeqFilter = currentCycleSeq, currentCycleSeq = currentCycleSeq, limit = poolLimit)
                        moreCandidatesAvailable = openPool.size >= poolLimit || freshPool.size >= poolLimit

                        // Bounded-recency Actor-attributed evidence pool — see
                        // SurfacingReason.RecentActorEvidence. Independent of open/fresh: an event
                        // written at an earlier cycle would otherwise vanish from the Horizon the
                        // moment its own cycle passes (it is neither open-status nor this cycle's
                        // content), which is exactly the gap this pool closes. Same minCycle window
                        // activeReactivation already uses — computed once below and reused, not a
                        // second independent knob.
                        val minCycleForActorPool = currentCycleSeq - activeRelevanceCycles
                        val actorEvidencePool = queryAttributedEvidencePool(sourceUids, minCycleForActorPool, currentCycleSeq, poolLimit)
                        moreCandidatesAvailable = moreCandidatesAvailable || actorEvidencePool.size >= poolLimit

                        val byUid = LinkedHashMap<String, RawCandidate>()
                        for (c in openPool) byUid[c.phraseUid] = c
                        for (c in freshPool) byUid.putIfAbsent(c.phraseUid, c)
                        for (c in actorEvidencePool) byUid.putIfAbsent(c.phraseUid, c)

                        testMidAssemblySync?.invoke()

                        // Independently bounded active-relevance candidate path (see
                        // queryActiveReactivations doc): a fresh relevant_to edge can target a
                        // phrase that never made it into either pool above (e.g. a very old open
                        // item ranked below poolLimit, or a resolved item being reactivated) — such
                        // a target is still admitted as a candidate here, not silently missed.
                        val minCycle = minCycleForActorPool
                        val reactivationResult = queryActiveReactivations(userEmail, minCycle, currentCycleSeq, now, activeRelevanceWallClock, poolLimit)
                        if (reactivationResult.hitLimit) moreCandidatesAvailable = true
                        for (targetUid in reactivationResult.infoByTargetUid.keys) {
                            if (byUid.containsKey(targetUid)) continue
                            val extra = queryAssertsForPhrase(sourceUids, targetUid) ?: continue
                            byUid[targetUid] = extra
                        }

                        // Defense in depth alongside the SQL-level exclusion in queryAsserts: any
                        // candidate whose original assertion or last status change is somehow ahead
                        // of the cycle being assembled (a caller sequencing bug, or a write that
                        // bypassed HorizonGraphStore) is dropped here too, rather than being
                        // misclassified as JustAsserted/DormantOpen. Runs after the reactivation
                        // merge so an independently-fetched candidate gets the same guard.
                        byUid.entries.removeAll { (_, c) ->
                            c.cycleSeq > currentCycleSeq || (c.statusCycleSeq != null && c.statusCycleSeq > currentCycleSeq)
                        }

                        val classified = byUid.values.map { c -> toHorizonItem(c, currentCycleSeq, reactivationResult.infoByTargetUid[c.phraseUid]) }
                        prioritized = classified.sortedWith(
                            compareBy<HorizonItem> { priorityRank(it.surfacing) }.thenByDescending { surfacingCycleSeq(it.surfacing) },
                        )
                    }

                    // Enforce the actual serialized-output byte budget, not just item count —
                    // measured against the real kotlinx.serialization JSON encoding of the COMPLETE
                    // structure (metadata included), scaled to this call's own budget.maxItems. Runs
                    // unconditionally for every path above, populated or empty. Items are dropped
                    // from the tail (lowest priority first) and moved into
                    // omittedSample/omittedAtLeast — never silently discarded — until the encoded
                    // payload fits. If even zero items doesn't fit, budgetExceeded signals the outer
                    // scope to return AssembleOutcome.BudgetExceeded instead of an oversized result.
                    var keptCount = minOf(budget.maxItems, prioritized.size)
                    var built = buildHorizon(userEmail, now, budget, prioritized, keptCount, moreCandidatesAvailable)
                    while (encodedByteSize(built) > byteBudget && keptCount > 0) {
                        keptCount--
                        built = buildHorizon(userEmail, now, budget, prioritized, keptCount, moreCandidatesAvailable)
                    }
                    if (encodedByteSize(built) > byteBudget) {
                        budgetExceeded = true
                        return@transaction
                    }
                    result = built
                }
                result
            }
            when {
                budgetExceeded -> AssembleOutcome.BudgetExceeded(
                    "the complete Horizon for userEmail=$userEmail exceeds its $byteBudget-byte serialized budget even at zero items",
                )
                horizonOrNull != null -> AssembleOutcome.Assembled(horizonOrNull)
                else -> {
                    logger.warn("assemble: could not acquire the Horizon read lock within ${lockTimeoutMs}ms for userEmail=$userEmail")
                    AssembleOutcome.ConsistencyFailure("could not acquire the Horizon read lock within ${lockTimeoutMs}ms — a writer may be holding it")
                }
            }
        } catch (e: Exception) {
            logger.warn("assemble failed for userEmail=$userEmail: ${e.message}", e)
            AssembleOutcome.QueryFailure("assemble failed for userEmail=$userEmail: ${e.message}")
        }
    }

    /** Builds the candidate result for [keptCount] kept items, moving the rest into omittedSample/omittedAtLeast — the single place both the item-count budget and the byte-budget enforcement loop construct a result, so evidence bookkeeping can't drift between the two reasons for dropping an item. */
    private fun buildHorizon(
        userEmail: String, now: Long, budget: HorizonBudget, prioritized: List<HorizonItem>, keptCount: Int, moreCandidatesAvailable: Boolean,
    ): ContextHorizon {
        val kept = prioritized.take(keptCount)
        val overflow = prioritized.drop(keptCount)
        val omittedSample = overflow.take(HorizonLimits.OMITTED_SAMPLE_CAP).map { item ->
            OmittedRef(item.sourceRefs.first(), item.category, "budget exceeded: ${describeSurfacing(item.surfacing)}")
        }
        return ContextHorizon(
            userEmail = userEmail,
            asOf = now,
            schemaVersion = HorizonLimits.SCHEMA_VERSION,
            items = kept,
            budget = budget.copy(itemCount = kept.size, truncated = overflow.isNotEmpty()),
            omittedSample = omittedSample,
            omittedAtLeast = overflow.size,
            moreCandidatesAvailable = moreCandidatesAvailable,
        )
    }

    /** The real encoded byte size [assemble] enforces its budget against — not a hand-derived estimate. */
    private fun encodedByteSize(horizon: ContextHorizon): Int = json.encodeToString(horizon).toByteArray(Charsets.UTF_8).size

    override suspend fun listCandidates(
        userEmail: String,
        category: HorizonItemCategory?,
        status: AssertionStatus?,
        limit: Int,
        cursor: String?,
    ): CandidatePage = withContext(Dispatchers.IO) {
        try {
            val userVertex = HorizonOwnership.findUserVertex(db, userEmail)
                ?: return@withContext CandidatePage(emptyList(), null)
            val sourceUids = trustedSourceUids(userVertex)
            if (sourceUids.isEmpty()) return@withContext CandidatePage(emptyList(), null)

            val offset = cursor?.toIntOrNull() ?: 0
            val conditions = mutableListOf("@out.uid IN :sourceUids")
            val params = mutableMapOf<String, Any>("sourceUids" to sourceUids, "offset" to offset, "limit" to limit)
            if (status != null) {
                conditions += "status = :status"
                params["status"] = status.name.lowercase()
            }
            // Category is a secondary axis derived from source type + status presence (see
            // categoryFor) rather than a stored column, so it is applied client-side after the
            // server-side SKIP/LIMIT page — a page can come back shorter than `limit`, or even
            // empty, when a category filter is also given. Continuation is therefore decided by
            // whether the RAW (pre-filter) page was full — see rawCount below — never by the
            // filtered row count: a page whose raw rows all happen to fail the category filter
            // must still continue to the next page, not be mistaken for "no more data".
            // `phraseUid` breaks ties on `cycleSeq` so SKIP/LIMIT paging is over a single
            // deterministic total order — without a tiebreaker, ArcadeDB's ordering among rows that
            // share a cycleSeq is not guaranteed stable across separate paged queries, which could
            // silently skip or repeat a row at a page boundary.
            val sql = """
                SELECT @out.uid as sourceUid, @out.type as sourceType, @in.uid as phraseUid, timestamp, cycleSeq, status
                FROM ASSERTS
                WHERE ${conditions.joinToString(" AND ")}
                ORDER BY cycleSeq DESC, phraseUid ASC
                SKIP :offset LIMIT :limit
            """.trimIndent()
            var rawCount = 0
            val rows = db.query("sql", sql, params).use { rs ->
                val out = mutableListOf<SourceRef>()
                while (rs.hasNext()) {
                    val row = rs.next().toMap()
                    rawCount++
                    val sourceType = row["sourceType"] as? String ?: ""
                    val rowStatus = row["status"] as? String
                    if (category != null && categoryFor(sourceType, rowStatus) != category) continue
                    out += SourceRef(
                        phraseUid = row["phraseUid"] as? String ?: continue,
                        sourceUid = row["sourceUid"] as? String ?: "",
                        sourceType = sourceType,
                        assertedAt = (row["timestamp"] as? Number)?.toLong() ?: 0L,
                        cycleSeq = (row["cycleSeq"] as? Number)?.toLong() ?: 0L,
                    )
                }
                out
            }
            val nextCursor = if (rawCount == limit) (offset + limit).toString() else null
            CandidatePage(rows, nextCursor)
        } catch (e: Exception) {
            logger.warn("listCandidates failed for userEmail=$userEmail: ${e.message}")
            CandidatePage(emptyList(), null)
        }
    }

    override suspend fun listOpenCandidatesWithText(userEmail: String, limit: Int): List<PropagationCandidate> = withContext(Dispatchers.IO) {
        try {
            val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@withContext emptyList()
            val sourceUids = trustedSourceUids(userVertex)
            if (sourceUids.isEmpty()) return@withContext emptyList()
            val sql = """
                SELECT @in.uid as phraseUid, @in.text as phraseText, cycleSeq
                FROM ASSERTS
                WHERE @out.uid IN :sourceUids AND status = 'open'
                ORDER BY statusCycleSeq DESC
                LIMIT :limit
            """.trimIndent()
            db.query("sql", sql, mapOf("sourceUids" to sourceUids, "limit" to limit)).use { rs ->
                val out = mutableListOf<PropagationCandidate>()
                while (rs.hasNext()) {
                    val row = rs.next().toMap()
                    out += PropagationCandidate(
                        phraseUid = row["phraseUid"] as? String ?: continue,
                        text = row["phraseText"] as? String ?: "",
                        cycleSeq = (row["cycleSeq"] as? Number)?.toLong() ?: 0L,
                    )
                }
                out
            }
        } catch (e: Exception) {
            logger.warn("listOpenCandidatesWithText failed for userEmail=$userEmail: ${e.message}")
            emptyList()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun trustedSourceUids(userVertex: Vertex): List<String> =
        userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS").mapNotNull { it.get("uid") as? String }

    /** SQL text + bound params for the `ASSERTS` candidate-pool query — extracted so a test can obtain the exact production query's execution plan without duplicating the SQL. */
    private data class AssertsQuery(val sql: String, val params: Map<String, Any>)

    /**
     * [currentCycleSeq] is always required and bounds every branch — future evidence (whose
     * `cycleSeq` for the fresh-pool branch, or `statusCycleSeq` for the open-status branch, is
     * ahead of the cycle being assembled) is excluded at the query level. [toHorizonItem] repeats
     * this exclusion defensively so a caller bug elsewhere can't smuggle future evidence through.
     */
    private fun buildAssertsQuery(
        sourceUids: List<String>, statusFilter: String?, cycleSeqFilter: Long?, currentCycleSeq: Long, limit: Int,
    ): AssertsQuery {
        val conditions = mutableListOf("@out.uid IN :sourceUids")
        val params = mutableMapOf<String, Any>("sourceUids" to sourceUids, "limit" to limit, "currentCycleSeq" to currentCycleSeq)
        val orderBy: String
        if (statusFilter != null) {
            // Open-status pool: ordered and bounded by the RECENCY OF THE STATUS ITSELF
            // (statusCycleSeq), not the phrase's original assertion — an item reopened recently
            // must sort ahead of one that has simply been open longer. Distinct from cycleSeq per
            // the class doc; conflating them previously ordered by the wrong recency signal.
            conditions += "status = :status"
            conditions += "statusCycleSeq <= :currentCycleSeq"
            params["status"] = statusFilter
            orderBy = "ORDER BY statusCycleSeq DESC"
        } else {
            orderBy = ""
        }
        if (cycleSeqFilter != null) {
            // Fresh pool: exact match already bounds this to currentCycleSeq itself — no separate
            // future-exclusion needed, an equality filter can't select a future value.
            conditions += "cycleSeq = :cycleSeq"
            params["cycleSeq"] = cycleSeqFilter
        }
        val sql = """
            SELECT @out.uid as sourceUid, @out.type as sourceType, @in.uid as phraseUid, @in.text as phraseText,
                   timestamp, cycleSeq, statusCycleSeq, status, kindMetadata
            FROM ASSERTS
            WHERE ${conditions.joinToString(" AND ")}
            $orderBy
            LIMIT :limit
        """.trimIndent()
        return AssertsQuery(sql, params)
    }

    /**
     * Test-only: runs the exact SQL/params [queryAsserts] would for the open-status pool, and
     * returns the flattened list of `getType()`/`getName()` from the resulting
     * [com.arcadedb.query.sql.executor.ExecutionPlan] (walked recursively through
     * `getSubSteps()`). Lets a test assert on real query-plan evidence — e.g. a
     * `FetchFromIndexStep` — instead of inferring index use from timing alone.
     */
    internal fun debugOpenPoolExecutionSteps(sourceUids: List<String>, currentCycleSeq: Long, limit: Int): List<String> {
        val query = buildAssertsQuery(sourceUids, statusFilter = "open", cycleSeqFilter = null, currentCycleSeq = currentCycleSeq, limit = limit)
        return db.query("sql", query.sql, query.params).use { rs ->
            while (rs.hasNext()) rs.next() // the plan is only fully populated once the result is produced
            val plan = rs.getExecutionPlan().orElse(null) ?: return@use listOf("<no execution plan available>")
            val steps = mutableListOf<String>()
            fun walk(step: com.arcadedb.query.sql.executor.ExecutionStep) {
                steps += "${step.getType()}: ${step.getDescription()}"
                step.subSteps.forEach { walk(it) }
            }
            plan.steps.forEach { walk(it) }
            steps
        }
    }

    private fun rowToRawCandidate(row: Map<String, Any?>): RawCandidate? {
        return RawCandidate(
            phraseUid = row["phraseUid"] as? String ?: return null,
            phraseText = row["phraseText"] as? String ?: "",
            sourceUid = row["sourceUid"] as? String ?: "",
            sourceType = row["sourceType"] as? String ?: "",
            assertedAt = (row["timestamp"] as? Number)?.toLong() ?: 0L,
            cycleSeq = (row["cycleSeq"] as? Number)?.toLong() ?: 0L,
            statusCycleSeq = (row["statusCycleSeq"] as? Number)?.toLong(),
            status = row["status"] as? String,
            kindMetadata = row["kindMetadata"] as? String,
        )
    }

    private fun queryAsserts(
        sourceUids: List<String>, statusFilter: String?, cycleSeqFilter: Long?, currentCycleSeq: Long, limit: Int,
    ): List<RawCandidate> {
        if (sourceUids.isEmpty()) return emptyList()
        val query = buildAssertsQuery(sourceUids, statusFilter, cycleSeqFilter, currentCycleSeq, limit)
        return db.query("sql", query.sql, query.params).use { rs ->
            val out = mutableListOf<RawCandidate>()
            while (rs.hasNext()) out += rowToRawCandidate(rs.next().toMap()) ?: continue
            out
        }
    }

    /**
     * Bounded-recency Actor-attributed evidence — see [SurfacingReason.RecentActorEvidence].
     * Deliberately excludes [ENVIRONMENT_SOURCE_TYPE]: that path's own equivalent visibility gap is
     * a named follow-up, not addressed by widening this pool to it.
     *
     * Resolves which of [sourceUids] are Actor-attributed *first*, via a plain `Source` lookup
     * ([actorAttributedSourceUidsAmong]), then issues one bounded equality query per cycle in
     * `[minCycle, currentCycleSeq]` — the exact same `@out.uid IN ... AND cycleSeq = :cycleSeq LIMIT
     * :limit` shape [buildAssertsQuery]'s fresh-pool branch already uses successfully, never
     * `ORDER BY cycleSeq` over a range. An earlier version filtered the whole window with
     * `cycleSeq >= :minCycle AND cycleSeq <= :currentCycleSeq ORDER BY cycleSeq DESC LIMIT :limit`
     * in one query; against real ArcadeDB 25.1.1 data with many rows sharing one `cycleSeq` value,
     * that produced **duplicate rows and silently dropped others** past the `LIMIT` — confirmed
     * empirically (a 13-row fixture came back as 36 rows spanning only 8 distinct phrases), not
     * merely suspected, and confirmed to be specifically the indexed `ORDER BY` (a `LIMIT`-only
     * query with the identical `WHERE` was correct). [activeRelevanceCycles] is already the caller's
     * own bound on how many such queries this issues — small by design (default 3), never
     * proportional to total graph size.
     */
    private fun queryAttributedEvidencePool(sourceUids: List<String>, minCycle: Long, currentCycleSeq: Long, limit: Int): List<RawCandidate> {
        if (sourceUids.isEmpty()) return emptyList()
        val actorSourceUids = actorAttributedSourceUidsAmong(sourceUids)
        if (actorSourceUids.isEmpty()) return emptyList()
        val out = mutableListOf<RawCandidate>()
        var cycle = currentCycleSeq
        while (cycle >= minCycle && out.size < limit) {
            out += queryAssertsExactCycle(actorSourceUids, cycle, limit - out.size)
            cycle--
        }
        return out
    }

    /** One cycle's worth of `ASSERTS` rows for [sourceUids] — equality on `cycleSeq`, never a range, and never `ORDER BY` over it. See [queryAttributedEvidencePool]'s doc for why. */
    private fun queryAssertsExactCycle(sourceUids: List<String>, cycleSeq: Long, limit: Int): List<RawCandidate> {
        if (limit <= 0) return emptyList()
        val sql = """
            SELECT @out.uid as sourceUid, @out.type as sourceType, @in.uid as phraseUid, @in.text as phraseText,
                   timestamp, cycleSeq, statusCycleSeq, status, kindMetadata
            FROM ASSERTS
            WHERE @out.uid IN :sourceUids AND cycleSeq = :cycleSeq
            LIMIT :limit
        """.trimIndent()
        val params = mapOf("sourceUids" to sourceUids, "cycleSeq" to cycleSeq, "limit" to limit)
        return db.query("sql", sql, params).use { rs ->
            val out = mutableListOf<RawCandidate>()
            while (rs.hasNext()) out += rowToRawCandidate(rs.next().toMap()) ?: continue
            out
        }
    }

    /** The subset of [sourceUids] whose `Source.type` is Actor-attributed — a plain vertex-type filter, no edge `@out`/`@in` projection involved. */
    private fun actorAttributedSourceUidsAmong(sourceUids: List<String>): List<String> {
        if (sourceUids.isEmpty()) return emptyList()
        val sql = "SELECT uid FROM Source WHERE uid IN :sourceUids AND type IN :actorSourceTypes"
        val params = mapOf("sourceUids" to sourceUids, "actorSourceTypes" to ACTOR_ATTRIBUTED_SOURCE_TYPES.toList())
        return db.query("sql", sql, params).use { rs ->
            val out = mutableListOf<String>()
            while (rs.hasNext()) out += rs.next().toMap()["uid"] as? String ?: continue
            out
        }
    }

    /** Fetches a single phrase's own `ASSERTS` record on demand, scoped to [sourceUids] — used to admit a reactivation target found by [queryActiveReactivations] that isn't already in the open/fresh pools. */
    private fun queryAssertsForPhrase(sourceUids: List<String>, phraseUid: String): RawCandidate? {
        if (sourceUids.isEmpty()) return null
        val sql = """
            SELECT @out.uid as sourceUid, @out.type as sourceType, @in.uid as phraseUid, @in.text as phraseText,
                   timestamp, cycleSeq, statusCycleSeq, status, kindMetadata
            FROM ASSERTS
            WHERE @in.uid = :phraseUid AND @out.uid IN :sourceUids
            LIMIT 1
        """.trimIndent()
        return db.query("sql", sql, mapOf("phraseUid" to phraseUid, "sourceUids" to sourceUids)).use { rs ->
            if (!rs.hasNext()) null else rowToRawCandidate(rs.next().toMap())
        }
    }

    private data class ReactivationQueryResult(val infoByTargetUid: Map<String, ReactivationInfo>, val hitLimit: Boolean)

    /**
     * Active `relevant_to` reactivations within `[minCycle, currentCycleSeq]` for [userEmail],
     * bounded by [limit] — **independently** of [ArcadeHorizonAssembler.assemble]'s open/fresh
     * candidate pools, not restricted to phrases they already selected. A target ranked below those
     * pools' own `LIMIT` (e.g. a very old open item, or a resolved item being reactivated) would
     * otherwise never be found: restricting this lookup to an already-bounded candidate set (a prior
     * version of this method) silently missed exactly that case.
     *
     * **Scoped to [userEmail] before `LIMIT`, via an index, not after.** The query filters on
     * `RELATED_TO.ownerEmail` — stamped by [HorizonGraphStore]'s writers at edge-creation time from
     * the already-validated caller, and backed by the composite `(ownerEmail, relationType,
     * cycleSeq)` index (see `SchemaBootstrap`) — so `LIMIT` applies only to this user's own
     * candidate edges. An earlier version filtered by `cycleSeq` range alone (a per-user counter
     * with no cross-user namespacing) and applied `LIMIT` before any ownership check, so a busy
     * multi-tenant graph could let many *other* users' edges crowd this user's own out of the
     * window entirely, before their existence was ever checked. Scoping by an indexed `ownerEmail`
     * fixes that without pre-fetching this user's full historical phrase set (itself unbounded by
     * construction — see [HorizonGraphStore]'s documented "edges accumulating on a single
     * long-lived Source" risk).
     *
     * **The `ownerEmail` stamp is a scoping aid, never trusted on its own.** Both the trigger *and*
     * the target of each candidate edge are still independently re-verified as owned by [userEmail]
     * via [HorizonOwnership] before being trusted — defense in depth against a stamp that is stale,
     * wrong, or bypassed entirely by a writer that doesn't go through [HorizonGraphStore] (a bug or
     * bad migration); such an edge is refused here rather than leaking another user's phrase text or
     * reactivating another user's item, exactly as before this change. [hitLimit] mirrors
     * [ArcadeHorizonAssembler]'s other bounded pools: true if the raw (pre-ownership-filter) row
     * count hit [limit], signaling more candidates may exist beyond what was inspected at all.
     */
    private fun queryActiveReactivations(
        userEmail: String,
        minCycle: Long,
        currentCycleSeq: Long,
        nowMillis: Long,
        wallClockCap: Duration?,
        limit: Int,
    ): ReactivationQueryResult {
        val sql = """
            SELECT @out.uid as triggerUid, @out.text as triggerText, @in.uid as targetUid, strength, cycleSeq, createdAt
            FROM RELATED_TO
            WHERE ownerEmail = :ownerEmail AND relationType = 'relevant_to' AND cycleSeq >= :minCycle AND cycleSeq <= :currentCycleSeq
            ORDER BY cycleSeq DESC
            LIMIT :limit
        """.trimIndent()
        val rows = db.query(
            "sql", sql,
            mapOf("ownerEmail" to userEmail, "minCycle" to minCycle, "currentCycleSeq" to currentCycleSeq, "limit" to limit),
        ).use { rs ->
            val out = mutableListOf<Map<String, Any?>>()
            while (rs.hasNext()) out += rs.next().toMap()
            out
        }
        val result = LinkedHashMap<String, ReactivationInfo>()
        for (row in rows) {
            val targetUid = row["targetUid"] as? String ?: continue
            if (result.containsKey(targetUid)) continue // already have the most-recent (ORDER BY cycleSeq DESC)
            val triggerUid = row["triggerUid"] as? String ?: continue
            val createdAt = (row["createdAt"] as? Number)?.toLong() ?: 0L
            if (wallClockCap != null && (nowMillis - createdAt) > wallClockCap.toMillis()) continue
            if (HorizonOwnership.findPhraseOwnedByUser(db, userEmail, targetUid) == null) continue
            if (HorizonOwnership.findPhraseOwnedByUser(db, userEmail, triggerUid) == null) {
                logger.warn(
                    "queryActiveReactivations: relevant_to trigger=$triggerUid not owned by " +
                        "userEmail=$userEmail — refusing to surface for target=$targetUid",
                )
                continue
            }
            result[targetUid] = ReactivationInfo(
                triggeringPhraseUid = triggerUid,
                triggeringPhraseText = BoundedText.of(row["triggerText"] as? String ?: ""),
                strength = (row["strength"] as? Number)?.toDouble() ?: 0.0,
                edgeCycleSeq = (row["cycleSeq"] as? Number)?.toLong() ?: 0L,
                edgeCreatedAt = createdAt,
            )
        }
        return ReactivationQueryResult(result, hitLimit = rows.size >= limit)
    }

    private fun toHorizonItem(c: RawCandidate, currentCycleSeq: Long, reactivation: ReactivationInfo?): HorizonItem {
        // JustAsserted is about the phrase's CONTENT being newly stated — the original assertion
        // cycleSeq, never statusCycleSeq. A status change in a later cycle must not make an old
        // phrase read as freshly stated in that cycle.
        val surfacing = when {
            reactivation != null -> SurfacingReason.ActiveReactivation(reactivation)
            c.cycleSeq == currentCycleSeq -> SurfacingReason.JustAsserted(c.cycleSeq)
            // Explicit precondition, not a bare `else` — DormantOpen specifically means an
            // open-status intention. Every pre-existing non-reactivated, non-fresh candidate reached
            // this point only via openPool (status='open'), so this changes nothing for them; it is
            // what makes room for a well-defined RecentActorEvidence branch below instead of
            // mislabeling that pool's members as open.
            c.status != null -> SurfacingReason.DormantOpen(c.statusCycleSeq ?: c.cycleSeq)
            else -> SurfacingReason.RecentActorEvidence(c.cycleSeq)
        }
        val status = c.status?.let { s -> runCatching { AssertionStatus.valueOf(s.uppercase()) }.getOrNull() }
        return HorizonItem(
            category = categoryFor(c.sourceType, c.status),
            text = BoundedText.of(c.phraseText),
            status = status,
            attentionDirective = null,
            provenance = provenanceFor(c.sourceType),
            surfacing = surfacing,
            sourceRefs = listOf(SourceRef(c.phraseUid, c.sourceUid, c.sourceType, c.assertedAt, c.cycleSeq))
                .take(HorizonLimits.MAX_SOURCE_REFS_PER_ITEM),
            sourceCount = 1,
            actorMetadata = parseActorMetadata(c.sourceType, c.kindMetadata),
        )
    }

    /**
     * Decodes the `ASSERTS.kindMetadata` JSON [ActorEventIngestionService] wrote at ingestion into
     * the same shared [ActorEventMetadata] shape it was encoded from — carrying basis/tool
     * provenance through to the response prompt (see [HorizonItem.actorMetadata]). Never non-null
     * for a non-Actor-attributed [sourceType]: an ordinary fact/environment-signal edge has no such
     * column populated, and even if it somehow did, surfacing it there would misattribute a claim
     * this pipeline never made. A decode failure (unexpected/malformed content) is logged and
     * treated as absent metadata, never a hard failure of the whole assemble() call.
     */
    private fun parseActorMetadata(sourceType: String, kindMetadataJson: String?): ActorEventMetadata? {
        if (sourceType !in ACTOR_ATTRIBUTED_SOURCE_TYPES) return null
        if (kindMetadataJson.isNullOrBlank()) return null
        return try {
            json.decodeFromString<ActorEventMetadata>(kindMetadataJson)
        } catch (e: Exception) {
            logger.warn("parseActorMetadata: failed to decode kindMetadata for sourceType=$sourceType: ${e.message}")
            null
        }
    }

    private fun categoryFor(sourceType: String, status: String?): HorizonItemCategory = when {
        sourceType == ENVIRONMENT_SOURCE_TYPE -> HorizonItemCategory.ENVIRONMENT_EVENT
        status != null -> HorizonItemCategory.INTENTION
        else -> HorizonItemCategory.FACT
    }

    private fun provenanceFor(sourceType: String): ProvenanceKind = when (sourceType) {
        ENVIRONMENT_SOURCE_TYPE -> ProvenanceKind.ENVIRONMENT_SIGNAL
        ACTOR_OBSERVATION_SOURCE_TYPE -> ProvenanceKind.ACTOR_OBSERVATION
        ACTOR_INTERPRETATION_SOURCE_TYPE -> ProvenanceKind.ACTOR_INTERPRETATION
        ACTOR_TOOL_RESULT_SOURCE_TYPE -> ProvenanceKind.ACTOR_TOOL_RESULT
        else -> ProvenanceKind.EXPLICIT_USER_STATEMENT
    }

    // RecentActorEvidence ranks above DormantOpen: bounded-recency Actor evidence is more likely to
    // matter right now than an intention that has simply been open indefinitely — the same intuition
    // already distinguishing JustAsserted/ActiveReactivation from DormantOpen, extended one step.
    private fun priorityRank(reason: SurfacingReason): Int = when (reason) {
        is SurfacingReason.ActiveReactivation -> 0
        is SurfacingReason.JustAsserted -> 1
        is SurfacingReason.RecentActorEvidence -> 2
        is SurfacingReason.DormantOpen -> 3
    }

    private fun surfacingCycleSeq(reason: SurfacingReason): Long = when (reason) {
        is SurfacingReason.ActiveReactivation -> reason.info.edgeCycleSeq
        is SurfacingReason.JustAsserted -> reason.cycleSeq
        is SurfacingReason.RecentActorEvidence -> reason.assertedCycleSeq
        is SurfacingReason.DormantOpen -> reason.lastStatusChangeCycleSeq
    }

    private fun describeSurfacing(reason: SurfacingReason): String = when (reason) {
        is SurfacingReason.ActiveReactivation -> "active reactivation"
        is SurfacingReason.JustAsserted -> "just asserted"
        is SurfacingReason.RecentActorEvidence -> "recent actor evidence"
        is SurfacingReason.DormantOpen -> "dormant open"
    }
}
