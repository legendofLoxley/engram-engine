package app.alfrd.engram.api

import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── API shapes ────────────────────────────────────────────────────────────────

@Serializable
data class PhaseTransitionRecord(
    val from: String,
    val to: String,
    val timestamp: Long,
    val evidence: String,
)

/**
 * Full scaffold state for a user — user-scoped, persists across sessions and container restarts.
 *
 * **User-scoped (stored here):**
 *   trustPhase, answeredCategories, sessionCount, lastInteractionAt, phaseTransitions
 *
 * **Session-scoped (NOT stored here — lives in CognitiveContext):**
 *   turnIndex, selectionResult, responseText, current topic, in-session turn history
 */
@Serializable
data class ScaffoldStateResponse(
    val userId: String,
    val trustPhase: String,
    val answeredCategories: Set<String>,
    val activeScaffoldQuestion: String? = null,
    val sessionCount: Int,
    val lastInteractionAt: Long? = null,
    val phaseTransitions: List<PhaseTransitionRecord>,
)

/**
 * Partial-update body for PUT /scaffold/state/{userId}.
 * Omitted fields keep their existing values; [activeScaffoldQuestion] is always overwritten
 * (send `null` explicitly to clear it).
 */
@Serializable
data class UpdateScaffoldStateRequest(
    val trustPhase: String? = null,
    val answeredCategories: Set<String>? = null,
    val activeScaffoldQuestion: String? = null,
    val sessionCount: Int? = null,
    val lastInteractionAt: Long? = null,
    val phaseTransitions: List<PhaseTransitionRecord>? = null,
)

// ── Routes ────────────────────────────────────────────────────────────────────

fun Application.configureScaffoldRoutes(db: Database) {
    val store = ScaffoldStateStore(db)

    routing {
        route("/scaffold") {
            /**
             * GET /scaffold/state/{userId}
             * Returns the persisted scaffold state for a user.
             * Returns a default ORIENTATION state (not stored) for users with no history.
             */
            get("/state/{userId}") {
                val userId = call.parameters["userId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing userId")

                call.respond(HttpStatusCode.OK, store.get(userId))
            }

            /**
             * PUT /scaffold/state/{userId}
             * Upserts scaffold state. Omitted fields retain their existing stored value.
             * [activeScaffoldQuestion] is always overwritten — pass `null` to clear it.
             */
            put("/state/{userId}") {
                val userId = call.parameters["userId"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing userId")

                val req = call.receive<UpdateScaffoldStateRequest>()
                val existing = store.get(userId)

                val updated = existing.copy(
                    trustPhase             = req.trustPhase ?: existing.trustPhase,
                    answeredCategories     = req.answeredCategories ?: existing.answeredCategories,
                    activeScaffoldQuestion = req.activeScaffoldQuestion,
                    sessionCount           = req.sessionCount ?: existing.sessionCount,
                    lastInteractionAt      = req.lastInteractionAt ?: existing.lastInteractionAt,
                    phaseTransitions       = req.phaseTransitions ?: existing.phaseTransitions,
                )

                store.upsert(userId, updated)
                call.respond(HttpStatusCode.OK, updated)
            }
        }
    }
}

// ── ScaffoldStateStore ────────────────────────────────────────────────────────

/**
 * ArcadeDB-backed store for [ScaffoldStateResponse].
 *
 * Reads are performed outside transactions (ArcadeDB allows non-transactional reads).
 * Writes use a transaction to guarantee atomicity of the upsert.
 */
class ScaffoldStateStore(private val db: Database) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the persisted scaffold state for [userId].
     * Returns a default ORIENTATION state (no answeredCategories, sessionCount=0)
     * for users with no stored record — does NOT write a record for new users.
     */
    fun get(userId: String): ScaffoldStateResponse {
        return try {
            db.query(
                "sql",
                "SELECT FROM UserScaffoldState WHERE userId = :userId",
                mapOf("userId" to userId),
            ).use { rs ->
                if (rs.hasNext()) {
                    val doc = rs.next().toMap()
                    val answeredCategories: Set<String> = try {
                        json.decodeFromString<List<String>>(
                            doc["answeredCategories"] as? String ?: "[]",
                        ).toSet()
                    } catch (_: Exception) { emptySet() }
                    val phaseTransitions: List<PhaseTransitionRecord> = try {
                        json.decodeFromString(
                            doc["phaseTransitions"] as? String ?: "[]",
                        )
                    } catch (_: Exception) { emptyList() }
                    ScaffoldStateResponse(
                        userId                 = userId,
                        trustPhase             = doc["trustPhase"] as? String ?: "ORIENTATION",
                        answeredCategories     = answeredCategories,
                        activeScaffoldQuestion = doc["activeScaffoldQuestion"] as? String,
                        sessionCount           = (doc["sessionCount"] as? Number)?.toInt() ?: 0,
                        lastInteractionAt      = (doc["lastInteractionAt"] as? Number)?.toLong(),
                        phaseTransitions       = phaseTransitions,
                    )
                } else {
                    defaultState(userId)
                }
            }
        } catch (_: Exception) {
            defaultState(userId)
        }
    }

    /** Upserts [state] for [userId]. Creates a new vertex if none exists; updates in place otherwise. */
    fun upsert(userId: String, state: ScaffoldStateResponse) {
        db.transaction {
            val existing = db.query(
                "sql",
                "SELECT FROM UserScaffoldState WHERE userId = :userId",
                mapOf("userId" to userId),
            ).use { rs ->
                // asVertex() returns Vertex (immutable); modify() upgrades it to MutableVertex
                if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null
            }

            val vertex = existing ?: db.newVertex("UserScaffoldState")
            vertex.set("userId", userId)
            vertex.set("trustPhase", state.trustPhase)
            vertex.set("answeredCategories", json.encodeToString(state.answeredCategories.toList()))
            vertex.set("activeScaffoldQuestion", state.activeScaffoldQuestion)
            vertex.set("sessionCount", state.sessionCount)
            if (state.lastInteractionAt != null) {
                vertex.set("lastInteractionAt", state.lastInteractionAt)
            }
            vertex.set("phaseTransitions", json.encodeToString(state.phaseTransitions))
            vertex.set("updatedAt", System.currentTimeMillis())
            vertex.save()
        }
    }

    private fun defaultState(userId: String) = ScaffoldStateResponse(
        userId                 = userId,
        trustPhase             = "ORIENTATION",
        answeredCategories     = emptySet(),
        activeScaffoldQuestion = null,
        sessionCount           = 0,
        lastInteractionAt      = null,
        phaseTransitions       = emptyList(),
    )
}
