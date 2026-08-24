package app.alfrd.engram.cognitive.pipeline.posture

import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.cognitive.providers.TranscriptionResult
import app.alfrd.engram.model.PostureMoveType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [computePostureSignals] and [selectMoveType].
 *
 * Coverage targets:
 * - All 10 [TurnShape] values reachable by [computePostureSignals]
 * - All 10 [PostureMoveType] values reachable by [selectMoveType]
 * - Barge-in → YIELD short-circuit (no further computation)
 * - responsePressure derived directly from end_of_turn_confidence (no heuristic)
 * - Edge cases: empty transcript, very short utterance, rapid barge-in
 */
class PostureComputationTest {

    // ── Helper builders ──────────────────────────────────────────────────────

    private fun ctx(
        utterance: String,
        fluxEvent: FluxEvent? = null,
        transcriptionResults: List<TranscriptionResult> = emptyList(),
    ) = CognitiveContext(
        utterance = utterance,
        sessionId = "test-session",
        userId = "test-user",
        fluxEvent = fluxEvent,
        transcriptionResults = transcriptionResults,
    )

    private fun endOfTurn(confidence: Double) =
        FluxEvent(FluxSpeechState.EndOfTurn, confidence)

    private fun eagerEndOfTurn(confidence: Double) =
        FluxEvent(FluxSpeechState.EagerEndOfTurn, confidence)

    private val startOfTurn = FluxEvent(FluxSpeechState.StartOfTurn)

    private fun speechFinalResult(transcript: String = "") =
        TranscriptionResult(transcript, isFinal = true, speechFinal = true, confidence = 0.9f)

    // ── TurnShape classification ─────────────────────────────────────────────

    @Test
    fun `empty transcript produces Fragmented`() {
        val signals = computePostureSignals(ctx(""))
        assertEquals(TurnShape.Fragmented, signals.turnShape)
    }

    @Test
    fun `very short utterance produces Fragmented`() {
        val signals = computePostureSignals(ctx("yeah"))
        assertEquals(TurnShape.Fragmented, signals.turnShape)
    }

    @Test
    fun `two content words still produces Fragmented`() {
        val signals = computePostureSignals(ctx("not sure"))
        assertEquals(TurnShape.Fragmented, signals.turnShape)
    }

    @Test
    fun `question mark ending produces Question`() {
        val signals = computePostureSignals(ctx("Can you remind me what we decided last time?"))
        assertEquals(TurnShape.Question, signals.turnShape)
    }

    @Test
    fun `interrogative opener produces Question`() {
        val signals = computePostureSignals(ctx("What did you end up recommending for the dashboard?"))
        assertEquals(TurnShape.Question, signals.turnShape)
    }

    @Test
    fun `how opener produces Question`() {
        val signals = computePostureSignals(ctx("How does the memory bridge work exactly?"))
        assertEquals(TurnShape.Question, signals.turnShape)
    }

    @Test
    fun `word restart produces Correction`() {
        // "I I want" — classic disfluency restart
        val signals = computePostureSignals(ctx("I I want to clarify what happened yesterday afternoon"))
        assertEquals(TurnShape.Correction, signals.turnShape)
    }

    @Test
    fun `double word restart produces Correction`() {
        val signals = computePostureSignals(ctx("The the meeting was actually moved to tomorrow morning"))
        assertEquals(TurnShape.Correction, signals.turnShape)
    }

    @Test
    fun `imperative opener produces TaskRequest`() {
        val signals = computePostureSignals(ctx("Can you find the notes from Thursday's standup?"))
        assertEquals(TurnShape.TaskRequest, signals.turnShape)
    }

    @Test
    fun `please opener produces TaskRequest`() {
        val signals = computePostureSignals(ctx("Please schedule a call with Maya for Friday afternoon"))
        assertEquals(TurnShape.TaskRequest, signals.turnShape)
    }

    @Test
    fun `personal sharing produces Disclosure`() {
        val signals = computePostureSignals(ctx("I feel like I've been going in circles with this project all week"))
        assertEquals(TurnShape.Disclosure, signals.turnShape)
    }

    @Test
    fun `first person was produces Disclosure`() {
        val signals = computePostureSignals(ctx("I was really stressed about the presentation yesterday honestly"))
        assertEquals(TurnShape.Disclosure, signals.turnShape)
    }

    @Test
    fun `collaborative language produces Collaborative`() {
        val signals = computePostureSignals(ctx("Let's figure out the best approach for this migration together"))
        assertEquals(TurnShape.Collaborative, signals.turnShape)
    }

