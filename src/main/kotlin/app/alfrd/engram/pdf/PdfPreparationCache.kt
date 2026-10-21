package app.alfrd.engram.pdf

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class CacheResult(val prepared: PreparedPdf, val fromCache: Boolean)

/**
 * A content-addressed cache of prepared PDFs: the key is the SHA-256 of the file's bytes, the extractor version and the OCR engine in use,
 * so the same file is never prepared twice, a changed file is its own entry, and an OCR engine that appears later is not hidden behind
 * an entry made without one. A damaged entry is ignored and rewritten. With no directory there is no cache (and no files).
 * The cache holds extracted text, so it sits beside the graph and is as private as it.
 */
class PdfPreparationCache(private val dir: File?) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getOrPrepare(bytes: ByteArray, preparer: PdfPreparer): CacheResult {
        val file = dir?.let { File(it, key(bytes, preparer) + ".json") }
        if (file != null && file.isFile) {
            try {
                return CacheResult(json.decodeFromString(PreparedPdf.serializer(), file.readText()), fromCache = true)
            } catch (e: Exception) {
                logger.warn("pdf cache: ignoring a damaged entry ({})", e.javaClass.simpleName)
            }
        }
        val prepared = preparer.prepare(bytes)
        if (file != null) {
            try {
                file.parentFile.mkdirs()
                val tmp = Files.createTempFile(file.parentFile.toPath(), "entry-", ".tmp")
                Files.writeString(tmp, json.encodeToString(PreparedPdf.serializer(), prepared))
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                logger.warn("pdf cache: could not write an entry ({})", e.javaClass.simpleName)
            }
        }
        return CacheResult(prepared, fromCache = false)
    }

    private fun key(bytes: ByteArray, preparer: PdfPreparer): String {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "$sha-v${PdfExtractor.VERSION}-${preparer.ocrId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)}"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PdfPreparationCache::class.java)

        /** ENGRAM_PDF_CACHE_DIR, else `./data/pdf-cache` (next to the graph's default `./data/engram-db`). */
        fun fromEnvironment() = PdfPreparationCache(File(System.getenv("ENGRAM_PDF_CACHE_DIR")?.takeIf { it.isNotBlank() } ?: "./data/pdf-cache"))
    }
}
