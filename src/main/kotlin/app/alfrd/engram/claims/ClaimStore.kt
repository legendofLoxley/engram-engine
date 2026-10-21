package app.alfrd.engram.claims

import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOwnership
import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/** An established claim as the graph holds it: its item, its identity in the Source, who claimed it, whether it is Settled, and every chunk it sits in. */
data class ClaimItem(
    val uid: String, val quote: String, val place: String?, val claimant: String?, val claimantAsWritten: String?, val subject: String, val kind: String,
    val runId: String?, val checkState: String, val recheckRunId: String?, val chunkUids: List<String>,
)

/** A stored run: where it stands and, once it has run, its counts. */
data class StoredRun(
    val runId: String, val sourceUid: String, val status: String, val modelName: String, val modelEndpoint: String?, val promptVersion: String,
    val passes: Int, val chunksTotal: Int, val chunksDone: Int, val report: RunReport?,
)

/** A retained disagreement as stored. */
data class StoredDisagreement(val runId: String, val chunkUid: String, val reason: String, val versionA: ClaimVersion?, val versionB: ClaimVersion?)

/**
 * The claim extraction's records in the graph: runs, retained disagreements, and the claim properties on established items.
 * Runs and disagreements are their own vertex types, never Phrases and never ASSERTed, so the Context Horizon cannot surface them.
 * Everything is scoped to one owner. Nothing here logs claim text.
 */
