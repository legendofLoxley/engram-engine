package app.alfrd.engram.claims

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceRecord
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.PdfFixtures
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PdfSourceService
import app.alfrd.engram.pdf.poster
import app.alfrd.engram.pdf.scenarios
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Attribution on the shape of the PPS districtwide scenarios sheet. The chunks are the **generated fixtures'** (a designed one-pager with a
 * ruled-less table and three narrative boxes; a poster whose cards carry counts at their edge), read by the real PDF extractor and committed
 * as a Source; the model is a script. So these prove what the extraction contract does with attributions: a figure is filed under the scenario
 * both passes name, a figure the passes file under different scenarios is not established, and a figure filed under a scenario whose section
 * it is not in is rejected. They do not prove what Qwen attributes: that is the box's gate, on the real file.
 */
class ClaimAttributionTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var store: SourceStore
    private lateinit var claims: ClaimStore
    private lateinit var service: ClaimExtractionService
    private val ana = "debug+attribution@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-attribution-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        val db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana)
        store = SourceStore(db); claims = ClaimStore(db)
        val structure = ArcadeHorizonStructureStore(db)
        service = ClaimExtractionService(
            store, claims, SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db)),
            ClaimantResolver(mapOf("Portland Public Schools" to listOf("PPS", "the District"))),
        )
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private fun commit(bytes: ByteArray, locator: String): SourceRecord =
        PdfSourceService(store, PdfExtractor(), PdfPreparationCache(null)).commit(ana, bytes, locator).let { store.sourceByUid(ana, it.sourceUid)!! }

    private fun c(quote: String, place: String?, subject: String = "") = PassClaim(quote, "PPS", subject, "number", place)

    private val tableRow = "Students attending a school above the threshold | 80% | 73% | 48%"

    @Test
    fun `the districtwide table row is three claims, one per scenario, each tied to the table chunk with its own place`() = runBlocking<Unit> {
        val s = commit(PdfFixtures.scenarios(), "file:districtwide.pdf")
        val model = FakeClaimModel { ch, _ ->
            if (ch.kind == "table") listOf(
                c(tableRow, "SCENARIO A", "A: students above threshold 80%"), c(tableRow, "SCENARIO B", "B: 73%"), c(tableRow, "STATUS QUO", "status quo: 48%"),
            ) else emptyList()
        }
        val report = service.run(ana, s.uid, "run-table", model)
        assertEquals(3, report.claimsEstablished)
        val byPlace = claims.claims(ana, s.uid).associateBy { it.place }
        assertEquals(setOf("SCENARIO A", "SCENARIO B", "STATUS QUO"), byPlace.keys)
        assertEquals("A: students above threshold 80%", byPlace.getValue("SCENARIO A").subject)
        assertEquals("B: 73%", byPlace.getValue("SCENARIO B").subject)
        assertEquals("status quo: 48%", byPlace.getValue("STATUS QUO").subject)
        byPlace.values.forEach { assertEquals(tableRow, it.quote); assertEquals(1, store.provenanceOf(ana, it.uid).size) }
        assertTrue(store.provenanceOf(ana, byPlace.getValue("SCENARIO A").uid).single().location.contains("table 1"))
    }

    @Test
    fun `passes that file the 73 percent figure under different scenarios do not establish it, and both versions are kept`() = runBlocking<Unit> {
        val s = commit(PdfFixtures.scenarios(), "file:districtwide.pdf")
        val model = FakeClaimModel { ch, pass ->
            if (ch.kind == "table") listOf(c(tableRow, "SCENARIO A"), c(tableRow, if (pass == 1) "SCENARIO B" else "STATUS QUO")) else emptyList()
        }
        val report = service.run(ana, s.uid, "run-swap", model)
        assertEquals(1, report.claimsEstablished, "only the figure both passes filed under A")
        assertEquals(listOf("SCENARIO A"), claims.claims(ana, s.uid).map { it.place })
        val d = claims.disagreements(ana, s.uid).single()
        assertEquals("SCENARIO B", d.versionA!!.place); assertEquals("STATUS QUO", d.versionB!!.place)
    }

    @Test
    fun `each narrative box's figure is established under its own scenario and no other`() = runBlocking<Unit> {
        val s = commit(PdfFixtures.scenarios(), "file:districtwide.pdf")
        val model = FakeClaimModel { ch, _ ->
            if (ch.kind == "text") listOf(
                c("About 80% of students\nattend a sustainable school.", "SCENARIO A"),
                c("About 73% of students will\nattend a sustainable school.", "SCENARIO B"),
                c("About 48% of students\nattend a sustainable school.", "STATUS QUO"),
            ) else emptyList()
        }
        val report = service.run(ana, s.uid, "run-boxes", model)
        assertEquals(3, report.claimsEstablished); assertEquals(0, report.disagreements)
        claims.claims(ana, s.uid).forEach { claim ->
            val pct = Regex("(\\d+)%").find(claim.quote)!!.groupValues[1]
            assertEquals(mapOf("80" to "SCENARIO A", "73" to "SCENARIO B", "48" to "STATUS QUO")[pct], claim.place, claim.quote)
        }
    }

    @Test
    fun `a figure both passes file under the wrong scenario's heading is rejected, because it is not in that heading's section`() = runBlocking<Unit> {
        val s = commit(PdfFixtures.scenarios(), "file:districtwide.pdf")
        val wrong = FakeClaimModel { ch, _ ->
            if (ch.kind == "text") listOf(
                c("About 73% of students will\nattend a sustainable school.", "SCENARIO A"), // 73% is in B's box
                c("About 80% of students\nattend a sustainable school.", "SCENARIO A"),
                c("About 48% of students\nattend a sustainable school.", "SCENARIO B"),
                c("Closures bring about two thirds", "STATUS QUO"),
            ) else emptyList()
        }
        val report = service.run(ana, s.uid, "run-wrong", wrong)
        assertEquals(1, report.claimsEstablished, "only the 80% figure, which really is under SCENARIO A")
        assertTrue(claims.claims(ana, s.uid).single().quote.contains("80%"))
        assertEquals(6, report.quotesRejected, "three misfiled claims, in both passes")
        assertTrue(claims.disagreements(ana, s.uid).all { it.reason == ClaimAgreement.OUT_OF_SECTION })
    }

    @Test
    fun `a card count is filed under the scenario whose section holds it, and 38 is never A's count under B`() = runBlocking<Unit> {
        val s = commit(PdfFixtures.poster(), "file:cards.pdf")
        val chunkText = store.chunks(ana, s.uid).first { it.kind == "text" }.text
        assertTrue(chunkText.indexOf("SCENARIO A") < chunkText.indexOf("\n38\n") && chunkText.indexOf("\n38\n") < chunkText.indexOf("SCENARIO B"), "fixture: 38 is in A's section")
        assertTrue(chunkText.indexOf("SCENARIO B") < chunkText.indexOf("\n34\n"), "fixture: 34 is in B's section")

        val right = FakeClaimModel { ch, _ -> if (ch.kind == "text") listOf(c("38", "SCENARIO A", "A boundary changes"), c("34", "SCENARIO B", "B boundary changes")) else emptyList() }
        val ok = service.run(ana, s.uid, "run-cards", right)
        assertEquals(2, ok.claimsEstablished)
        assertEquals(setOf("38" to "SCENARIO A", "34" to "SCENARIO B"), claims.claims(ana, s.uid).map { it.quote to it.place }.toSet())

        val bad = commit(PdfFixtures.poster(), "file:cards2.pdf")
        val swapped = FakeClaimModel { ch, _ -> if (ch.kind == "text") listOf(c("38", "SCENARIO B", "B boundary changes"), c("34", "SCENARIO A", "A boundary changes")) else emptyList() }
        val none = service.run(ana, bad.uid, "run-cards-swapped", swapped)
        assertEquals(0, none.claimsEstablished, "a swapped pair of counts establishes nothing")
        assertEquals(4, none.quotesRejected)
        assertTrue(claims.claims(ana, bad.uid).isEmpty())
    }

    @Test
    fun `a swap the two passes disagree about is not established even where the section check cannot tell`() = runBlocking<Unit> {
        val s = commit(PdfFixtures.poster(), "file:cards.pdf")
        val model = FakeClaimModel { ch, pass ->
            // "SCHOOLS CLOSING" is no heading either pass names as a place here, so only agreement between the passes protects the count.
            if (ch.kind == "text") listOf(c("14 schools close", if (pass == 1) "the left card" else "the right card")) else emptyList()
        }
        val report = service.run(ana, s.uid, "run-places", model)
        assertEquals(0, report.claimsEstablished)
        assertEquals("passes differ on the place", claims.disagreements(ana, s.uid).single().reason)
    }
}
