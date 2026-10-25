package app.alfrd.engram.claims

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.db.ChunkDraft
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceRecord
import app.alfrd.engram.db.SourceStore
import com.arcadedb.database.Database
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** One fact, one claim, however many readers saw it: the vision reader's claims and the text reader's are linked, and disagreement is kept visible. */
class ClaimLinkTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private lateinit var claims: ClaimStore
    private val ana = "debug+link-a@test.alfrd.internal"
    private var n = 0

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-link-test-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana)
        store = SourceStore(db)
        claims = ClaimStore(db)
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private fun service(): ClaimExtractionService {
        val structure = ArcadeHorizonStructureStore(db)
        val commit = SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db))
        return ClaimExtractionService(store, claims, commit, ClaimantResolver())
    }

    /** A model that reads only one chunk of the Source, named for the reader it stands for. */
    private class OneChunk(private val inner: FakeClaimModel, private val ordinal: Int) : ClaimPassModel by inner {
        override fun reads(chunk: ChunkRecord) = chunk.ordinal == ordinal
    }

    private fun claim(quote: String, subject: String, about: List<ThingRef>, asOf: String?) =
        PassClaim(quote, null, subject, "NUMBER", about = about, asOf = asOf)

    private data class Result(val source: SourceRecord, val report: LinkReport, val all: List<ClaimItem>)

    /** Both readers read the same page: the text reader chunk 0, the structure reader chunk 1. Linking runs when the second finishes. */
    private fun readBoth(textChunk: String, text: PassClaim, structureChunk: String, structure: PassClaim): Result = runBlocking {
        val s = store.ingest(
            ana, SourceInput("document", "gdrive:link-${n++}", "Report", "$textChunk\n$structureChunk", chunks = listOf(
                ChunkDraft("pdf p. 2", textChunk, page = 2), ChunkDraft("pdf p. 2, vision reading", structureChunk, page = 2),
            )),
        ).source
        val textModel = FakeClaimModel { _, _ -> listOf(text) }.also { it.promptVersion = "claims-v5" }
        val structureModel = FakeClaimModel { _, _ -> listOf(structure) }.also { it.promptVersion = "claims-from-structure-v7" }
        service().run(ana, s.uid, "t-${n}", OneChunk(textModel, 0))
        service().run(ana, s.uid, "s-${n}", OneChunk(structureModel, 1))
        Result(s, claims.linkReaders(store, ana, s.uid, "again-$n"), claims.claims(ana, s.uid))
    }

    private val clark = listOf(ThingRef("Clark Elementary", "school", "PPS"))

    @Test
    fun `two readers that state the same fact are linked, both stay, and both stay Settled`() {
        val r = readBoth(
            "In 2023–24, 78% of Clark Elementary's students were students of color.",
            claim("78% of Clark Elementary's students were students of color", "students of color", clark, "2023–24"),
            "Clark Elementary (2023–24) > students of color: 78%",
            claim("Clark Elementary (2023–24) > students of color: 78%", "students of color", clark, "2023–24"),
        )
        assertEquals(2, r.all.size, "nothing was added or removed")
        val s = r.all.single { LinkReportKey.structure(it) }; val t = r.all.single { !LinkReportKey.structure(it) }
        assertEquals(listOf(t.uid), s.corroboratedBy); assertEquals(listOf(s.uid), t.corroboratedBy)
        assertTrue(r.all.all { it.checkState == "Settled" && it.conflictsWith.isEmpty() })
        // The link was made when the second reader finished, so this later call only finds it.
        assertEquals(1, r.report.corroborated); assertEquals(0, r.report.conflicts)
        assertEquals(0, r.report.newLinks, "the second reader's run already linked them; calling again writes nothing")
        assertEquals(0, r.report.structureOnly); assertEquals(0, r.report.textOnly)
        assertEquals(1L, db.query("sql", "SELECT count(*) AS n FROM CORROBORATES").use { it.next().getProperty<Number>("n").toLong() }, "stored once, readable from both claims")
    }

    @Test
    fun `different schools named for the same value disagree, and both claims go to Re-checking with nothing deleted`() {
        val r = readBoth(
            "In 2023–24, 78% of Clark Elementary's students were students of color.",
            claim("78% of Clark Elementary's students were students of color", "students of color", clark, "2023–24"),
            "Harrison Park > students of color: 78%",
            claim("Harrison Park > students of color: 78%", "students of color", listOf(ThingRef("Harrison Park", "school")), null),
        )
        assertEquals(2, r.all.size)
        assertEquals(1, r.report.conflicts)
        assertTrue(r.report.conflictReasons.single().contains("different schools"), r.report.conflictReasons.toString())
        assertTrue(r.all.all { it.checkState == "Re-checking" && it.conflictsWith.size == 1 && it.corroboratedBy.isEmpty() }, r.all.toString())
    }

    @Test
    fun `different periods for the same fact disagree`() {
        val r = readBoth(
            "In 2023–24, 78% of Clark Elementary's students were students of color.",
            claim("78% of Clark Elementary's students were students of color", "students of color", clark, "2023–24"),
            "Clark Elementary (2024–25) > students of color: 78%",
            claim("Clark Elementary (2024–25) > students of color: 78%", "students of color", clark, "2024–25"),
        )
        assertEquals(1, r.report.conflicts)
        assertTrue(r.report.conflictReasons.single().contains("different periods"), r.report.conflictReasons.toString())
    }

    @Test
    fun `things of different kinds, or a missing thing or period, are not a conflict`() {
        val r = readBoth(
            "Under SCENARIO A, the District reports 80% of students attend a sustainable school.",
            claim("80% of students attend a sustainable school", "students attend a sustainable school", listOf(ThingRef("District", "public body")), null),
            "SCENARIO A > students attend a sustainable school: 80%",
            claim("SCENARIO A > students attend a sustainable school: 80%", "students attend a sustainable school", listOf(ThingRef("SCENARIO A", "scenario")), "2031–32"),
        )
        assertEquals(0, r.report.conflicts); assertEquals(1, r.report.corroborated)
        assertTrue(r.all.all { it.checkState == "Settled" })
    }

    @Test
    fun `a claim only one reader made, or whose value the other reader does not print, stands alone`() {
        val r = readBoth(
            "In 2023–24, 78% of Clark Elementary's students were students of color.",
            claim("78% of Clark Elementary's students were students of color", "students of color", clark, "2023–24"),
            "Clark Elementary (2023–24) > multilingual learners: 39%",
            claim("Clark Elementary (2023–24) > multilingual learners: 39%", "multilingual learners", clark, "2023–24"),
        )
        assertEquals(0, r.report.corroborated + r.report.conflicts)
        assertEquals(1, r.report.structureOnly); assertEquals(1, r.report.textOnly)
        assertTrue(r.all.all { it.corroboratedBy.isEmpty() && it.conflictsWith.isEmpty() && it.checkState == "Settled" })
    }

    @Test
    fun `a bare number that two unrelated claims share is not the same fact`() {
        val r = readBoth(
            "The Board met 11 times last year to hear public comment.",
            claim("The Board met 11 times last year", "board meetings", emptyList(), null),
            "SCENARIO B > schools closing: 11",
            claim("SCENARIO B > schools closing: 11", "schools closing", listOf(ThingRef("SCENARIO B", "scenario")), null),
        )
        assertEquals(0, r.report.corroborated + r.report.conflicts)
        assertEquals(1, r.report.structureOnly); assertEquals(1, r.report.textOnly)
    }

    @Test
    fun `claims of one reader are never linked to each other`() {
        assertTrue(ClaimLinker.isStructure("claims-from-structure-v7"))
        assertTrue(!ClaimLinker.isStructure("claims-v5") && !ClaimLinker.isStructure(null))
    }
}

private object LinkReportKey {
    /** A structure claim's quote is its line of the vision chunk (`heading > label: value`). */
    fun structure(c: ClaimItem) = c.quote.contains(" > ") || c.quote.contains("(20")
}