    @Test
    fun `we should produces Collaborative`() {
        val signals = computePostureSignals(ctx("We should probably revisit the onboarding flow before the release"))
        assertEquals(TurnShape.Collaborative, signals.turnShape)
    }

    @Test
    fun `topic opener produces TopicOpener`() {
        val signals = computePostureSignals(ctx("So, I wanted to bring up something different about the roadmap"))
        assertEquals(TurnShape.TopicOpener, signals.turnShape)
    }

    @Test
    fun `by the way produces TopicOpener`() {
        val signals = computePostureSignals(ctx("By the way, there's a design review scheduled for next week"))
        assertEquals(TurnShape.TopicOpener, signals.turnShape)
    }

    @Test
    fun `fyi marker produces FYI`() {
        val signals = computePostureSignals(ctx("Just so you know, the API keys are rotating next Tuesday morning"))
        assertEquals(TurnShape.FYI, signals.turnShape)
    }

    @Test
    fun `heads up produces FYI`() {
        val signals = computePostureSignals(ctx("Heads up, the staging environment is going down for maintenance"))
        assertEquals(TurnShape.FYI, signals.turnShape)
    }

    @Test
    fun `heavy filler use produces Continuation`() {
        // "um uh um like" — over 30 % of words are fillers
        val signals = computePostureSignals(ctx("um uh um like I was thinking about the architecture changes"))
        assertEquals(TurnShape.Continuation, signals.turnShape)
    }

    @Test
    fun `StartOfTurn flux event produces BargeIn immediately`() {
        val signals = computePostureSignals(ctx("wait actually", fluxEvent = startOfTurn))
        assertEquals(TurnShape.BargeIn, signals.turnShape)
        assertEquals(1.0, signals.responsePressure, 0.0001)
    }

    // ── Barge-in short-circuit ───────────────────────────────────────────────

    @Test
    fun `StartOfTurn short-circuits all other logic regardless of transcript`() {
        // Even a clear question should resolve to BargeIn if Flux says StartOfTurn.
        val signals = computePostureSignals(ctx("What is the capital of France?", fluxEvent = startOfTurn))
        assertEquals(TurnShape.BargeIn, signals.turnShape)
    }

    @Test
    fun `rapid barge-in on empty transcript still produces BargeIn`() {
        val signals = computePostureSignals(ctx("", fluxEvent = startOfTurn))
        assertEquals(TurnShape.BargeIn, signals.turnShape)
        assertEquals(1.0, signals.responsePressure, 0.0001)
    }

    // ── responsePressure from Flux end_of_turn_confidence (no heuristic) ────

    @Test
    fun `EndOfTurn confidence 0 dot 9 maps to responsePressure near 0 dot 975`() {
        val signals = computePostureSignals(ctx("Here is my update on the design review process", fluxEvent = endOfTurn(0.9)))
        assertEquals(0.975, signals.responsePressure, 0.001)
    }

    @Test
    fun `EndOfTurn confidence 0 dot 0 maps to responsePressure 0 dot 75`() {
        val signals = computePostureSignals(ctx("Here is my update on the design review process", fluxEvent = endOfTurn(0.0)))
        assertEquals(0.75, signals.responsePressure, 0.001)
    }

    @Test
    fun `EndOfTurn confidence 1 dot 0 maps to responsePressure 1 dot 0`() {
        val signals = computePostureSignals(ctx("Here is my update on the design review process", fluxEvent = endOfTurn(1.0)))
        assertEquals(1.0, signals.responsePressure, 0.001)
    }

    @Test
    fun `EagerEndOfTurn confidence 0 dot 6 maps to responsePressure 0 dot 62`() {
        val signals = computePostureSignals(ctx("Here is my update on the design review process", fluxEvent = eagerEndOfTurn(0.6)))
        assertEquals(0.62, signals.responsePressure, 0.001)
    }

    @Test
    fun `EagerEndOfTurn confidence 0 dot 0 maps to responsePressure 0 dot 50`() {
        val signals = computePostureSignals(ctx("Here is my update on the design review process", fluxEvent = eagerEndOfTurn(0.0)))
        assertEquals(0.5, signals.responsePressure, 0.001)
    }

    @Test
    fun `no flux event without speech_final gives low pressure 0 dot 20`() {
        val signals = computePostureSignals(ctx("Here is my update on the design review process"))
        assertEquals(0.20, signals.responsePressure, 0.001)
    }

    @Test
    fun `speech_final transcription result raises floor to 0 dot 60 when no flux event`() {
        val signals = computePostureSignals(
            ctx(
                "Here is my update on the design review process",
                transcriptionResults = listOf(speechFinalResult("Here is my update on the design review process")),
            ),
        )
        assertEquals(0.60, signals.responsePressure, 0.001)
    }

