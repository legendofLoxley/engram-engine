package app.alfrd.engram.cognitive.providers.local

import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.ToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `LocalLlmClient.complete()` goes through `AbstractLlmClient`'s `withTimeout`, which does not
 * cooperate with `runTest`'s virtual-time scheduler over a *real* blocking HTTP call to
 * MockWebServer (the call never "completes" from the virtual scheduler's point of view, so
 * `withTimeout` fires a spurious virtual-time timeout). Matches the existing convention for this
 * exact situation (see [app.alfrd.engram.cognitive.providers.cloud.ElevenLabsTtsClientTest]): use
 * real time (plain `@Test` + `runBlocking`), not `runTest`.
 */
class LocalLlmClientTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun client(model: String = "local"): LocalLlmClient =
        LocalLlmClient(baseUrl = "http://${server.hostName}:${server.port}", model = model)

    private fun enqueueChat(bodyJson: String) {
        server.enqueue(MockResponse().setBody(bodyJson).addHeader("Content-Type", "application/json"))
    }

    // -------------------------------------------------------------------------
    // Thinking-disable flag actually serialized on the wire
    // -------------------------------------------------------------------------

    @Test
    fun `request body serializes enable_thinking false despite it being the field's default value`() = runBlocking {
        enqueueChat("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"hi"}}],"model":"m"}""")

        client().complete(LlmRequest(prompt = "hello"))

        val sentBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val kwargs = sentBody["chat_template_kwargs"]?.jsonObject
        assertEquals(false, kwargs?.get("enable_thinking")?.jsonPrimitive?.content?.toBoolean())
    }

    // -------------------------------------------------------------------------
    // Tool request/response mapping (the LlmClient contract Interpreter depends on)
    // -------------------------------------------------------------------------

    @Test
    fun `tools in the request are sent in OpenAI function-calling shape`() = runBlocking {
        enqueueChat("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"model":"m"}""")

        val tool = ToolDefinition(
            name = "assert_open_intention",
            description = "Record an open intention.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("quote") { put("type", "string") } }
            },
        )
        client().complete(LlmRequest(prompt = "I need to prepare a demo.", tools = listOf(tool)))

        val sentBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val toolsArray = sentBody["tools"]?.jsonArray
        assertTrue(toolsArray != null && toolsArray.isNotEmpty(), "request must carry a tools array when LlmRequest.tools is non-empty")
        val firstFunction = toolsArray!![0].jsonObject["function"]!!.jsonObject
        assertEquals("assert_open_intention", firstFunction["name"]?.jsonPrimitive?.content)
        assertEquals("Record an open intention.", firstFunction["description"]?.jsonPrimitive?.content)
        assertEquals("object", firstFunction["parameters"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `a tool_calls response is mapped to ToolCall with parsed JSON arguments`() = runBlocking {
        enqueueChat(
            """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
              "tool_calls":[{"type":"function","function":{"name":"assert_open_intention",
              "arguments":"{\"quote\":\"prepare a demo\",\"is_first_person_and_not_negated\":true}"}}]}}],
              "model":"m"}"""
        )

        val tool = ToolDefinition(name = "assert_open_intention", description = "d", inputSchema = buildJsonObject { put("type", "object") })
        val response = client().complete(LlmRequest(prompt = "I need to prepare a demo.", tools = listOf(tool)))

        assertEquals(1, response.toolCalls.size)
        assertEquals("assert_open_intention", response.toolCalls[0].name)
        assertEquals("prepare a demo", response.toolCalls[0].input["quote"]?.jsonPrimitive?.content)
        assertEquals(true, response.toolCalls[0].input["is_first_person_and_not_negated"]?.jsonPrimitive?.content?.toBoolean())
    }

    // -------------------------------------------------------------------------
    // Empty/truncated completion must not look like a successful answer
    // -------------------------------------------------------------------------

    @Test
    fun `empty content with no tool call throws instead of returning a blank success`() {
        // AbstractLlmClient retries MAX_RETRIES=2 times (3 attempts total) before giving up.
        repeat(3) {
            enqueueChat("""{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":""}}],"model":"m"}""")
        }

        assertThrows(RuntimeException::class.java) {
            runBlocking { client().complete(LlmRequest(prompt = "hello", timeoutMs = 5_000)) }
        }
    }

    @Test
    fun `empty content is fine when accompanied by a tool call`() = runBlocking {
        enqueueChat(
            """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
              "tool_calls":[{"type":"function","function":{"name":"t","arguments":"{}"}}]}}],"model":"m"}"""
        )

        val response = client().complete(LlmRequest(prompt = "hello", tools = listOf(
            ToolDefinition(name = "t", description = "d", inputSchema = buildJsonObject { put("type", "object") })
        )))

        assertEquals("", response.text)
        assertEquals(1, response.toolCalls.size)
    }

    // -------------------------------------------------------------------------
    // Provider/model identity on the response
    // -------------------------------------------------------------------------

    @Test
    fun `successful response reports local provider and the server's own model id`() = runBlocking {
        enqueueChat("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"hi there"}}],"model":"reported-model-id"}""")

        val response = client(model = "requested-model").complete(LlmRequest(prompt = "hello"))

        assertEquals("hi there", response.text)
        assertEquals("local", response.providerName)
        assertEquals("reported-model-id", response.modelName)
    }
}
