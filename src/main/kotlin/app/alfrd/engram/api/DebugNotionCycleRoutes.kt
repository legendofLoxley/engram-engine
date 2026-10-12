package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.agentwork.ChannelSnapshot
import app.alfrd.engram.cognitive.pipeline.agentwork.CyclePlanTrace
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleInfo
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class NotionCyclesDebugResponse(
    val enabled: Boolean,
    /** Why the channel is not running. Null while it is. */
    val disabledReason: String? = null,
    val setup: NotionCycleInfo? = null,
    val channel: ChannelSnapshot? = null,
    val requestsMade: Long? = null,
    val retriesMade: Long? = null,
    /** The most recent planning decisions, newest first. Always present, so an empty list reads as "none yet". */
    val traces: List<CyclePlanTrace>,
)

/**
 * `GET /debug/notion-cycles[?limit=N]` — the Director's Notion input channel as it is right now:
 * whether it runs, what its last poll did, and the planning decision for each recent cycle (what
 * the model proposed, what code accepted and rejected, what was written).
 *
 * Read-only, and it reads memory only: it makes no Notion request and holds no token. A trace
 * carries cycle and goal ids, titles, task types, links and reason codes — the same things the
 * Notion rows themselves carry — and never a token or a token path.
 *
 * Same `debug-token` auth and `DEBUG_CONVERSE_ENABLED` gate as every other debug route.
 */
fun Application.configureDebugNotionCycleRoutes(startup: NotionCycleStartup) {
    routing {
        authenticate("debug-token") {
            route("/debug") {
                get("/notion-cycles") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_TRACES) ?: DEFAULT_TRACES
                    val body = when (startup) {
                        is NotionCycleStartup.Disabled -> NotionCyclesDebugResponse(enabled = false, disabledReason = startup.reason, traces = emptyList())
                        is NotionCycleStartup.Running -> {
                            val runtime = startup.runtime
                            NotionCyclesDebugResponse(
                                enabled = true,
                                setup = runtime.info,
                                channel = runtime.channel.snapshot(),
                                requestsMade = runtime.requestsMade,
                                retriesMade = runtime.retriesMade,
                                traces = runtime.traces.recent(limit),
                            )
                        }
                    }
                    call.respond(HttpStatusCode.OK, body)
                }
            }
        }
    }
}

private const val DEFAULT_TRACES = 10
private const val MAX_TRACES = 50
