package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore
import app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentOutcome
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.alfrd.engram.api.DebugHermesAssignment")

@Serializable
data class HermesAssignmentCompletionResponse(
    val assignmentId: String,
    /** `"Completed"` | `"Failed"` — mirrors [HermesAssignmentOutcome]'s two variants. */
    val outcome: String,
    val text: String,
    val toolName: String? = null,
    val toolSucceeded: Boolean? = null,
    /** The independent graph-ingestion outcome (e.g. `"Committed"`) — surfaced so a caller can
     *  tell "delivered to the conversation" and "committed to the graph" apart, per the design
     *  contract's own point: a committed result is not proof the user received it, and the
     *  reverse holds too — this route answering 200 is not proof the graph write succeeded. */
    val graphIngestOutcome: String,
)

/**
 * The explicit, polled delivery channel for a Hermes assignment's completion —
 * [HermesAssignmentCompletionStore]'s doc explains why this is deliberately independent of
 * [app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService]/`RecentActorEvidence`.
 * The WebUI runner adapter polls this by `assignmentId` (learned from the same
 * `/debug/converse` response that issued the assignment, via `PipelineTrace.hermesDelegation`)
 * to know when — and how — to deliver the result into the *originating* conversation, without
 * requiring the user to send another message.
 *
 * Same `debug-token` auth and `DEBUG_CONVERSE_ENABLED` gate as every other debug route.
 * Synthetic-only, same enforcement convention as `/debug/actor-event`: this is a read path for
 * the dev/debug harness, never a live-user entry point. 404 covers both "unknown assignmentId"
 * and "known assignmentId, wrong userEmail" identically — a caller cannot distinguish "never
 * existed" from "not yours" by probing.
 */
fun Application.configureDebugHermesAssignmentRoutes(store: HermesAssignmentCompletionStore) {
    routing {
        authenticate("debug-token") {
            route("/debug") {
                get("/hermes-assignment/{assignmentId}") {
                    val assignmentId = call.parameters["assignmentId"]?.takeIf { it.isNotBlank() }
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "assignmentId is required"))

                    val userEmail = call.request.queryParameters["userEmail"]?.takeIf { it.isNotBlank() }
                        ?: DebugConverseService.resolveUserId(call.request.queryParameters["syntheticUserId"])

                    if (!userEmail.endsWith(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN, ignoreCase = true)) {
                        logger.warn("hermes-assignment: rejected non-synthetic userEmail={} for assignmentId={}", userEmail, assignmentId)
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "userEmail must be a synthetic identity ending in '${DebugConverseService.SYNTHETIC_EMAIL_DOMAIN}'"),
                        )
                    }

                    val completion = store.get(assignmentId, userEmail)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "no completion recorded yet for this assignment"))

                    val response = when (val outcome = completion.outcome) {
                        is HermesAssignmentOutcome.Completed -> HermesAssignmentCompletionResponse(
                            assignmentId = assignmentId,
                            outcome = "Completed",
                            text = outcome.findingsText,
                            toolName = outcome.toolName,
                            toolSucceeded = outcome.toolSucceeded,
                            graphIngestOutcome = completion.graphIngestOutcome,
                        )
                        is HermesAssignmentOutcome.Failed -> HermesAssignmentCompletionResponse(
                            assignmentId = assignmentId,
                            outcome = "Failed",
                            text = outcome.reason,
                            graphIngestOutcome = completion.graphIngestOutcome,
                        )
                    }
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }
    }
}
