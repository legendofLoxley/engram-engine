package app.alfrd.engram.claims

import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.ToolDefinition
import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.SourceRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The claim passes against the local model (the Arx llama-server). It takes a [LocalLlmClient], not the general client interface,
 * so there is no way to hand it a hosted model: extraction inference stays on the box. Each pass is one forced tool call; the two
 * passes of a chunk are separate requests with different wording and no shared context, so neither sees the other's answer.
 * The model that answered is the one the server reports on the response.
 */
class LocalClaimModel(
    private val client: LocalLlmClient,
    override val endpoint: String? = null,
    private val prompts: PromptSet = PromptSet.DEFAULT,
) : ClaimPassModel {

    override val promptVersion: String = prompts.version

    /** Structure read from a page image is claimed from by [StructuredClaimModel], not by these passes. */
    override fun reads(chunk: ChunkRecord): Boolean = chunk.kind != app.alfrd.engram.vision.VisionReading.CHUNK_KIND

    override suspend fun extract(source: SourceRecord, chunk: ChunkRecord, pass: Int): PassResult {
        require(pass == 1 || pass == 2) { "pass must be 1 or 2" }
        val request = LlmRequest(
            prompt = userPrompt(source, chunk),
            systemPrompt = if (pass == 1) prompts.pass1 else prompts.pass2,
            model = LlmModel.CLAUDE_SONNET_4_5, // a label only: the local client ignores it
            maxTokens = MAX_TOKENS,
            timeoutMs = TIMEOUT_MS,
            tools = listOf(REPORT_TOOL),
            forcedToolName = TOOL_NAME,
            temperature = 0.2,
            reasoning = true,
        )
        // An answer that cannot be read (most often a tool call cut off at the token limit) is asked for once more: the model samples, so a
        // second answer is a fresh one. A server error or timeout is not retried here.
        var last = ""
        repeat(2) {
            val response = client.complete(request)
            val why = "finish ${response.finishReason ?: "unreported"}, ${response.completionTokens ?: "?"} tokens" +
                if (response.finishReason == "length") " (cut off at the token limit of $MAX_TOKENS)" else ""
            val call = response.toolCalls.firstOrNull { it.name == TOOL_NAME }
            if (call == null) last = "no $TOOL_NAME call ($why)"
            else try {
                return PassResult(parseClaims(call.input), response.modelName ?: "unreported", response.finishReason, response.completionTokens)
            } catch (e: IllegalStateException) {
                last = "${e.message} ($why)"
            }
        }
        // Fixed wording and counts only, never text: the run report may show it.
        throw ClaimPassFailure("unreadable answer twice: $last")
    }

    companion object {
        const val TOOL_NAME = "report_claims"
        private const val MAX_TOKENS = 24_000
        private const val TIMEOUT_MS = 20L * 60 * 1000

        fun userPrompt(source: SourceRecord, chunk: ChunkRecord): String = buildString {
            append("DOCUMENT: ").append(source.title).append('\n')
            append("LOCATION: ").append(chunk.location).append('\n')
            chunk.section?.takeIf { it.isNotBlank() }?.let { append("SECTION: ").append(it).append('\n') }
            append("\nPASSAGE:\n").append(chunk.text)
        }

        val REPORT_TOOL = ToolDefinition(
            name = TOOL_NAME,
            description = "Report the claims the passage explicitly makes (an empty list if it makes none).",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("claims", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("quote", buildJsonObject { put("type", "string") })
                                put("claimant", buildJsonObject { put("type", "string") })
                                put("subject", buildJsonObject { put("type", "string") })
                                put("kind", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { ClaimKind.entries.forEach { add(JsonPrimitive(it.name.lowercase())) } }) })
                                put("place", buildJsonObject { put("type", "string") })
                                put("passage", buildJsonObject { put("type", "string") })
                                put("as_of", buildJsonObject { put("type", "string") })
                                put("about", buildJsonObject {
                                    put("type", "array")
                                    put("items", buildJsonObject {
                                        put("type", "object")
                                        put("properties", buildJsonObject {
                                            put("name", buildJsonObject { put("type", "string") })
                                            put("kind", buildJsonObject { put("type", "string") })
                                            put("parent", buildJsonObject { put("type", "string") })
                                        })
                                        put("required", buildJsonArray { add(JsonPrimitive("name")) })
                                    })
                                })
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("quote")); add(JsonPrimitive("kind")) })
                        })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("claims")) })
            },
        )

        /** Reads the tool arguments. A missing `claims` list, or a claim with no quote, is an unreadable answer; it is not "no claims". */
        fun parseClaims(input: JsonObject): List<PassClaim> {
            val array = input["claims"] as? JsonArray ?: throw IllegalStateException("the answer has no claims list")
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            return array.map { el ->
                val o = el as? JsonObject ?: throw IllegalStateException("a claim is not an object")
                val quote = str(o, "quote")
                if (quote.isEmpty()) throw IllegalStateException("a claim has no quote")
                // Context is optional: an item of it the model wrote in another shape is left out, never a reason to refuse the answer.
                val about = (o["about"] as? JsonArray)?.mapNotNull { t ->
                    (t as? JsonObject)?.let { ThingRef(str(it, "name"), str(it, "kind"), str(it, "parent").takeIf { p -> p.isNotEmpty() }) }?.takeIf { it.name.isNotEmpty() }
                }.orEmpty()
                PassClaim(
                    quote = quote, claimant = str(o, "claimant").takeIf { it.isNotEmpty() }, subject = str(o, "subject"),
                    kind = str(o, "kind").ifEmpty { ClaimKind.FACT.name }, place = str(o, "place").takeIf { it.isNotEmpty() },
                    passage = str(o, "passage").takeIf { it.isNotEmpty() }, about = about, asOf = str(o, "as_of").takeIf { it.isNotEmpty() },
                )
            }
        }
    }
}

/** A pass whose answer could not be read, twice. The message is fixed wording and counts, safe to show in a run report. */
class ClaimPassFailure(message: String) : IllegalStateException(message)
