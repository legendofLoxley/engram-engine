package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One entry of the model's `ops` list: `op("record_item", "kind" to "task", "quote" to "...")`. */
fun op(verb: String, vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
    put("verb", JsonPrimitive(verb))
    fields.forEach { (k, v) -> put(k, v.toJson()) }
}

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}

/** The model's forced `apply_operations` call carrying [ops] (possibly none). */
fun opsToolCall(vararg ops: JsonObject): ToolCall = ToolCall(
    name = Interpreter.TOOL_NAME,
    input = buildJsonObject { put("ops", JsonArray(ops.toList())) },
)

fun opsResponse(vararg ops: JsonObject): LlmResponse =
    LlmResponse(text = "", toolCalls = listOf(opsToolCall(*ops)), latencyMs = 5, retryCount = 0)

/** True for the interpretation stage's request (it forces the tool); false for the Actor's reply call. */
val LlmRequest.isInterpretation: Boolean get() = forcedToolName == Interpreter.TOOL_NAME

/** Just the user's message from an interpretation request — the rest of the prompt is the snapshot, which shows earlier turns' items. */
val LlmRequest.interpretedMessage: String get() = prompt.substringAfter("User message:\n")
