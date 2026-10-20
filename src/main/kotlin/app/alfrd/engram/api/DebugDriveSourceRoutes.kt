package app.alfrd.engram.api

import app.alfrd.engram.drive.DriveApiException
import app.alfrd.engram.drive.DriveConfigException
import app.alfrd.engram.drive.DriveShelf
import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable
data class DebugDriveCommitRequest(val syntheticUserId: String)

@Serializable
data class DebugDriveFile(val id: String, val name: String, val mimeType: String, val size: Long?, val version: String?, val md5: String?, val modifiedTime: String?, val webViewLink: String?, val driveId: String?)

@Serializable
data class DebugDriveFiles(val folderId: String, val files: List<DebugDriveFile>)

/**
 * Reads the configured Google Drive folder into Sources: `POST /debug/sources/drive` fetches every PDF in it (read-only) and commits each as a Source
 * under a synthetic identity; `GET /debug/drive/files` lists the PDFs without fetching them. The folder is the one in configuration, never one the
 * caller names. Same `debug-token` auth, `DEBUG_CONVERSE_ENABLED` gate and loopback-only rule as `/debug/sources`; the loopback check comes before the
 * body is read, the identity is synthetic and never owner-jacob, and the Drive account and credentials are not touched for a caller that is refused.
 * Each Source's Drive provenance and the page of each chunk are shown by `GET /debug/sources/{uid}/provenance`.
 */
fun Application.configureDebugDriveSourceRoutes(
    db: Database,
    shelf: () -> DriveShelf?,
    isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback,
) {
    suspend fun ApplicationCall.driveErrors(block: suspend () -> Unit) {
        try { block() } catch (e: DriveConfigException) {
            respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "drive is not configured correctly")))
        } catch (e: DriveApiException) {
            respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "drive request failed")))
        } catch (e: IOException) {
            respond(HttpStatusCode.BadGateway, mapOf("error" to "drive could not be reached"))
        }
    }
    suspend fun ApplicationCall.shelfOrRefuse(): DriveShelf? = shelf() ?: run {
        respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "drive is not configured: set GOOGLE_DRIVE_CREDENTIALS_FILE (a mode-600 file outside the repo) and GOOGLE_DRIVE_FOLDER_ID"))
        null
    }

    routing {
        authenticate("debug-token") {
            route("/debug") {
                post("/sources/drive") {
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val req = call.receive<DebugDriveCommitRequest>()
                    if (req.syntheticUserId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "syntheticUserId is required"))
                    val email = DebugConverseService.resolveUserId(req.syntheticUserId)
                    if (email.contains("owner-jacob")) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "that identity is real data and is never used by the debug routes"))
                    val s = call.shelfOrRefuse() ?: return@post
                    call.driveErrors {
                        DebugConverseService.ensureSyntheticUser(db, email)
                        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { s.ingest(email) })
                    }
                }
                get("/drive/files") {
                    if (!isLoopback(call)) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val s = call.shelfOrRefuse() ?: return@get
                    call.driveErrors {
                        val files = withContext(Dispatchers.IO) { s.listPdfs() }
                        call.respond(HttpStatusCode.OK, DebugDriveFiles(s.folderId, files.map { DebugDriveFile(it.id, it.name, it.mimeType, it.size, it.version, it.md5, it.modifiedTime, it.webViewLink, it.driveId) }))
                    }
                }
            }
        }
    }
}
