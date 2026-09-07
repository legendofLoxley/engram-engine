package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * The result of [HorizonAssembler.assemble] — success carries the snapshot; failure is explicit
 * and distinguishable from "the user has no data yet". A caller must not treat [ConsistencyFailure]
 * as an empty [ContextHorizon] — no read was actually taken.
 */
sealed interface AssembleOutcome {
    data class Assembled(val horizon: ContextHorizon) : AssembleOutcome
    /** The [HorizonConsistencyLock] read lock could not be acquired within the bounded timeout — no snapshot was read, consistent or otherwise. */
    data class ConsistencyFailure(val reason: String) : AssembleOutcome
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
     * **Consistency is enforced, not merely observed.** [ArcadeHorizonAssembler] acquires
     * [HorizonConsistencyLock]'s read lock for the entire duration of its internal queries,
     * excluding every [HorizonGraphStore] writer for that window — see that lock's doc for which
     * writers this covers, and for the concrete evidence (a reproduced deadlock) that ruled out an
     * ArcadeDB-native lock/isolation level in favor of this one. If the lock cannot be acquired
     * within the bounded timeout (a single `tryLock`, never a retry loop), this returns
     * [AssembleOutcome.ConsistencyFailure] rather than a possibly-mixed snapshot — no read is taken
     * at all in that case, so there is nothing to be inconsistent.
     *
     * **Future-cycle evidence is excluded, not misclassified.** Per [HorizonGraphStore]'s
     * sequence-continuity contract, [currentCycleSeq] is assumed to be at or after every `cycleSeq`
     * already committed for [userEmail]. Any candidate whose original assertion or most recent
     * status change is nonetheless ahead of [currentCycleSeq] (a caller sequencing bug, or a write
     * that bypassed [HorizonGraphStore]) is dropped from the snapshot entirely, both by the
     * underlying query and defensively again after — never surfaced as `JustAsserted` or any other
     * classification it would otherwise get by falling through.
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
}

