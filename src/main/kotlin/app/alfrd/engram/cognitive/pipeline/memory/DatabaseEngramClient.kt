package app.alfrd.engram.cognitive.pipeline.memory

import app.alfrd.engram.api.PhaseTransitionRecord
import app.alfrd.engram.api.ScaffoldStateResponse
import app.alfrd.engram.api.ScaffoldStateStore
import app.alfrd.engram.api.queryPhrases
import com.arcadedb.database.Database
import com.arcadedb.graph.Edge
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * ArcadeDB-backed implementation of [EngramClient].
 *
 * Phrase ingestion writes directly to the graph:
 *   User(email) → TRUSTS → Source(onboarding_conversation) → ASSERTS → Phrase
 *
 * The Source vertex is created on first ingestion for a given user and reused on subsequent
 * turns — one Source per user for all chat-captured phrases.
 *
 * Scaffold state is persisted in [UserScaffoldState] vertices via [ScaffoldStateStore],
 * which is the same backing store used by the scaffold REST routes.
 *
 * Decomposition delegates to the naive heuristic in [InMemoryEngramClient] — the real
 * LLM-based decomposer is a future task.
 *
 * All DB calls run on [Dispatchers.IO]. Failures are logged and swallowed — the pipeline
 * must never crash due to a write-path failure.
 */
