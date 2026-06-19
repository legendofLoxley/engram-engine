package app.alfrd.engram.api

import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.cognitive.pipeline.PipelineTrace
import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

private val logger = LoggerFactory.getLogger("app.alfrd.engram.api.DebugConverse")

// ── Request / response shapes ─────────────────────────────────────────────────

@Serializable
data class DebugConverseRequest(
    val message: String,
    /** Omit to start a fresh session; provide the same value across calls to continue context. */
    val sessionId: String? = null,
    /**
     * Short label mapped to a synthetic user email (`debug+<label>@test.alfrd.internal`).
     * Omit to generate a one-shot UUID user per request.
     */
    val syntheticUserId: String? = null,
    /**
     * Seed the scaffold trust phase for this synthetic user before running the turn.
     * One of: ORIENTATION, WORKING_RHYTHM, CONTEXT, UNDERSTANDING.
     * Affects initSession greeting selection and first-response scoring on the NEXT call;
     * the current processForDebug turn does not load trust phase (mirrors production behaviour).
     */
    val trustPhase: String? = null,
)

@Serializable
data class DebugConverseResponse(
    /** Unique ID for this turn — present on all log lines emitted during this call. */
    val traceId: String,
    val reply: String,
    /** The sessionId actually used (may be a generated UUID if the request omitted one). */
    val sessionId: String,
    /** Resolved synthetic user email used for this turn. */
    val syntheticUserId: String,
    /**
     * Which path produced the reply.
     * One of: CognitivePipeline, LlmBranch, FirstSessionHandler, FallbackText.
     */
    val resolutionPath: String,
    val fallbackTriggered: Boolean,
    val fallbackReason: String?,
    val totalLatencyMs: Long,
    /**
     * Full pipeline trace — includes candidatePhrases, graphMutations, latency breakdown,
     * routing decision, comprehension tier, and response selection scores.
     */
    val trace: PipelineTrace,
)

@Serializable
data class DebugPurgeResponse(
    val deletedUserCount: Int,
    val message: String,
)

// ── Route configuration ───────────────────────────────────────────────────────

/**
 * Registers the `/debug/converse` and `/debug/converse/purge` endpoints.
 *
 * Guarded by the `debug-token` bearer auth provider (configured in [configureAuth]).
 * Both endpoints are only reachable when `DEBUG_CONVERSE_ENABLED=true` is set in the
 * environment — this function is never called otherwise (see Application.kt).
 *
 * @param debugSessionManager A dedicated [SessionManager] instance isolated from the
 *   production session pool. Reusing a [sessionId] continues context; omitting one
 *   starts fresh.
 * @param db The ArcadeDB instance used to create/purge synthetic User vertices.
 */
fun Application.configureDebugConverseRoutes(
    debugSessionManager: SessionManager,
    db: Database?,
) {
    routing {
        authenticate("debug-token") {
            route("/debug") {

                post("/converse") {
                    val traceId = UUID.randomUUID().toString()
                    MDC.put("traceId", traceId)
                    try {
                        val req = call.receive<DebugConverseRequest>()

                        val sessionId = req.sessionId?.takeIf { it.isNotBlank() }
                            ?: UUID.randomUUID().toString()
                        val syntheticEmail = DebugConverseService.resolveUserId(req.syntheticUserId)

                        logger.info(
                            "debug-converse traceId={} sessionId={} userId={} freshSession={}",
                            traceId, sessionId, syntheticEmail, req.sessionId.isNullOrBlank(),
                        )

                        if (db != null) {
                            DebugConverseService.ensureSyntheticUser(db, syntheticEmail)
                            req.trustPhase?.let { phase ->
                                DebugConverseService.seedScaffoldState(db, syntheticEmail, phase)
                            }
                        }

                        val startMs = System.currentTimeMillis()
                        val pipeline = debugSessionManager.getOrCreate(sessionId)
                        val debugResult = pipeline.processForDebug(req.message, sessionId, syntheticEmail)
                        val totalLatencyMs = System.currentTimeMillis() - startMs

                        val fallbackTriggered = debugResult.chat.synthesisSource == "fallback"
                            || debugResult.chat.synthesisSource == "llm"
                        val fallbackReason = when (debugResult.chat.synthesisSource) {
                            "llm"      -> "llm_branch_no_graph_phrase"
                            "fallback" -> "no_phrase_selected_or_service_unavailable"
                            else       -> null
                        }

                        val resolutionPath = when (debugResult.chat.synthesisSource) {
                            "first-session", "first-session-turn1" -> "FirstSessionHandler"
                            "llm"      -> "LlmBranch"
                            "fallback" -> "FallbackText"
                            else       -> "CognitivePipeline"
                        }

                        logger.info(
                            "debug-converse traceId={} resolutionPath={} fallback={} latencyMs={}",
                            traceId, resolutionPath, fallbackTriggered, totalLatencyMs,
                        )

                        call.respond(
                            HttpStatusCode.OK,
                            DebugConverseResponse(
                                traceId           = traceId,
                                reply             = debugResult.chat.responseText,
                                sessionId         = sessionId,
                                syntheticUserId   = syntheticEmail,
                                resolutionPath    = resolutionPath,
                                fallbackTriggered = fallbackTriggered,
                                fallbackReason    = fallbackReason,
                                totalLatencyMs    = totalLatencyMs,
                                trace             = debugResult.trace,
                            )
                        )
                    } finally {
                        MDC.remove("traceId")
                    }
                }

                delete("/converse/purge") {
                    if (db == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "No database available in this environment"),
                        )
                        return@delete
                    }
                    try {
                        val deletedCount = DebugConverseService.purgeAllSyntheticUsers(db)
                        logger.info("debug: purge complete deletedCount={}", deletedCount)
                        call.respond(
                            HttpStatusCode.OK,
                            DebugPurgeResponse(
                                deletedUserCount = deletedCount,
                                message = "Deleted $deletedCount synthetic user(s) and all associated edges",
                            )
                        )
                    } catch (e: Exception) {
                        logger.error("debug: purge failed: {}", e.message, e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Purge failed")),
                        )
                    }
                }
            }
        }
    }
}