    @Test
    fun `confidence fed directly to formula without intermediate rounding`() {
        // confidence 0.4 → 0.75 + 0.4 * 0.25 = 0.85
        val signals = computePostureSignals(ctx("Here is my update on the design review process", fluxEvent = endOfTurn(0.4)))
        assertEquals(0.85, signals.responsePressure, 0.001)
    }

    // ── surfaceEnergy ────────────────────────────────────────────────────────

    @Test
    fun `clean neutral statement has low surface energy`() {
        val signals = computePostureSignals(ctx("The deployment finished successfully at three in the afternoon"))
        assertTrue(signals.surfaceEnergy < 0.3, "Expected low energy, got ${signals.surfaceEnergy}")
    }

    @Test
    fun `emotional language raises surface energy`() {
        // Dense emotional markers: "really", "stressed", "absolutely", "terrible", "honestly" = 5/13 words
        // energy = (5/13) * 0.8 = 0.31 — above 0.3
        val signals = computePostureSignals(
            ctx("I was really stressed about the presentation and it was absolutely terrible honestly"),
        )
        assertTrue(signals.surfaceEnergy > 0.2, "Expected elevated energy, got ${signals.surfaceEnergy}")
    }

    @Test
    fun `exclamation mark boosts surface energy`() {
        val signals = computePostureSignals(ctx("The build finally passed after all those hours of debugging work!"))
        assertTrue(signals.surfaceEnergy >= 0.15, "Expected exclamation boost, got ${signals.surfaceEnergy}")
    }

    @Test
    fun `surface energy is clamped to 0 dot 0 to 1 dot 0`() {
        // Pathologically dense disfluency should not exceed 1.0
        val signals = computePostureSignals(
            ctx("um um um uh uh really totally absolutely definitely completely honestly wow amazing!"),
        )
        assertTrue(signals.surfaceEnergy <= 1.0)
        assertTrue(signals.surfaceEnergy >= 0.0)
    }

    // ── All 10 PostureMoveType values reachable ──────────────────────────────

    @Test
    fun `YIELD - BargeIn turn shape produces Yield immediately`() {
        val move = selectMoveType(PostureSignals(TurnShape.BargeIn, 0.5, 1.0))
        assertEquals(PostureMoveType.YIELD, move)
    }

    @Test
    fun `WAIT - low pressure plus Fragmented produces Wait`() {
        val move = selectMoveType(PostureSignals(TurnShape.Fragmented, 0.2, 0.2))
        assertEquals(PostureMoveType.WAIT, move)
    }

    @Test
    fun `MULTI_UTTERANCE_HOLD - low pressure non-Fragmented produces MultiUtteranceHold`() {
        val move = selectMoveType(PostureSignals(TurnShape.FYI, 0.2, 0.2))
        assertEquals(PostureMoveType.MULTI_UTTERANCE_HOLD, move)
    }

    @Test
    fun `MULTI_UTTERANCE_HOLD - low pressure Continuation produces MultiUtteranceHold`() {
        val move = selectMoveType(PostureSignals(TurnShape.Continuation, 0.4, 0.15))
        assertEquals(PostureMoveType.MULTI_UTTERANCE_HOLD, move)
    }

    @Test
    fun `REPAIR - Correction with low surface energy produces Repair`() {
        val move = selectMoveType(PostureSignals(TurnShape.Correction, 0.3, 0.6))
        assertEquals(PostureMoveType.REPAIR, move)
    }

    @Test
    fun `REPAIR - Correction with no prior move produces Repair`() {
        val move = selectMoveType(PostureSignals(TurnShape.Correction, 0.7, 0.6), priorMoveType = null)
        assertEquals(PostureMoveType.REPAIR, move)
    }

    @Test
    fun `MISREAD_RECOVERY - Correction plus high energy plus non-trivial prior move`() {
        val move = selectMoveType(
            PostureSignals(TurnShape.Correction, surfaceEnergy = 0.7, responsePressure = 0.6),
            priorMoveType = PostureMoveType.ORIENT,
        )
        assertEquals(PostureMoveType.MISREAD_RECOVERY, move)
    }

    @Test
    fun `MISREAD_RECOVERY - not triggered when prior move is RECEIPT`() {
        // RECEIPT is excluded from the "was wrong" path — must produce REPAIR
        val move = selectMoveType(
            PostureSignals(TurnShape.Correction, surfaceEnergy = 0.7, responsePressure = 0.6),
            priorMoveType = PostureMoveType.RECEIPT,
        )
        assertEquals(PostureMoveType.REPAIR, move)
    }

