package app.alfrd.engram.cognitive.providers.local

import app.alfrd.engram.cognitive.providers.AbstractLlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.LlmTimeoutError
import app.alfrd.engram.cognitive.providers.ToolCall
import app.alfrd.engram.cognitive.providers.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * Routes LLM requests to a local OpenAI-chat-completions-compatible endpoint (e.g. llama.cpp's
 * `llama-server`) instead of a cloud provider — the local counterpart to [app.alfrd.engram.cognitive.providers.cloud.CloudLlmClient],
 * per [app.alfrd.engram.cognitive.providers.LlmClient]'s "swap cloud for local via config" contract.
 *
 * [request.model][LlmRequest.model] (an Anthropic/Gemini [app.alfrd.engram.cognitive.providers.LlmModel])
 * is intentionally not forwarded: a llama.cpp server serves exactly one loaded model and accepts
 * any `model` string, so [model] here is a fixed label rather than a provider-model mapping.
 *
 * Tool-calling ([LlmRequest.tools]) is mapped to the OpenAI `tools`/`tool_calls` wire shape —
 * verified against a live llama.cpp endpoint before implementing (an OpenAI-*text*-completion-
 * compatible server does not necessarily support the separate tool-calling contract; this one
 * does, confirmed by a direct `finish_reason: "tool_calls"` response). [ToolCall.input] is parsed
 * from `function.arguments`, which OpenAI's shape carries as a JSON-encoded *string*, not a nested
 * object like Anthropic's `input`.
 */
class LocalLlmClient(
    private val baseUrl: String = System.getenv("LOCAL_LLM_BASE_URL") ?: "http://127.0.0.1:8081/v1",
    private val model: String = System.getenv("LOCAL_LLM_MODEL") ?: "local",
) : AbstractLlmClient() {

    // encodeDefaults = true: without it, kotlinx.serialization omits chat_template_kwargs
    // entirely (it sits at its declared default), silently dropping the enable_thinking=false
    // override this client depends on.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http: HttpClient = HttpClient.newHttpClient()

    override suspend fun doComplete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        val messages = buildList {
            request.systemPrompt?.let { add(ChatMessage(role = "system", content = it)) }
            add(ChatMessage(role = "user", content = request.prompt))
        }
        // The pipeline's LlmRequest.maxTokens (e.g. Actor's 512) is sized for Claude's low hidden-
        // reasoning overhead. A local reasoning-tuned model can exhaust that entire budget on
        // chain-of-thought before emitting any visible content — reproduced directly against this
        // endpoint (finish_reason "length", empty content, non-empty reasoning_content). Disabling
        // thinking mode per-request (a llama.cpp/Qwen3 chat-template convention; harmlessly ignored
        // by templates that don't define it) keeps that budget usable without touching the shared
        // MAX_TOKENS constant or the vendor model server's own config.
        val bodyStr = json.encodeToString(
            ChatCompletionRequest(
                model = model,
                messages = messages,
                max_tokens = request.maxTokens,
                tools = request.tools.takeIf { it.isNotEmpty() }?.map { it.toWireFormat() },
            )
        )

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .timeout(Duration.ofMillis(request.timeoutMs))
            .build()

        val response = try {
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            throw LlmTimeoutError("Local LLM request timed out after ${request.timeoutMs} ms", e)
        }

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Local LLM error ${response.statusCode()}: ${response.body()}")
        }

        val parsed = parseCompletion(response.body())

        // A blank completion with no tool call is never a usable answer — most commonly this
        // model exhausting its token budget on hidden reasoning before emitting anything visible
        // (finish_reason "length"; reproduced directly against this endpoint). Reporting it as a
        // normal LlmResponse would let it silently pass through as "the model said nothing",
        // indistinguishable from a deliberate empty reply. Throwing lets AbstractLlmClient's
        // retry policy engage and makes the failure visible to the caller instead.
        if (parsed.text.isBlank() && parsed.toolCalls.isEmpty()) {
            throw RuntimeException(
                "Local LLM returned an empty completion (finish_reason=${parsed.finishReason}) — not a usable answer"
            )
        }

        LlmResponse(
            text = parsed.text,
            toolCalls = parsed.toolCalls,
            latencyMs = System.currentTimeMillis() - startMs,
            retryCount = 0,
            providerName = "local",
            modelName = parsed.reportedModel ?: model,
        )
    }

    private fun ToolDefinition.toWireFormat() =
        OpenAiTool(function = OpenAiFunctionDef(name = name, description = description, parameters = inputSchema))

    private data class ParsedCompletion(
        val text: String,
        val toolCalls: List<ToolCall>,
        val finishReason: String?,
        val reportedModel: String?,
    )

    private fun parseCompletion(body: String): ParsedCompletion {
        val root = json.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val message = choice?.get("message")?.jsonObject
        val text = message?.get("content")?.jsonPrimitive?.content ?: ""
        val toolCalls = message?.get("tool_calls")?.jsonArray?.mapNotNull { parseToolCall(it.jsonObject) } ?: emptyList()
        val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.content
        val reportedModel = root["model"]?.jsonPrimitive?.content
        return ParsedCompletion(text, toolCalls, finishReason, reportedModel)
    }

    private fun parseToolCall(obj: JsonObject): ToolCall? {
        val function = obj["function"]?.jsonObject ?: return null
        val name = function["name"]?.jsonPrimitive?.content ?: return null
        // OpenAI's wire shape carries arguments as a JSON-encoded string, not a nested object.
        val argumentsStr = function["arguments"]?.jsonPrimitive?.content ?: "{}"
        val input = try {
            json.parseToJsonElement(argumentsStr).jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        return ToolCall(name = name, input = input)
    }

    @Serializable
    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val max_tokens: Int,
        val chat_template_kwargs: ChatTemplateKwargs = ChatTemplateKwargs(),
        val tools: List<OpenAiTool>? = null,
    )

    @Serializable
    private data class ChatTemplateKwargs(val enable_thinking: Boolean = false)

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class OpenAiTool(val type: String = "function", val function: OpenAiFunctionDef)

    @Serializable
    private data class OpenAiFunctionDef(val name: String, val description: String, val parameters: JsonObject)
}
