package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.TtsClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElevenLabsTtsClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        apiKey: String = "test-key",
        voiceId: String = "test-voice-id",
        modelId: String = "eleven_flash_v2_5",
        outputFormat: String = "pcm_22050",
    ): ElevenLabsTtsClient = ElevenLabsTtsClient(
        apiKey = apiKey,
        voiceId = voiceId,
        modelId = modelId,
        outputFormat = outputFormat,
        baseUrl = "http://${server.hostName}:${server.port}",
    )

    // -------------------------------------------------------------------------
    // Instantiation
    // -------------------------------------------------------------------------

    @Test
    fun `ElevenLabsTtsClient instantiates with explicit api key`() {
        val ttsClient: TtsClient = ElevenLabsTtsClient(apiKey = "test-key")
        assertNotNull(ttsClient)
    }

    // -------------------------------------------------------------------------
    // Audio streaming
    // -------------------------------------------------------------------------

    @Test
    fun `streamSpeech emits PCM audio bytes from server response`() = runTest {
        val audioData = ByteArray(512) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(audioData)))

        val chunks = client().streamSpeech("Hello world").toList()

        val received = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertArrayEquals(audioData, received)
    }

    @Test
    fun `streamSpeech collects all bytes when response spans multiple reads`() = runTest {
        // Body larger than CHUNK_SIZE (4096) — guarantees at least two read() calls
        val audioData = ByteArray(10_000) { (it % 256).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(audioData)))

        val chunks = client().streamSpeech("Streaming test").toList()

        assertTrue(chunks.size >= 2, "Expected multiple chunks but got ${chunks.size}")
        val received = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertArrayEquals(audioData, received)
    }

    @Test
    fun `streamSpeech flow completes after all audio is received`() = runTest {
        val audioData = ByteArray(256) { 0x7F.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(audioData)))

        val chunks = client().streamSpeech("Complete test").toList()

        assertNotNull(chunks)
        assertTrue(chunks.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    fun `streamSpeech throws on 401 Unauthorized`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"detail":{"message":"Invalid API key"}}""")
        )

        val ex = assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                client().streamSpeech("Hello").toList()
            }
        }
        assertTrue(ex.message?.contains("401") == true, "Exception should mention status 401: ${ex.message}")
    }

    @Test
    fun `streamSpeech throws on 500 Internal Server Error`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val ex = assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                client().streamSpeech("Hello").toList()
            }
        }
        assertTrue(ex.message?.contains("500") == true, "Exception should mention status 500: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // Request verification
    // -------------------------------------------------------------------------

    @Test
    fun `streamSpeech sends correct URL with voice ID and output format`() = runTest {
        val audioData = ByteArray(8)
        server.enqueue(MockResponse().setBody(Buffer().write(audioData)))

        client(voiceId = "my-voice", outputFormat = "pcm_44100")
            .streamSpeech("URL test")
            .toList()

        val request = server.takeRequest()
        val path = request.path ?: ""
        assertTrue(path.contains("my-voice"), "URL should contain voice ID: $path")
        assertTrue(path.contains("output_format=pcm_44100"), "URL should contain output_format: $path")
        assertTrue(path.contains("/v1/text-to-speech/"), "URL should contain API path: $path")
    }

    @Test
    fun `streamSpeech sends xi-api-key header`() = runTest {
        val audioData = ByteArray(8)
        server.enqueue(MockResponse().setBody(Buffer().write(audioData)))

        client(apiKey = "secret-key-123").streamSpeech("Header test").toList()

        val request = server.takeRequest()
        assertEquals("secret-key-123", request.getHeader("xi-api-key"))
    }

    @Test
    fun `streamSpeech sends correct JSON body with text model_id and voice_settings`() = runTest {
        val audioData = ByteArray(8)
        server.enqueue(MockResponse().setBody(Buffer().write(audioData)))

        client(modelId = "eleven_flash_v2_5")
            .streamSpeech("Test sentence for body check")
            .toList()

        val request = server.takeRequest()
        val bodyStr = request.body.readUtf8()
        assertTrue(bodyStr.contains("Test sentence for body check"), "Body should contain text: $bodyStr")
        assertTrue(bodyStr.contains("model_id"), "Body should contain model_id: $bodyStr")
        assertTrue(bodyStr.contains("eleven_flash_v2_5"), "Body should contain model value: $bodyStr")
        assertTrue(bodyStr.contains("voice_settings"), "Body should contain voice_settings: $bodyStr")
        assertTrue(bodyStr.contains("stability"), "Body should contain stability: $bodyStr")
        assertTrue(bodyStr.contains("similarity_boost"), "Body should contain similarity_boost: $bodyStr")
        assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(";")?.trim())
    }

    @Test
    fun `streamSpeech with empty text sends empty string in request body`() = runTest {
        val audioData = ByteArray(8)
        server.enqueue(MockResponse().setBody(Buffer().write(audioData)))

        client().streamSpeech("").toList()

        val request = server.takeRequest()
        val bodyStr = request.body.readUtf8()
        // text field is present and is an empty string
        assertTrue(bodyStr.contains("\"text\""), "Body should contain text key: $bodyStr")
        assertTrue(bodyStr.contains("\"\""), "Body should contain empty string value: $bodyStr")
    }

    // -------------------------------------------------------------------------
    // Cancellation
    // -------------------------------------------------------------------------

    @Test
    fun `streamSpeech cancellation cleans up without hanging`() = runTest {
        // Use a large body so streaming doesn't complete before we cancel
        val audioData = ByteArray(100_000) { 0x42.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(audioData)))

        val collectJob = backgroundScope.launch {
            client().streamSpeech("Cancel test").toList()
        }

        // Give the job a moment to start streaming, then cancel
        collectJob.cancel()
        // backgroundScope handles join at runTest exit — test must not hang
    }
}
