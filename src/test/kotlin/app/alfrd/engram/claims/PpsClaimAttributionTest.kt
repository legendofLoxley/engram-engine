package app.alfrd.engram.claims

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PdfSourceService
import app.alfrd.engram.pdf.TesseractOcr
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The same attribution contract as [ClaimAttributionTest], on the **real** PPS districtwide scenarios PDF (read from ENGRAM_PPS_PDF_DIR, a file named
 * `*district*`; never committed). Skipped, and says so, without it. The model is still a script: this checks that the real document's chunks carry the
 * 80 / 73 / 48 percent row in the order the scenarios are printed, and that the extraction contract files each figure under its own scenario.
 */
class PpsClaimAttributionTest {
    @Test
    fun `the real districtwide table row establishes 80, 73 and 48 percent under scenarios A, B and status quo`() = runBlocking<Unit> {
        val dir = System.getenv("ENGRAM_PPS_PDF_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory, "ENGRAM_PPS_PDF_DIR is not set: the PPS PDFs are not available here")
        val pdf = dir!!.listFiles { f -> f.name.endsWith(".pdf", true) && f.name.contains("district", true) }?.firstOrNull()
        assumeTrue(pdf != null, "no *district*.pdf in $dir")
        val work = Files.createTempDirectory("engram-pps-claims-").toFile()
        val manager = DatabaseManager(File(work, "db").path)
        try {
            val db = manager.getDatabase(); SchemaBootstrap.bootstrap(db)
            val user = DebugConverseService.resolveUserId("pps-claims"); DebugConverseService.ensureSyntheticUser(db, user)
            val store = SourceStore(db); val claims = ClaimStore(db)
            val committed = PdfSourceService(store, PdfExtractor(TesseractOcr.detect()), PdfPreparationCache(File(work, "cache"))).commit(user, pdf!!.readBytes(), "file:${pdf.name}")
            val chunks = store.chunks(user, committed.sourceUid)
            val row = chunks.flatMap { c -> c.text.lines().map { c to it } }.firstOrNull { (_, l) -> Regex("80\\s*%.*73\\s*%.*48\\s*%").containsMatchIn(l) }
            assertTrue(row != null, "no line of the real document carries 80%, 73% and 48% in that order")
            val (chunk, line) = row!!
            val structure = ArcadeHorizonStructureStore(db)
            val service = ClaimExtractionService(
                store, claims, SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db)), ClaimantResolver(),
            )
            val model = FakeClaimModel { ch, _ ->
                if (ch.uid == chunk.uid) listOf("SCENARIO A", "SCENARIO B", "STATUS QUO").map { PassClaim(line.trim(), "PPS", "", "number", it) } else emptyList()
            }
            val report = service.run(user, committed.sourceUid, "pps-real-1", model)
            assertEquals(3, report.claimsEstablished)
            assertEquals(setOf("SCENARIO A", "SCENARIO B", "STATUS QUO"), claims.claims(user, committed.sourceUid).map { it.place }.toSet())
        } finally { manager.close(); work.deleteRecursively() }
    }
}
