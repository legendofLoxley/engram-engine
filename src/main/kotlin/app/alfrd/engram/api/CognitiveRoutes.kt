package app.alfrd.engram.api

import app.alfrd.engram.cognitive.SessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val utterance: String,
    val sessionId: String,
    val userId: String,
)

@Serializable
data class ChatResponse(
    val response: String,
    val intent: String,
    val latencyMs: Long,
)

fun Application.configureCognitiveRoutes(sessionManager: SessionManager) {
    routing {
        route("/cognitive") {
            post("/chat") {
                val req = call.receive<ChatRequest>()
                val startMs = System.currentTimeMillis()

                val pipeline = sessionManager.getOrCreate(req.sessionId)
                val result   = pipeline.processForChat(req.utterance, req.sessionId, req.userId)

                val latencyMs = System.currentTimeMillis() - startMs

                call.respond(
                    HttpStatusCode.OK,
                    ChatResponse(
                        response  = result.responseText,
                        intent    = result.intent.name,
                        latencyMs = latencyMs,
                    )
                )
            }
        }
    }
}