    @Test
    fun `COMMIT - TaskRequest with high response pressure produces Commit`() {
        val move = selectMoveType(PostureSignals(TurnShape.TaskRequest, 0.3, 0.8))
        assertEquals(PostureMoveType.COMMIT, move)
    }

    @Test
    fun `ORIENT - TopicOpener with moderate pressure produces Orient`() {
        val move = selectMoveType(PostureSignals(TurnShape.TopicOpener, 0.3, 0.5))
        assertEquals(PostureMoveType.ORIENT, move)
    }

    @Test
    fun `HOLD - Disclosure with elevated surface energy produces Hold`() {
        // surfaceEnergy 0.4 is above the 0.3 threshold for Disclosure -> Hold
        val move = selectMoveType(PostureSignals(TurnShape.Disclosure, 0.4, 0.5))
        assertEquals(PostureMoveType.HOLD, move)
    }

    @Test
    fun `PROBE - Question with low energy and moderate pressure produces Probe`() {
        val move = selectMoveType(PostureSignals(TurnShape.Question, 0.3, 0.5))
        assertEquals(PostureMoveType.PROBE, move)
    }

    @Test
    fun `RECEIPT - default fallback for unmatched signals`() {
        // FYI with moderate energy and moderate-high pressure matches no specific branch
        val move = selectMoveType(PostureSignals(TurnShape.FYI, 0.3, 0.7))
        assertEquals(PostureMoveType.RECEIPT, move)
    }

    @Test
    fun `RECEIPT - Collaborative with moderate signals falls through to Receipt`() {
        val move = selectMoveType(PostureSignals(TurnShape.Collaborative, 0.3, 0.7))
        assertEquals(PostureMoveType.RECEIPT, move)
    }

    // ── End-to-end: computePostureSignals → selectMoveType ──────────────────

    @Test
    fun `barge-in event produces YIELD end-to-end`() {
        val signals = computePostureSignals(ctx("wait no", fluxEvent = startOfTurn))
        val move = selectMoveType(signals)
        assertEquals(PostureMoveType.YIELD, move)
    }

    @Test
    fun `task request with EndOfTurn high confidence produces COMMIT end-to-end`() {
        val signals = computePostureSignals(
            ctx(
                "Can you schedule a call with the engineering team for tomorrow morning?",
                fluxEvent = endOfTurn(0.95),
            ),
        )
        val move = selectMoveType(signals)
        assertEquals(PostureMoveType.COMMIT, move)
    }

    @Test
    fun `question with EndOfTurn moderate confidence produces PROBE end-to-end`() {
        val signals = computePostureSignals(
            ctx(
                "What does the memory bridge actually do under the hood?",
                fluxEvent = endOfTurn(0.5),
            ),
        )
        val move = selectMoveType(signals)
        assertEquals(PostureMoveType.PROBE, move)
    }

    @Test
    fun `fragmented utterance with no flux signal produces WAIT end-to-end`() {
        val signals = computePostureSignals(ctx("um yeah"))
        val move = selectMoveType(signals)
        // "um yeah" → only filler + 1 content word → Fragmented; no flux → low pressure
        assertEquals(PostureMoveType.WAIT, move)
    }

    @Test
    fun `disclosure with emotional language and EagerEndOfTurn produces HOLD end-to-end`() {
        // Dense emotional sentence to ensure surfaceEnergy > 0.3 threshold for Hold.
        // "really", "overwhelmed", "stressed", "honestly" = 4 emotional markers in ~11 words
        val signals = computePostureSignals(
            ctx(
                "I feel really overwhelmed and stressed by everything honestly it was awful",
                fluxEvent = eagerEndOfTurn(0.8),
            ),
        )
        val move = selectMoveType(signals)
        assertEquals(PostureMoveType.HOLD, move)
    }

    @Test
    fun `word restart utterance with no prior move produces REPAIR end-to-end`() {
        val signals = computePostureSignals(
            ctx(
                "I I meant the staging server not the production environment actually",
                fluxEvent = endOfTurn(0.85),
            ),
        )
        val move = selectMoveType(signals)
        assertEquals(PostureMoveType.REPAIR, move)
    }

    @Test
    fun `topic opener with moderate EndOfTurn confidence produces ORIENT end-to-end`() {
        val signals = computePostureSignals(
            ctx(
                "So, I wanted to bring up something about the new dashboard features",
                fluxEvent = endOfTurn(0.55),
            ),
        )
        val move = selectMoveType(signals)
        assertEquals(PostureMoveType.ORIENT, move)
    }

    // ── Interrogative-contraction Question detection ─────────────────────────

