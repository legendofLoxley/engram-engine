package app.alfrd.engram.api

import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.cognitive.pipeline.PhaseEventStreamer
import app.alfrd.engram.cognitive.pipeline.PipelineTrace
import app.alfrd.engram.cognitive.pipeline.posture.FluxEvent
import app.alfrd.engram.cognitive.pipeline.posture.FluxSpeechState
import app.alfrd.engram.cognitive.providers.TranscriptionResult
import app.alfrd.engram.model.PostureMoveType
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

@Serializable
data class StreamTranscriptionResult(
    val transcript: String = "",
    val isFinal: Boolean = false,
    val speechFinal: Boolean = false,
    val confidence: Float = 0f,
)

@Serializable
data class StreamFluxEvent(
    val speechState: String,
    val endOfTurnConfidence: Double = 0.0,
)

@Serializable
data class FirstResponseStreamRequest(
    val utterance: String = "",
    val sessionId: String,
    val userId: String,
    val inputEvent: String = "input.speech.stopped",
    val audioPlaying: Boolean = false,
    val transcriptionResults: List<StreamTranscriptionResult> = emptyList(),
    val fluxEvent: StreamFluxEvent? = null,
    val priorMoveType: String? = null,
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

            post("/chat/stream/first-response") {
                val req = call.receive<FirstResponseStreamRequest>()
                val pipeline = sessionManager.getOrCreate(req.sessionId)
                val streamer = PhaseEventStreamer(pipeline)

                val parsedFlux = req.fluxEvent?.let { incoming ->
                    val state = try {
                        FluxSpeechState.valueOf(incoming.speechState)
                    } catch (_: Exception) {
                        FluxSpeechState.Unknown
                    }
                    FluxEvent(state, incoming.endOfTurnConfidence)
                }

                val parsedTranscription = req.transcriptionResults.map {
                    TranscriptionResult(
                        transcript = it.transcript,
                        isFinal = it.isFinal,
                        speechFinal = it.speechFinal,
                        confidence = it.confidence,
                    )
                }

                val priorMoveType = req.priorMoveType?.let {
                    try { PostureMoveType.valueOf(it) } catch (_: Exception) { null }
                }

                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.Connection, "keep-alive")
                call.response.headers.append("X-Accel-Buffering", "no")

                call.respondBytesWriter(contentType = ContentType.parse("text/event-stream; charset=utf-8")) {
                    streamer.streamFirstResponse(
                        utterance = req.utterance,
                        sessionId = req.sessionId,
                        userId = req.userId,
                        inputEvent = req.inputEvent,
                        audioPlaying = req.audioPlaying,
                        transcriptionResults = parsedTranscription,
                        fluxEvent = parsedFlux,
                        priorMoveType = priorMoveType,
                    ).collect { event ->
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
