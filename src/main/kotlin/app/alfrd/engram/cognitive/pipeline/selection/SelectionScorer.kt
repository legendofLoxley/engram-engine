package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.model.ResponsePhrase
import app.alfrd.engram.model.SelectedEdge
import app.alfrd.engram.model.OutcomeSignal
import java.time.Instant
import java.time.LocalTime

/**
 * Stateless scoring functions for the five selection dimensions.
 * Each function returns a score in [0.0, 1.0].
 */
object SelectionScorer {

    // ── Freshness ───────────────────────────────────────────────────────────
    /**
     * Decay table:
     *   never used       = 1.0
     *   >7 days ago      = 0.9
     *   1–7 days ago     = 0.6
     *   same session, >10 turns ago = 0.3
     *   same session, ≤10 turns ago = 0.05
     *   last turn        = 0.0
     */
    fun freshness(
        phrase: ResponsePhrase,
        selectedEdges: List<SelectedEdge>,
        sessionId: String,
        turnIndex: Int,
        now: Instant,
    ): Double {
        if (selectedEdges.isEmpty()) return 1.0

        val mostRecent = selectedEdges.maxByOrNull { it.timestamp } ?: return 1.0

        // Same session?
        if (mostRecent.sessionId == sessionId) {
            val turnDelta = turnIndex - mostRecent.turnIndex
            return when {
                turnDelta <= 0 -> 0.0   // last turn (or current)
                turnDelta <= 10 -> 0.05
                else -> 0.3
            }
        }

        // Different session — time-based decay
        val ageMs = now.toEpochMilli() - mostRecent.timestamp
        val ageDays = ageMs / (1000.0 * 60 * 60 * 24)
        return when {
            ageDays > 7.0 -> 0.9
            ageDays >= 1.0 -> 0.6
            else -> 0.3 // < 1 day, different session
        }
    }

    // ── Contextual Fit ──────────────────────────────────────────────────────
    fun contextualFit(phrase: ResponsePhrase, ctx: CognitiveContext): Double {
        var score = 0.5 // baseline
        val text = phrase.text.lowercase()
        val hour = LocalTime.ofInstant(ctx.timestamp, ctx.zoneId ?: java.time.ZoneId.systemDefault()).hour
        val turnCount = ctx.priorUtterances.size + 1

        // Time-of-day alignment for greetings — non-overlapping windows
        if (phrase.category == "GREETING") {
            val isMorningPhrase   = text.contains("morning") && !text.contains("good morning. the day")
            val isAfternoonPhrase = text.contains("afternoon")
            val isEveningPhrase   = text.contains("evening")

            if (isMorningPhrase || isAfternoonPhrase || isEveningPhrase) {
                val inWindow = when {
                    isMorningPhrase   -> hour in 7..11
                    isAfternoonPhrase -> hour in 12..16
                    isEveningPhrase   -> hour in 17..21
                    else              -> true
                }
                return if (inWindow) 1.0 else 0.0
            }

            // Late-night phrase: 10 PM – 11:59 PM
            if (text.contains("midnight oil")) {
                return if (hour in 22..23) 1.0 else 0.0
            }

            // Early-start phrase: midnight – 6:59 AM
            if (text.contains("early start")) {
                return if (hour in 0..6) 1.0 else 0.0
            }

            // Session-gap-aware phrases
            val gapMs   = ctx.lastInteractionAt?.let { ctx.timestamp.toEpochMilli() - it }
            val gapDays = gapMs?.let { it / (1000.0 * 60 * 60 * 24) }

            if (text.contains("been a while")) {
                return if (gapDays != null && gapDays > 7) 1.0 else 0.1
            }

            if (text.contains("right where we left off")) {
                return if (gapDays != null && gapDays < 1.0) 1.0 else 0.1
            }

            // First-ever session: boost meet/acquaint phrases; penalise them for returning users
            if (text.contains("meet you") || text.contains("acquainted") || text.contains("work best when")) {
                return if (ctx.sessionCount == 0) 1.0 else 0.0
            }

            // Turn count: greetings score high on turn 1, decay toward 0 by turn 5
            score += when {
                turnCount == 1  -> 0.4
                turnCount <= 3  -> 0.2
                turnCount <= 5  -> 0.0
                else            -> -0.3
            }
        }

        // Session state: "Welcome back" scores higher on returning sessions
        if (text.contains("welcome back") || text.contains("see you again")) {
            val isReturning = ctx.trustPhase != null &&
                ctx.trustPhase != "ORIENTATION"
            score += if (isReturning) 0.3 else -0.3
        }

        return score.coerceIn(0.0, 1.0)
    }

    // ── Communication Fit ───────────────────────────────────────────────────
    fun communicationFit(phrase: ResponsePhrase, ctx: CognitiveContext): Double {
        // MVP: professional_warm baseline → 0.7 for all phrases
        return 0.7
    }

    // ── Phase Appropriateness ───────────────────────────────────────────────
    fun phaseAppropriateness(phrase: ResponsePhrase, ctx: CognitiveContext): Double {
        val currentPhase = ctx.trustPhase ?: return 0.5 // unknown → neutral
        val phaseAffinity = phrase.phaseAffinity

        if (currentPhase in phaseAffinity) return 1.0

        // Check adjacency
        val phases = listOf("ORIENTATION", "WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")
        val currentIdx = phases.indexOf(currentPhase)
        if (currentIdx == -1) return 0.5

        val adjacentPhases = buildSet {
            if (currentIdx > 0) add(phases[currentIdx - 1])
            if (currentIdx < phases.lastIndex) add(phases[currentIdx + 1])
        }

        return if (phaseAffinity.any { it in adjacentPhases }) 0.3 else 0.0
    }

    // ── Effectiveness ───────────────────────────────────────────────────────
    data class OutcomeSummary(val signal: OutcomeSignal, val count: Int)

    fun effectiveness(outcomeSummaries: List<OutcomeSummary>): Double {
        val totalCount = outcomeSummaries.sumOf { it.count }
        if (totalCount == 0) return 0.5 // baseline for new phrases

        val coldStartWeight = (totalCount / 10.0).coerceAtMost(1.0)

        val adjustment = outcomeSummaries.sumOf { summary ->
            val delta = when (summary.signal) {
                OutcomeSignal.ENGAGED -> 0.1
                OutcomeSignal.EXPANDED -> 0.2
                OutcomeSignal.CORRECTED -> -0.2
                OutcomeSignal.DISENGAGED -> -0.1
                OutcomeSignal.NEUTRAL -> 0.0
            }
            delta * summary.count
        }

        val raw = 0.5 + adjustment
        // Blend toward baseline with cold-start dampening
        val effectiveScore = 0.5 + (raw - 0.5) * coldStartWeight
        return effectiveScore.coerceIn(0.0, 1.0)
    }
}
