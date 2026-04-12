package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest

/**
 * Onboarding branch — drives the scaffold loop that orients the assistant to a new user.
 *
 * Flow per turn:
 * 1. Load scaffold state from [engramClient].
 * 2. On first interaction ever: return the opening question, store as active scaffold question.
 * 3. Otherwise: decompose + ingest the utterance, mark newly covered categories, determine
 *    the next uncovered category in priority order (IDENTITY → EXPERTISE → PREFERENCE →
 *    ROUTINE → RELATIONSHIP → CONTEXT), generate a contextual question via [llmClient],
 *    and store it as the new active scaffold question.
 * 4. When all categories are covered: emit a summary acknowledgment and clear the active question.
 *
 * LLM failure falls back to a hardcoded question — the onboarding loop never stalls.
 */
class OnboardingBranch(
    private val engramClient: EngramClient,
    private val llmClient: LlmClient?,
) : Branch {

    companion object {
        val SCAFFOLD_PRIORITY = listOf(
            PhraseCategory.IDENTITY,
            PhraseCategory.EXPERTISE,
            PhraseCategory.PREFERENCE,
            PhraseCategory.ROUTINE,
            PhraseCategory.RELATIONSHIP,
            PhraseCategory.CONTEXT,
        )

        const val OPENER = "Good to meet you. I'd like to get oriented so I can be useful quickly. What are you working on right now?"
    }

    override suspend fun execute(ctx: CognitiveContext) {
        val state = try {
            engramClient.getScaffoldState(ctx.userId)
        } catch (e: Exception) {
            ScaffoldState()
        }

        // ── First ever interaction — send the opener ───────────────────────────
        if (state.answeredCategories.isEmpty() && state.activeScaffoldQuestion == null) {
            val newState = state.copy(activeScaffoldQuestion = OPENER)
            tryUpdateState(ctx.userId, newState)
            ctx.scaffoldState = newState
            ctx.branchResult = BranchResult(content = OPENER, responseStrategy = ResponseStrategy.SIMPLE)
            return
        }

        // ── Decompose and ingest the user's answer ────────────────────────────
        val candidates = try {
            engramClient.decompose(ctx.utterance, ctx.priorUtterances)
        } catch (e: Exception) {
            emptyList()
        }

        try {
            if (candidates.isNotEmpty()) engramClient.ingest(candidates)
        } catch (_: Exception) {}

        // ── Advance answered categories ───────────────────────────────────────
        val newlyCovered = candidates.map { it.category }.toSet()
        val updatedAnswered = state.answeredCategories + newlyCovered

        // ── Find next uncovered category ──────────────────────────────────────
        val nextUncovered = SCAFFOLD_PRIORITY.firstOrNull { it !in updatedAnswered }

        if (nextUncovered == null) {
            // All categories covered — wrap up
            val summary = "I think I have a good picture now — thank you for sharing. I'll keep all of this in mind as we work together."
            val newState = state.copy(answeredCategories = updatedAnswered, activeScaffoldQuestion = null)
            tryUpdateState(ctx.userId, newState)
            ctx.scaffoldState = null
            ctx.branchResult = BranchResult(content = summary, responseStrategy = ResponseStrategy.SIMPLE)
            return
        }

        // ── Generate next scaffold question ───────────────────────────────────
        val question = generateScaffoldQuestion(nextUncovered, candidates.take(3).map { it.content }, ctx)

        val newState = state.copy(answeredCategories = updatedAnswered, activeScaffoldQuestion = question)
        tryUpdateState(ctx.userId, newState)
        ctx.scaffoldState = newState
        ctx.branchResult = BranchResult(content = question, responseStrategy = ResponseStrategy.SIMPLE)
    }

    private suspend fun generateScaffoldQuestion(
        category: PhraseCategory,
        recentContent: List<String>,
        ctx: CognitiveContext,
    ): String {
        if (llmClient != null) {
            try {
                val knownContext = recentContent.joinToString("; ")
                val categoryLabel = category.name.lowercase()
                val systemPrompt = buildString {
                    appendLine("You are a composed, warm assistant getting oriented with a new user.")
                    appendLine("Ask one natural follow-up question about their $categoryLabel.")
                    appendLine("Keep it to 1–2 sentences. Be conversational, not clinical or interrogative.")
                    if (knownContext.isNotBlank()) {
                        appendLine("You already know: $knownContext")
                        appendLine("Weave that in to make the question feel like a real conversation.")
                    }
                    append("Do not repeat information you already have.")
                }
                val response = llmClient.complete(
                    LlmRequest(
                        prompt = "Ask about the user's $categoryLabel.",
                        systemPrompt = systemPrompt,
                        model = LlmModel.CLAUDE_SONNET_4_5,
                        maxTokens = 100,
                        timeoutMs = 10_000,
                    )
                )
                if (response.text.isNotBlank()) return response.text
            } catch (_: Exception) {
                // Fall through to hardcoded fallback
            }
        }
        return hardcodedQuestion(category)
    }

    private fun hardcodedQuestion(category: PhraseCategory): String = when (category) {
        PhraseCategory.IDENTITY      -> "What's your role or what kind of work do you do?"
        PhraseCategory.EXPERTISE     -> "What tools, languages, or technologies do you work with most?"
        PhraseCategory.PREFERENCE    -> "Is there a particular way you prefer to work or communicate?"
        PhraseCategory.ROUTINE       -> "What does a typical day or week look like for you?"
        PhraseCategory.RELATIONSHIP  -> "Do you work with a team, or mostly independently?"
        PhraseCategory.CONTEXT       -> "Is there anything important about your current situation I should know?"
    }

    private suspend fun tryUpdateState(userId: String, state: ScaffoldState) {
        try {
            engramClient.updateScaffoldState(userId, state)
        } catch (_: Exception) {}
    }
}
