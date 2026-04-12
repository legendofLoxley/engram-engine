package app.alfrd.engram.cognitive.pipeline.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.logging.Logger

/**
 * HTTP implementation of [EngramClient] targeting the engram-engine REST API at [baseUrl].
 *
 * Endpoint availability:
 * - `POST /ingest/text` — available
 * - `GET /phrases?q=…` — available
 * - `PATCH /phrases/:id` — available
 * - `decompose` — not yet available; falls back to the naive keyword heuristic
 * - `getScaffoldState` / `updateScaffoldState` — not yet available; falls back to in-memory map
 *
 * Any network failure leads to a logged warning and a graceful degradation —
 * branches are expected to continue producing responses using LLM general knowledge.
 */
class HttpEngramClient(
    private val baseUrl: String = "http://localhost:18792",
) : EngramClient {

    private val logger = Logger.getLogger(HttpEngramClient::class.java.name)
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newHttpClient()

    // Scaffold state is not yet persisted server-side.
    private val scaffoldStates = mutableMapOf<String, ScaffoldState>()

    // ── Decompose (local heuristic — no server endpoint yet) ──────────────────

    override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> =
        InMemoryEngramClient().decompose(text, context)

    // ── Ingest ────────────────────────────────────────────────────────────────

    override suspend fun ingest(candidates: List<PhraseCandidate>) = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(IngestRequest(texts = candidates.map { it.content }))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/ingest/text"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                logger.warning("ingest returned HTTP ${resp.statusCode()}: ${resp.body()}")
            }
        } catch (e: Exception) {
            logger.warning("engram-engine unreachable during ingest: ${e.message}")
        }
    }

    // ── Query phrases ─────────────────────────────────────────────────────────

    override suspend fun queryPhrases(concept: String): List<Phrase> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(concept, "UTF-8")
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/phrases?q=$encoded"))
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) {
                val dtos = json.decodeFromString<List<PhraseDto>>(resp.body())
                dtos.map { it.toPhrase() }
            } else {
                logger.warning("queryPhrases returned HTTP ${resp.statusCode()}")
                emptyList()
            }
        } catch (e: Exception) {
            logger.warning("engram-engine unreachable during queryPhrases: ${e.message}")
            emptyList()
        }
    }

    // ── Amend phrase ──────────────────────────────────────────────────────────

    override suspend fun amendPhrase(phraseId: String, newContent: String) = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(AmendRequest(content = newContent))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/phrases/$phraseId"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                logger.warning("amendPhrase returned HTTP ${resp.statusCode()}")
            }
        } catch (e: Exception) {
            logger.warning("engram-engine unreachable during amendPhrase: ${e.message}")
        }
    }

    // ── Scaffold state (in-memory fallback — no server endpoint yet) ──────────

    override suspend fun getScaffoldState(userId: String): ScaffoldState {
        logger.warning("Scaffold persistence is deferred — using in-memory state for userId=$userId")
        return scaffoldStates.getOrPut(userId) { ScaffoldState() }
    }

    override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) {
        scaffoldStates[userId] = state
    }

    // ── Private serialization shapes ──────────────────────────────────────────

    @Serializable
    private data class IngestRequest(val texts: List<String>)

    @Serializable
    private data class AmendRequest(val content: String)

    @Serializable
    private data class PhraseDto(
        val id: String,
        val content: String,
        val source: String = "unknown",
        @SerialName("trust_phase") val trustPhase: Int = 1,
        val score: Double = 0.5,
    ) {
        fun toPhrase() = Phrase(
            id = id,
            content = content,
            source = source,
            trustPhase = trustPhase,
            score = score,
        )
    }
}
