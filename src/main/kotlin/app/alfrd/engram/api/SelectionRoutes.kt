package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionQuery
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionResult
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

fun Application.configureSelectionRoutes(selectionService: ResponseSelectionService) {
    routing {
        route("/response") {
            post("/select") {
                val req = call.receive<ResponseSelectionQuery>()

                // Build a minimal CognitiveContext from the query's serializable fields
                val ctx = req.context ?: CognitiveContext(
                    utterance = "",
                    sessionId = req.sessionId,
                    userId = req.userId,
                    timestamp = Instant.now(),
                )

                val queryWithCtx = req.copy(context = ctx)
                val startMs = System.currentTimeMillis()

                val results = selectionService.select(queryWithCtx)

                val latencyMs = System.currentTimeMillis() - startMs
                call.response.header("X-Selection-Latency-Ms", latencyMs.toString())

                if (results.isEmpty()) {
                    call.respond(HttpStatusCode.OK, emptyList<ResponseSelectionResult>())
                } else {
                    call.respond(HttpStatusCode.OK, results)
                }
            }
        }
    }
}
