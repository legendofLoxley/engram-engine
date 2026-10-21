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
 * The real PDFs of the PPS Evidence folder, read from a directory the tester fills (ENGRAM_PPS_PDF_DIR); they are fetched for the test and never
 * committed. Without the variable the test is skipped, and says so. This is **local-file evidence only**: it exercises extraction and the commit path
 * on real documents, not the Drive client (see the Drive tests for that). A file named `*district*` is the districtwide scenarios sheet and gets the
 * table and attribution checks, which were verified against the rendered pages. A per-file report is written to build/pps-report.txt.
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
                val t0 = System.nanoTime()
                val first = service.commit(user, bytes, "file:${f.name}")
                val ms = (System.nanoTime() - t0) / 1_000_000
                val sources = store.sources(user).filter { it.locator == "file:${f.name}" }
                assertEquals(1, sources.size, f.name)
                val chunks = store.chunks(user, first.sourceUid)
                assertTrue(chunks.all { it.page in 1..first.pageCount }, f.name)
                // Where printed numbers were found they follow the physical pages by one constant offset.
                val offsets = chunks.filter { it.printedPage?.toIntOrNull() != null }.map { it.printedPage!!.toInt() - it.page!! }.toSet()
                assertTrue(offsets.size <= 1, "${f.name}: printed numbers do not follow one offset: $offsets")
                val again = service.commit(user, bytes, "file:${f.name}")
                assertTrue(!again.created && again.chunksCreated == 0 && again.fromCache, f.name)
                report.appendLine("processing: $ms ms (cold cache, includes extraction, OCR where needed, and the commit)")
                report.appendLine(PdfSourceCli.report(f.name, PdfPreparationCache(File(work, "cache")).getOrPrepare(bytes, PdfExtractor(TesseractOcr.detect())).prepared))

                if (f.name.contains("district", ignoreCase = true)) {
                    val table = kotlinx.serialization.json.Json.decodeFromString(PdfTable.serializer(), chunks.first { it.kind == "table" }.table!!)
                    assertEquals(listOf("Measure", "SCENARIO A", "SCENARIO B", "STATUS QUO"), table.rows[0])
                    // Checked against the rendered page: the row "Students attending a school above the threshold" reads 80% (A), 73% (B), 48% (status quo).
                    assertEquals(listOf("Students attending a school above the threshold", "80%", "73%", "48%"), table.rows.single { it[0].startsWith("Students attending") })
                    val text = chunks.filter { it.kind == "text" }.joinToString("\n") { it.text }
                    // The narrative boxes keep their columns: A, then B, then the status quo, each with its own figure.
                    assertTrue(text.indexOf("About 80%") in 0 until text.indexOf("About 73%") && text.indexOf("About 73%") < text.indexOf("About 48%"), text.take(2000))
                }
            }
            File("build").mkdirs(); File("build/pps-report.txt").writeText(report.toString())
        } finally { manager.close(); work.deleteRecursively() }
    }
}
