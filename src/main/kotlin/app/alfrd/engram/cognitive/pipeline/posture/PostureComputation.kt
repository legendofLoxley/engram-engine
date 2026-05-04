package app.alfrd.engram.cognitive.pipeline.posture

import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.cognitive.providers.TranscriptionResult
import app.alfrd.engram.model.PostureMoveType

// ── Disfluency / signal lexicons ────────────────────────────────────────────

/** Single-word fillers indicating hedging or an ongoing thought. */
private val FILLER_WORDS = setOf(
    "um", "uh", "er", "ah", "hmm", "hm",
    "like", "actually", "basically",
)

/** Emotional intensifiers that raise surface energy. */
private val EMOTIONAL_MARKERS = setOf(
    "really", "very", "absolutely", "definitely", "totally",
    "completely", "extremely", "honestly", "seriously",
    "wow", "amazing", "terrible", "awful", "great", "incredible",
    "stressed", "overwhelmed", "frustrated", "worried", "anxious",
    "nervous", "upset", "excited", "devastated", "thrilled",
)

/** Words that trigger interrogative classification when sentence-initial. */
private val QUESTION_WORDS = setOf("who", "what", "when", "where", "why", "how", "which", "whose", "whom")

/** Task-request openers — imperative or request phrasing with specific action verbs. */
private val TASK_REQUEST_PATTERN = Regex(
    // Pure imperatives at start of utterance
    """^(please |show me |find |tell me |set |get |make |create |send |schedule )"""
    // Polite requests ('can/could/would you') paired with a concrete action verb;
    // generic phrases like 'can you remind me' do NOT match — only task-oriented verbs do.
    + """|(can|could|would) you (find|schedule|set|get|make|create|send|show|add|remove|update|delete|check|book|order|search|look up|open|pull up)""",
    RegexOption.IGNORE_CASE,
)

/** Personal-sharing markers indicating a Disclosure turn. */
private val DISCLOSURE_PATTERN = Regex(
    """\b(i feel|i felt|i was|i am|i'm|i've been|i think|i believe|my )\b""",
    RegexOption.IGNORE_CASE,
)

/** Joint-planning language indicating a Collaborative turn. */
private val COLLABORATIVE_PATTERN = Regex(
    """\b(we should|let's|what do you think|should we|can we|together)\b""",
    RegexOption.IGNORE_CASE,
)

/** Context-shift openers indicating a Topic Opener turn. */
private val TOPIC_OPENER_PATTERN = Regex(
    """^(so[,\s]|hey[,\s]|by the way|speaking of|anyway|on another note|quick question|also[,\s])""",
    RegexOption.IGNORE_CASE,
)

/** Explicit informational-delivery markers indicating an FYI turn. */
private val FYI_PATTERN = Regex(
    """\b(just so you know|fyi|heads up|for your info|in case you|worth noting|just wanted to let you know)\b""",
    RegexOption.IGNORE_CASE,
)

/** Disfluency restart: a word immediately followed by itself (e.g., "I I want", "the the"). */
private val RESTART_PATTERN = Regex("""\b(\w+)\s+\1\b""", RegexOption.IGNORE_CASE)

// ── Public API ───────────────────────────────────────────────────────────────

/**
 * Compute pre-comprehension [PostureSignals] from the STT events and Deepgram Flux event
 * stored in [ctx].
 *
 * **Pure logic — no I/O, no coroutines, < 1 ms.**
 *
 * Barge-in is detected first and short-circuits all further computation: if [ctx.fluxEvent]
 * carries [FluxSpeechState.StartOfTurn], the function returns immediately with
 * [TurnShape.BargeIn] and maximum [PostureSignals.responsePressure].
 *
 * @param ctx Live cognitive context for the current turn.
 */
