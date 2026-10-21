package app.alfrd.engram.claims

import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.SourceRecord
import kotlinx.serialization.Serializable

/** What sort of assertion a claim is. Carried on the claim; never used to rank or weigh it. */
enum class ClaimKind {
    FACT, NUMBER, DECISION, VOTE, COMMITMENT, DEADLINE, REQUIREMENT, POSITION;

    companion object {
        fun parse(raw: String?): ClaimKind? = entries.firstOrNull { it.name.equals(raw?.trim()?.replace(' ', '_'), ignoreCase = true) }
    }
}

/**
 * One claim a pass reported for a chunk: the passage it rests on, who asserted it ([claimant] as the pass wrote it; null when the
 * passage names nobody), what it is about, its kind, and, optionally, where in the chunk it sits (a table row, a box).
 */
@Serializable
data class PassClaim(
    val quote: String,
    val claimant: String? = null,
    val subject: String = "",
    val kind: String = ClaimKind.FACT.name,
    val place: String? = null,
)

/** What one pass returned for one chunk, and the model that actually answered. */
data class PassResult(val claims: List<PassClaim>, val modelName: String)

/**
 * One pass of the claim model over one chunk. The two passes of a chunk are separate calls that share nothing: neither sees the
 * other's answer. [modelName] comes from what the server reports on each response, never from configuration.
 */
interface ClaimPassModel {
    /** Where the model is served, for the run's record. */
    val endpoint: String?

    /** Names the instructions the passes were given, so a run says which wording produced it. */
    val promptVersion: String

    /** [pass] is 1 or 2. Throws when the pass could not be completed or its answer could not be read. */
    suspend fun extract(source: SourceRecord, chunk: ChunkRecord, pass: Int): PassResult
}

/** The check state of an established claim. There is no number anywhere: a claim is Settled until a later run questions it. */
enum class CheckState(val label: String) { SETTLED("Settled"), RECHECKING("Re-checking") }

/** One version of a claim as a pass reported it, after its claimant was resolved. */
@Serializable
data class ClaimVersion(
    val quote: String,
    val claimant: String? = null,
    val claimantAsWritten: String? = null,
    val subject: String = "",
    val kind: String = ClaimKind.FACT.name,
    val place: String? = null,
)

/** Two passes' versions of a claim that did not agree, kept as recorded. A version is null when that pass reported nothing like it. */
data class Disagreement(val chunkUid: String, val reason: String, val versionA: ClaimVersion?, val versionB: ClaimVersion?)

/** A claim both passes reported with the same quote, claimant and place: the only thing that can become an established claim. */
data class AgreedClaim(val chunkUid: String, val quote: String, val claimant: String?, val claimantAsWritten: String?, val subject: String, val kind: ClaimKind, val place: String?)

/** The outcome for one chunk. [failed] says why the chunk produced nothing when a pass could not be completed. */
data class ChunkOutcome(val chunkUid: String, val agreed: List<AgreedClaim>, val disagreements: List<Disagreement>, val failed: String? = null)

/** What a run did, counted. Agreement is a diagnostic about the two passes, not a measure of whether a claim is true. */
@Serializable
data class RunReport(
    val runId: String,
    val sourceUid: String,
    val status: String,
    val modelName: String,
    val modelEndpoint: String?,
    val promptVersion: String,
    val passes: Int,
    val chunksTotal: Int,
    val chunksDone: Int,
    val chunksFailed: Int,
    val claimsEstablished: Int,
    val claimsAlreadyThere: Int,
    val locationsAdded: Int,
    val claimsRechecking: Int,
    val disagreements: Int,
    val quotesRejected: Int,
    /** Claims both passes agreed on, and all distinct claims either pass reported with a usable quote. A diagnostic about the passes, not about truth. */
    val claimsAgreed: Int,
    val claimsReported: Int,
    /** [claimsAgreed] over [claimsReported]; null when neither pass reported anything. */
    val agreementRate: Double?,
    /** What the model name was required to contain; null when the run was made without that check (the stamp is then unverified). */
    val modelExpectation: String?,
    val failures: List<String> = emptyList(),
)
