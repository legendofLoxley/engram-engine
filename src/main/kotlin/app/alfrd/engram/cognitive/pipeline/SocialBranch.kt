package app.alfrd.engram.cognitive.pipeline

import java.time.LocalTime

/**
 * SocialBranch — handles greetings, thanks, and farewells without LLM or memory.
 * Target < 50 ms.
 */
class SocialBranch : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        val lower = ctx.utterance.trim().lowercase()
        val response = when {
            isGoodbye(lower) -> "Until next time."
            isThanks(lower)  -> "Of course."
            else             -> timeBasedGreeting()
        }
        ctx.branchResult = BranchResult(
            content = response,
            responseStrategy = ResponseStrategy.SOCIAL,
        )
    }

    private fun timeBasedGreeting(): String {
        val hour = LocalTime.now().hour
        return when {
            hour < 12 -> "Good morning."
            hour < 17 -> "Good afternoon."
            else      -> "Good evening."
        }
    }

    private fun isGoodbye(lower: String) =
        listOf("bye", "goodbye", "see you", "until next time", "take care").any { lower.contains(it) }

    private fun isThanks(lower: String) =
        listOf("thanks", "thank you", "cheers").any { lower.contains(it) }
}
