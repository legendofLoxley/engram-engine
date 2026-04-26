package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse

/**
 * Wraps any [LlmClient] and prepends [VOICE_IDENTITY_SYSTEM_PROMPT] to every request's
 * system prompt. Single injection point — branches provide only their branch-specific addendum.
 */
internal class VoiceContextLlmClient(private val delegate: LlmClient) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val enriched = request.copy(
            systemPrompt = if (request.systemPrompt != null)
                "$VOICE_IDENTITY_SYSTEM_PROMPT\n\n${request.systemPrompt}"
            else
                VOICE_IDENTITY_SYSTEM_PROMPT,
        )
        return delegate.complete(enriched)
    }
}
