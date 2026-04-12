package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.AbstractLlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.LlmTimeoutError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Routes LLM requests to Anthropic (Claude) or Google (Gemini) based on the requested [LlmModel].
 *
 * API keys are injected via constructor; in production they are read from environment variables
 * so that swapping to a local provider only requires changing the [LlmClient] binding — no rewrite.
 *
 * Non-2xx responses throw a RuntimeException so [AbstractLlmClient]'s retry logic kicks in.
 * Timeout throws [LlmTimeoutError] directly — no retry per policy.
 */
class CloudLlmClient(
    private val anthropicApiKey: String = System.getenv("ANTHROPIC_API_KEY") ?: "",
    private val googleApiKey: String = System.getenv("GOOGLE_AI_API_KEY") ?: "",
) : AbstractLlmClient() {

    val hasAnthropicKey: Boolean get() = anthropicApiKey.isNotBlank()
    val hasGoogleKey: Boolean    get() = googleApiKey.isNotBlank()

    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newHttpClient()

    override suspend fun doComplete(request: LlmRequest): LlmResponse =
        when (request.model) {
            LlmModel.CLAUDE_HAIKU_3_5,
            LlmModel.CLAUDE_SONNET_4_5,
            LlmModel.CLAUDE_SONNET_4 -> callAnthropic(request)

            LlmModel.GEMINI_FLASH_2_0,
            LlmModel.GEMINI_FLASH_2_0_LITE -> callGoogle(request)
        }

    // ── Anthropic Messages API ────────────────────────────────────────────────

    private suspend fun callAnthropic(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        val modelId = when (request.model) {
            LlmModel.CLAUDE_HAIKU_3_5   -> "claude-haiku-3-5-20250307"
            LlmModel.CLAUDE_SONNET_4_5 -> "claude-sonnet-4-5-20250514"
            LlmModel.CLAUDE_SONNET_4   -> "claude-sonnet-4-20250514"
            else                       -> request.model.apiId
        }

        val bodyObj = buildAnthropicBody(modelId, request)
        val bodyStr = json.encodeToString(bodyObj)

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", anthropicApiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .timeout(Duration.ofMillis(request.timeoutMs))
            .build()

        val response = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            throw LlmTimeoutError("Anthropic request timed out after ${request.timeoutMs} ms", e)
        }

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Anthropic API error ${response.statusCode()}: ${response.body()}")
        }

        val text = parseAnthropicText(response.body())
        LlmResponse(text = text, latencyMs = System.currentTimeMillis() - startMs, retryCount = 0)
    }

    private fun buildAnthropicBody(modelId: String, request: LlmRequest): AnthropicRequest {
        val messages = listOf(AnthropicMessage(role = "user", content = request.prompt))
        return AnthropicRequest(
            model = modelId,
            max_tokens = request.maxTokens,
            system = request.systemPrompt,
            messages = messages,
        )
    }

    private fun parseAnthropicText(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        val content = root["content"]?.jsonArray ?: return ""
        return content
            .firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: ""
    }

    // ── Google Gemini API ─────────────────────────────────────────────────────

    private suspend fun callGoogle(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        val modelId = when (request.model) {
            LlmModel.GEMINI_FLASH_2_0      -> "gemini-2.0-flash"
            LlmModel.GEMINI_FLASH_2_0_LITE -> "gemini-2.0-flash-lite"
            else                           -> request.model.apiId
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$googleApiKey"

        val bodyObj = buildGeminiBody(request)
        val bodyStr = json.encodeToString(bodyObj)

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .timeout(Duration.ofMillis(request.timeoutMs))
            .build()

        val response = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            throw LlmTimeoutError("Gemini request timed out after ${request.timeoutMs} ms", e)
        }

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Gemini API error ${response.statusCode()}: ${response.body()}")
        }

        val text = parseGeminiText(response.body())
        LlmResponse(text = text, latencyMs = System.currentTimeMillis() - startMs, retryCount = 0)
    }

    private fun buildGeminiBody(request: LlmRequest): GeminiRequest {
        val systemInstruction = request.systemPrompt?.let {
            GeminiContent(parts = listOf(GeminiPart(text = it)))
        }
        val contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = request.prompt))))
        val genConfig = GeminiGenerationConfig(
            maxOutputTokens = request.maxTokens,
            responseMimeType = if (request.structuredOutput) "application/json" else null,
        )
        return GeminiRequest(
            systemInstruction = systemInstruction,
            contents = contents,
            generationConfig = genConfig,
        )
    }

    private fun parseGeminiText(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        return root["candidates"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject?.get("content")
            ?.jsonObject?.get("parts")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
            ?: ""
    }

    // ── Serialization shapes ──────────────────────────────────────────────────

    @Serializable
    private data class AnthropicRequest(
        val model: String,
        val max_tokens: Int,
        val system: String? = null,
        val messages: List<AnthropicMessage>,
    )

    @Serializable
    private data class AnthropicMessage(val role: String, val content: String)

    @Serializable
    private data class GeminiRequest(
        val systemInstruction: GeminiContent? = null,
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenerationConfig? = null,
    )

    @Serializable
    private data class GeminiContent(val parts: List<GeminiPart>)

    @Serializable
    private data class GeminiPart(val text: String)

    @Serializable
    private data class GeminiGenerationConfig(
        val maxOutputTokens: Int? = null,
        val responseMimeType: String? = null,
    )
}