fun computePostureSignals(ctx: CognitiveContext): PostureSignals {
    // Barge-in short-circuit — must precede all other logic.
    val fluxState = ctx.fluxEvent?.speechState ?: FluxSpeechState.Unknown
    if (fluxState == FluxSpeechState.StartOfTurn) {
        return PostureSignals(
            turnShape = TurnShape.BargeIn,
            surfaceEnergy = 0.5,
            responsePressure = 1.0,
        )
    }

    val transcript = ctx.utterance.trim()
    val words = transcript.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val wordCount = words.size.coerceAtLeast(1)

    val fillerCount = words.count { it.lowercase() in FILLER_WORDS }
    val restartCount = RESTART_PATTERN.findAll(transcript).count()

    val turnShape = classifyTurnShape(transcript, words, fillerCount, restartCount)
    val surfaceEnergy = computeSurfaceEnergy(transcript, words, fillerCount, restartCount, wordCount)
    val responsePressure = computeResponsePressure(ctx.fluxEvent, ctx.transcriptionResults)

    return PostureSignals(turnShape, surfaceEnergy, responsePressure)
}

/**
 * Stateless per-turn decision tree: maps [PostureSignals] to a [PostureMoveType].
 *
 * **All 10 move types are reachable.** Barge-in → [PostureMoveType.YIELD] is evaluated
 * first and short-circuits all subsequent branches.
 *
 * @param signals       Pre-computed posture signals for this turn.
 * @param priorMoveType The [PostureMoveType] alfrd used in the immediately prior turn, if any.
 *                      Required to distinguish [PostureMoveType.MISREAD_RECOVERY] (alfrd's
 *                      prior move was wrong) from plain [PostureMoveType.REPAIR].
 */
fun selectMoveType(
    signals: PostureSignals,
    priorMoveType: PostureMoveType? = null,
): PostureMoveType {
    // 1. Barge-in — immediate short-circuit, no further computation.
    if (signals.turnShape == TurnShape.BargeIn) return PostureMoveType.YIELD

    // 2. Low response pressure — user hasn't finished or is still forming thought.
    if (signals.responsePressure < 0.3) {
        return if (signals.turnShape == TurnShape.Fragmented) PostureMoveType.WAIT
        else PostureMoveType.MULTI_UTTERANCE_HOLD
    }

    // 3. Correction paths — check for misread before plain repair.
    //    Misread Recovery: alfrd's prior move was non-trivial *and* user is visibly frustrated
    //    (elevated surface energy in a correction turn).
    if (signals.turnShape == TurnShape.Correction) {
        val priorWasWrong = priorMoveType != null &&
            priorMoveType != PostureMoveType.RECEIPT &&
            signals.surfaceEnergy > 0.5
        return if (priorWasWrong) PostureMoveType.MISREAD_RECOVERY else PostureMoveType.REPAIR
    }

    // 4. Task request with high response pressure — commit to action.
    if (signals.turnShape == TurnShape.TaskRequest && signals.responsePressure > 0.6) {
        return PostureMoveType.COMMIT
    }

    // 5. Topic opener — orient to the new direction whenever response pressure is non-trivial.
    //    No upper pressure bound: orienting is always appropriate when a new topic is introduced.
    if (signals.turnShape == TurnShape.TopicOpener && signals.responsePressure >= 0.3) {
        return PostureMoveType.ORIENT
    }

    // 6. Disclosure with elevated surface energy — hold space for the user.
    //    Threshold at 0.3 so that emotionally charged disclosures (not just extremely dense
    //    ones) trigger Hold without requiring pathologically high marker density.
    if (signals.turnShape == TurnShape.Disclosure && signals.surfaceEnergy > 0.3) {
        return PostureMoveType.HOLD
    }

    // 7. Exploratory question: low-to-medium energy + non-trivial pressure → probe.
    //    Upper bound is 0.95 so that high-confidence questions (EndOfTurn > 0.8) still
    //    resolve to Probe rather than falling through to Receipt.
    if (signals.turnShape == TurnShape.Question &&
        signals.surfaceEnergy < 0.5 &&
        signals.responsePressure in 0.3..0.95
    ) {
        return PostureMoveType.PROBE
    }

    // 8. Default fallback.
    return PostureMoveType.RECEIPT
}

// ── Private helpers ──────────────────────────────────────────────────────────

