package app.alfrd.engram.drive

import app.alfrd.engram.db.DriveFacts
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PdfSourceService
import app.alfrd.engram.pdf.TesseractOcr
import com.arcadedb.database.Database
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class DriveFileResult(
    val fileId: String, val name: String,
    /** `created` (a new Source), `unchanged` (already there, nothing written), `skipped` (not a PDF) or `failed`. */
    val outcome: String,
    val reason: String? = null, val sourceUid: String? = null, val version: String? = null, val md5: String? = null, val webViewLink: String? = null,
    val pageCount: Int? = null, val chunkCount: Int? = null, val chunksCreated: Int? = null, val tables: Int? = null, val readMode: String? = null,
    val ocrPages: Int? = null, val unreadPages: Int? = null, val fileSha256: String? = null, val elapsedMs: Long? = null,
)

@Serializable
data class DriveIngestReport(val folderId: String, val files: List<DriveFileResult>, val created: Int, val unchanged: Int, val skipped: Int, val failed: Int) {
    companion object {
        fun of(folderId: String, files: List<DriveFileResult>) = DriveIngestReport(
            folderId, files, files.count { it.outcome == "created" }, files.count { it.outcome == "unchanged" },
            files.count { it.outcome == "skipped" }, files.count { it.outcome == "failed" },
        )
    }
}

/** One configured folder of one Drive account that can be listed and ingested. */
interface DriveShelf {
    val folderId: String
    fun listPdfs(): List<DriveFile>
    fun ingest(userEmail: String): DriveIngestReport
}

/**
 * Fetches the PDFs in the configured Drive folder (the ingestion boundary: nothing outside it, and sub-folders are not entered) and commits
 * each as one Source through [PdfSourceService], so it takes the same path as every other Source.
 *
 * **Identity and reruns.** A Source's locator is `gdrive:<file id>` (stable across renames and moves) and its identity is that with the SHA-256 of
 * the file. A file whose content md5 matches a Source already read under its id is `unchanged` and is not even downloaded. Changed content is a new
 * Source and the old one stays, with the version it was read at; the same bytes under a newer Drive version are not a new Source.
 * **Provenance** kept on the Source: Drive file id, name and link as read, version, md5, modified time, Shared Drive id, head revision and folder;
 * the physical and printed page of each chunk is on the chunk.
 * One file that cannot be fetched or read is reported as `failed` with a reason and writes nothing; the others go on.
 */
