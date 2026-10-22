package app.alfrd.engram.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** What a vision model answered for one image: the text, the model the server reported, how long it took and how it ended. */
data class VisionAnswer(val text: String, val model: String?, val ms: Long, val finishReason: String?, val completionTokens: Int?)

/** A model that reads an image. Local servers only: see [LocalVisionClient]. */
fun interface VisionModel {
    fun read(png: ByteArray, prompt: String): VisionAnswer
}

/**
 * One image and one prompt to a local OpenAI-compatible server (llama.cpp `llama-server` started with `--mmproj`), as a single user message
 * with the image as a data URL. Loopback only: a page image never leaves the box, so a base URL whose host is not loopback is refused at
 * construction. [thinking] null leaves the server's own default; false asks the chat template to skip reasoning (faster, often less careful).
 */
class LocalVisionClient(
    baseUrl: String,
    private val maxTokens: Int = 8_000,
    private val timeout: Duration = Duration.ofMinutes(10),
    private val thinking: Boolean? = null,
    private val temperature: Double? = null,
) : VisionModel {
    private val base: String = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val host = URI.create(base).host
        require(host in LOOPBACK) { "vision servers are loopback only (got host '$host')" }
    }

    override fun read(png: ByteArray, prompt: String): VisionAnswer {
        val body = buildJsonObject {
            put("model", "local")
            put("max_tokens", maxTokens)
            temperature?.let { put("temperature", it) }
            thinking?.let { t -> put("chat_template_kwargs", buildJsonObject { put("enable_thinking", t) }) }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png)) })
                        })
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    })
                })
            })
        }
        val started = System.currentTimeMillis()
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$base/chat/completions")).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val ms = System.currentTimeMillis() - started
        // The status only: a server's error body can echo the request.
        if (response.statusCode() !in 200..299) throw IllegalStateException("vision server answered HTTP ${response.statusCode()}")
        val root = json.parseToJsonElement(response.body()).jsonObject
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: throw IllegalStateException("vision server returned no choice")
        val text = (choice["message"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        return VisionAnswer(
            text = text, model = (root["model"] as? JsonPrimitive)?.contentOrNull, ms = ms,
            finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull,
            completionTokens = ((root["usage"] as? JsonObject)?.get("completion_tokens") as? JsonPrimitive)?.intOrNull,
        )
    }

    companion object {
        val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "[::1]")
    }
}
