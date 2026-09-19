package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActivityFeed
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActivityFeedResult
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.alfrd.engram.api.DebugHermesActivity")

@Serializable
data class HermesActivityItemResponse(
    val eventId: String,
    val assignmentId: String,
    val cycleSeq: Long?,
    val targetFilename: String,
    val state: String,
    val summary: String,
    val occurredAt: Long,
)

/**
 * `items` is always the full, bounded-snapshot response body on success — never a delta. `stale`
 * distinguishes the two meanings of an empty [items] one level up (mirrors
 * [HermesActivityFeedResult]): a caller must not render "no eligible items" when the read itself
 * never completed. This route reports that as a non-2xx status (see below) rather than a 200 body
 * with a flag, but the field is kept here too so a body read out of band (or a future direct
 * consumer of this DTO) can't misinterpret an unexpected empty-with-200 as "confirmed empty" either.
 */
@Serializable
data class HermesActivityListResponse(
    val items: List<HermesActivityItemResponse>,
)

/**
 * The independently-arriving activity surface's one backend route — read-only, durable-graph-backed
 * (see [HermesActivityFeed]'s own doc for why it deliberately never consults
 * [app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore]). Same `debug-token`
 * auth, `DEBUG_CONVERSE_ENABLED` gate, and synthetic-identity-only enforcement as every sibling
 * debug route (see [DebugHermesAssignmentRoutes]) — this is the dev/debug harness's read path, never
 * a live-user entry point.
 *
 * A bounded snapshot, not a cursor: every call returns "what's eligible right now" (capped at
 * [HermesActivityFeed.MAX_ITEMS]); the caller (the WebUI runner adapter, then the browser) always
 * reconciles by [HermesActivityItemResponse.eventId], never by trusting a saved position.
 *
 * [HermesActivityFeedResult.Failed] (the underlying graph read itself failed — never "confirmed no
 * items") is surfaced as `503`, exactly the same convention [DebugActorEventRoutes]'s
 * `GET /debug/actor-event/{eventId}` already uses for [app.alfrd.engram.cognitive.pipeline.horizon.HorizonGraphStore.ActorEventLookupResult.LookupFailed]
 * — so a caller already handling that convention needs no new one here.
 */
fun Application.configureDebugHermesActivityRoutes(db: Database, activeAssignments: HermesActiveAssignmentRegistry) {
    val horizonGraphStore = ArcadeHorizonGraphStore(db)

    routing {
        authenticate("debug-token") {
            route("/debug") {
                get("/hermes-activity") {
                    val userEmail = call.request.queryParameters["userEmail"]?.takeIf { it.isNotBlank() }
                        ?: DebugConverseService.resolveUserId(call.request.queryParameters["syntheticUserId"])

                    if (!userEmail.endsWith(DebugConverseService.SYNTHETIC_EMAIL_DOMAIN, ignoreCase = true)) {
                        logger.warn("hermes-activity: rejected non-synthetic userEmail={}", userEmail)
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "userEmail must be a synthetic identity ending in '${DebugConverseService.SYNTHETIC_EMAIL_DOMAIN}'"),
                        )
                    }

                    when (val result = HermesActivityFeed.list(userEmail, horizonGraphStore, activeAssignments)) {
                        is HermesActivityFeedResult.Ok -> {
                            logger.info("hermes-activity userEmail={} items={}", userEmail, result.items.size)
                            call.respond(
                                HttpStatusCode.OK,
                                HermesActivityListResponse(
                                    items = result.items.map {
                                        HermesActivityItemResponse(
                                            eventId = it.eventId,
                                            assignmentId = it.assignmentId,
                                            cycleSeq = it.cycleSeq,
                                            targetFilename = it.targetFilename,
                                            state = it.state,
                                            summary = it.summary,
                                            occurredAt = it.occurredAt,
                                        )
                                    },
                                ),
                            )
                        }
                        is HermesActivityFeedResult.Failed -> {
                            logger.warn("hermes-activity userEmail={} failed: {}", userEmail, result.reason)
                            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to result.reason))
                        }
                    }
                }
            }
        }
    }
}
