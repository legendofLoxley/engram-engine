package app.alfrd.engram.cognitive.pipeline.horizon

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** The result of [HorizonPropagator.propagate]. */
sealed interface PropagationOutcome {
    /**
     * [candidatesConsidered] is exactly the bounded pool [HorizonAssembler.listOpenCandidatesWithText]
     * returned — controlled debug evidence for what propagation actually had to work with,
     * independent of [edgesCreated]. [incompleteTargets] is every `(toPhraseUid, strength)` pair
     * where a salient-token overlap was found (so [HorizonGraphStore.markRelevant] was genuinely
     * attempted) but it returned `false` — a real write failure, e.g. an ownership check failing or
     * the consistency lock timing out. Defaults to empty so every pre-existing construction site
     * (including [NoOpHorizonPropagator]) is unaffected. Before this field existed, a partial
     * failure here was indistinguishable from full success: [SalientTokenPropagator] only ever
     * appended to [edgesCreated] on a true `markRelevant` result and otherwise silently moved on to
     * the next candidate, so `Propagated` alone never meant "every attempted edge exists."
     */
    data class Propagated(
        val edgesCreated: List<RelevanceEdgeSummary>,
        val candidatesConsidered: List<PropagationCandidate> = emptyList(),
        val incompleteTargets: List<RelevanceEdgeSummary> = emptyList(),
    ) : PropagationOutcome
    data class Failed(val reason: String) : PropagationOutcome
}

/**
 * One `relevant_to` edge propagation actually created (or found already present — see
 * [HorizonGraphStore.markRelevant]'s idempotency), for tracing — or, inside
 * [PropagationOutcome.Propagated.incompleteTargets], one it tried and failed to create.
 * `@Serializable` so [ActorEventIngestionService] can durably persist an incomplete set for precise
 * retry (`ASSERTS.incompletePropagationTargets`) without a bespoke encoding.
 */
@Serializable
data class RelevanceEdgeSummary(val fromPhraseUid: String, val toPhraseUid: String, val strength: Double)

/**
 * Bounded, deterministic, CPU-driven relevance derivation — the "Bounded CPU-driven propagation"
 * stage of the governing cognitive cycle, never another LLM call.
 *
 * **Inputs:** the phrase(s) actually confirmed-written *this cycle* — a user assertion, an
 * ordinary decomposed fact, or an environment signal, whichever the caller passed — compared
 * against the user's own bounded pool of `status=open` intentions
 * ([HorizonAssembler.listOpenCandidatesWithText], never [HorizonAssembler.assemble]'s output,
 * which is already trimmed to a small response-prompt budget before propagation would ever see
 * it — an eligible intention that didn't make that budget must still be reconsiderable).
 *
 * **Effect:** for each new phrase, a `relevant_to` edge (new → old, via
 * [HorizonGraphStore.markRelevant]) to every open candidate whose text shares at least one
 * "salient" token with it — not a ratio threshold, since short phrases make ratio thresholds
 * brittle and a single shared distinctive token (a proper noun, say) is exactly the signal this
 * task's scenario needs. Recorded `strength` is `|intersection| / min(|new|, |candidate|)`,
 * deterministic and explainable.
 *
 * **Limits, stated rather than hidden:** this is lexical, not semantic — a full paraphrase
 * sharing no vocabulary with the open item will never connect; a coincidentally-shared *rare*
 * word between two genuinely unrelated topics can still false-positive. "Not a stopword" is not
 * "distinctive": the salience filter excludes both ordinary function words and a second,
 * deliberately generic tier of high-frequency conversational words (see [salientTokens]) —
 * reducing, not eliminating, the risk of connecting two phrases that merely share common
 * vocabulary. Only targets `OPEN`-status intentions, never plain facts or resolved items —
 * unresolved intentions/obligations are what deserve to resurface, per the governing design.
 * Bounded to this cycle's new phrase(s) × the candidate pool's own limit — cost independent of
 * total graph size. Never hard-codes any fixture-specific term; the stopword list is generic
 * English, not tuned to any particular scenario's vocabulary.
 */
interface HorizonPropagator {
    /**
     * [newPhrases] is `(phraseUid, text)` for whatever was actually confirmed-written this cycle —
     * empty on most turns, by design: a genuinely bounded no-op, not wasted work.
     */
    suspend fun propagate(userEmail: String, cycleSeq: Long, newPhrases: List<Pair<String, String>>): PropagationOutcome
}

/**
 * Always a no-op — creates nothing, never fails. Exists so the propagation-enabled-vs-disabled
 * comparison can hold graph state, the assembler, and the coordinator identical between arms and
 * vary only this one collaborator.
 */
