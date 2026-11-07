package app.alfrd.engram.api

import app.alfrd.engram.director.OwnerHorizonStartup
import app.alfrd.engram.director.OwnerHorizonUnavailable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * `POST /owner/horizon` — the owner's Horizon tool (see [app.alfrd.engram.director.OwnerHorizonService]).
 *
 * This is the owner's path, not a debug route: it is not behind `DEBUG_CONVERSE_ENABLED`, and it is the only route that may write to the
 * `owner-jacob` identity, which the identity named by `OWNER_IDENTITY` fixes (a caller cannot name one). The guards on the debug routes are untouched.
 *
 * Order of refusal: not loopback → 403; route not serving → 503; missing or wrong bearer token → 401; bad body → 400.
 * Body (all optional): `{"message": string, "operations": [...]}`. An empty body is a pure read. Any other field, `goals` included, is a 400:
 * a field that is quietly ignored reads as accepted.
 */
fun Application.configureOwnerHorizonRoutes(
    startup: OwnerHorizonStartup,
    isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback,
) {
    routing {
        post("/owner/horizon") {
            if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
            val running = when (startup) {
                is OwnerHorizonStartup.Disabled -> return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "owner horizon is not serving: ${startup.reason}"))
                is OwnerHorizonStartup.Running -> startup
            }
            val presented = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
            if (presented.isEmpty() || !MessageDigest.isEqual(presented.toByteArray(), running.token.toByteArray())) {
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing token"))
            }
            val raw = call.receiveText()
            if (raw.length > MAX_BODY_CHARS) return@post call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "body too large"))
            val body = if (raw.isBlank()) JsonObject(emptyMap()) else try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (_: Exception) { null } ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "body must be a JSON object"))
            body.keys.firstOrNull { it != "message" && it != "operations" }?.let {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown field: $it"))
            }
            val message = when (val m = body["message"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (m.isString) m.content else return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "message must be a string"))
                else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "message must be a string"))
            }
            val ops = when (val o = body["operations"]) {
                null, JsonNull -> null
                is JsonArray -> o
                else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "operations must be an array"))
            }
            try {
                call.respond(HttpStatusCode.OK, running.service.handle(message, ops))
            } catch (e: OwnerHorizonUnavailable) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "unavailable")))
            }
        }
    }
}

private const val MAX_BODY_CHARS = 200_000
