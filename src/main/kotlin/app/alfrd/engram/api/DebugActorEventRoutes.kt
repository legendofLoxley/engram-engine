package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService
import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventKind
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.PropagationOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.SalientTokenPropagator
import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.alfrd.engram.api.DebugActorEvent")

@Serializable
data class DebugActorEventRequest(
    /** `"observation"` | `"interpretation"` | `"tool_result"` — closed set, mapped server-side; an unrecognized value is a 400, never a passthrough into graph-visible provenance. */
    val kind: String,
    val text: String,
    /** Required — the idempotency key. Every genuinely new event needs its own; resending the same value is how a caller signals "this may be a retry." */
    val eventId: String,
    val sourceName: String,
    val assignmentId: String? = null,
    val occurredAt: Long? = null,
    /** Required when [kind] is `"interpretation"`. */
    val basis: String? = null,
    /** Required when [kind] is `"tool_result"`. */
    val toolName: String? = null,
    /** Required when [kind] is `"tool_result"` — a self-reported claim, not independently verified by this endpoint or the service it calls. */
    val toolSucceeded: Boolean? = null,
    /**
     * A synthetic identity to ingest as, overriding [syntheticUserId] when both are given — but
     * unlike `/debug/environment-signal`'s convention, NOT a real user's email: this route enforces
     * (400, not merely documents) that every [userEmail] ends in [DebugConverseService.SYNTHETIC_EMAIL_DOMAIN].
     * This route is the synthetic injection harness (see [configureDebugActorEventRoutes]'s class
     * doc) and never a live-user entry point, so there is no "verify against a real identity" mode
     * here the way `/debug/environment-signal` has.
     */
    val userEmail: String? = null,
    /** Short label mapped to a synthetic user email (`debug+<label>@test.alfrd.internal`) via [DebugConverseService.resolveUserId]. Ignored if [userEmail] is set. */
    val syntheticUserId: String? = null,
)

@Serializable
data class DebugActorEventResponse(
    val userEmail: String,
    val eventId: String,
    val outcome: String,
    val phraseUid: String? = null,
    val cycleSeq: Long? = null,
    val propagationOutcome: String? = null,
    val edgesCreated: Int = 0,
    val reason: String? = null,
)

/**
 * The controlled, authenticated, disableable synthetic entry point for an Actor-attributed event —
 * a thin route over [ActorEventIngestionService], the reusable core. This is explicitly the
 * **synthetic injection harness**, never disguised as user speech and never routed through
 * [CognitiveRoutes]/[app.alfrd.engram.cognitive.pipeline.Actor] — kept clearly distinguished from
 * what a production Hermes adapter would be: that adapter would call
 * [ActorEventIngestionService.ingest] directly from wherever Hermes results actually arrive, not
 * through this debug-token-gated HTTP surface. Same `debug-token` auth and `DEBUG_CONVERSE_ENABLED`
 * gate as `/debug/environment-signal` and `/debug/converse` — reusing the exact infrastructure
 * already deployed and already disableable, rather than inventing a second mechanism.
 *
 * **Its synthetic-only scope is enforced here, not merely asserted by this doc comment.** Every
 * resolved `userEmail` (from [DebugActorEventRequest.userEmail] or, via
 * [DebugConverseService.resolveUserId], from [DebugActorEventRequest.syntheticUserId]) must end in
 * [DebugConverseService.SYNTHETIC_EMAIL_DOMAIN] — a request naming any other identity is rejected
 * with 400 before [ActorEventIngestionService.ingest] is ever called. Deliberately stricter than
 * `/debug/environment-signal`, which intentionally also accepts a real user's email for live
 * verification against a synthetic identity on the deployed path — that route is untouched here;
 * this one's own doc has always called it "the synthetic injection harness," so it is held to that.
 */
fun Application.configureDebugActorEventRoutes(db: Database) {
    val cycleSequencer = ArcadeCycleSequencer(db)
    val horizonGraphStore = ArcadeHorizonGraphStore(db)
    val horizonAssembler = ArcadeHorizonAssembler(db)
    val propagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler)
    val service = ActorEventIngestionService(cycleSequencer, horizonGraphStore, propagator)

    routing {
        authenticate("debug-token") {
            route("/debug") {
                post("/actor-event") {
                    val req = call.receive<DebugActorEventRequest>()
                    val kind = req.toActorEventKind() ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "unrecognized or incomplete kind='${req.kind}' — expected observation|interpretation|tool_result with their required fields"),
                    )

                    val userEmail = req.userEmail?.takeIf { it.isNotBlank() }
                        ?: DebugConverseService.resolveUserId(req.syntheticUserId)

                    // Enforced, not merely documented: this route is the synthetic injection
                    // harness (see class doc), never a live-user entry point. req.userEmail is
                    // caller-supplied free text — unlike syntheticUserId (always funneled through
                    // resolveUserId, which can only ever produce a synthetic address), it could
                    // otherwise name any real user's identity.
                    if (!userEmail.endsWith(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN, ignoreCase = true)) {
                        logger.warn("actor-event: rejected non-synthetic userEmail={} — this route is the synthetic injection harness only, never a live-user entry point", userEmail)
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "userEmail must be a synthetic identity ending in '${DebugConverseService.SYNTHETIC_EMAIL_DOMAIN}' — this route is the synthetic injection harness, never a live-user entry point"),
                        )
                    }
                    DebugConverseService.ensureSyntheticUser(db, userEmail)

                    val outcome = service.ingest(
                        userEmail = userEmail,
                        eventId = req.eventId,
                        kind = kind,
                        sourceName = req.sourceName,
                        assignmentId = req.assignmentId,
                        occurredAt = req.occurredAt,
                    )

                    logger.info("actor-event userEmail={} eventId={} outcome={}", userEmail, req.eventId, outcome::class.simpleName)
                    call.respond(outcome.toHttpStatus(), outcome.toResponse(userEmail))
                }
            }
        }
    }
}

