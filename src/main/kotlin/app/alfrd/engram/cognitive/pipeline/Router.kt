package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.providers.LlmClient


/** Maps an [IntentType] (and optional [TurnShape]) to the appropriate [Branch] instance. Pure function — no state. */
class Router(
    private val engramClient: EngramClient,
    private val llmClient: LlmClient?,
    private val selectionService: ResponseSelectionService? = null,
    private val memoryWriteService: MemoryWriteService? = null,
) {

    fun route(intent: IntentType, turnShape: TurnShape? = null): Branch {
        // ── Text-path posture routing ───────────────────────────────────────
        // Low-pressure shapes resolve to a verbal acknowledgment; the utterance
        // is still ingested by the universal memory step in CognitivePipeline.
        when (turnShape) {
            TurnShape.Disclosure,
            TurnShape.FYI,
            TurnShape.Continuation -> return VerbalMoveBranch(selectionService, turnShape)
            TurnShape.TaskRequest  -> return TaskBranch(engramClient, memoryWriteService)
            TurnShape.Correction   -> return CorrectionBranch()
            else                   -> { /* fall through to intent-based routing */ }
        }

        // ── Intent-based routing ────────────────────────────────────────────
        return when (intent) {
            IntentType.SOCIAL                -> SocialBranch(selectionService)
            IntentType.QUESTION              -> QuestionBranch(engramClient, llmClient)
            IntentType.TASK                  -> TaskBranch(engramClient, memoryWriteService)
            IntentType.CORRECTION            -> CorrectionBranch()
            IntentType.META                  -> QuestionBranch(engramClient, llmClient)
            IntentType.CLARIFICATION,
            IntentType.AMBIGUOUS             -> ClarificationBranch()
        }
    }
}
