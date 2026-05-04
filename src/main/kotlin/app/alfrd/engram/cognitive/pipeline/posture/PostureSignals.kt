package app.alfrd.engram.cognitive.pipeline.posture

/**
 * Classification of the user's current utterance, inferred from transcript content
 * and disfluency patterns without LLM involvement.
 *
 * - [Question]      — interrogative syntax or question-word opener
 * - [Correction]    — word restarts (disfluency restart pattern)
 * - [TopicOpener]   — context-shift markers ("so,", "by the way", etc.)
 * - [Disclosure]    — personal sharing ("I feel", "I was", etc.)
 * - [FYI]           — informational delivery ("just so you know", neutral statements)
 * - [Continuation]  — heavy filler use (um, uh, like) indicating ongoing thought
 * - [Fragmented]    — very short or content-poor utterance (≤ 2 content words)
 * - [BargeIn]       — user started speaking while alfrd was speaking (Flux StartOfTurn)
 * - [TaskRequest]   — imperative or request phrasing ("can you", "please", etc.)
 * - [Collaborative] — joint-planning language ("let's", "we should", etc.)
 */
enum class TurnShape {
    Question,
    Correction,
    TopicOpener,
    Disclosure,
    FYI,
    Continuation,
    Fragmented,
    BargeIn,
    TaskRequest,
    Collaborative,
}

/**
 * Deepgram Flux VAD speech-state event type.
 *
 * These events drive [PostureSignals.responsePressure] directly — no heuristic derivation.
 */
enum class FluxSpeechState {
    /** User has finished their turn (high-confidence end-of-turn signal). */
    EndOfTurn,

    /** User appears to be finishing soon — early, medium-confidence signal. */
    EagerEndOfTurn,

    /** User started speaking while alfrd was already speaking (barge-in). */
    StartOfTurn,

    /** No Flux event received for this turn. */
    Unknown,
}

/**
 * A single Deepgram Flux VAD event accompanying an utterance.
 *
 * @property speechState         The Flux speech state for this turn.
 * @property endOfTurnConfidence End-of-turn confidence score in [0.0, 1.0]. Non-zero only for
 *                               [FluxSpeechState.EndOfTurn] and [FluxSpeechState.EagerEndOfTurn].
 */
data class FluxEvent(
    val speechState: FluxSpeechState,
    val endOfTurnConfidence: Double = 0.0,
)

/**
 * Pre-comprehension posture signals derived from STT events and Deepgram Flux.
 *
 * Computed by [computePostureSignals] from a [CognitiveContext] before any LLM call.
 * All values are pure — no I/O, no network, < 1 ms to produce.
 *
 * @property turnShape         Classification of the user's utterance shape.
 * @property surfaceEnergy     Disfluency density + emotional-marker intensity (0.0–1.0).
 *                             High = many disfluencies, emotional language, urgency.
 *                             Low  = clean speech, neutral, informational.
 * @property responsePressure  Derived **directly** from Deepgram Flux [endOfTurnConfidence]
 *                             (no heuristic). High = EndOfTurn with high confidence.
 *                             Low = no end-of-turn signal or low confidence.
 */
data class PostureSignals(
    val turnShape: TurnShape,
    val surfaceEnergy: Double,
    val responsePressure: Double,
)