/** Maps the request's own closed `kind` string (and its kind-specific fields) into [ActorEventKind] — the classification the graph will actually see. Null on any unrecognized or incomplete combination, never a best-effort guess. */
private fun DebugActorEventRequest.toActorEventKind(): ActorEventKind? = when (kind) {
    "observation" -> ActorEventKind.Observation(text)
    "interpretation" -> basis?.takeIf { it.isNotBlank() }?.let { ActorEventKind.Interpretation(text, it) }
    "tool_result" -> {
        val name = toolName?.takeIf { it.isNotBlank() }
        if (name != null && toolSucceeded != null) ActorEventKind.ToolResult(text, name, toolSucceeded) else null
    }
    else -> null
}

private fun ActorEventIngestOutcome.toHttpStatus(): HttpStatusCode = when (this) {
    is ActorEventIngestOutcome.Committed, is ActorEventIngestOutcome.DuplicateDelivery -> HttpStatusCode.OK
    is ActorEventIngestOutcome.ConflictingReuse, is ActorEventIngestOutcome.Rejected -> HttpStatusCode.BadRequest
    is ActorEventIngestOutcome.AllocationFailed, is ActorEventIngestOutcome.WriteFailed,
    is ActorEventIngestOutcome.LookupFailed, is ActorEventIngestOutcome.PropagationUncertain,
    -> HttpStatusCode.ServiceUnavailable
}

private fun ActorEventIngestOutcome.toResponse(userEmail: String): DebugActorEventResponse = when (this) {
    is ActorEventIngestOutcome.Committed -> DebugActorEventResponse(
        userEmail = userEmail, eventId = eventId, outcome = "Committed",
        phraseUid = phraseUid, cycleSeq = cycleSeq,
        propagationOutcome = describePropagation(propagationOutcome),
        edgesCreated = (propagationOutcome as? PropagationOutcome.Propagated)?.edgesCreated?.size ?: 0,
    )
    is ActorEventIngestOutcome.DuplicateDelivery -> DebugActorEventResponse(
        userEmail = userEmail, eventId = eventId, outcome = "DuplicateDelivery",
        phraseUid = existingPhraseUid, cycleSeq = existingCycleSeq,
        propagationOutcome = propagationOutcome?.let { describePropagation(it) },
        edgesCreated = (propagationOutcome as? PropagationOutcome.Propagated)?.edgesCreated?.size ?: 0,
    )
    is ActorEventIngestOutcome.ConflictingReuse -> DebugActorEventResponse(
        userEmail = userEmail, eventId = eventId, outcome = "ConflictingReuse", phraseUid = existingPhraseUid, reason = reason,
    )
    is ActorEventIngestOutcome.AllocationFailed -> DebugActorEventResponse(userEmail = userEmail, eventId = eventId, outcome = "AllocationFailed")
    is ActorEventIngestOutcome.WriteFailed -> DebugActorEventResponse(userEmail = userEmail, eventId = eventId, outcome = "WriteFailed", reason = reason)
    is ActorEventIngestOutcome.Rejected -> DebugActorEventResponse(userEmail = userEmail, eventId = eventId, outcome = "Rejected", reason = reason)
    is ActorEventIngestOutcome.LookupFailed -> DebugActorEventResponse(userEmail = userEmail, eventId = eventId, outcome = "LookupFailed", reason = reason)
    is ActorEventIngestOutcome.PropagationUncertain -> DebugActorEventResponse(
        userEmail = userEmail, eventId = eventId, outcome = "PropagationUncertain",
        phraseUid = existingPhraseUid, cycleSeq = existingCycleSeq, reason = reason,
    )
}

private fun describePropagation(outcome: PropagationOutcome): String = when (outcome) {
    is PropagationOutcome.Propagated -> if (outcome.incompleteTargets.isEmpty()) "Propagated" else "Propagated(incomplete=${outcome.incompleteTargets.size})"
    is PropagationOutcome.Failed -> "Failed:${outcome.reason}"
}
