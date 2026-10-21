package app.alfrd.engram.pdf

import app.alfrd.engram.db.ChunkDraft
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PdfCommitResult(
    val sourceUid: String,
    /** False when this exact file under this locator was already there: nothing was written. */
    val created: Boolean,
    val chunksCreated: Int,
    val chunkCount: Int,
    /** True when the file had been prepared before and its extraction came from the cache. */
    val fromCache: Boolean,
    val pageCount: Int,
    val readMode: String,
    val fileSha256: String,
    val pages: List<PagePrep>,
    val tables: Int = 0,
)

/**
 * Prepares a PDF (through the cache) and commits it as one `document` Source through [SourceStore.ingest], the same path every other
 * source takes. The Source's identity is the SHA-256 of the file with its locator: the same file again writes nothing, a changed file
 * is a new Source and the old one stays. Source and chunks only; no item is made here.
 */
class PdfSourceService(private val store: SourceStore, private val preparer: PdfPreparer, private val cache: PdfPreparationCache) {
    private val json = Json { encodeDefaults = false }

    fun commit(userEmail: String, bytes: ByteArray, locator: String, title: String? = null, drive: app.alfrd.engram.db.DriveFacts? = null): PdfCommitResult {
        val r = cache.getOrPrepare(bytes, preparer)
        val p = r.prepared
        val drafts = p.chunks.map { c ->
            ChunkDraft(
                location = c.location, text = c.text, page = c.page, printedPage = c.printedPage, section = c.section, kind = c.kind,
                extractor = c.extractor, ocrConfidence = c.ocrConfidence, box = c.box?.let { json.encodeToString(it) },
                table = c.table?.let { json.encodeToString(it) },
            )
        }
        require(drafts.isNotEmpty()) { "nothing could be read from this PDF: no text layer, and no OCR result" }
        val ingest = store.ingest(
            userEmail,
            SourceInput(
                type = "document", locator = locator, title = title?.takeIf { it.isNotBlank() } ?: p.title.ifBlank { locator.substringAfterLast('/').substringAfterLast(':') },
                text = p.chunks.joinToString("\n\n") { it.text }, readMode = p.readMode, chunks = drafts, fileSha256 = p.sha256,
                pageCount = p.pageCount, ocrPages = p.ocrPages, unreadPages = p.unreadPages, drive = drive,
            ),
        )
        return PdfCommitResult(
            ingest.source.uid, ingest.created, ingest.chunksCreated, ingest.source.chunkCount, r.fromCache, p.pageCount, ingest.source.readMode, p.sha256, p.pages,
            p.chunks.count { it.kind == "table" },
        )
    }
}
