package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.AbstractLlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse

/**
 * Routes LLM requests to Anthropic (Claude) or Google (Gemini) based on the requested [LlmModel].
 *
 * API keys are injected via constructor; in production they are read from environment variables
 * so that swapping to a local provider only requires changing the [LlmClient] binding — no rewrite.
 *
 * HTTP call implementations are stubbed with [TODO] — networking is a separate task.
 */
class CloudLlmClient(
    private val anthropicApiKey: String = System.getenv("ANTHROPIC_API_KEY") ?: "",
    private val googleApiKey: String = System.getenv("GOOGLE_API_KEY") ?: "",
) : AbstractLlmClient() {

    override suspend fun doComplete(request: LlmRequest): LlmResponse =
        when (request.model) {
            LlmModel.CLAUDE_SONNET_4_5,
            LlmModel.CLAUDE_SONNET_4 -> callAnthropic(request)

            LlmModel.GEMINI_FLASH_2_0,
            LlmModel.GEMINI_FLASH_2_0_LITE -> callGoogle(request)
        }

    // -------------------------------------------------------------------------
    // Provider-specific call sites (HTTP not yet implemented)
    // -------------------------------------------------------------------------

    @Suppress("UNUSED_PARAMETER")
    private suspend fun callAnthropic(request: LlmRequest): LlmResponse {
        TODO("Anthropic Messages API HTTP call not yet implemented")
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun callGoogle(request: LlmRequest): LlmResponse {
        TODO("Google Generative Language API HTTP call not yet implemented")
    }
}
