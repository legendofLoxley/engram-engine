package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore
import app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentOutcome
import app.alfrd.engram.cognitive.pipeline.hermes.HermesCancellationRequestOutcome
import app.alfrd.engram.cognitive.pipeline.hermes.HermesCompletionDecision
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
    /** `"Completed"` | `"Failed"` — mirrors [HermesAssignmentOutcome]'s two variants: what Hermes
     *  actually did. Never confused with [decision] below — see [HermesAssignmentCompletion]'s doc. */
    val executionOutcome: String,
    /** `"Accepted"` | `"Withheld"` — the Director's own delivery decision (see
     *  [app.alfrd.engram.cognitive.pipeline.hermes.HermesCompletionDirector]). [text] is always
     *  exactly [HermesCompletionDecision.deliveryText] — the caller (the WebUI runner adapter)
     *  renders it verbatim and must never compose its own wrapper around it. */
    val decision: String,
    val text: String,
    /** Execution diagnostics only — present when [executionOutcome] is `"Completed"`, absorbed
     *  into the accept/withhold [decision] already, never re-inspected by a caller to second-guess it. */
    val toolName: String? = null,
    val toolSucceeded: Boolean? = null,
    /** The independent graph-ingestion outcome (e.g. `"Committed"`) — surfaced so a caller can
     *  tell "delivered to the conversation" and "committed to the graph" apart, per the design
     *  contract's own point: a committed result is not proof the user received it, and the
     *  reverse holds too — this route answering 200 is not proof the graph write succeeded (and,
     *  per the correction that added `GET /debug/actor-event/{eventId}`, is not itself an
     *  independent graph read either — it is the same in-process ingest() call's own report).
     */
    val graphIngestOutcome: String,
)

/**
 * Response for `POST /hermes-assignment/{assignmentId}/cancel` — a *request*, never a guarantee.
 * [requested] is true only if a genuinely still-active assignment was found for this id and
 * userEmail; false covers "unknown id", "not yours", and "already finished" identically, the same
 * "never distinguishable by probing" convention as everywhere else in this route. Confirmed
 * termination — what actually happened — is only ever knowable afterward, via
 * `GET /hermes-assignment/{assignmentId}` reporting `executionOutcome="Cancelled"`.
 */
@Serializable
data class HermesCancellationResponse(
    val assignmentId: String,
    val requested: Boolean,
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
fun Application.configureDebugHermesAssignmentRoutes(
    store: HermesAssignmentCompletionStore,
    activeAssignments: HermesActiveAssignmentRegistry,
) {
    routing {
        authenticate("debug-token") {
            route("/debug") {
                post("/hermes-assignment/{assignmentId}/cancel") {
                    val assignmentId = call.parameters["assignmentId"]?.takeIf { it.isNotBlank() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "assignmentId is required"))

                    val userEmail = call.request.queryParameters["userEmail"]?.takeIf { it.isNotBlank() }
                        ?: DebugConverseService.resolveUserId(call.request.queryParameters["syntheticUserId"])

                    if (!userEmail.endsWith(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN, ignoreCase = true)) {
                        logger.warn("hermes-assignment cancel: rejected non-synthetic userEmail={} for assignmentId={}", userEmail, assignmentId)
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "userEmail must be a synthetic identity ending in '${DebugConverseService.SYNTHETIC_EMAIL_DOMAIN}'"),
                        )
                    }

                    val outcome = activeAssignments.requestCancellation(assignmentId, userEmail)
                    logger.info("hermes-assignment cancel requested assignmentId={} userEmail={} outcome={}", assignmentId, userEmail, outcome::class.simpleName)
                    call.respond(
                        HttpStatusCode.OK,
                        HermesCancellationResponse(
                            assignmentId = assignmentId,
                            requested = outcome is HermesCancellationRequestOutcome.Requested,
                        ),
                    )
                }

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

                    val outcome = completion.outcome
                    val response = HermesAssignmentCompletionResponse(
                        assignmentId = assignmentId,
                        executionOutcome = when (outcome) {
                            is HermesAssignmentOutcome.Completed -> "Completed"
                            is HermesAssignmentOutcome.Failed -> "Failed"
                            is HermesAssignmentOutcome.Cancelled -> "Cancelled"
                        },
                        decision = when (completion.decision) {
                            is HermesCompletionDecision.Accepted -> "Accepted"
                            is HermesCompletionDecision.Withheld -> "Withheld"
                        },
                        // Always the Director's own composed text — never re-derived from outcome
                        // here. This is the entire point of the correction: the runner adapter
                        // downstream renders exactly this, with no wrapper of its own.
                        text = completion.decision.deliveryText,
                        toolName = (outcome as? HermesAssignmentOutcome.Completed)?.toolName,
                        toolSucceeded = (outcome as? HermesAssignmentOutcome.Completed)?.toolSucceeded,
                        graphIngestOutcome = completion.graphIngestOutcome,
                    )
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }
    }
}