class NoOpHorizonPropagator : HorizonPropagator {
    override suspend fun propagate(userEmail: String, cycleSeq: Long, newPhrases: List<Pair<String, String>>): PropagationOutcome =
        PropagationOutcome.Propagated(emptyList())
}

class SalientTokenPropagator(
    private val horizonGraphStore: HorizonGraphStore,
    private val horizonAssembler: HorizonAssembler,
    private val candidatePoolLimit: Int = 200,
) : HorizonPropagator {

    private val logger = LoggerFactory.getLogger(SalientTokenPropagator::class.java)

    override suspend fun propagate(
        userEmail: String,
        cycleSeq: Long,
        newPhrases: List<Pair<String, String>>,
    ): PropagationOutcome {
        if (newPhrases.isEmpty()) return PropagationOutcome.Propagated(emptyList())
        return try {
            val candidates = horizonAssembler.listOpenCandidatesWithText(userEmail, candidatePoolLimit)
            if (candidates.isEmpty()) return PropagationOutcome.Propagated(emptyList(), candidates)

            val created = mutableListOf<RelevanceEdgeSummary>()
            val incomplete = mutableListOf<RelevanceEdgeSummary>()
            for ((newUid, newText) in newPhrases) {
                val newSalient = salientTokens(newText)
                if (newSalient.isEmpty()) continue
                for (candidate in candidates) {
                    if (candidate.phraseUid == newUid) continue // never a self-link
                    val candidateSalient = salientTokens(candidate.text)
                    if (candidateSalient.isEmpty()) continue
                    val intersection = newSalient intersect candidateSalient
                    if (intersection.isEmpty()) continue
                    val strength = intersection.size.toDouble() / minOf(newSalient.size, candidateSalient.size)
                    val applied = horizonGraphStore.markRelevant(userEmail, cycleSeq, newUid, candidate.phraseUid, strength)
                    val summary = RelevanceEdgeSummary(newUid, candidate.phraseUid, strength)
                    if (applied) created += summary else incomplete += summary
                }
            }
            PropagationOutcome.Propagated(created, candidates, incomplete)
        } catch (e: Exception) {
            logger.warn("propagate failed for userEmail=$userEmail cycleSeq=$cycleSeq: ${e.message}")
            PropagationOutcome.Failed("propagate failed for userEmail=$userEmail: ${e.message}")
        }
    }

    companion object {
        /**
         * Generic, fixture-independent English stopwords: ordinary function words plus a second,
         * deliberately generic tier of high-frequency conversational words ("get", "app", "data",
         * "just", "priority", ...) that are common enough in ordinary speech to be weak evidence
         * of actual relatedness on their own. Sharing one of these is not "distinctive" — see this
         * file's class doc for what that means for the rule's accuracy.
         */
        private val GENERIC_STOPWORDS: Set<String> = setOf(
            "a", "an", "the", "and", "or", "but", "if", "then", "so", "because", "as", "of", "at",
            "by", "for", "with", "about", "against", "between", "into", "through", "during",
            "before", "after", "above", "below", "to", "from", "up", "down", "in", "out", "on",
            "off", "over", "under", "again", "further", "once", "here", "there", "when", "where",
            "why", "how", "all", "any", "both", "each", "few", "more", "most", "other", "some",
            "such", "no", "nor", "not", "only", "own", "same", "than", "too", "very", "can",
            "will", "don", "should", "now", "i", "me", "my", "myself", "we", "our", "ours",
            "ourselves", "you", "your", "yours", "yourself", "yourselves", "he", "him", "his",
            "himself", "she", "her", "hers", "herself", "it", "its", "itself", "they", "them",
            "their", "theirs", "themselves", "what", "which", "who", "whom", "this", "that",
            "these", "those", "am", "is", "are", "was", "were", "be", "been", "being", "have",
            "has", "had", "having", "do", "does", "did", "doing", "would", "could", "shall",
            "might", "must", "let", "lets",
            "get", "getting", "got", "good", "app", "apps", "data", "keep", "keeping", "meaning",
            "running", "run", "thing", "things", "make", "making", "want", "wanted", "need",
            "needed", "know", "knew", "like", "liked", "just", "really", "priority", "priorities",
        )

        /** Lowercased, tokenized, stopword- and short-token-filtered — see class doc for the rule this feeds. */
        internal fun salientTokens(text: String): Set<String> =
            text.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 3 && it !in GENERIC_STOPWORDS }
                .toSet()
    }
}
