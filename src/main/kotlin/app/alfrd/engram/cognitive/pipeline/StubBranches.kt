package app.alfrd.engram.cognitive.pipeline

/** Stub — onboarding flow is not yet wired. */
class OnboardingBranch : Branch {
    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content = "Onboarding is not yet wired.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}

/** Stub — question answering is not yet wired. */
class QuestionBranch : Branch {
    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content = "Question answering is not yet wired.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}

/** Stub — task execution is not yet wired. */
class TaskBranch : Branch {
    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content = "I've noted that — task execution is coming soon.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}

/** Stub — correction handling is not yet available. */
class CorrectionBranch : Branch {
    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content = "Corrections aren't available yet.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}

/** Stub — memory queries are not yet available. */
class MetaBranch : Branch {
    override suspend fun execute(ctx: CognitiveContext) {
        ctx.branchResult = BranchResult(
            content = "Memory queries aren't available yet.",
            responseStrategy = ResponseStrategy.SIMPLE,
        )
    }
}
