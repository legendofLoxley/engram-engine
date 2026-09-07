package app.alfrd.engram.cognitive.pipeline.horizon

/**
 * The bounded, evidence-backed read model this package assembles from durable graph state.
 *
 * This is a foundation increment: [HorizonGraphStore] (write) and [HorizonAssembler] (read) are
 * standalone and unwired — no [app.alfrd.engram.cognitive.pipeline.CognitivePipeline] call site
 * exists yet. Propagation judgment (deciding what's relevant) is out of scope; every
 * `relevant_to`/`supersedes` edge a caller creates here is asserted directly, never derived.
 */

/**
 * `Source.type` for environment-originated evidence, reusing the existing
 * `User -TRUSTS-> Source -ASSERTS-> Phrase` shape conversational facts already use (confirmed no
 * exhaustive consumer of `Source.type` anywhere in `src/main`). Closes the gap where
 * `EngramClient.ingest()` hardcodes `Source.type = "onboarding_conversation"`.
 */
const val ENVIRONMENT_SOURCE_TYPE = "environment_signal"

/** Tunable bounds shared by [HorizonGraphStore] and [HorizonAssembler]. Foundation defaults, not policy. */
object HorizonLimits {
    const val MAX_ITEM_TEXT_LENGTH = 280
    const val MAX_SOURCE_REFS_PER_ITEM = 3
    const val OMITTED_SAMPLE_CAP = 5
    const val DEFAULT_RELEVANCE_CYCLES = 3
    const val CANDIDATE_POOL_OVERFETCH = 3 // per-bucket over-fetch multiplier vs. budget.maxItems
    const val SCHEMA_VERSION = 1
}

/** What kind of content this is — independent of its lifecycle, why it surfaced, or who asserted it. */
enum class HorizonItemCategory { INTENTION, FACT, ENVIRONMENT_EVENT, CORRECTION }

/** Lifecycle of an intention-shaped assertion. Absent (null) on a [HorizonItem] means not intention-shaped at all. */
enum class AssertionStatus { OPEN, RESOLVED }

/**
 * Explicit user instruction about attention (CH-05: "keep this in view" / "don't bring this up").
 * Modeled so a future increment can populate it without another migration — always null this increment;
 * no code path here sets it.
 */
enum class AttentionDirective { PINNED, SUPPRESSED }

/** Who/what asserted an item — kept distinct from category and lifecycle. */
enum class ProvenanceKind { EXPLICIT_USER_STATEMENT, ENVIRONMENT_SIGNAL, MODEL_INFERENCE }

/**
 * Inlined text capped at [HorizonLimits.MAX_ITEM_TEXT_LENGTH]. The full text is always recoverable
 * by looking up the phrase uid this accompanies — truncation here bounds serialized output, it
 * never destroys the underlying evidence.
 */
data class BoundedText(val text: String, val truncated: Boolean) {
    companion object {
        fun of(fullText: String, maxLength: Int = HorizonLimits.MAX_ITEM_TEXT_LENGTH): BoundedText =
            if (fullText.length > maxLength) BoundedText(fullText.take(maxLength) + "…", truncated = true)
            else BoundedText(fullText, truncated = false)
    }
}

/**
 * A pointer back to the specific graph vertex/edge an item or omission derives from — evidence is
 * preserved by reference, never only by an inlined summary. [cycleSeq] is the cycle the underlying
 * `ASSERTS` edge was written in (identity, not a timestamp).
 */
data class SourceRef(
    val phraseUid: String,
    val sourceUid: String,
    val sourceType: String,
    val assertedAt: Long,
    val cycleSeq: Long,
)

/** Why a `relevant_to` edge exists to fresh evidence — the specific, inspectable graph change behind an [SurfacingReason.ActiveReactivation]. */
data class ReactivationInfo(
    val triggeringPhraseUid: String,
    val triggeringPhraseText: BoundedText,
    val strength: Double,
    val edgeCycleSeq: Long,
    val edgeCreatedAt: Long,
)

/**
 * Why an item is included in *this* [ContextHorizon] — kept distinct from [HorizonItemCategory]
 * (what it is) and [AssertionStatus] (its lifecycle). Cycle-identity based, never a wall-clock
 * proximity check — see [HorizonAssembler.assemble].
 */
sealed interface SurfacingReason {
    /** Asserted in the exact cycle this snapshot was assembled for. */
    data class JustAsserted(val cycleSeq: Long) : SurfacingReason
    /** Has a `relevant_to` edge whose cycle is within the assembler's active-relevance window. */
    data class ActiveReactivation(val info: ReactivationInfo) : SurfacingReason
    /** Status is OPEN, asserted in an earlier cycle, and not actively reactivated right now. */
    data class DormantOpen(val lastStatusChangeCycleSeq: Long) : SurfacingReason
}

/** One bounded candidate in a [ContextHorizon]. */
data class HorizonItem(
    val category: HorizonItemCategory,
    val text: BoundedText,
    val status: AssertionStatus?,
    val attentionDirective: AttentionDirective?,
    val provenance: ProvenanceKind,
    val surfacing: SurfacingReason,
    val sourceRefs: List<SourceRef>,
    val sourceCount: Int,
)

/** A candidate excluded from `items` by the budget. Reference-only — no inlined text, nothing to truncate. */
data class OmittedRef(val sourceRef: SourceRef, val category: HorizonItemCategory, val reason: String)

data class HorizonBudget(val maxItems: Int, val itemCount: Int, val truncated: Boolean) {
    companion object {
        val DEFAULT = HorizonBudget(maxItems = 12, itemCount = 0, truncated = false)
    }
}

/**
 * The bounded Horizon snapshot. Never itself persisted — derived fresh from graph state on every
 * call, so restart recovery is just re-assembling (see [HorizonAssembler]). Every collection here
 * is capped regardless of graph size; [omittedAtLeast] is an honest lower bound, not an exact count
 * (see [ContextHorizon] doc on `moreCandidatesAvailable` below) — an exact enumeration is only ever
 * obtained by walking [HorizonAssembler.listCandidates] to completion.
 */
data class ContextHorizon(
    val userEmail: String,
    val asOf: Long,
    val schemaVersion: Int,
    val items: List<HorizonItem>,
    val budget: HorizonBudget,
    val omittedSample: List<OmittedRef>,
    /** Lower bound on the true overflow count — the bounded over-fetch pool's overflow, not a global count. */
    val omittedAtLeast: Int,
    /** True if a bounded sub-query itself hit its own pool cap — candidates may exist beyond what was inspected at all. */
    val moreCandidatesAvailable: Boolean,
)

/** One page of a full, explicitly paginated candidate enumeration — see [HorizonAssembler.listCandidates]. */
data class CandidatePage(val items: List<SourceRef>, val nextCursor: String?)
