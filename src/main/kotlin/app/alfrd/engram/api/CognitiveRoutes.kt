package app.alfrd.engram.api

import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.cognitive.pipeline.PhaseEventStreamer
import app.alfrd.engram.cognitive.pipeline.PipelineTrace
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val comprehensionTier: Int,
)

@Serializable
data class DebugChatResponse(
    val response: String,
    val intent: String,
    val latencyMs: Long,
    val comprehensionTier: Int,
    val debug: PipelineTrace,
)

@Serializable
data class InitSessionRequest(
    val sessionId: String,
    val userId: String,
    val context: Map<String, String>? = null,
)

@Serializable
data class InitSessionResponse(
    val greeting: String,
    val phraseId: String,
    val sessionId: String,
    /** Scaffold question to append for ORIENTATION users with < 3 answered categories. Null otherwise. */
    val scaffoldQuestion: String? = null,
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
                        response          = result.responseText,
                        intent            = result.intent.name,
                        latencyMs         = latencyMs,
                        comprehensionTier = result.comprehensionTier,
                    )
                )
            }

            post("/chat/debug") {
                val req = call.receive<ChatRequest>()
                val startMs = System.currentTimeMillis()

                val pipeline = sessionManager.getOrCreate(req.sessionId)
                val debugResult = pipeline.processForDebug(req.utterance, req.sessionId, req.userId)

                val latencyMs = System.currentTimeMillis() - startMs

                call.respond(
                    HttpStatusCode.OK,
                    DebugChatResponse(
                        response          = debugResult.chat.responseText,
                        intent            = debugResult.chat.intent.name,
                        latencyMs         = latencyMs,
                        comprehensionTier = debugResult.chat.comprehensionTier,
                        debug             = debugResult.trace,
                    )
                )
            }

            post("/chat/stream") {
                val req = call.receive<ChatRequest>()
                val pipeline = sessionManager.getOrCreate(req.sessionId)
                val streamer = PhaseEventStreamer(pipeline)

                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.Connection, "keep-alive")
                // Disable proxy buffering so events reach the browser immediately.
                call.response.headers.append("X-Accel-Buffering", "no")

                call.respondBytesWriter(contentType = ContentType.parse("text/event-stream; charset=utf-8")) {
                    streamer.stream(req.utterance, req.sessionId, req.userId).collect { event ->
                        val line = "data: ${Json.encodeToString(event)}\n\n"
                        writeFully(line.encodeToByteArray())
                        flush()
                    }
                }
            }

            post("/init") {
                val req = call.receive<InitSessionRequest>()

                val pipeline = sessionManager.getOrCreate(req.sessionId)
                val result   = pipeline.initSession(req.sessionId, req.userId, req.context)

                call.respond(
                    HttpStatusCode.OK,
                    InitSessionResponse(
                        greeting         = result.greeting,
                        phraseId         = result.phraseId,
                        sessionId        = result.sessionId,
                        scaffoldQuestion = result.scaffoldQuestion,
                    )
                )
            }
        }
    }
}