class ClaimStore(private val db: Database) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Runs ─────────────────────────────────────────────────────────────────

    fun run(userEmail: String, runId: String): StoredRun? =
        db.query("sql", "SELECT FROM ExtractionRun WHERE runId = :r AND ownerEmail = :o", mapOf("r" to runId, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().toRun() else null }

    fun runs(userEmail: String, sourceUid: String? = null): List<StoredRun> =
        db.query("sql", "SELECT FROM ExtractionRun WHERE ownerEmail = :o ORDER BY startedAt", mapOf("o" to userEmail))
            .use { rs -> val out = ArrayList<StoredRun>(); while (rs.hasNext()) out += rs.next().toElement().asVertex().toRun(); out }
            .filter { sourceUid == null || it.sourceUid == sourceUid }

    fun startRun(userEmail: String, runId: String, sourceUid: String, endpoint: String?, promptVersion: String, passes: Int, chunksTotal: Int) {
        db.transaction {
            db.newVertex("ExtractionRun").apply {
                set("uid", UUID.randomUUID().toString()); set("ownerEmail", userEmail); set("runId", runId); set("sourceUid", sourceUid)
                set("status", "running"); set("startedAt", System.currentTimeMillis()); set("modelName", "")
                endpoint?.let { set("modelEndpoint", it) }
                set("promptVersion", promptVersion); set("passes", passes); set("chunksTotal", chunksTotal); set("chunksDone", 0)
                save()
            }
        }
    }

    fun updateRun(userEmail: String, runId: String, status: String? = null, modelName: String? = null, chunksDone: Int? = null, report: RunReport? = null) {
        db.transaction {
            val v = runVertex(userEmail, runId) ?: return@transaction
            val m = v.modify()
            status?.let { m.set("status", it) }
            modelName?.let { m.set("modelName", it) }
            chunksDone?.let { m.set("chunksDone", it) }
            report?.let { m.set("report", json.encodeToString(it)) }
            if (status == "complete") m.set("finishedAt", System.currentTimeMillis())
            m.save()
        }
    }

    private fun runVertex(userEmail: String, runId: String): Vertex? =
        db.query("sql", "SELECT FROM ExtractionRun WHERE runId = :r AND ownerEmail = :o", mapOf("r" to runId, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    private fun Vertex.toRun() = StoredRun(
        runId = get("runId") as String, sourceUid = get("sourceUid") as? String ?: "", status = get("status") as? String ?: "running",
        modelName = get("modelName") as? String ?: "", modelEndpoint = get("modelEndpoint") as? String, promptVersion = get("promptVersion") as? String ?: "",
        passes = (get("passes") as? Number)?.toInt() ?: 2, chunksTotal = (get("chunksTotal") as? Number)?.toInt() ?: 0,
        chunksDone = (get("chunksDone") as? Number)?.toInt() ?: 0,
        report = (get("report") as? String)?.let { runCatching { json.decodeFromString<RunReport>(it) }.getOrNull() },
    )

    // ── Disagreements ────────────────────────────────────────────────────────

    /** Retains a disagreement. The same disagreement of the same run is stored once, so resuming a run does not repeat it. Returns true when it was new. */
    fun addDisagreement(userEmail: String, runId: String, sourceUid: String, d: Disagreement): Boolean {
        val a = d.versionA?.let { json.encodeToString(it) }
        val b = d.versionB?.let { json.encodeToString(it) }
        val key = digest("$userEmail|$runId|${d.chunkUid}|${d.reason}|${a.orEmpty()}|${b.orEmpty()}")
        val exists = db.query("sql", "SELECT count(*) AS n FROM ClaimDisagreement WHERE dkey = :k", mapOf("k" to key)).use { (it.next().getProperty<Number>("n")).toLong() } > 0
        if (exists) return false
        db.transaction {
            db.newVertex("ClaimDisagreement").apply {
                set("uid", UUID.randomUUID().toString()); set("ownerEmail", userEmail); set("runId", runId); set("sourceUid", sourceUid)
                set("chunkUid", d.chunkUid); set("reason", d.reason); set("createdAt", System.currentTimeMillis()); set("dkey", key)
                a?.let { set("versionA", it) }; b?.let { set("versionB", it) }
                save()
            }
        }
        return true
    }

    fun disagreements(userEmail: String, sourceUid: String, runId: String? = null): List<StoredDisagreement> =
        db.query("sql", "SELECT FROM ClaimDisagreement WHERE sourceUid = :s AND ownerEmail = :o ORDER BY createdAt", mapOf("s" to sourceUid, "o" to userEmail)).use { rs ->
            val out = ArrayList<StoredDisagreement>()
            while (rs.hasNext()) {
                val v = rs.next().toElement().asVertex()
                if (runId != null && v.get("runId") != runId) continue
                out += StoredDisagreement(
                    v.get("runId") as String, v.get("chunkUid") as String, v.get("reason") as? String ?: "",
                    (v.get("versionA") as? String)?.let { json.decodeFromString<ClaimVersion>(it) },
                    (v.get("versionB") as? String)?.let { json.decodeFromString<ClaimVersion>(it) },
                )
            }
            out
        }

    // ── Claims ───────────────────────────────────────────────────────────────

    /** Stamps an item as an established claim (idempotent: an item already stamped keeps its run, state and wording). */
    fun markEstablished(userEmail: String, itemUid: String, sourceUid: String, claim: AgreedClaim, runId: String) {
        val item = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, itemUid) ?: return
        if (item.get("claimRunId") != null) return
        db.transaction {
            item.modify()
                .set("claimKind", claim.kind.name).set("claimSubject", claim.subject).set("claimQuote", claim.quote)
                .set("claimSourceUid", sourceUid).set("claimQuoteKey", quoteHash(claim.quote)).set("claimRunId", runId)
                .set("checkState", CheckState.SETTLED.label)
                .also { m ->
                    claim.claimant?.let { m.set("claimClaimant", it) }; claim.claimantAsWritten?.let { m.set("claimantAsWritten", it) }
                    claim.place?.let { m.set("claimPlace", it) }
                }
                .save()
        }
    }

    /** Moves an established claim to Re-checking. The claim itself, its quote, its claimant and its chunks are untouched. */
    fun markRechecking(userEmail: String, itemUid: String, runId: String): Boolean {
        val item = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, itemUid) ?: return false
        if (item.get("checkState") == CheckState.RECHECKING.label) return false
        db.transaction { item.modify().set("checkState", CheckState.RECHECKING.label).set("recheckRunId", runId).save() }
        return true
    }

    /** The established claims of [sourceUid] whose quote is [quote] (one quote can be several claims, one per place in its chunk). */
    fun claimsWithQuote(userEmail: String, sourceUid: String, quote: String): List<ClaimItem> =
        db.query(
            "sql", "SELECT FROM Phrase WHERE claimSourceUid = :s AND claimQuoteKey = :k AND ownerEmail = :o",
            mapOf("s" to sourceUid, "k" to quoteHash(quote), "o" to userEmail),
        ).use { rs -> val out = ArrayList<ClaimItem>(); while (rs.hasNext()) out += toClaim(rs.next().toElement().asVertex()); out }

    /** Every established claim of [sourceUid]. */
    fun claims(userEmail: String, sourceUid: String): List<ClaimItem> =
        db.query("sql", "SELECT FROM Phrase WHERE claimSourceUid = :s AND ownerEmail = :o ORDER BY createdAt", mapOf("s" to sourceUid, "o" to userEmail))
            .use { rs -> val out = ArrayList<ClaimItem>(); while (rs.hasNext()) out += toClaim(rs.next().toElement().asVertex()); out }

    /** The established claims that sit in [chunkUid]. */
    fun claimsInChunk(userEmail: String, sourceUid: String, chunkUid: String): List<ClaimItem> = claims(userEmail, sourceUid).filter { chunkUid in it.chunkUids }

    private fun toClaim(v: Vertex): ClaimItem {
        val edges = v.getEdges(Vertex.DIRECTION.OUT, "QUOTES").toList()
        return ClaimItem(
            uid = v.get("uid") as String, quote = v.get("claimQuote") as? String ?: v.get("text") as? String ?: "", place = v.get("claimPlace") as? String,
            claimant = v.get("claimClaimant") as? String,
            claimantAsWritten = v.get("claimantAsWritten") as? String, subject = v.get("claimSubject") as? String ?: "",
            kind = v.get("claimKind") as? String ?: ClaimKind.FACT.name, runId = v.get("claimRunId") as? String,
            checkState = v.get("checkState") as? String ?: CheckState.SETTLED.label, recheckRunId = v.get("recheckRunId") as? String,
            chunkUids = edges.map { it.inVertex.asVertex().get("uid") as String }.distinct(),
        )
    }

    companion object {
        fun quoteHash(quote: String): String = digest(ClaimAgreement.quoteKey(quote))

        private fun digest(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
    }
}