    @Test
    fun `what contraction without question mark produces Question - text path`() {
        assertEquals(TurnShape.Question, classifyTextPathTurnShape("what's my dog's name"))
    }

    @Test
    fun `who contraction without question mark produces Question - text path`() {
        assertEquals(TurnShape.Question, classifyTextPathTurnShape("who's coming to the party tonight"))
    }

    @Test
    fun `what contraction without question mark produces Question via computePostureSignals`() {
        val signals = computePostureSignals(ctx("what's my dog's name"))
        assertEquals(TurnShape.Question, signals.turnShape)
    }

    @Test
    fun `who contraction without question mark produces Question via computePostureSignals`() {
        val signals = computePostureSignals(ctx("who's coming to the party tonight"))
        assertEquals(TurnShape.Question, signals.turnShape)
    }

    @Test
    fun `can you update task still classifies as TaskRequest despite question mark`() {
        // TaskRequest is checked before Question so "can you update X?" resolves to TaskRequest.
        assertEquals(TurnShape.TaskRequest, classifyTextPathTurnShape("can you update the doc?"))
    }

    @Test
    fun `non-interrogative contraction does not produce Question`() {
        // "i'm" -> root "i" is not in QUESTION_WORDS; sentence should classify as Disclosure.
        assertEquals(TurnShape.Disclosure, classifyTextPathTurnShape("i'm really tired and feeling overwhelmed today"))
    }

    // ── attunementDirective — posture read as a natural-language directive, never a line pick ──

    @Test
    fun `Disclosure shape produces an emotional-weight directive regardless of surface energy`() {
        val directive = attunementDirective(TurnShape.Disclosure, surfaceEnergy = 0.0)
        assertTrue(directive.contains("emotional weight", ignoreCase = true), "got: $directive")
    }

    @Test
    fun `elevated surface energy without a Disclosure shape still produces the emotional-weight directive`() {
        // e.g. a null turn shape (classifyTextPathTurnShape found no explicit pattern) but the
        // utterance is still dense with emotional markers — this is the exact mechanism behind
        // the "onboarding greeting on an emotionally loaded turn" misfire this task fixes.
        val directive = attunementDirective(turnShape = null, surfaceEnergy = 0.32)
        assertTrue(directive.contains("emotional weight", ignoreCase = true), "got: $directive")
    }

    @Test
    fun `surface energy at or below the elevated threshold does not produce the emotional-weight directive`() {
        val directive = attunementDirective(turnShape = null, surfaceEnergy = 0.3)
        assertFalse(directive.contains("emotional weight", ignoreCase = true), "got: $directive")
    }

    @Test
    fun `Correction shape produces a plain-acceptance directive`() {
        val directive = attunementDirective(TurnShape.Correction, surfaceEnergy = 0.0)
        assertTrue(directive.contains("correcting", ignoreCase = true), "got: $directive")
    }

    @Test
    fun `TaskRequest shape produces a direct-and-concrete directive`() {
        val directive = attunementDirective(TurnShape.TaskRequest, surfaceEnergy = 0.0)
        assertTrue(directive.contains("direct", ignoreCase = true), "got: $directive")
    }

    @Test
    fun `Question shape produces a clear-answer directive`() {
        val directive = attunementDirective(TurnShape.Question, surfaceEnergy = 0.0)
        assertTrue(directive.contains("answer", ignoreCase = true), "got: $directive")
    }

    @Test
    fun `Continuation shape produces a give-them-room directive`() {
        val directive = attunementDirective(TurnShape.Continuation, surfaceEnergy = 0.0)
        assertTrue(directive.contains("room", ignoreCase = true), "got: $directive")
    }

    @Test
    fun `FYI shape produces a brief-acknowledgment directive`() {
        val directive = attunementDirective(TurnShape.FYI, surfaceEnergy = 0.0)
        assertTrue(directive.contains("acknowledgment", ignoreCase = true), "got: $directive")
    }

    @Test
    fun `null turn shape with low surface energy produces a neutral directive`() {
        val directive = attunementDirective(turnShape = null, surfaceEnergy = 0.0)
        assertTrue(directive.contains("naturally", ignoreCase = true), "got: $directive")
    }

    @Test
    fun `voice-only shapes fall through to the neutral directive on the text path`() {
        // TopicOpener/Collaborative/Fragmented/BargeIn are never produced by
        // classifyTextPathTurnShape, but attunementDirective still handles them gracefully.
        val directive = attunementDirective(TurnShape.TopicOpener, surfaceEnergy = 0.0)
        assertTrue(directive.contains("naturally", ignoreCase = true), "got: $directive")
    }
}
