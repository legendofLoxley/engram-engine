package app.alfrd.engram.cognitive.pipeline

/** Maps an [IntentType] to the appropriate [Branch] instance. Pure function — no state. */
class Router {

    fun route(intent: IntentType): Branch = when (intent) {
        IntentType.ONBOARDING            -> OnboardingBranch()
        IntentType.SOCIAL                -> SocialBranch()
        IntentType.QUESTION              -> QuestionBranch()
        IntentType.TASK                  -> TaskBranch()
        IntentType.CORRECTION            -> CorrectionBranch()
        IntentType.META                  -> MetaBranch()
        IntentType.CLARIFY,
        IntentType.AMBIGUOUS             -> ClarificationBranch()
    }
}
