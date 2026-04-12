package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.TtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * ElevenLabs streaming TTS client.
 *
 * POSTs to the ElevenLabs `/v1/text-to-speech/{voiceId}/stream` endpoint and streams
 * the raw PCM response body as [ByteArray] chunks via a [Flow].
 *
 * @param apiKey       ElevenLabs API key (defaults to the `ELEVENLABS_API_KEY` env var).
 * @param voiceId      Voice identifier.  Default: JBFqnCBsd6RMkjVDRZzb (George).
 * @param modelId      Model identifier.  Default: eleven_flash_v2_5.
 * @param outputFormat PCM output format query param.  Default: pcm_22050.
 * @param httpClient   Injectable OkHttpClient — swap for test doubles as needed.
 * @param baseUrl      Base URL for the ElevenLabs API — override in tests to point at MockWebServer.
 */
class ElevenLabsTtsClient(
    private val apiKey: String = System.getenv("ELEVENLABS_API_KEY") ?: "",
    private val voiceId: String = "JBFqnCBsd6RMkjVDRZzb",
    private val modelId: String = "eleven_flash_v2_5",
    private val outputFormat: String = "pcm_22050",
    internal val httpClient: OkHttpClient = OkHttpClient(),
    internal val baseUrl: String = "https://api.elevenlabs.io",
) : TtsClient {

    override fun streamSpeech(text: String): Flow<ByteArray> = channelFlow {
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/v1/text-to-speech/$voiceId/stream?output_format=$outputFormat"
            val jsonBody: JsonObject = buildJsonObject {
                put("text", text)
                put("model_id", modelId)
                putJsonObject("voice_settings") {
                    put("stability", 0.6)
                    put("similarity_boost", 0.8)
                }
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", apiKey)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    throw IOException("ElevenLabs TTS error ${response.code}: $errBody")
                }
                val bodyStream = response.body?.byteStream()
                    ?: throw IOException("ElevenLabs TTS returned empty response body")

                val buffer = ByteArray(CHUNK_SIZE)
                while (true) {
                    ensureActive()
                    val bytesRead = bodyStream.read(buffer)
                    if (bytesRead == -1) break
                    send(buffer.copyOf(bytesRead))
                }
            }
        }
    }

    companion object {
        private const val CHUNK_SIZE = 4096
    }
}