private fun classifyTurnShape(
    transcript: String,
    words: List<String>,
    fillerCount: Int,
    restartCount: Int,
): TurnShape {
    // Fragmented: empty or content-poor (≤ 2 non-filler words).
    val contentWords = words.filter { it.lowercase() !in FILLER_WORDS }
    if (contentWords.size <= 2) return TurnShape.Fragmented

    // Correction: disfluency restarts detected (word immediately repeated).
    if (restartCount > 0) return TurnShape.Correction

    // Task request: imperative or request opener — checked before Question so that
    // "Can you find...?" resolves to TaskRequest rather than Question.
    if (TASK_REQUEST_PATTERN.containsMatchIn(transcript)) return TurnShape.TaskRequest

    // Question: ends with "?" or opens with an interrogative word.
    val firstWord = words.firstOrNull()?.lowercase()?.trimEnd('?', ',', '.') ?: ""
    if (transcript.endsWith("?") || firstWord in QUESTION_WORDS) return TurnShape.Question

    // Continuation: heavy filler density (> 30 % of words) — checked before Disclosure
    // to prevent filler-heavy utterances matching personal-pronoun patterns.
    if (fillerCount.toDouble() / words.size > 0.3) return TurnShape.Continuation

    // Disclosure: personal sharing.
    if (DISCLOSURE_PATTERN.containsMatchIn(transcript)) return TurnShape.Disclosure

    // Collaborative: joint-planning language.
    if (COLLABORATIVE_PATTERN.containsMatchIn(transcript)) return TurnShape.Collaborative

    // Topic opener: context-shift markers.
    if (TOPIC_OPENER_PATTERN.containsMatchIn(transcript)) return TurnShape.TopicOpener

    // FYI: explicit informational markers.
    if (FYI_PATTERN.containsMatchIn(transcript)) return TurnShape.FYI

    // Default: neutral informational delivery.
    return TurnShape.FYI
}

private fun computeSurfaceEnergy(
    transcript: String,
    words: List<String>,
    fillerCount: Int,
    restartCount: Int,
    wordCount: Int,
): Double {
    // Disfluency density: fillers + weighted restarts.
    val disfluencyDensity = (fillerCount + restartCount * 2).toDouble() / wordCount

    // Emotional marker density.
    val emotionalCount = words.count { it.lowercase() in EMOTIONAL_MARKERS }
    val emotionalDensity = emotionalCount.toDouble() / wordCount

    // Urgency punctuation boost.
    val exclamationBoost = if (transcript.contains('!')) 0.15 else 0.0

    // Weighted combination: 40 % disfluency, 50 % emotional, 10 % urgency punctuation.
    // Emotional weight is higher so that emotionally loaded speech registers clearly.
    return (disfluencyDensity * 0.4 + emotionalDensity * 0.8 + exclamationBoost)
        .coerceIn(0.0, 1.0)
}

/**
 * Derives [PostureSignals.responsePressure] **directly** from [fluxEvent.endOfTurnConfidence].
 *
 * No heuristic derivation — the Flux confidence value is used as-is within a linear mapping:
 *
 * | Flux state            | Formula                          | Range      |
 * |-----------------------|----------------------------------|------------|
 * | EndOfTurn             | 0.75 + confidence × 0.25         | 0.75–1.00  |
 * | EagerEndOfTurn        | 0.50 + confidence × 0.20         | 0.50–0.70  |
 * | StartOfTurn (barge-in)| 1.0 (handled in computePosture…) | 1.00       |
 * | speech_final=true     | 0.60 floor                       | ≥ 0.60     |
 * | No signal             | 0.20                             | 0.20       |
 */
private fun computeResponsePressure(
    fluxEvent: FluxEvent?,
    transcriptionResults: List<TranscriptionResult>,
): Double {
    val speechFinalFloor = if (transcriptionResults.any { it.speechFinal }) 0.6 else 0.2
    return when (fluxEvent?.speechState) {
        FluxSpeechState.EndOfTurn -> {
            val confidence = fluxEvent.endOfTurnConfidence.coerceIn(0.0, 1.0)
            0.75 + confidence * 0.25
        }
        FluxSpeechState.EagerEndOfTurn -> {
            val confidence = fluxEvent.endOfTurnConfidence.coerceIn(0.0, 1.0)
            (0.50 + confidence * 0.20).coerceAtLeast(speechFinalFloor)
        }
        FluxSpeechState.StartOfTurn -> 1.0   // barge-in path; kept for completeness
        else -> speechFinalFloor
    }
}
