package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.providers.LlmClient

/** Maps an [IntentType] to the appropriate [Branch] instance. Pure function — no state. */
class Router(
    private val engramClient: EngramClient,
    private val llmClient: LlmClient?,
    private val selectionService: ResponseSelectionService? = null,
    private val memoryWriteService: MemoryWriteService? = null,
) {

    fun route(intent: IntentType): Branch = when (intent) {
        IntentType.ONBOARDING            -> OnboardingBranch(engramClient, llmClient, memoryWriteService)
        IntentType.SOCIAL                -> SocialBranch(selectionService)
        IntentType.QUESTION              -> QuestionBranch(engramClient, llmClient)
        IntentType.TASK                  -> TaskBranch(engramClient, memoryWriteService)
        IntentType.CORRECTION            -> CorrectionBranch()
        IntentType.META                  -> MetaBranch()
        IntentType.CLARIFICATION,
        IntentType.AMBIGUOUS             -> ClarificationBranch()
    }
}
