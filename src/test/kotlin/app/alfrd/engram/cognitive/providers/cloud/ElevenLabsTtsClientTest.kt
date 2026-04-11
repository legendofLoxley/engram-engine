package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.TtsClient
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ElevenLabsTtsClientTest {

    @Test
    fun `ElevenLabsTtsClient instantiates with explicit api key`() {
        val client: TtsClient = ElevenLabsTtsClient(apiKey = "test-key")
        assertNotNull(client)
    }

    @Test
    fun `streamSpeech returns a non-null flow without connecting`() {
        val client = ElevenLabsTtsClient(apiKey = "test-key")
        // We only verify the Flow is returned; collecting it would call the TODO.
        val flow = client.streamSpeech("Hello world")
        assertNotNull(flow)
    }
}
