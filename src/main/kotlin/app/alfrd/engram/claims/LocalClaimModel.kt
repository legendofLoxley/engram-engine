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
class LocalClaimModel(private val client: LocalLlmClient, override val endpoint: String? = null) : ClaimPassModel {

    override val promptVersion: String = PROMPT_VERSION

    override suspend fun extract(source: SourceRecord, chunk: ChunkRecord, pass: Int): PassResult {
        require(pass == 1 || pass == 2) { "pass must be 1 or 2" }
        val response = client.complete(
            LlmRequest(
                prompt = userPrompt(source, chunk),
                systemPrompt = if (pass == 1) SYSTEM_ONE else SYSTEM_TWO,
                model = LlmModel.CLAUDE_SONNET_4_5, // a label only: the local client ignores it
                maxTokens = MAX_TOKENS,
                timeoutMs = TIMEOUT_MS,
                tools = listOf(REPORT_TOOL),
                forcedToolName = TOOL_NAME,
                temperature = 0.2,
                reasoning = true,
            ),
        )
        val call = response.toolCalls.firstOrNull { it.name == TOOL_NAME } ?: throw IllegalStateException("the model made no $TOOL_NAME call")
        return PassResult(parseClaims(call.input), response.modelName ?: "unreported")
    }

    companion object {
        const val PROMPT_VERSION = "claims-v1"
        const val TOOL_NAME = "report_claims"
        private const val MAX_TOKENS = 12_000
        private const val TIMEOUT_MS = 20L * 60 * 1000

        private val KINDS = ClaimKind.entries.joinToString(", ") { it.name.lowercase() }

        private val RULES = """
            A claim is one checkable assertion that the passage explicitly makes: a fact, a number with its units or date, a decision or a vote, a commitment or a deadline, a stated requirement, or an attributed position.
            Not claims: headings, labels, boilerplate, navigation text, rhetoric, questions, and anything you would have to infer. If the passage does not state it, it is not a claim.
            Split independent assertions into separate claims. If the passage says the same thing twice, report it once.
            For every claim give:
            - quote: the exact words of the passage that make the assertion, copied character for character. Never paraphrase, never join separate sentences, never correct typos.
            - claimant: the person or organization the passage says made the assertion, as the passage names them. If the passage names nobody, leave it empty. Do not guess.
            - subject: a few words saying what the claim is about.
            - kind: one of $KINDS.
            - place: only if the claim belongs to a particular card, box, section, column or row of the passage, say which. When it sits under a heading, give that heading exactly as it is written (for example SCENARIO A). A number printed near a title belongs to the heading it sits under, not to the one above or beside it. Otherwise leave it empty.
            If the passage has no claims, report an empty list.
        """.trimIndent()

        private val SYSTEM_ONE = "You extract claims from a passage of a document.\n\n$RULES\n\nReport the claims in the order they appear in the passage."

        private val SYSTEM_TWO = """
            Your job is to read one passage of a document and list the assertions it explicitly makes.
            Work through the passage sentence by sentence, and for each sentence (or table row, or list item) decide whether it makes a checkable assertion.

            $RULES
        """.trimIndent()

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
                PassClaim(
                    quote = quote, claimant = str(o, "claimant").takeIf { it.isNotEmpty() }, subject = str(o, "subject"),
                    kind = str(o, "kind").ifEmpty { ClaimKind.FACT.name }, place = str(o, "place").takeIf { it.isNotEmpty() },
                )
            }
        }
    }
}
