package app.alfrd.engram.cognitive.pipeline.confidence

import app.alfrd.engram.cognitive.pipeline.memory.ConfidenceEvidenceEntry
import app.alfrd.engram.cognitive.pipeline.memory.ConfidenceEvidenceKind
import app.alfrd.engram.cognitive.pipeline.memory.ConfidencePhase
import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.model.OutcomeSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Records evidence that moves a topic's [app.alfrd.engram.cognitive.pipeline.memory.TopicConfidence]
 * per the Onboarding Scaffold Specification §4 (amended): confidence is earned per-topic, never
 * decays from being wrong, and the only non-monotonic state — an unresolved contradiction —
 * resolves upward only.
 *
 * All public methods are **non-suspend and fire-and-forget** (`scope.launch { ... }`), matching
 * [app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService.recordOutcome] and
 * [app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService.captureUtterance] — these fire
 * every turn, so awaiting a real graph write here would risk exactly the kind of hang the
 * "10 consecutive turns without crash or hang" acceptance criterion checks for.
 *
 * Deltas are always >= 0 — every code path here only ever raises a topic's score. There is no
 * method that lowers one.
 */
class TopicConfidenceService(
    private val engramClient: EngramClient,
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val logger = LoggerFactory.getLogger(TopicConfidenceService::class.java)

    companion object {
        // Explicit feedback is weighted higher than inferred competence, per spec.
        const val COMPETENCE_ENGAGED_DELTA = 0.05
        const val COMPETENCE_EXPANDED_DELTA = 0.08
        const val FEEDBACK_AFFIRMED_DELTA = 0.25
        const val CORRECTION_CONFIRMED_DELTA = 0.20

        // Score -> phase bucketing. Tunable; scores are otherwise unbounded (monotonic).
        const val WORKING_RHYTHM_THRESHOLD = 0.2
        const val CONTEXT_THRESHOLD = 0.5
        const val UNDERSTANDING_THRESHOLD = 0.9

        private const val MAX_EVIDENCE_ENTRIES = 20

        fun phaseFor(score: Double): ConfidencePhase = when {
            score >= UNDERSTANDING_THRESHOLD -> ConfidencePhase.UNDERSTANDING
            score >= CONTEXT_THRESHOLD -> ConfidencePhase.CONTEXT
            score >= WORKING_RHYTHM_THRESHOLD -> ConfidencePhase.WORKING_RHYTHM
            else -> ConfidencePhase.ORIENTATION
        }
    }

    /** Demonstrated competence — inferred from response-phrase effectiveness. ENGAGED/EXPANDED only; other signals never lower confidence, so they simply no-op. */
    fun recordDemonstratedCompetence(userEmail: String, topic: String?, signal: OutcomeSignal) {
        val delta = when (signal) {
            OutcomeSignal.ENGAGED -> COMPETENCE_ENGAGED_DELTA
            OutcomeSignal.EXPANDED -> COMPETENCE_EXPANDED_DELTA
            else -> return
        }
        recordEvidence(userEmail, topic, ConfidenceEvidenceKind.COMPETENCE, delta, "outcome=$signal")
    }

    /** Explicit user affirmation — first-class, higher-weighted than inferred competence. */
    fun recordExplicitAffirmation(userEmail: String, topic: String?) {
        recordEvidence(userEmail, topic, ConfidenceEvidenceKind.FEEDBACK_AFFIRMED, FEEDBACK_AFFIRMED_DELTA, "explicit affirmation")
    }

    /** A correction was detected and is about to be written — flags the topic as temporarily uncertain. No score change. */
    fun recordContradictionDetected(userEmail: String, topic: String?) {
        if (userEmail.isBlank() || topic.isNullOrBlank()) return
        scope.launch {
            try {
                val current = engramClient.getTopicConfidence(userEmail, topic)
                if (current.hasUnresolvedContradiction) return@launch
                engramClient.updateTopicConfidence(
                    userEmail, topic,
                    current.copy(hasUnresolvedContradiction = true, updatedAt = clock.millis()),
                )
            } catch (e: Exception) {
                logger.warn("recordContradictionDetected failed for userEmail=$userEmail topic=$topic: ${e.message}")
            }
        }
    }

    /** A correction was applied and confirmed — clears the contradiction flag and raises confidence. Never lowers it. */
    fun recordCorrectionConfirmed(userEmail: String, topic: String?) {
        recordEvidence(
            userEmail, topic, ConfidenceEvidenceKind.CORRECTION_CONFIRMED, CORRECTION_CONFIRMED_DELTA,
            "correction applied and confirmed", resolveContradiction = true,
        )
    }

    // ── Shared evidence application ──────────────────────────────────────────

    private fun recordEvidence(
        userEmail: String,
        topic: String?,
        kind: ConfidenceEvidenceKind,
        delta: Double,
        note: String,
        resolveContradiction: Boolean = false,
    ) {
        if (userEmail.isBlank() || topic.isNullOrBlank()) return
        scope.launch {
            try {
                val current = engramClient.getTopicConfidence(userEmail, topic)
                val now = clock.millis()
                val newScore = current.score + delta
                val updated = current.copy(
                    score = newScore,
                    phase = phaseFor(newScore),
                    hasUnresolvedContradiction = if (resolveContradiction) false else current.hasUnresolvedContradiction,
                    evidence = (current.evidence + ConfidenceEvidenceEntry(kind, delta, now, note)).takeLast(MAX_EVIDENCE_ENTRIES),
                    updatedAt = now,
                )
                engramClient.updateTopicConfidence(userEmail, topic, updated)
            } catch (e: Exception) {
                logger.warn("recordEvidence failed for userEmail=$userEmail topic=$topic kind=$kind: ${e.message}")
            }
        }
    }
}