class DriveShelfService(
    private val drive: DriveClient,
    private val pdfs: PdfSourceService,
    private val store: SourceStore,
    override val folderId: String,
) : DriveShelf {
    private val logger = LoggerFactory.getLogger(DriveShelfService::class.java)

    override fun listPdfs(): List<DriveFile> { drive.folder(folderId); return drive.children(folderId).filter { it.mimeType == PDF } }

    override fun ingest(userEmail: String): DriveIngestReport {
        drive.folder(folderId)
        val children = drive.children(folderId).sortedBy { it.name }
        val known = store.sources(userEmail).filter { it.drive != null }
        val results = children.map { f ->
            val locator = locatorOf(f.id)
            val result = when {
                f.isFolder -> skipped(f, "a folder: sub-folders are not entered")
                f.mimeType != PDF -> skipped(f, "not a PDF (${f.mimeType.take(80)})")
                f.md5 != null && known.any { it.locator == locator && it.drive?.md5 == f.md5 } -> {
                    val s = known.first { it.locator == locator && it.drive?.md5 == f.md5 }
                    DriveFileResult(
                        f.id, f.name, "unchanged", sourceUid = s.uid, version = f.version, md5 = f.md5, webViewLink = f.webViewLink, pageCount = s.pageCount,
                        chunkCount = s.chunkCount, chunksCreated = 0, readMode = s.readMode, ocrPages = s.ocrPages, unreadPages = s.unreadPages, fileSha256 = s.fileSha256,
                    )
                }
                else -> fetchAndCommit(userEmail, f, locator)
            }
            logger.info("drive: file {} {}", f.id, result.outcome)
            result
        }
        val report = DriveIngestReport.of(folderId, results)
        logger.info("drive: folder {} ingested: {} created, {} unchanged, {} skipped, {} failed", folderId, report.created, report.unchanged, report.skipped, report.failed)
        return report
    }

    private fun fetchAndCommit(userEmail: String, f: DriveFile, locator: String): DriveFileResult {
        val start = System.nanoTime()
        return try {
            val bytes = drive.download(f)
            val r = pdfs.commit(
                userEmail, bytes, locator, f.name,
                DriveFacts(f.id, f.name, f.webViewLink, f.version, f.md5, f.modifiedTime, f.driveId, f.headRevisionId, folderId),
            )
            val source = store.sourceByUid(userEmail, r.sourceUid)
            DriveFileResult(
                f.id, f.name, if (r.created) "created" else "unchanged", sourceUid = r.sourceUid, version = f.version, md5 = f.md5, webViewLink = f.webViewLink,
                pageCount = r.pageCount, chunkCount = r.chunkCount, chunksCreated = r.chunksCreated, tables = r.tables, readMode = r.readMode,
                ocrPages = source?.ocrPages, unreadPages = source?.unreadPages, fileSha256 = r.fileSha256, elapsedMs = (System.nanoTime() - start) / 1_000_000,
            )
        } catch (e: DriveApiException) {
            failed(f, e.message ?: "drive request failed", start)
        } catch (e: IllegalArgumentException) {
            failed(f, e.message ?: "could not be read", start)
        } catch (e: Exception) {
            failed(f, "could not be processed (${e.javaClass.simpleName})", start)
        }
    }

    private fun skipped(f: DriveFile, why: String) = DriveFileResult(f.id, f.name, "skipped", reason = why)
    private fun failed(f: DriveFile, why: String, startNanos: Long) =
        DriveFileResult(f.id, f.name, "failed", reason = why, version = f.version, md5 = f.md5, elapsedMs = (System.nanoTime() - startNanos) / 1_000_000)

    companion object {
        const val PDF = "application/pdf"
        fun locatorOf(fileId: String) = "gdrive:$fileId"
    }
}

/**
 * The shelf as configured on the box: `GOOGLE_DRIVE_CREDENTIALS_FILE` (a path; the file holds the secrets, mode 600, outside the repo) and
 * `GOOGLE_DRIVE_FOLDER_ID`. The credentials are read when first used, not at start, so a missing or too-open file is an error on the call
 * that needs it and not a failure to boot. Absent configuration means no shelf.
 */
class ConfiguredDriveShelf(private val db: Database, private val credentialsPath: String, override val folderId: String) : DriveShelf {
    private var service: DriveShelfService? = null

    @Synchronized
    private fun delegate(): DriveShelfService = service ?: run {
        val http = DriveHttp.production()
        val tokens = GoogleAccessTokens(GoogleCredentials.load(credentialsPath), http)
        val store = SourceStore(db)
        DriveShelfService(DriveClient(tokens, http), PdfSourceService(store, PdfExtractor(TesseractOcr.detect()), PdfPreparationCache.fromEnvironment()), store, folderId)
            .also { service = it }
    }

    override fun listPdfs() = delegate().listPdfs()
    override fun ingest(userEmail: String) = delegate().ingest(userEmail)

    companion object {
        fun fromEnvironment(db: Database): DriveShelf? {
            val path = System.getenv("GOOGLE_DRIVE_CREDENTIALS_FILE")?.takeIf { it.isNotBlank() } ?: return null
            val folder = System.getenv("GOOGLE_DRIVE_FOLDER_ID")?.takeIf { it.isNotBlank() } ?: return null
            return ConfiguredDriveShelf(db, path, folder)
        }
    }
}
