package app.alfrd.engram.vision

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.claims.ClaimExtractionService
import app.alfrd.engram.claims.ClaimStore
import app.alfrd.engram.claims.ClaimantResolver
import app.alfrd.engram.claims.FakeClaimModel
import app.alfrd.engram.claims.LocalClaimModel
import app.alfrd.engram.claims.PassClaim
import app.alfrd.engram.claims.StructuredClaimModel
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/**
 * Vision reading on the generated fixtures (the districtwide-like scenarios page with its table, the poster of cards with counts), read by the
 * real PDF extractor and committed as Sources, with a scripted vision model. Proves routing, the check, the added chunk, idempotency and claims
 * from structure; not what the real model reads.
 */
class VisionReadingTest {
    private lateinit var manager: DatabaseManager
    private lateinit var dir: java.io.File
    private lateinit var store: SourceStore
    private lateinit var claims: ClaimStore
    private lateinit var commit: SourceCommitService
    private val ana = "debug+vision@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("engram-vision-").toFile()
        manager = DatabaseManager(dir.resolve("db").toString())
        val db = manager.getDatabase(); SchemaBootstrap.bootstrap(db); DebugConverseService.ensureSyntheticUser(db, ana)
        store = SourceStore(db); claims = ClaimStore(db)
        val structure = ArcadeHorizonStructureStore(db)
        commit = SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db))
    }

    @AfterEach
    fun tearDown() { manager.close(); dir.deleteRecursively() }

    private val cache by lazy { PdfPreparationCache(dir.resolve("cache")) }
    private fun commitPdf(bytes: ByteArray, locator: String): String = PdfSourceService(store, PdfExtractor(), cache).commit(ana, bytes, locator).sourceUid

    private class Scripted(var answer: String) : VisionModel {
        var calls = 0
        override fun read(png: ByteArray, prompt: String): VisionAnswer { calls++; return VisionAnswer(answer, "Qwen3.8-27B-UD-Q6_K_XL.gguf", 5, "stop", 10) }
    }

    private fun service(model: VisionModel, ocr: VisionModel? = null) = VisionReadingService(store, model, ocr, PdfExtractor(), cache)

    private val posterAnswer = """
        {"sections": [
          {"heading": "STATUS QUO", "numbers": [{"value": "26%", "label": "of students in sustainable schools"}]},
          {"heading": "SCENARIO A", "numbers": [{"value": "14", "label": "schools close"}], "subsections": [
            {"heading": "SCHOOLS CLOSING", "numbers": ["14"]}, {"heading": "BOUNDARY CHANGES", "numbers": ["38"]}]},
          {"heading": "SCENARIO B", "numbers": [{"value": "11", "label": "schools close"}, {"value": "13", "label": "schools close"}], "subsections": [
            {"heading": "SCHOOLS CLOSING", "numbers": ["11"]}, {"heading": "BOUNDARY CHANGES", "numbers": ["38"]}]}
        ]}
    """.trimIndent()

    @Test
    fun `pages with a table are routed to vision and prose pages are not`() {
        val bytes = PdfFixtures.scenarios()
        val uid = commitPdf(bytes, "file:districtwide.pdf")
        val plan = service(Scripted("{}")).plan(ana, uid, bytes)
        assertTrue("table" in plan.single().reasons, plan.toString())
        val prose = PdfFixtures.blankPage()
        val proseUid = commitPdf(prose, "file:prose.pdf")
        assertTrue(service(Scripted("{}")).plan(ana, proseUid, prose).all { it.reasons.isEmpty() || it.reasons == listOf("no text layer") })
    }

    @Test
    fun `a page read by vision becomes one checked chunk, and misfiled, invented and unlabelled values are left out`() {
        val bytes = PdfFixtures.poster()
        val uid = commitPdf(bytes, "file:cards.pdf")
        val before = store.chunks(ana, uid)
        val model = Scripted(posterAnswer)
        val r = service(model).readPage(ana, uid, bytes, 1)
        assertEquals("added", r.outcome, r.toString())
        val chunk = store.chunks(ana, uid).single { it.kind == VisionReading.CHUNK_KIND }
        assertEquals(before.size, chunk.ordinal, "added after the text chunks")
        assertEquals(before.map { it.uid to it.text }, store.chunks(ana, uid).filter { it.kind != VisionReading.CHUNK_KIND }.map { it.uid to it.text }, "text chunks untouched")
        assertEquals("vision:Qwen3.8-27B-UD-Q6_K_XL.gguf", chunk.extractor)
        val lines = chunk.text.lines()
        assertTrue("SCENARIO A > BOUNDARY CHANGES: 38" in lines, chunk.text)
        assertTrue("SCENARIO A: 14 schools close" in lines && "SCENARIO B: 11 schools close" in lines, chunk.text)
        assertFalse(lines.any { it.startsWith("SCENARIO B > BOUNDARY CHANGES:") }, "38 under B is not where the page prints it")
        assertFalse(lines.any { it.contains("13 schools close") }, "13 is not on the page")
        assertTrue(r.rejectedValues.any { it.contains("SCENARIO B > BOUNDARY CHANGES :: 38") && it.contains("not under that heading") }, r.rejectedValues.toString())
        assertTrue(r.rejectedValues.any { it.contains(":: 13") && it.contains("not on the page") }, r.rejectedValues.toString())
        assertTrue(r.rejectedValues.any { it.contains(":: 26%") && it.contains("label not on the page") }, "a label the page does not print: ${r.rejectedValues}")
        assertEquals(r.accepted, VisionReading.entries(chunk).size)
    }

    @Test
    fun `reading a page again adds nothing and calls no model, and another file is refused`() {
        val bytes = PdfFixtures.poster()
        val uid = commitPdf(bytes, "file:cards.pdf")
        val model = Scripted(posterAnswer)
        service(model).readPage(ana, uid, bytes, 1)
        val n = store.chunks(ana, uid).size
        val again = service(model).readPage(ana, uid, bytes, 1)
        assertEquals("already there", again.outcome)
        assertEquals(1, model.calls)
        assertEquals(n, store.chunks(ana, uid).size)
        assertThrows<IllegalArgumentException> { service(model).readPage(ana, uid, PdfFixtures.scenarios(), 1) }
        assertThrows<IllegalArgumentException> { service(model).readPage(ana, uid, bytes, 5) }
    }

    @Test
    fun `an unreadable answer or nothing verified stores nothing`() {
        val bytes = PdfFixtures.poster()
        val uid = commitPdf(bytes, "file:cards.pdf")
        val n = store.chunks(ana, uid).size
        assertEquals("failed", service(Scripted("I can't read this")).readPage(ana, uid, bytes, 1).outcome)
        assertEquals("nothing verified", service(Scripted("""{"sections":[{"heading":"SCENARIO Z","numbers":["99"]}]}""")).readPage(ana, uid, bytes, 1).outcome)
        assertEquals(n, store.chunks(ana, uid).size)
        assertEquals("failed", service(VisionModel { _, _ -> error("down") }).readPage(ana, uid, bytes, 1).outcome)
    }

    @Test
    fun `table cells are kept with their row label and column header`() {
        val bytes = PdfFixtures.scenarios()
        val uid = commitPdf(bytes, "file:districtwide.pdf")
        val answer = """{"sections": [{"heading": "District-wide, side by side", "tables": [[
            ["Measure", "SCENARIO A", "SCENARIO B", "STATUS QUO"],
            ["Students attending a school above the threshold", "80%", "73%", "48%"],
            ["Schools or sites that would close", "14", "11", "0"],
            ["Students attending a school above the threshold", "80%", "99%", "48%"]]]}]}"""
        val r = service(Scripted(answer)).readPage(ana, uid, bytes, 1)
        val lines = store.chunks(ana, uid).single { it.kind == VisionReading.CHUNK_KIND }.text.lines()
        assertTrue("District-wide, side by side > Students attending a school above the threshold > SCENARIO A: 80%" in lines, lines.toString())
        assertTrue("District-wide, side by side > Students attending a school above the threshold > STATUS QUO: 48%" in lines)
        assertTrue("District-wide, side by side > Schools or sites that would close > SCENARIO B: 11" in lines)
        assertTrue(r.rejectedValues.any { it.contains(":: 99%") }, "an invented cell is left out")
    }

    @Test
    fun `claims are made straight from the vision chunk, under the right scenario, and the text passes leave vision chunks alone`() = runBlocking<Unit> {
        val bytes = PdfFixtures.poster()
        val uid = commitPdf(bytes, "file:cards.pdf")
        service(Scripted(posterAnswer)).readPage(ana, uid, bytes, 1)
        val claimService = ClaimExtractionService(store, claims, commit, ClaimantResolver())
        val report = claimService.run(ana, uid, "structure-1", StructuredClaimModel(), expectModel = null)
        assertEquals("complete", report.status)
        val byQuote = claims.claims(ana, uid).associateBy { it.quote }
        val c38 = byQuote.getValue("SCENARIO A > BOUNDARY CHANGES: 38")
        assertEquals("SCENARIO A", c38.place); assertEquals("BOUNDARY CHANGES", c38.subject); assertEquals("NUMBER", c38.kind)
        assertTrue(byQuote.keys.none { it.startsWith("SCENARIO B > BOUNDARY CHANGES") })
        assertEquals(report.claimsEstablished, VisionReading.entries(store.chunks(ana, uid).single { it.kind == VisionReading.CHUNK_KIND }).size)

        // A later text run neither reads the vision chunk nor questions its claims; a structure run never questions text claims.
        val textModel = FakeClaimModel { ch, _ -> if (ch.kind == VisionReading.CHUNK_KIND) error("must not read") else emptyList<PassClaim>() }
        val asText = object : app.alfrd.engram.claims.ClaimPassModel by textModel {
            override fun reads(chunk: app.alfrd.engram.db.ChunkRecord) = chunk.kind != VisionReading.CHUNK_KIND
        }
        val text = claimService.run(ana, uid, "text-1", asText)
        assertEquals(0, text.claimsRechecking)
        assertEquals(setOf("Settled"), claims.claims(ana, uid).map { it.checkState }.toSet())
        assertFalse(LocalClaimModel(LocalLlmClient("http://127.0.0.1:1/v1")).reads(store.chunks(ana, uid).single { it.kind == VisionReading.CHUNK_KIND }))
        assertTrue(StructuredClaimModel().reads(store.chunks(ana, uid).single { it.kind == VisionReading.CHUNK_KIND }))
        assertFalse(StructuredClaimModel().reads(store.chunks(ana, uid).first { it.kind != VisionReading.CHUNK_KIND }))
    }
}
