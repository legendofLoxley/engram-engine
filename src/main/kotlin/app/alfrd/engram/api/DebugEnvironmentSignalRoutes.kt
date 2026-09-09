package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.PerUserCycleLock
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

private val logger = LoggerFactory.getLogger("app.alfrd.engram.api.DebugEnvironmentSignal")

@Serializable
data class DebugEnvironmentSignalRequest(
    val text: String,
    val sourceName: String,
    /** Required — every environment signal is one distinct conversational event with its own identity, never deduplicated by content; see [app.alfrd.engram.cognitive.pipeline.horizon.RequestLedger]. */
    val requestId: String,
    /** A real user's email, for live verification against a synthetic identity on the actual deployed path. Prefer this over [syntheticUserId] when both could apply. */
    val userEmail: String? = null,
    /** Short label mapped to a synthetic user email (`debug+<label>@test.alfrd.internal`), matching `/debug/converse`'s convention. Ignored if [userEmail] is set. */
    val syntheticUserId: String? = null,
    val metadata: String = "{}",
)

@Serializable
data class DebugEnvironmentSignalResponse(
    val userEmail: String,
    val cycleSeq: Long?,
    val phraseUid: String?,
    val propagationOutcome: String,
    val edgesCreated: Int,
)

/**
 * The controlled, user-scoped, authenticated, disableable entry point for a synthetic environment
 * signal — its own distinct provenance (`environment_signal`-typed `Source`, via
 * [app.alfrd.engram.cognitive.pipeline.horizon.HorizonGraphStore.ingestEnvironmentSignal]), never
 * disguised as user speech and never routed through [CognitiveRoutes]/[Actor]. Reuses the exact
 * store/assembler/propagator instances the conversational path uses, run inside
 * [PerUserCycleLock] for the target user — the same lock the conversational cycle acquires, so an
 * environment signal can never interleave with a conversational turn for the same user (see that
 * lock's doc). Same `debug-token` auth and `DEBUG_CONVERSE_ENABLED` gate as `/debug/converse` —
 * reusing the exact infrastructure already deployed and already disableable, rather than
 * inventing a second mechanism.
 */
fun Application.configureDebugEnvironmentSignalRoutes(db: Database) {
    val cycleSequencer = ArcadeCycleSequencer(db)
    val horizonGraphStore = ArcadeHorizonGraphStore(db)
    val horizonAssembler = ArcadeHorizonAssembler(db)
    val propagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler)

    routing {
        authenticate("debug-token") {
            route("/debug") {
                post("/environment-signal") {
                    val req = call.receive<DebugEnvironmentSignalRequest>()
                    val userEmail = req.userEmail?.takeIf { it.isNotBlank() }
                        ?: DebugConverseService.resolveUserId(req.syntheticUserId)
                    DebugConverseService.ensureSyntheticUser(db, userEmail)

                    val result = PerUserCycleLock.withLock(userEmail) {
                        val cycleSeq = cycleSequencer.allocateCycle(userEmail)
                            ?: return@withLock null
                        val phraseUid = horizonGraphStore.ingestEnvironmentSignal(
                            userEmail = userEmail,
                            cycleSeq = cycleSeq,
                            sourceName = req.sourceName,
                            text = req.text,
                            metadata = req.metadata,
                        )
                        val propagationOutcome = if (phraseUid != null) {
                            propagator.propagate(userEmail, cycleSeq, listOf(phraseUid to req.text))
                        } else {
                            PropagationOutcome.Failed("environment signal ingest failed")
                        }
                        Triple(cycleSeq, phraseUid, propagationOutcome)
                    }

                    if (result == null) {
                        logger.warn("environment-signal: cycle allocation failed for userEmail={}", userEmail)
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "cycle allocation failed"))
                        return@post
                    }
                    val (cycleSeq, phraseUid, propagationOutcome) = result
                    logger.info(
                        "environment-signal userEmail={} cycleSeq={} phraseUid={} sourceName={} propagationOutcome={}",
                        userEmail, cycleSeq, phraseUid, req.sourceName, propagationOutcome,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        DebugEnvironmentSignalResponse(
                            userEmail = userEmail,
                            cycleSeq = cycleSeq,
                            phraseUid = phraseUid,
                            propagationOutcome = when (propagationOutcome) {
                                is PropagationOutcome.Propagated -> "Propagated"
                                is PropagationOutcome.Failed -> "Failed:${propagationOutcome.reason}"
                            },
                            edgesCreated = (propagationOutcome as? PropagationOutcome.Propagated)?.edgesCreated?.size ?: 0,
                        ),
                    )
                }
            }
        }
    }
}
