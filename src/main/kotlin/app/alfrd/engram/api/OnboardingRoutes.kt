package app.alfrd.engram.api

import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// TODO: gate this endpoint with auth before exposing beyond closed beta.
fun Application.configureOnboardingRoutes(db: Database) {
    val service = OnboardingService(db)

    routing {
        route("/onboard") {
            /**
             * POST /onboard/seed
             *
             * Accepts a JSON array of [InviteeManifest] objects and wires the full
             * graph structure for each invitee in a single transaction per invitee:
             * User vertex, INVITED edge from Jacob, personal Source + TRUSTS + Phrases,
             * global Source (idempotent) + TRUSTS + Phrases (deduplicated by hash).
             *
             * Returns one [InviteeResult] per manifest in the same order as the input.
             * Individual failures do not abort the rest of the batch.
             */
            post("/seed") {
                val manifests = try {
                    call.receive<List<InviteeManifest>>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request body: ${e.message}"),
                    )
                }

                if (manifests.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Request body must be a non-empty array of invitee manifests"),
                    )
                }

                val results = service.seedBatch(manifests)
                val status = if (results.all { it.success }) HttpStatusCode.OK
                             else HttpStatusCode.MultiStatus
                call.respond(status, results)
            }
        }
    }
}
