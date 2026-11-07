package app.alfrd.engram.api

import app.alfrd.engram.director.DirectorTickSnapshot
import app.alfrd.engram.director.DirectorTickStartup
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * `GET /debug/director-tick` — the Director's standing-context tick as it is right now: whether it runs, the latest change marker, what changed
 * at the last tick, and the full rendered standing prompt (which holds the identity's own memory text, hence loopback only).
 *
 * Read-only, and it reads the tick's memory: it queries nothing. Same `debug-token` auth and `DEBUG_CONVERSE_ENABLED` gate as every other debug route.
 */
fun Application.configureDebugDirectorTickRoutes(
    startup: DirectorTickStartup,
    isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback,
) {
    routing {
        authenticate("debug-token") {
            route("/debug") {
                get("/director-tick") {
                    if (!isLoopback(call)) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val body = when (startup) {
                        is DirectorTickStartup.Disabled -> DirectorTickSnapshot.disabled(startup.reason)
                        is DirectorTickStartup.Running -> startup.tick.snapshot()
                    }
                    call.respond(HttpStatusCode.OK, body)
                }
            }
        }
    }
}
