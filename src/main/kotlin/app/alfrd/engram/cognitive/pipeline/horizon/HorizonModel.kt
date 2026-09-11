package app.alfrd.engram.cognitive.pipeline.horizon

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

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

/**
 * `Source.type` values for the three Actor-attributed provenance kinds [ActorEventIngestionService]
 * distinguishes — never inferred from a caller-supplied free-text field (see that service's
 * `ActorEventKind`). Kept distinct from each other and from [ENVIRONMENT_SOURCE_TYPE] so
 * [ArcadeHorizonAssembler.provenanceFor] never defaults any of them to
 * [ProvenanceKind.EXPLICIT_USER_STATEMENT].
 */
const val ACTOR_OBSERVATION_SOURCE_TYPE = "actor_observation"
const val ACTOR_INTERPRETATION_SOURCE_TYPE = "actor_interpretation"
const val ACTOR_TOOL_RESULT_SOURCE_TYPE = "actor_tool_result"

/** The bounded-recency evidence pool in [ArcadeHorizonAssembler.assemble] is scoped to exactly these — see [SurfacingReason.RecentActorEvidence]. Deliberately excludes [ENVIRONMENT_SOURCE_TYPE]: that path's own visibility gap is a named follow-up, not touched by this increment. */
val ACTOR_ATTRIBUTED_SOURCE_TYPES: Set<String> = setOf(
    ACTOR_OBSERVATION_SOURCE_TYPE,
    ACTOR_INTERPRETATION_SOURCE_TYPE,
    ACTOR_TOOL_RESULT_SOURCE_TYPE,
)

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

/**
 * Who/what asserted an item — kept distinct from category and lifecycle. [ACTOR_OBSERVATION],
 * [ACTOR_INTERPRETATION] and [ACTOR_TOOL_RESULT] are three deliberately distinct kinds (never
 * collapsed into one "Actor" value, and never defaulted to [EXPLICIT_USER_STATEMENT]): an Actor
 * directly reporting something it observed, an inference the Actor drew, and a tool's own reported
 * result are different epistemic claims — see [ActorEventIngestionService.ActorEventKind]. None of
 * these three is independently verified by this increment merely because a caller labels an event
 * "tool" — see that service's doc.
 */
@Serializable
enum class ProvenanceKind {
    EXPLICIT_USER_STATEMENT, ENVIRONMENT_SIGNAL, MODEL_INFERENCE,
    ACTOR_OBSERVATION, ACTOR_INTERPRETATION, ACTOR_TOOL_RESULT,
}

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
    /**
     * Not open-status, not this cycle's own content, not actively reactivated — surfaced purely
     * because it is Actor-attributed evidence ([ACTOR_ATTRIBUTED_SOURCE_TYPES]) asserted within the
     * bounded recency window ([ArcadeHorizonAssembler.assemble]'s `activeRelevanceCycles`, same knob
     * `ActiveReactivation` already uses). Deliberately never [DormantOpen]: that variant's name and
     * precondition specifically mean an open-status intention, and a completed Actor result is not
     * one — mislabeling it that way just to keep it visible was explicitly ruled out.
     *
     * **This is eligibility, not a guarantee of inclusion.** [essential] is false for this reason in
     * [app.alfrd.engram.cognitive.pipeline.HorizonItemsRenderer] — [ArcadeHorizonAssembler.assemble]'s
     * own pool limit / byte budget, and separately [app.alfrd.engram.cognitive.pipeline.Actor]'s
     * prompt budget ladder, can both still drop it under pressure from higher-priority or more
     * numerous competing candidates, exactly as they already can for any other droppable item. It
     * also stops being eligible at all once `currentCycleSeq` moves past the window — this does not
     * make evidence persist indefinitely, and it does not by itself complete "Director freshness."
     */
    @Serializable
    data class RecentActorEvidence(val assertedCycleSeq: Long) : SurfacingReason
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

/**
 * The immutable payload [actorEventFingerprint] hashes — every field that, if it differed between
 * two deliveries under the same `eventId`, would mean they are genuinely different events (a
 * conflicting reuse), not a retry of the same one. Deliberately excludes anything server-generated
 * or allocated (receipt time, `cycleSeq`) — those can legitimately differ between an original
 * delivery and its retry without the underlying event being any different. [occurredAt] is the
 * **raw, caller-supplied** value, `null` when omitted — never defaulted to a receipt time before
 * hashing, or an identical retry that omits it would conflict with itself purely because the wall
 * clock advanced between attempts. A `data class` (not a `Map`) so kotlinx.serialization's JSON
 * encoding is fixed by declaration order — deterministic without relying on map-iteration order —
 * and so embedded delimiter-like characters in [text] can never shift field boundaries the way naive
 * string concatenation could.
 */
@Serializable
private data class ActorEventFingerprintPayload(
    val provenanceSourceType: String,
    val sourceName: String,
    val text: String,
    val assignmentId: String? = null,
    val occurredAt: Long? = null,
    val basis: String? = null,
    val toolName: String? = null,
    val toolSucceeded: Boolean? = null,
)

private val fingerprintJson = Json { encodeDefaults = true }

/** SHA-256 over [ActorEventFingerprintPayload]'s canonical JSON encoding — see that type's doc for exactly what is and isn't included. */
fun actorEventFingerprint(
    provenanceSourceType: String,
    sourceName: String,
    text: String,
    assignmentId: String?,
    occurredAt: Long?,
    basis: String? = null,
    toolName: String? = null,
    toolSucceeded: Boolean? = null,
): String {
    val payload = ActorEventFingerprintPayload(provenanceSourceType, sourceName, text, assignmentId, occurredAt, basis, toolName, toolSucceeded)
    val canonical = fingerprintJson.encodeToString(payload)
    val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