class DatabaseEngramClient(
    private val db: Database,
) : EngramClient {

    private val scaffoldStore = ScaffoldStateStore(db)
    private val heuristicDecompose = InMemoryEngramClient()
    private val logger = LoggerFactory.getLogger(DatabaseEngramClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val SOURCE_TYPE = "onboarding_conversation"
        const val TRUST_SCORE = 0.7
    }

    // ── Decompose ─────────────────────────────────────────────────────────────

    override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> =
        heuristicDecompose.decompose(text, context)

    // ── Ingest ────────────────────────────────────────────────────────────────

    /**
     * Writes each candidate as a Phrase vertex attributed to the user's conversation Source.
     *
     * If no User vertex exists for [userEmail], ingestion is skipped silently — the
     * system requires users to be seeded via /onboard/seed before phrase attribution
     * is possible.
     */
    override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String) = withContext(Dispatchers.IO) {
        if (userEmail.isBlank() || candidates.isEmpty()) return@withContext
        try {
            db.transaction {
                val now = System.currentTimeMillis()

                val userVertex = db.query(
                    "sql",
                    "SELECT FROM User WHERE email = :email",
                    mapOf("email" to userEmail),
                ).use { rs ->
                    if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null
                }
                if (userVertex == null) {
                    logger.warn("ingest: no User vertex for email=$userEmail — skipping")
                    return@transaction
                }

                // Find or create the user's onboarding_conversation Source.
                val sourceName = "$SOURCE_TYPE:$userEmail"
                val sourceVertex = db.query(
                    "sql",
                    "SELECT FROM Source WHERE name = :name",
                    mapOf("name" to sourceName),
                ).use { rs ->
                    if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null
                } ?: run {
                    val sv = db.newVertex("Source").apply {
                        set("uid", UUID.randomUUID().toString())
                        set("name", sourceName)
                        set("type", SOURCE_TYPE)
                        set("metadata", """{"userEmail":"$userEmail","createdAt":$now}""")
                        save()
                    }
                    userVertex.newEdge("TRUSTS", sv, false).apply {
                        set("scores", "[]")
                        save()
                    }
                    sv
                }

                // Create Phrase vertices and ASSERTS edges.
                val scores = """[{"type":"trust","perspective":"user","value":$TRUST_SCORE}]"""
                for (candidate in candidates) {
                    val phraseVertex = db.newVertex("Phrase").apply {
                        set("uid", UUID.randomUUID().toString())
                        set("text", candidate.content)
                        set("hash", sha256(candidate.content))
                        set("visibility", "private")
                        set("createdAt", now)
                        set("updatedAt", now)
                        save()
                    }
                    sourceVertex.newEdge("ASSERTS", phraseVertex, false).apply {
                        set("context", SOURCE_TYPE)
                        set("timestamp", now)
                        set("scores", scores)
                        save()
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("ingest failed for userEmail=$userEmail: ${e.message}")
        }
    }

    // ── Query phrases ─────────────────────────────────────────────────────────

    override suspend fun queryPhrases(userEmail: String, concept: String?, limit: Int): List<ScoredPhrase> =
        withContext(Dispatchers.IO) {
            queryPhrases(db, userEmail, concept, limit)
        }

    // ── Amend phrase ──────────────────────────────────────────────────────────

    override suspend fun amendPhrase(phraseId: String, newContent: String) = withContext(Dispatchers.IO) {
        try {
            db.transaction {
                db.query(
                    "sql",
                    "SELECT FROM Phrase WHERE uid = :uid",
                    mapOf("uid" to phraseId),
                ).use { rs ->
                    if (rs.hasNext()) {
                        val vertex = rs.next().toElement().asVertex().modify()
                        vertex.set("text", newContent)
                        vertex.set("hash", sha256(newContent))
                        vertex.set("updatedAt", System.currentTimeMillis())
                        vertex.save()
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("amendPhrase failed for phraseId=$phraseId: ${e.message}")
        }
    }

    // ── Scaffold state ────────────────────────────────────────────────────────

    override suspend fun getScaffoldState(userId: String): ScaffoldState = withContext(Dispatchers.IO) {
        scaffoldStore.get(userId).toDomain()
    }

    override suspend fun updateScaffoldState(userId: String, state: ScaffoldState) = withContext(Dispatchers.IO) {
        scaffoldStore.upsert(userId, state.toResponse(userId))
    }

    // ── Topic confidence ─────────────────────────────────────────────────────
    //
    // Attached to the graph via User -CONFIDENT_IN-> Concept, not a flat per-user field — the
    // Concept vertex is find-or-created by normalized topic name (mirroring the Source
    // find-or-create block in ingest() above), and the CONFIDENT_IN edge between User and
    // Concept carries the evolving TopicConfidence state as edge properties.

    override suspend fun getTopicConfidence(userEmail: String, topic: String): TopicConfidence {
        val normalized = normalizeTopic(topic)
        if (userEmail.isBlank() || normalized.isBlank()) return TopicConfidence(topic = normalized)
        return withContext(Dispatchers.IO) {
            try {
                val userVertex = findUserVertex(userEmail) ?: return@withContext TopicConfidence(topic = normalized)
                val conceptVertex = findConceptVertex(normalized) ?: return@withContext TopicConfidence(topic = normalized)
                val edge = findConfidentInEdge(userVertex, conceptVertex)
                    ?: return@withContext TopicConfidence(topic = normalized)
                edge.toTopicConfidence(normalized)
            } catch (e: Exception) {
                logger.warn("getTopicConfidence failed for userEmail=$userEmail topic=$topic: ${e.message}")
                TopicConfidence(topic = normalized)
            }
        }
    }

    override suspend fun updateTopicConfidence(
        userEmail: String,
        topic: String,
        confidence: TopicConfidence,
    ) = withContext(Dispatchers.IO) {
        val normalized = normalizeTopic(topic)
        if (userEmail.isBlank() || normalized.isBlank()) return@withContext
        try {
            db.transaction {
                val userVertex = findUserVertex(userEmail)
                if (userVertex == null) {
                    logger.warn("updateTopicConfidence: no User vertex for email=$userEmail — skipping")
                    return@transaction
                }
                val conceptVertex = findOrCreateConceptVertex(normalized)
                val existingEdge = findConfidentInEdge(userVertex, conceptVertex)
                val edge = existingEdge?.modify() ?: userVertex.newEdge("CONFIDENT_IN", conceptVertex, false)
                edge.set("score", confidence.score)
                edge.set("phase", confidence.phase.name)
                edge.set("hasUnresolvedContradiction", confidence.hasUnresolvedContradiction)
                edge.set("evidence", json.encodeToString(confidence.evidence.takeLast(20)))
                edge.set("updatedAt", confidence.updatedAt)
                edge.save()
            }
        } catch (e: Exception) {
            logger.warn("updateTopicConfidence failed for userEmail=$userEmail topic=$topic: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun normalizeTopic(topic: String): String = topic.trim().lowercase()

    private fun findUserVertex(userEmail: String): Vertex? = db.query(
        "sql",
        "SELECT FROM User WHERE email = :email",
        mapOf("email" to userEmail),
    ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    private fun findConceptVertex(normalizedName: String): Vertex? = db.query(
        "sql",
        "SELECT FROM Concept WHERE normalizedName = :name",
        mapOf("name" to normalizedName),
    ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    private fun findOrCreateConceptVertex(normalizedName: String): Vertex =
        findConceptVertex(normalizedName) ?: db.newVertex("Concept").apply {
            set("uid", UUID.randomUUID().toString())
            set("name", normalizedName)
            set("type", "topic")
            set("normalizedName", normalizedName)
            save()
        }

    private fun findConfidentInEdge(userVertex: Vertex, conceptVertex: Vertex): Edge? {
        val conceptUid = conceptVertex.get("uid")
        return userVertex.getEdges(Vertex.DIRECTION.OUT, "CONFIDENT_IN")
            .firstOrNull { it.getVertex(Vertex.DIRECTION.IN)?.get("uid") == conceptUid }
    }

    private fun Edge.toTopicConfidence(topic: String): TopicConfidence {
        val phaseStr = get("phase") as? String ?: "ORIENTATION"
        val evidenceJson = get("evidence") as? String ?: "[]"
        val evidence = try {
            json.decodeFromString<List<ConfidenceEvidenceEntry>>(evidenceJson)
        } catch (_: Exception) {
            emptyList()
        }
        return TopicConfidence(
            topic = topic,
            score = (get("score") as? Number)?.toDouble() ?: 0.0,
            phase = try {
                ConfidencePhase.valueOf(phaseStr)
            } catch (_: Exception) {
                ConfidencePhase.ORIENTATION
            },
            hasUnresolvedContradiction = get("hasUnresolvedContradiction") as? Boolean ?: false,
            evidence = evidence,
            updatedAt = (get("updatedAt") as? Number)?.toLong() ?: 0L,
        )
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.trim().lowercase().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun ScaffoldStateResponse.toDomain(): ScaffoldState {
        val phase = when (trustPhase) {
            "WORKING_RHYTHM" -> 2
            "CONTEXT"        -> 3
            "UNDERSTANDING"  -> 4
            else             -> 1
        }
        return ScaffoldState(
            trustPhase             = phase,
            answeredCategories     = answeredCategories.mapNotNull {
                try { PhraseCategory.valueOf(it) } catch (_: Exception) { null }
            }.toSet(),
            activeScaffoldQuestion = activeScaffoldQuestion,
            sessionCount           = sessionCount,
            lastInteractionAt      = lastInteractionAt,
            phaseTransitions       = phaseTransitions.map { r ->
                ScaffoldPhaseTransition(from = r.from, to = r.to, timestamp = r.timestamp, evidence = r.evidence)
            },
        )
    }

    private fun ScaffoldState.toResponse(userId: String) = ScaffoldStateResponse(
        userId                 = userId,
        trustPhase             = when (trustPhase) {
            2    -> "WORKING_RHYTHM"
            3    -> "CONTEXT"
            4    -> "UNDERSTANDING"
            else -> "ORIENTATION"
        },
        answeredCategories     = answeredCategories.map { it.name }.toSet(),
        activeScaffoldQuestion = activeScaffoldQuestion,
        sessionCount           = sessionCount,
        lastInteractionAt      = lastInteractionAt ?: System.currentTimeMillis(),
        phaseTransitions       = phaseTransitions.map { t ->
            PhaseTransitionRecord(from = t.from, to = t.to, timestamp = t.timestamp, evidence = t.evidence)
        },
    )
}
