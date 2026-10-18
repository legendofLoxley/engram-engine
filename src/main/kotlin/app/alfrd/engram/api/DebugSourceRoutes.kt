package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.CommitFailedException
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.pipeline.horizon.SourceItemDraft
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceStore
import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class DebugSourceBody(
    val type: String,
    val locator: String,
    val title: String = "",
    val text: String,
    val readMode: String = "full",
    val threadId: String? = null,
)

@Serializable
data class DebugItemBody(val kind: String, val text: String, val quote: String, val chunk: Int, val claimant: String? = null)

@Serializable
data class DebugSourceCommitRequest(val syntheticUserId: String, val source: DebugSourceBody, val items: List<DebugItemBody> = emptyList(), val writer: String = "director")

@Serializable
data class DebugChunk(val ordinal: Int, val location: String, val chars: Int, val text: String)

@Serializable
data class DebugSourceView(
    val uid: String, val type: String, val locator: String, val title: String, val readMode: String, val threadId: String?,
    val contentHash: String, val chunks: List<DebugChunk>,
)

/**
 * The synthetic harness for the Source and chunk model, standing in for the Hermes fetch that will feed it: commit a read
 * source and the items taken from it, read sources back with their chunks, and trace an item to its chunk and location.
 * Same `debug-token` auth and `DEBUG_CONVERSE_ENABLED` gate as the other debug routes, loopback callers only, synthetic
 * identities only (`debug+<label>@test.alfrd.internal`), and never `owner-jacob`. Memory text is returned to the caller,
 * never logged.
 */
fun Application.configureDebugSourceRoutes(
    db: Database,
    isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback,
) {
    val store = SourceStore(db)
    val structure = ArcadeHorizonStructureStore(db)
    val commit = SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db))

    suspend fun ApplicationCall.userOrRefuse(label: String?): String? {
        if (!isLoopback(this)) { respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only")); return null }
        val email = DebugConverseService.resolveUserId(label?.takeIf { it.isNotBlank() } ?: return run { respond(HttpStatusCode.BadRequest, mapOf("error" to "syntheticUserId is required")); null })
        if (email.contains("owner-jacob")) { respond(HttpStatusCode.Forbidden, mapOf("error" to "that identity is real data and is never used by the debug routes")); return null }
        return email
    }

    routing {
        authenticate("debug-token") {
            route("/debug") {
                post("/sources/commit") {
                    // The loopback check comes before the body is read: a caller that is not local gets 403 whatever it sent.
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val req = call.receive<DebugSourceCommitRequest>()
                    val email = call.userOrRefuse(req.syntheticUserId) ?: return@post
                    DebugConverseService.ensureSyntheticUser(db, email)
                    val kinds = req.items.map { ItemKind.entries.firstOrNull { k -> k.name.equals(it.kind, ignoreCase = true) } }
                    if (kinds.any { it == null }) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "item kind must be fact, task, event or question"))
                    val report = try {
                        commit.commit(
                            email,
                            SourceInput(req.source.type, req.source.locator, req.source.title, req.source.text, req.source.readMode, req.source.threadId),
                            req.items.mapIndexed { i, it -> SourceItemDraft(kinds[i]!!, it.text, it.quote, it.chunk, it.claimant) },
                            req.writer,
                        )
                    } catch (e: IllegalArgumentException) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "bad request")))
                    } catch (e: CommitFailedException) {
                        return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "commit failed")))
                    }
                    call.respond(HttpStatusCode.OK, report)
                }
                get("/sources") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    call.respond(HttpStatusCode.OK, store.sources(email).map { s ->
                        DebugSourceView(s.uid, s.type, s.locator, s.title, s.readMode, s.threadId, s.contentHash,
                            store.chunks(email, s.uid).map { DebugChunk(it.ordinal, it.location, it.text.length, it.text) })
                    })
                }
                get("/items/{uid}/provenance") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    call.respond(HttpStatusCode.OK, store.provenanceOf(email, call.parameters["uid"].orEmpty()).map {
                        mapOf("chunkUid" to it.chunkUid, "location" to it.location, "sourceType" to it.sourceType, "sourceLocator" to it.sourceLocator,
                            "sourceTitle" to it.sourceTitle, "readMode" to it.readMode, "writer" to it.writer.orEmpty(), "claimant" to it.claimant.orEmpty())
                    })
                }
            }
        }
    }
}
