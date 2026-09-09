package app.alfrd.engram.cognitive.pipeline.horizon

import kotlinx.serialization.Serializable

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

    /**
     * The total budget on a [ContextHorizon]'s serialized (JSON, `kotlinx.serialization` — the
     * format this codebase already uses for every other outbound payload) UTF-8-encoded byte size,
     * at worst-case population for a given [maxItems]. Every collection ([maxItems] items,
     * [MAX_SOURCE_REFS_PER_ITEM] refs each, [OMITTED_SAMPLE_CAP] omitted entries) and every inlined
     * text field ([MAX_ITEM_TEXT_LENGTH] chars, `+1` for the truncation ellipsis) is already fixed
     * regardless of graph size — this is a byte-denominated restatement of that same bound, sized
     * generously enough to absorb the worst case for a single JSON-encoded UTF-16 code unit: 3 bytes
     * (a non-surrogate BMP character, e.g. CJK, needs 3 UTF-8 bytes per code unit — worse than a
     * surrogate-pair character's 4 bytes / 2 units, and worse than an escaped ASCII control
     * character's 2 bytes / 1 unit; see [BoundedText.of] and `HorizonAssemblerTest` for the measured
     * evidence this holds even under adversarial escaping/multibyte content). `refFieldCeiling`
     * generously covers a `SourceRef`'s uid/type fields plus its JSON field-name/punctuation
     * overhead; `perItemStructuralCeiling` covers a `HorizonItem`'s own field names/punctuation.
     * Scales with [maxItems] rather than a fixed constant so a caller requesting a nondefault item
     * budget (via [HorizonBudget.maxItems]) is checked against a correspondingly sized ceiling, not
     * silently held to the default's — [ArcadeHorizonAssembler] enforces this at assembly time.
     */
    private const val BYTES_PER_UTF16_UNIT_WORST_CASE = 3
    private const val REF_FIELD_CEILING_BYTES = 200
    private const val PER_ITEM_STRUCTURAL_CEILING_BYTES = 300
    private const val TOP_LEVEL_STRUCTURAL_CEILING_BYTES = 1_000
    fun serializedByteBudget(maxItems: Int): Int {
        val perItemTextBytes = 2 * (MAX_ITEM_TEXT_LENGTH + 1) * BYTES_PER_UTF16_UNIT_WORST_CASE // item text + reactivation triggering text
        val perItemBytes = perItemTextBytes + MAX_SOURCE_REFS_PER_ITEM * REF_FIELD_CEILING_BYTES + PER_ITEM_STRUCTURAL_CEILING_BYTES
        return maxItems * perItemBytes + OMITTED_SAMPLE_CAP * REF_FIELD_CEILING_BYTES + TOP_LEVEL_STRUCTURAL_CEILING_BYTES
    }
    val MAX_SERIALIZED_HORIZON_BYTES: Int = serializedByteBudget(HorizonBudget.DEFAULT.maxItems)
}

/** What kind of content this is — independent of its lifecycle, why it surfaced, or who asserted it. */
@Serializable
enum class HorizonItemCategory { INTENTION, FACT, ENVIRONMENT_EVENT, CORRECTION }

/** Lifecycle of an intention-shaped assertion. Absent (null) on a [HorizonItem] means not intention-shaped at all. */
@Serializable
enum class AssertionStatus { OPEN, RESOLVED }

/**
 * Explicit user instruction about attention (CH-05: "keep this in view" / "don't bring this up").
 * Modeled so a future increment can populate it without another migration — always null this increment;
 * no code path here sets it.
 */
@Serializable
enum class AttentionDirective { PINNED, SUPPRESSED }

/** Who/what asserted an item — kept distinct from category and lifecycle. */
@Serializable
enum class ProvenanceKind { EXPLICIT_USER_STATEMENT, ENVIRONMENT_SIGNAL, MODEL_INFERENCE }

/**
 * Inlined text capped at [HorizonLimits.MAX_ITEM_TEXT_LENGTH]. The full text is always recoverable
 * by looking up the phrase uid this accompanies — truncation here bounds serialized output, it
 * never destroys the underlying evidence.
 */
@Serializable
data class BoundedText(val text: String, val truncated: Boolean) {
    companion object {
        /**
         * Truncates at [maxLength] UTF-16 code units, but never mid-surrogate-pair: cutting a
         * multibyte character (e.g. an emoji, encoded as a high+low surrogate pair) in half leaves
         * a lone surrogate, which is not valid UTF-16 and does not round-trip through UTF-8
         * encoding — confirmed empirically (`String.toByteArray(UTF_8)` followed by decoding back
         * does not reproduce the original truncated string; the lone surrogate is replaced). Back
         * off one code unit when the character straddling the cut point is a high surrogate so the
         * result always ends on a complete character.
         */
        fun of(fullText: String, maxLength: Int = HorizonLimits.MAX_ITEM_TEXT_LENGTH): BoundedText {
            if (fullText.length <= maxLength) return BoundedText(fullText, truncated = false)
            var cut = maxLength
            if (cut > 0 && Character.isHighSurrogate(fullText[cut - 1])) cut -= 1
            return BoundedText(fullText.substring(0, cut) + "…", truncated = true)
        }
    }
}

/**
 * A pointer back to the specific graph vertex/edge an item or omission derives from — evidence is
 * preserved by reference, never only by an inlined summary. [cycleSeq] is the cycle the underlying
 * `ASSERTS` edge was written in (identity, not a timestamp).
 */
@Serializable
data class SourceRef(
    val phraseUid: String,
    val sourceUid: String,
    val sourceType: String,
    val assertedAt: Long,
    val cycleSeq: Long,
)

/** Why a `relevant_to` edge exists to fresh evidence — the specific, inspectable graph change behind an [SurfacingReason.ActiveReactivation]. */
@Serializable
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
@Serializable
sealed interface SurfacingReason {
    /** Asserted in the exact cycle this snapshot was assembled for. */
    @Serializable
    data class JustAsserted(val cycleSeq: Long) : SurfacingReason
    /** Has a `relevant_to` edge whose cycle is within the assembler's active-relevance window. */
    @Serializable
    data class ActiveReactivation(val info: ReactivationInfo) : SurfacingReason
    /** Status is OPEN, asserted in an earlier cycle, and not actively reactivated right now. */
    @Serializable
    data class DormantOpen(val lastStatusChangeCycleSeq: Long) : SurfacingReason
}

/** One bounded candidate in a [ContextHorizon]. */
@Serializable
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
@Serializable
data class OmittedRef(val sourceRef: SourceRef, val category: HorizonItemCategory, val reason: String)

@Serializable
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
@Serializable
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
@Serializable
data class CandidatePage(val items: List<SourceRef>, val nextCursor: String?)

/**
 * One open-status candidate with its text, as returned by [HorizonAssembler.listOpenCandidatesWithText]
 * for propagation — deliberately not [SourceRef] (reference-only by design, used inside bounded
 * [ContextHorizon] payloads) and not routed through [HorizonAssembler.assemble] (already trimmed
 * to a small response-prompt budget before propagation would ever see it).
 */
@Serializable
data class PropagationCandidate(val phraseUid: String, val text: String, val cycleSeq: Long)
