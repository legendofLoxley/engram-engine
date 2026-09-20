package app.alfrd.engram.cognitive.providers.cloud

import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Anthropic wire shape for [LlmRequest.forcedToolName] and [LlmRequest.temperature] — asserted without a network call. */
class CloudLlmClientRequestTest {

    private val client = CloudLlmClient(anthropicApiKey = "test-key", googleApiKey = "")
    private val tool = ToolDefinition("apply_operations", "Write.", buildJsonObject { put("type", "object") })

    private fun body(request: LlmRequest) = Json.parseToJsonElement(client.anthropicRequestJson("claude-sonnet-4-6", request)).jsonObject

    @Test
    fun `a forced tool is sent as Anthropic tool_choice, with the temperature`() {
        val sent = body(LlmRequest(prompt = "hi", model = LlmModel.CLAUDE_SONNET_4_5, tools = listOf(tool), forcedToolName = "apply_operations", temperature = 0.0))

        val choice = sent["tool_choice"]!!.jsonObject
        assertEquals("tool", choice["type"]?.jsonPrimitive?.content)
        assertEquals("apply_operations", choice["name"]?.jsonPrimitive?.content)
        assertEquals("0.0", sent["temperature"]?.jsonPrimitive?.content)
        assertEquals("apply_operations", sent["tools"]!!.toString().let { Regex("\"name\":\"(apply_operations)\"").find(it)!!.groupValues[1] })
    }

    @Test
    fun `neither field is sent unless asked for`() {
        val sent = body(LlmRequest(prompt = "hi", tools = listOf(tool)))
        assertFalse(sent.containsKey("tool_choice"))
        assertFalse(sent.containsKey("temperature"))
    }

    @Test
    fun `a forced tool with no tools offered is not sent dangling`() {
        val sent = body(LlmRequest(prompt = "hi", forcedToolName = "apply_operations"))
        assertTrue(!sent.containsKey("tool_choice"))
    }
}
