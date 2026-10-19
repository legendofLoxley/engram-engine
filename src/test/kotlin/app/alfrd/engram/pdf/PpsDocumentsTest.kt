package app.alfrd.engram.pdf

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.scripts.PdfSourceCli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The public PPS PDFs, read from a directory the tester fills (ENGRAM_PPS_PDF_DIR); they are downloaded for the test and never committed.
 * Without the variable the test is skipped, and says so. A file named `*comparison*` is the Board comparison of Proposals A and B and gets
 * the table and reading-order checks. A report of every file is written to build/pps-report.txt.
 */
class PpsDocumentsTest {
    @Test
    fun `each PPS pdf commits as one Source with page-located chunks, twice with nothing added`() {
        val dir = System.getenv("ENGRAM_PPS_PDF_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory, "ENGRAM_PPS_PDF_DIR is not set: the PPS PDFs are not available here")
        val pdfs = dir!!.listFiles { f -> f.name.endsWith(".pdf", true) }!!.sortedBy { it.name }
        assumeTrue(pdfs.isNotEmpty(), "no PDFs in $dir")
        val work = Files.createTempDirectory("engram-pps-").toFile()
        val manager = DatabaseManager(File(work, "db").path)
        val report = StringBuilder()
        try {
            val db = manager.getDatabase(); SchemaBootstrap.bootstrap(db)
            val user = DebugConverseService.resolveUserId("pps-docs"); DebugConverseService.ensureSyntheticUser(db, user)
            val store = SourceStore(db)
            val service = PdfSourceService(store, PdfExtractor(TesseractOcr.detect()), PdfPreparationCache(File(work, "cache")))
            for (f in pdfs) {
                val bytes = f.readBytes()
                val first = service.commit(user, bytes, "file:${f.name}")
                val sources = store.sources(user).filter { it.locator == "file:${f.name}" }
                assertEquals(1, sources.size, f.name)
                val chunks = store.chunks(user, first.sourceUid)
                assertTrue(chunks.all { it.page in 1..first.pageCount }, f.name)
                // Where printed numbers were found they follow the physical pages by one constant offset.
                val offsets = chunks.filter { it.printedPage?.toIntOrNull() != null }.map { it.printedPage!!.toInt() - it.page!! }.toSet()
                assertTrue(offsets.size <= 1, "${f.name}: printed numbers do not follow one offset: $offsets")
                val again = service.commit(user, bytes, "file:${f.name}")
                assertTrue(!again.created && again.chunksCreated == 0 && again.fromCache, f.name)
                report.appendLine(PdfSourceCli.report(f.name, PdfPreparationCache(File(work, "cache")).getOrPrepare(bytes, PdfExtractor(TesseractOcr.detect())).prepared))

                if (f.name.contains("comparison", ignoreCase = true)) {
                    val tables = chunks.filter { it.kind == "table" }
                    assertTrue(tables.isNotEmpty(), "the Board comparison has a table")
                    val cells = tables.flatMap { t -> kotlinx.serialization.json.Json.parseToJsonElement(t.table!!).toString().let { listOf(it) } }.joinToString()
                    assertTrue(cells.contains("80%") && cells.contains("73%") && cells.contains("48%"), "table cells hold 48%, 80% and 73%")
                }
            }
            File("build").mkdirs(); File("build/pps-report.txt").writeText(report.toString())
        } finally { manager.close(); work.deleteRecursively() }
    }
}
