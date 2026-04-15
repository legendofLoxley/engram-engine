package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.providers.LlmClient

/** Maps an [IntentType] to the appropriate [Branch] instance. Pure function — no state. */
class Router(
    private val engramClient: EngramClient,
    private val llmClient: LlmClient?,
    private val selectionService: ResponseSelectionService? = null,
) {

    fun route(intent: IntentType): Branch = when (intent) {
        IntentType.ONBOARDING            -> OnboardingBranch(engramClient, llmClient)
        IntentType.SOCIAL                -> SocialBranch(selectionService)
        IntentType.QUESTION              -> QuestionBranch(engramClient, llmClient)
        IntentType.TASK                  -> TaskBranch(engramClient)
        IntentType.CORRECTION            -> CorrectionBranch()
        IntentType.META                  -> MetaBranch()
        IntentType.CLARIFICATION,
        IntentType.AMBIGUOUS             -> ClarificationBranch()
    }
}
