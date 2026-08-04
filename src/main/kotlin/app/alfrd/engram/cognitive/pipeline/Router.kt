package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.TurnShape

/**
 * Maps an [IntentType] (and optional [TurnShape]) to the appropriate [Branch] instance.
 * Pure function — no state, no dependencies. Branches are directors: they classify the turn
 * and produce conditioners, never language or I/O, so they need nothing wired in here.
 */
class Router {

    fun route(intent: IntentType, turnShape: TurnShape? = null): Branch {
        // ── Text-path posture routing ───────────────────────────────────────
        // Low-pressure shapes resolve to a verbal acknowledgment; the utterance
        // is still ingested by the universal memory step in CognitivePipeline.
        when (turnShape) {
            TurnShape.Disclosure,
            TurnShape.FYI,
            TurnShape.Continuation -> return VerbalMoveBranch(turnShape)
            TurnShape.TaskRequest  -> return TaskBranch()
            TurnShape.Correction   -> return CorrectionBranch()
            else                   -> { /* fall through to intent-based routing */ }
        }

        // ── Intent-based routing ────────────────────────────────────────────
        return when (intent) {
            IntentType.SOCIAL                -> SocialBranch()
            IntentType.QUESTION              -> QuestionBranch()
            IntentType.TASK                  -> TaskBranch()
            IntentType.CORRECTION            -> CorrectionBranch()
            IntentType.META                  -> QuestionBranch()
            IntentType.CLARIFICATION,
            IntentType.AMBIGUOUS             -> ClarificationBranch()
        }
    }
}
