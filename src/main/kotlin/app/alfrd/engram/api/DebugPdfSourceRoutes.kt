package app.alfrd.engram.api

import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PdfPreparer
import app.alfrd.engram.pdf.PdfSourceService
import app.alfrd.engram.pdf.TesseractOcr
import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Base64

/** Either [contentBase64] (the file's bytes) or [path] (a PDF on this machine) names the file; [locator] defaults to `file:<name>`. */
@Serializable
data class DebugPdfCommitRequest(
    val syntheticUserId: String,
    val contentBase64: String? = null,
    val path: String? = null,
    val locator: String? = null,
    val title: String? = null,
)

@Serializable
data class DebugPdfSourceInfo(val uid: String, val type: String, val locator: String, val title: String, val readMode: String, val fileSha256: String?, val pageCount: Int?, val ocrPages: Int?, val unreadPages: Int?)

@Serializable
data class DebugPdfChunkInfo(
    val ordinal: Int, val chunkUid: String, val kind: String?, val page: Int?, val printedPage: String?, val section: String?, val location: String,
    val extractor: String?, val ocrConfidence: Double?, val chars: Int,
)

@Serializable
data class DebugPdfProvenance(val source: DebugPdfSourceInfo, val chunks: List<DebugPdfChunkInfo>)

/**
 * Commits a PDF as a Source with page-located chunks, and shows where every chunk of a source came from. Same `debug-token` auth,
 * `DEBUG_CONVERSE_ENABLED` gate and loopback-only rule as the other `/debug/sources` routes, synthetic identities only, never owner-jacob.
 * The loopback check comes before the body is read. A file can be named by path, but only a file that starts `%PDF-` and ends `.pdf`
 * (at most [MAX_BYTES]); nothing else on disk is ever read, and the file's text is returned only as the chunks of a PDF.
 * Extraction runs here, in the Director process on the box, never in the Hermes container.
 */
fun Application.configureDebugPdfSourceRoutes(
    db: Database,
    preparer: PdfPreparer = PdfExtractor(TesseractOcr.detect()),
    cache: PdfPreparationCache = PdfPreparationCache.fromEnvironment(),
    isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback,
) {
    val store = SourceStore(db)
    val service = PdfSourceService(store, preparer, cache)

    suspend fun ApplicationCall.badRequest(msg: String) = respond(HttpStatusCode.BadRequest, mapOf("error" to msg))

    routing {
        authenticate("debug-token") {
            route("/debug") {
                post("/sources/pdf") {
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val req = call.receive<DebugPdfCommitRequest>()
                    val email = DebugConverseService.resolveUserId(req.syntheticUserId.takeIf { it.isNotBlank() } ?: return@post call.badRequest("syntheticUserId is required"))
                    if (email.contains("owner-jacob")) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "that identity is real data and is never used by the debug routes"))
                    val (bytes, defaultLocator) = when {
                        req.contentBase64 != null -> (try { Base64.getDecoder().decode(req.contentBase64) } catch (e: IllegalArgumentException) { return@post call.badRequest("contentBase64 is not base64") }) to "file:upload.pdf"
                        req.path != null -> {
                            val f = File(req.path)
                            if (!f.isFile || !f.name.endsWith(".pdf", ignoreCase = true)) return@post call.badRequest("path must name an existing .pdf file")
                            if (f.length() > MAX_BYTES) return@post call.badRequest("file is larger than ${MAX_BYTES / 1_000_000} MB")
                            f.readBytes() to "file:${f.name}"
                        }
                        else -> return@post call.badRequest("give contentBase64 or path")
                    }
                    if (bytes.size > MAX_BYTES) return@post call.badRequest("file is larger than ${MAX_BYTES / 1_000_000} MB")
                    DebugConverseService.ensureSyntheticUser(db, email)
                    val result = try {
                        service.commit(email, bytes, req.locator?.takeIf { it.isNotBlank() } ?: defaultLocator, req.title)
                    } catch (e: IllegalArgumentException) {
                        return@post call.badRequest(e.message ?: "bad request")
                    }
                    call.respond(HttpStatusCode.OK, result)
                }
                get("/sources/{uid}/provenance") {
                    if (!isLoopback(call)) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val label = call.request.queryParameters["syntheticUserId"]?.takeIf { it.isNotBlank() } ?: return@get call.badRequest("syntheticUserId is required")
                    val email = DebugConverseService.resolveUserId(label)
                    if (email.contains("owner-jacob")) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "that identity is real data and is never used by the debug routes"))
                    val s = store.sourceByUid(email, call.parameters["uid"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such source"))
                    call.respond(
                        HttpStatusCode.OK,
                        DebugPdfProvenance(
                            DebugPdfSourceInfo(s.uid, s.type, s.locator, s.title, s.readMode, s.fileSha256, s.pageCount, s.ocrPages, s.unreadPages),
                            store.chunks(email, s.uid).map {
                                DebugPdfChunkInfo(it.ordinal, it.uid, it.kind, it.page, it.printedPage, it.section, it.location, it.extractor, it.ocrConfidence, it.text.length)
                            },
                        ),
                    )
                }
            }
        }
    }
}

private const val MAX_BYTES = 100_000_000L
