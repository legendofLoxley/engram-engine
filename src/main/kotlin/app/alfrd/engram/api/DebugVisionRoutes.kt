package app.alfrd.engram.api

import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PdfPreparer
import app.alfrd.engram.pdf.TesseractOcr
import app.alfrd.engram.vision.LocalVisionClient
import app.alfrd.engram.vision.VisionModel
import app.alfrd.engram.vision.VisionReadingService
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

/** The PDF the source was read from, by [path] on this machine or as [contentBase64]; its SHA-256 must match the source. */
@Serializable
data class DebugVisionRequest(
    val syntheticUserId: String,
    val path: String? = null,
    val contentBase64: String? = null,
    val page: Int? = null,
    val ocr: Boolean = true,
)

/**
 * Vision reading of a stored PDF Source: `POST /debug/sources/{uid}/vision/plan` says which pages go to vision and why; `POST
 * /debug/sources/{uid}/vision/page` reads one page and adds its checked structure as a chunk (see [VisionReadingService]). One page per call:
 * a page takes the vision model about a hundred seconds, and a caller can follow and resume page by page. Same `debug-token` auth,
 * `DEBUG_CONVERSE_ENABLED` gate and loopback-only rule as the other `/debug/sources` routes; synthetic identities only, never owner-jacob; the
 * loopback check comes before the body is read. A file is named by path only if it ends `.pdf` (at most [MAX_PDF_BYTES]). The vision servers
 * are local only ([LocalVisionClient] refuses any other host): VISION_LLM_BASE_URL (default 8083) and OCR_LLM_BASE_URL (default 8084).
 */
fun Application.configureDebugVisionRoutes(
    db: Database,
    structure: VisionModel = LocalVisionClient(System.getenv("VISION_LLM_BASE_URL") ?: "http://127.0.0.1:8083/v1"),
    ocr: VisionModel? = LocalVisionClient(System.getenv("OCR_LLM_BASE_URL") ?: "http://127.0.0.1:8084/v1", temperature = 0.0),
    preparer: PdfPreparer = PdfExtractor(TesseractOcr.detect()),
    cache: PdfPreparationCache = PdfPreparationCache.fromEnvironment(),
    isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback,
) {
    val store = SourceStore(db)
    val service = VisionReadingService(store, structure, ocr, preparer, cache)

    suspend fun ApplicationCall.bad(msg: String) = respond(HttpStatusCode.BadRequest, mapOf("error" to msg))

    /** The user and the file, or null after answering. */
    suspend fun ApplicationCall.input(): Triple<String, String, ByteArray>? {
        if (!isLoopback(this)) { respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only")); return null }
        val req = receive<DebugVisionRequest>()
        val email = DebugConverseService.resolveUserId(req.syntheticUserId.takeIf { it.isNotBlank() } ?: return run { bad("syntheticUserId is required"); null })
        if (email.contains("owner-jacob")) { respond(HttpStatusCode.Forbidden, mapOf("error" to "that identity is real data and is never used by the debug routes")); return null }
        val bytes = when {
            req.contentBase64 != null -> try { Base64.getDecoder().decode(req.contentBase64) } catch (e: IllegalArgumentException) { bad("contentBase64 is not base64"); return null }
            req.path != null -> {
                val f = File(req.path)
                if (!f.isFile || !f.name.endsWith(".pdf", ignoreCase = true)) { bad("path must name an existing .pdf file"); return null }
                if (f.length() > MAX_PDF_BYTES) { bad("file is larger than ${MAX_PDF_BYTES / 1_000_000} MB"); return null }
                f.readBytes()
            }
            else -> { bad("give path or contentBase64"); return null }
        }
        attributes.put(PAGE_KEY, req.page ?: 0); attributes.put(OCR_KEY, req.ocr)
        return Triple(email, parameters["uid"].orEmpty(), bytes)
    }

    routing {
        authenticate("debug-token") {
            route("/debug") {
                post("/sources/{uid}/vision/plan") {
                    val (email, uid, bytes) = call.input() ?: return@post
                    val plan = try { service.plan(email, uid, bytes) } catch (e: IllegalArgumentException) { return@post call.bad(e.message ?: "bad request") }
                    call.respond(HttpStatusCode.OK, plan)
                }
                post("/sources/{uid}/vision/page") {
                    val (email, uid, bytes) = call.input() ?: return@post
                    val page = call.attributes[PAGE_KEY].takeIf { it > 0 } ?: return@post call.bad("page is required")
                    val result = try { service.readPage(email, uid, bytes, page, call.attributes[OCR_KEY]) } catch (e: IllegalArgumentException) {
                        return@post call.bad(e.message ?: "bad request")
                    }
                    call.respond(if (result.outcome == "failed") HttpStatusCode.BadGateway else HttpStatusCode.OK, result)
                }
            }
        }
    }
}

const val MAX_PDF_BYTES = 100_000_000L
private val PAGE_KEY = io.ktor.util.AttributeKey<Int>("vision-page")
private val OCR_KEY = io.ktor.util.AttributeKey<Boolean>("vision-ocr")
