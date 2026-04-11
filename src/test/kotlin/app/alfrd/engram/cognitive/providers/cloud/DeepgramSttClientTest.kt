package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.SttClient
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class DeepgramSttClientTest {

    @Test
    fun `DeepgramSttClient instantiates with explicit api key`() {
        val client: SttClient = DeepgramSttClient(apiKey = "test-key")
        assertNotNull(client)
    }

    @Test
    fun `streamTranscription returns a non-null flow without connecting`() {
        val client = DeepgramSttClient(apiKey = "test-key")
        // We only verify the Flow is returned; collecting it would call the TODO.
        val flow = client.streamTranscription(emptyFlow())
        assertNotNull(flow)
    }
}
