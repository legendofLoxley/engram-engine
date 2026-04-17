package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest

/**
 * Question branch — graph-augmented answers using Claude Sonnet.
 *
 * Flow:
 * 1. Query memory for phrases related to the question.
 * 2. If phrases found: include them as context in the LLM prompt.
 * 3. If no phrases: answer from general LLM knowledge.
 * 4. Response strategy is COMPLEX for answers > 100 words, SIMPLE otherwise.
 *
 * LLM failure produces a graceful fallback message — no crash.
 */
class QuestionBranch(
    private val engramClient: EngramClient,
    private val llmClient: LlmClient?,
) : Branch {

    override suspend fun execute(ctx: CognitiveContext) {
        if (llmClient == null) {
            ctx.branchResult = BranchResult(
                content = "I'm having trouble with that question right now. Could you ask again?",
                responseStrategy = ResponseStrategy.SIMPLE,
            )
            return
        }

        val phrases = try {
            engramClient.queryPhrases(ctx.memoryQueryHint ?: ctx.utterance, ctx.userId)
        } catch (_: Exception) {
            emptyList()
        }

        try {
            val systemPrompt = if (phrases.isNotEmpty()) {
                val context = phrases.take(5).joinToString("\n") { phrase ->
                    val confidence = "%.0f".format(phrase.score * 100)
                    "- ${phrase.content} [source: ${phrase.source}, confidence: $confidence%]"
                }
                """
                    You are a composed, warm assistant. Answer the user's question using what you know about them.
                    Context about the user (source and confidence shown):
                    $context
                    Treat lower-confidence items as tentative. Answer concisely and warmly. 1–3 sentences unless more depth is clearly needed.
                """.trimIndent()
            } else {
                "You are a composed, warm assistant. No personal context is available. Answer generally and helpfully. Be concise."
            }

            val response = llmClient.complete(
                LlmRequest(
                    prompt = ctx.utterance,
                    systemPrompt = systemPrompt,
                    model = LlmModel.CLAUDE_SONNET_4_5,
                    maxTokens = 512,
                    timeoutMs = 20_000,
                )
            )

            val wordCount = response.text.trim().split(Regex("\\s+")).size
            val strategy = if (wordCount > 100) ResponseStrategy.COMPLEX else ResponseStrategy.SIMPLE

            ctx.branchResult = BranchResult(content = response.text, responseStrategy = strategy)
        } catch (_: Exception) {
            ctx.branchResult = BranchResult(
                content = "I'm having trouble with that question right now. Could you ask again?",
                responseStrategy = ResponseStrategy.SIMPLE,
            )
        }
    }
}
