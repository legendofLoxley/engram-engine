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
     * **Reactivation candidacy is independently bounded, not restricted to the pools.**
     * A `relevant_to` edge can target a phrase that never appears in a pool (e.g. a very old item,
     * or a resolved item being reactivated); such a target is still found and admitted as a
     * candidate, via its own independently bounded query — see [ArcadeHorizonAssembler.queryActiveReactivations].
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
    /** Every tunable behind what is in view. See [HorizonPolicy]. */
    private val policy: HorizonPolicy = HorizonPolicy(),
    /** Wall-clock milliseconds. Deadlines are judged against this at read time; injected so a test can move time. */
    private val clock: () -> Long = System::currentTimeMillis,
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
        val now = clock()
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
                    var containerViews: List<HorizonContainerView> = emptyList()
                    var focusRef: HorizonFocusRef? = null
                    var zoomLevel = FocusZoom.OVERVIEW
                    if (sourceUids.isEmpty()) {
                        prioritized = emptyList()
                        // No Source yet means no items, but the user may already have projects and a focus (Sources
                        // are created with a first item, projects are not): their shape is still part of the view.
                        val selection = HorizonSelector(db, policy).select(userEmail, currentCycleSeq, now, emptyList())
                        containerViews = selection.containers
                        focusRef = selection.focus
                        zoomLevel = selection.zoom
                    } else {
                        // Bounded over-fetch pool — cost is a function of poolLimit and sourceUids
                        // (small, one per source type per user), not total graph size. What is open,
                        // in focus, mentioned or due is the selector's to find (below); the pools here
                        // are only what the selector cannot see: what was just said this cycle and
                        // what Hermes or the environment recently reported.
                        val poolLimit = budget.maxItems * HorizonLimits.CANDIDATE_POOL_OVERFETCH
                        val freshPool = queryAsserts(sourceUids, cycleSeqFilter = currentCycleSeq, currentCycleSeq = currentCycleSeq, limit = poolLimit)
                        moreCandidatesAvailable = freshPool.size >= poolLimit

                        // Bounded-recency Actor-attributed evidence pool — see
                        // SurfacingReason.RecentActorEvidence. Independent of the fresh pool: an event
                        // written at an earlier cycle would otherwise vanish from the Horizon the
                        // moment its own cycle passes (it is not this cycle's content), which is
                        // exactly the gap this pool closes. Same minCycle window
                        // activeReactivation already uses — computed once below and reused, not a
                        // second independent knob.
                        val minCycleForActorPool = currentCycleSeq - activeRelevanceCycles
                        val actorEvidencePool = queryAttributedEvidencePool(sourceUids, minCycleForActorPool, currentCycleSeq, poolLimit)
                        moreCandidatesAvailable = moreCandidatesAvailable || actorEvidencePool.size >= poolLimit

                        // Environment reports get the same bounded-recency treatment: reports from outside the
                        // conversation may break into view, in the same few peripheral slots (see rank / peripheralSlots).
                        val environmentPool = queryEvidencePool(sourceUids, setOf(ENVIRONMENT_SOURCE_TYPE), minCycleForActorPool, currentCycleSeq, poolLimit)
                        moreCandidatesAvailable = moreCandidatesAvailable || environmentPool.size >= poolLimit

                        val byUid = LinkedHashMap<String, RawCandidate>()
                        for (c in freshPool) byUid[c.phraseUid] = c
                        for (c in actorEvidencePool) byUid.putIfAbsent(c.phraseUid, c)
                        for (c in environmentPool) byUid.putIfAbsent(c.phraseUid, c)

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

                        // ── Selection: what the graph and the clock say deserves a place ──
                        // Focus, nudges, spreading, deadlines, explicit attention and what is still open,
                        // from HorizonSelector; the pools above keep contributing what only they can see
                        // (just said, reactivated, reported by Hermes), now as components of one score.
                        val selection = HorizonSelector(db, policy).select(userEmail, currentCycleSeq, now, freshPool.map { it.phraseUid })
                        // More was offered than the pool takes, or the selector's own scans hit a bound: say so, never silently.
                        if (selection.truncated || selection.activations.size > poolLimit) moreCandidatesAvailable = true
                        val selectorPicks = selection.activations.values.sortedByDescending { it.score }.take(poolLimit)
                        for (pick in selectorPicks) {
                            if (byUid.containsKey(pick.uid)) continue
                            // Ownership is proved here: an item is admitted only if one of the user's own Sources asserts it.
                            queryAssertsForPhrase(sourceUids, pick.uid)?.let { byUid[pick.uid] = it }
                        }
                        // Defense in depth alongside the SQL-level exclusion in queryAsserts: any
                        // candidate whose original assertion or last status change is somehow ahead
                        // of the cycle being assembled (a caller sequencing bug, or a write that
                        // bypassed HorizonGraphStore) is dropped here, rather than being
                        // misclassified as JustAsserted/DormantOpen. Runs after every source has
                        // been merged — the pools, reactivations and the selector's picks — so
                        // whichever way a candidate arrived, it gets the same guard.
                        byUid.entries.removeAll { (_, c) ->
                            c.cycleSeq > currentCycleSeq || (c.statusCycleSeq != null && c.statusCycleSeq > currentCycleSeq)
                        }

                        val states = HorizonSelector(db, policy).loadStates(byUid.keys)
                        val actorUids = actorEvidencePool.map { it.phraseUid }.toSet()
                        val envUids = environmentPool.map { it.phraseUid }.toSet()

                        val ranked = byUid.values.mapNotNull { c ->
                            val state = states[c.phraseUid]
                            // Left out: finished, told to stop, corrected away, or filed under a project that is done or silenced.
                            if (state != null && (state.status == "resolved" || state.attention == AttentionDirective.SUPPRESSED || state.superseded ||
                                    state.containerAttention == AttentionDirective.SUPPRESSED || (state.container != null && state.containerStatus != "active"))
                            ) return@mapNotNull null
                            rank(c, state, selection, reactivationResult.infoByTargetUid[c.phraseUid], currentCycleSeq, c.phraseUid in actorUids, c.phraseUid in envUids)
                        }.filter { it.item.score >= policy.visibilityFloor }

                        // Reports from outside the conversation may come in, but only in a few slots.
                        val peripheralOnly = ranked.filter { r -> r.item.why.all { it.kind == "peripheral" } }.sortedByDescending { it.tie }
                        val overPeripheralCap = peripheralOnly.drop(policy.peripheralSlots).map { it.item.sourceRefs.first().phraseUid }.toSet()
                        prioritized = ranked.filter { it.item.sourceRefs.first().phraseUid !in overPeripheralCap }
                            .sortedWith(compareByDescending<Ranked> { it.item.score }.thenByDescending { it.tie })
                            .map { it.item }
                        containerViews = selection.containers
                        focusRef = selection.focus
                        zoomLevel = selection.zoom
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
                    var built = buildHorizon(userEmail, now, budget, prioritized, keptCount, moreCandidatesAvailable, zoomLevel, focusRef, containerViews)
                    while (encodedByteSize(built) > byteBudget && keptCount > 0) {
                        keptCount--
                        built = buildHorizon(userEmail, now, budget, prioritized, keptCount, moreCandidatesAvailable, zoomLevel, focusRef, containerViews)
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
        zoom: FocusZoom, focus: HorizonFocusRef?, containers: List<HorizonContainerView>,
    ): ContextHorizon {
        val kept = prioritized.take(keptCount)
        val inView = kept.mapNotNull { it.container?.uid }.groupingBy { it }.eachCount()
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
            zoom = zoom,
            focus = focus,
            containers = containers.map { it.copy(itemsInView = inView[it.uid] ?: 0) },
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
     * The `ASSERTS` query for what was asserted in exactly one cycle. [currentCycleSeq] bounds it: future evidence
     * is excluded at the query level, and [rank]'s caller repeats the exclusion defensively so a caller bug
     * elsewhere can't smuggle future evidence through.
     */
    private fun buildAssertsQuery(sourceUids: List<String>, cycleSeqFilter: Long?, currentCycleSeq: Long, limit: Int): AssertsQuery {
        val conditions = mutableListOf("@out.uid IN :sourceUids")
        val params = mutableMapOf<String, Any>("sourceUids" to sourceUids, "limit" to limit, "currentCycleSeq" to currentCycleSeq)
        if (cycleSeqFilter != null) {
            // An exact match already bounds this to currentCycleSeq itself — no separate future-exclusion
            // needed, an equality filter can't select a future value.
            conditions += "cycleSeq = :cycleSeq"
            params["cycleSeq"] = cycleSeqFilter
        }
        val sql = """
            SELECT @out.uid as sourceUid, @out.type as sourceType, @in.uid as phraseUid, @in.text as phraseText,
                   timestamp, cycleSeq, statusCycleSeq, status, kindMetadata
            FROM ASSERTS
            WHERE ${conditions.joinToString(" AND ")}
            LIMIT :limit
        """.trimIndent()
        return AssertsQuery(sql, params)
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

    private fun queryAsserts(sourceUids: List<String>, cycleSeqFilter: Long?, currentCycleSeq: Long, limit: Int): List<RawCandidate> {
        if (sourceUids.isEmpty()) return emptyList()
        val query = buildAssertsQuery(sourceUids, cycleSeqFilter, currentCycleSeq, limit)
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
    private fun queryAttributedEvidencePool(sourceUids: List<String>, minCycle: Long, currentCycleSeq: Long, limit: Int): List<RawCandidate> =
        queryEvidencePool(sourceUids, ACTOR_ATTRIBUTED_SOURCE_TYPES, minCycle, currentCycleSeq, limit)

    /** [queryAttributedEvidencePool]'s query, for any set of `Source.type`s — the environment's reports use it too. */
    private fun queryEvidencePool(sourceUids: List<String>, types: Set<String>, minCycle: Long, currentCycleSeq: Long, limit: Int): List<RawCandidate> {
        if (sourceUids.isEmpty()) return emptyList()
        val actorSourceUids = sourceUidsOfTypes(sourceUids, types)
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
    private fun sourceUidsOfTypes(sourceUids: List<String>, types: Set<String>): List<String> {
        if (sourceUids.isEmpty()) return emptyList()
        val sql = "SELECT uid FROM Source WHERE uid IN :sourceUids AND type IN :actorSourceTypes"
        val params = mapOf("sourceUids" to sourceUids, "actorSourceTypes" to types.toList())
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
     * bounded by [limit] — **independently** of [ArcadeHorizonAssembler.assemble]'s candidate
     * pools, not restricted to phrases they already selected. A target outside those
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

    private class Ranked(val item: HorizonItem, val tie: Long)

    /**
     * Turns one candidate into an item with a score and the reasons behind it. The pools contribute
     * what they always did — something just said, reactivated by a fresh `relevant_to` link, reported
     * from outside the conversation — each as one component with its own strength; [selection]'s focus,
     * nudge, spread, deadline, pinned and still-open components are added to those, and the item's score
     * is them combined (each adds, none can pass 1). Which one is named as *the* reason is the strongest,
     * ties going to the more specific.
     */
    private fun rank(
        c: RawCandidate,
        state: HorizonSelector.ItemState?,
        selection: HorizonSelector.Selection,
        reactivation: ReactivationInfo?,
        currentCycleSeq: Long,
        fromActor: Boolean,
        fromEnvironment: Boolean,
    ): Ranked? {
        val activation = selection.activations[c.phraseUid]
        val parts = mutableListOf<ScoreComponent>()
        if (reactivation != null) parts += ScoreComponent("reactivation", policy.reactivationActivation, "linked to \"${reactivation.triggeringPhraseText.text.take(60)}\"")
        if (c.cycleSeq == currentCycleSeq) parts += ScoreComponent("justAsserted", policy.justAssertedActivation, null)
        if ((fromActor || fromEnvironment) && c.cycleSeq != currentCycleSeq) parts += ScoreComponent("peripheral", policy.peripheralActivation, null)
        if (activation != null) parts += activation.components
        if (parts.isEmpty()) return null

        val ordered = parts.sortedWith(compareByDescending<ScoreComponent> { it.value }.thenBy { SPECIFICITY.indexOf(it.kind).let { i -> if (i < 0) SPECIFICITY.size else i } })
        val primary = ordered.first()
        val surfacing: SurfacingReason = when (primary.kind) {
            "pinned" -> SurfacingReason.Pinned
            "reactivation" -> SurfacingReason.ActiveReactivation(reactivation!!)
            "justAsserted" -> SurfacingReason.JustAsserted(c.cycleSeq)
            "focus" -> SurfacingReason.InFocus(selection.focus?.name, selection.zoom, activation?.hops ?: 0)
            "urgency" -> SurfacingReason.Urgent(activation?.dueAt ?: state?.dueAt ?: 0L, activation?.overdue ?: false)
            "nudge", "spread" -> SurfacingReason.Activated(primary.detail ?: activation?.via ?: "mentioned", activation?.hops ?: 0)
            "peripheral" -> if (fromEnvironment) SurfacingReason.Peripheral(c.cycleSeq) else SurfacingReason.RecentActorEvidence(c.cycleSeq)
            else -> SurfacingReason.DormantOpen(c.statusCycleSeq ?: c.cycleSeq)
        }
        val status = c.status?.let { s -> runCatching { AssertionStatus.valueOf(s.uppercase()) }.getOrNull() }
        val item = HorizonItem(
            category = categoryFor(c.sourceType, c.status),
            text = BoundedText.of(c.phraseText),
            status = status,
            attentionDirective = state?.attention,
            provenance = provenanceFor(c.sourceType),
            surfacing = surfacing,
            sourceRefs = listOf(SourceRef(c.phraseUid, c.sourceUid, c.sourceType, c.assertedAt, c.cycleSeq)).take(HorizonLimits.MAX_SOURCE_REFS_PER_ITEM),
            sourceCount = 1,
            actorMetadata = parseActorMetadata(c.sourceType, c.kindMetadata),
            score = HorizonPolicy.combine(parts.map { it.value }),
            why = ordered.take(HorizonLimits.MAX_SCORE_COMPONENTS),
            kind = state?.kind,
            dueAt = state?.dueAt,
            askedBy = state?.askedBy,
            container = state?.container,
        )
        // Ties keep the order the pools always gave them: by the cycle the thing that surfaced it happened in.
        val tie = when (surfacing) {
            is SurfacingReason.ActiveReactivation -> surfacing.info.edgeCycleSeq
            is SurfacingReason.DormantOpen -> surfacing.lastStatusChangeCycleSeq
            else -> c.cycleSeq
        }
        return Ranked(item, tie)
    }

    /** When two components tie, the one named first here is reported as the reason. */
    private val SPECIFICITY = listOf("pinned", "reactivation", "justAsserted", "focus", "nudge", "urgency", "spread", "peripheral", "baseline")

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

    private fun describeSurfacing(reason: SurfacingReason): String = when (reason) {
        is SurfacingReason.ActiveReactivation -> "active reactivation"
        is SurfacingReason.JustAsserted -> "just asserted"
        is SurfacingReason.RecentActorEvidence -> "recent actor evidence"
        is SurfacingReason.DormantOpen -> "dormant open"
        is SurfacingReason.Pinned -> "pinned"
        is SurfacingReason.InFocus -> "in focus"
        is SurfacingReason.Activated -> "activated"
        is SurfacingReason.Urgent -> "urgent"
        is SurfacingReason.Peripheral -> "peripheral"
    }
}