class ArcadeHorizonAssembler(
    private val db: Database,
    private val lockTimeoutMs: Long = HorizonConsistencyLock.DEFAULT_TIMEOUT_MS,
) : HorizonAssembler {

    private val logger = LoggerFactory.getLogger(ArcadeHorizonAssembler::class.java)

    /**
     * Test-only synchronization point, invoked once per [assemble] call inside the read
     * transaction, strictly between fetching the candidate pools and fetching reactivations.
     * No-op (`null`) in production and never set by any production code path. Exists so a test can
     * deterministically land a real, separately-committed concurrent write at that exact point and
     * observe — rather than assume — what isolation the wrapping transaction actually provides.
     * See `HorizonAssemblerTest`'s concurrency test for the empirical result this documents.
     */
    internal var testMidAssemblySync: (() -> Unit)? = null

    private data class RawCandidate(
        val phraseUid: String,
        val phraseText: String,
        val sourceUid: String,
        val sourceType: String,
        val assertedAt: Long,
        val cycleSeq: Long,           // ORIGINAL assertion cycle — never touched by a status change
        val statusCycleSeq: Long?,    // cycle of the most recent status change, if any — distinct from cycleSeq
        val status: String?,
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
        // Held for the entire read, excluding every HorizonGraphStore writer for that window — see
        // HorizonConsistencyLock for which writers this covers and why a hand-rolled lock rather
        // than an ArcadeDB-native isolation level/lock. A lock-acquisition timeout is an explicit
        // AssembleOutcome.ConsistencyFailure below, never a silently-returned possibly-mixed
        // snapshot — an internal query exception (caught inside the lock) still degrades to the
        // existing "empty" behavior, a separate, unrelated failure mode.
        val horizonOrNull: ContextHorizon? = withHorizonReadLock(db, lockTimeoutMs) {
            var result = empty
            try {
                db.transaction {
                val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@transaction
                val sourceUids = trustedSourceUids(userVertex)
                if (sourceUids.isEmpty()) return@transaction

                // Bounded over-fetch pools — cost is a function of poolLimit and sourceUids (small,
                // one per source type per user), not total graph size. The open-status branch is
                // confirmed (by direct execution-plan inspection, not timing alone — see
                // debugOpenPoolExecutionSteps and its test) to use the ASSERTS[status] index rather
                // than a full type scan.
                val poolLimit = budget.maxItems * HorizonLimits.CANDIDATE_POOL_OVERFETCH
                val openPool = queryAsserts(sourceUids, statusFilter = "open", cycleSeqFilter = null, currentCycleSeq = currentCycleSeq, limit = poolLimit)
                val freshPool = queryAsserts(sourceUids, statusFilter = null, cycleSeqFilter = currentCycleSeq, currentCycleSeq = currentCycleSeq, limit = poolLimit)
                val moreCandidatesAvailable = openPool.size >= poolLimit || freshPool.size >= poolLimit

                val byUid = LinkedHashMap<String, RawCandidate>()
                for (c in openPool) byUid[c.phraseUid] = c
                for (c in freshPool) byUid.putIfAbsent(c.phraseUid, c)
                // Defense in depth alongside the SQL-level exclusion in queryAsserts: any candidate
                // whose original assertion or last status change is somehow ahead of the cycle being
                // assembled (a caller sequencing bug, or a write that bypassed HorizonGraphStore) is
                // dropped here too, rather than being misclassified as JustAsserted/DormantOpen.
                byUid.entries.removeAll { (_, c) ->
                    c.cycleSeq > currentCycleSeq || (c.statusCycleSeq != null && c.statusCycleSeq > currentCycleSeq)
                }
                val candidateUids = byUid.keys.toList()

                testMidAssemblySync?.invoke()

                // Reactivation is checked only among this already-bounded pool, not the whole
                // graph — a fresh `relevant_to` edge targeting something outside the pool (e.g. a
                // very old open item beyond poolLimit) is a known limitation of this foundation
                // increment's bounded-retrieval tradeoff, not a correctness bug for anything the
                // pool actually contains.
                val minCycle = currentCycleSeq - activeRelevanceCycles
                val reactivations = if (candidateUids.isEmpty()) emptyMap() else queryActiveReactivations(
                    userEmail, candidateUids, minCycle, currentCycleSeq, now, activeRelevanceWallClock,
                )

                val classified = byUid.values.map { c -> toHorizonItem(c, currentCycleSeq, reactivations[c.phraseUid]) }
                val prioritized = classified.sortedWith(
                    compareBy<HorizonItem> { priorityRank(it.surfacing) }.thenByDescending { surfacingCycleSeq(it.surfacing) },
                )
                val kept = prioritized.take(budget.maxItems)
                val overflow = prioritized.drop(budget.maxItems)
                val omittedSample = overflow.take(HorizonLimits.OMITTED_SAMPLE_CAP).map { item ->
                    OmittedRef(item.sourceRefs.first(), item.category, "budget exceeded: ${describeSurfacing(item.surfacing)}")
                }

                result = ContextHorizon(
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
            } catch (e: Exception) {
                logger.warn("assemble failed for userEmail=$userEmail: ${e.message}")
            }
            result
        }
        if (horizonOrNull != null) {
            AssembleOutcome.Assembled(horizonOrNull)
        } else {
            logger.warn("assemble: could not acquire the Horizon read lock within ${lockTimeoutMs}ms for userEmail=$userEmail")
            AssembleOutcome.ConsistencyFailure("could not acquire the Horizon read lock within ${lockTimeoutMs}ms — a writer may be holding it")
        }
    }

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
            // server-side SKIP/LIMIT page — a page can come back shorter than `limit` when a
            // category filter is also given. Acceptable for this foundation's completeness-checking
            // use (walking pages to exhaustion), not a hot path.
            val sql = """
                SELECT @out.uid as sourceUid, @out.type as sourceType, @in.uid as phraseUid, timestamp, cycleSeq, status
                FROM ASSERTS
                WHERE ${conditions.joinToString(" AND ")}
                ORDER BY cycleSeq DESC
                SKIP :offset LIMIT :limit
            """.trimIndent()
            val rows = db.query("sql", sql, params).use { rs ->
                val out = mutableListOf<SourceRef>()
                while (rs.hasNext()) {
                    val row = rs.next().toMap()
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
            val nextCursor = if (rows.size == limit) (offset + limit).toString() else null
            CandidatePage(rows, nextCursor)
        } catch (e: Exception) {
            logger.warn("listCandidates failed for userEmail=$userEmail: ${e.message}")
            CandidatePage(emptyList(), null)
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
                   timestamp, cycleSeq, statusCycleSeq, status
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

    private fun queryAsserts(
        sourceUids: List<String>, statusFilter: String?, cycleSeqFilter: Long?, currentCycleSeq: Long, limit: Int,
    ): List<RawCandidate> {
        if (sourceUids.isEmpty()) return emptyList()
        val query = buildAssertsQuery(sourceUids, statusFilter, cycleSeqFilter, currentCycleSeq, limit)
        return db.query("sql", query.sql, query.params).use { rs ->
            val out = mutableListOf<RawCandidate>()
            while (rs.hasNext()) {
                val row = rs.next().toMap()
                out += RawCandidate(
                    phraseUid = row["phraseUid"] as? String ?: continue,
                    phraseText = row["phraseText"] as? String ?: "",
                    sourceUid = row["sourceUid"] as? String ?: "",
                    sourceType = row["sourceType"] as? String ?: "",
                    assertedAt = (row["timestamp"] as? Number)?.toLong() ?: 0L,
                    cycleSeq = (row["cycleSeq"] as? Number)?.toLong() ?: 0L,
                    statusCycleSeq = (row["statusCycleSeq"] as? Number)?.toLong(),
                    status = row["status"] as? String,
                )
            }
            out
        }
    }

    /**
     * Active `relevant_to` reactivations targeting [candidateUids], within `[minCycle, currentCycleSeq]`.
     * Each result's triggering phrase is independently verified as owned by [userEmail] before being
     * trusted — defense in depth: even a cross-user edge injected outside [HorizonGraphStore] (a bug
     * or bad migration) is refused here rather than leaking another user's phrase text.
     */
    private fun queryActiveReactivations(
        userEmail: String,
        candidateUids: List<String>,
        minCycle: Long,
        currentCycleSeq: Long,
        nowMillis: Long,
        wallClockCap: Duration?,
    ): Map<String, ReactivationInfo> {
        val sql = """
            SELECT @out.uid as triggerUid, @out.text as triggerText, @in.uid as targetUid, strength, cycleSeq, createdAt
            FROM RELATED_TO
            WHERE relationType = 'relevant_to' AND @in.uid IN :candidateUids AND cycleSeq >= :minCycle AND cycleSeq <= :currentCycleSeq
            ORDER BY cycleSeq DESC
        """.trimIndent()
        val rows = db.query(
            "sql", sql,
            mapOf("candidateUids" to candidateUids, "minCycle" to minCycle, "currentCycleSeq" to currentCycleSeq),
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
        return result
    }

    private fun toHorizonItem(c: RawCandidate, currentCycleSeq: Long, reactivation: ReactivationInfo?): HorizonItem {
        // JustAsserted is about the phrase's CONTENT being newly stated — the original assertion
        // cycleSeq, never statusCycleSeq. A status change in a later cycle must not make an old
        // phrase read as freshly stated in that cycle.
        val surfacing = when {
            reactivation != null -> SurfacingReason.ActiveReactivation(reactivation)
            c.cycleSeq == currentCycleSeq -> SurfacingReason.JustAsserted(c.cycleSeq)
            else -> SurfacingReason.DormantOpen(c.statusCycleSeq ?: c.cycleSeq)
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
        )
    }

    private fun categoryFor(sourceType: String, status: String?): HorizonItemCategory = when {
        sourceType == ENVIRONMENT_SOURCE_TYPE -> HorizonItemCategory.ENVIRONMENT_EVENT
        status != null -> HorizonItemCategory.INTENTION
        else -> HorizonItemCategory.FACT
    }

    private fun provenanceFor(sourceType: String): ProvenanceKind =
        if (sourceType == ENVIRONMENT_SOURCE_TYPE) ProvenanceKind.ENVIRONMENT_SIGNAL else ProvenanceKind.EXPLICIT_USER_STATEMENT

    private fun priorityRank(reason: SurfacingReason): Int = when (reason) {
        is SurfacingReason.ActiveReactivation -> 0
        is SurfacingReason.JustAsserted -> 1
        is SurfacingReason.DormantOpen -> 2
    }

    private fun surfacingCycleSeq(reason: SurfacingReason): Long = when (reason) {
        is SurfacingReason.ActiveReactivation -> reason.info.edgeCycleSeq
        is SurfacingReason.JustAsserted -> reason.cycleSeq
        is SurfacingReason.DormantOpen -> reason.lastStatusChangeCycleSeq
    }

    private fun describeSurfacing(reason: SurfacingReason): String = when (reason) {
        is SurfacingReason.ActiveReactivation -> "active reactivation"
        is SurfacingReason.JustAsserted -> "just asserted"
        is SurfacingReason.DormantOpen -> "dormant open"
    }
}
