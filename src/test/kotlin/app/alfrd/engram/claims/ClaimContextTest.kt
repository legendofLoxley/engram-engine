package app.alfrd.engram.claims

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.db.ChunkDraft
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.ConceptStore
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceRecord
import app.alfrd.engram.db.SourceStore
import com.arcadedb.database.Database
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID

/** Claims in context: the passage a value comes from, the things it is about and its period, and things resolved at read time. */
class ClaimContextTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private lateinit var claims: ClaimStore
    private val ana = "debug+context-a@test.alfrd.internal"
    private val resolver = ClaimantResolver(mapOf("Portland Public Schools" to listOf("PPS", "the District")))

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-context-test-").toFile()
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
        return ClaimExtractionService(store, claims, commit, resolver)
    }

    private fun n(sql: String): Long = db.query("sql", sql).use { (it.next().getProperty<Number>("n")).toLong() }

    private fun ingest(vararg texts: String): SourceRecord = store.ingest(
        ana, SourceInput("document", "gdrive:clark", "School by School Analysis", texts.joinToString("\n"), chunks = texts.mapIndexed { i, t -> ChunkDraft("page ${i + 30}", t, page = i + 30) }),
    ).source

    private val sentence = "In 2023–24, 78% of Clark Elementary's students were students of color and 39% were multilingual learners."
    private val clark = ThingRef("Clark Elementary", "school", "PPS")

    private fun value(quote: String, subject: String, about: List<ThingRef> = listOf(clark), asOf: String? = "2023–24", passage: String? = sentence) =
        PassClaim(quote, null, subject, "number", passage = passage, about = about, asOf = asOf)

    @Test
    fun `two values of one sentence are two claims decomposed from one passage, each about the school and the year`() = runBlocking<Unit> {
        val s = ingest(sentence)
        val model = FakeClaimModel { _, _ -> listOf(value("78% of Clark Elementary's students were students of color", "students of color"), value("39% were multilingual learners", "multilingual learners")) }
        service().run(ana, s.uid, "ctx-1", model)

        val got = claims.claims(ana, s.uid).sortedBy { it.quote }
        assertEquals(2, got.size, got.toString())
        got.forEach { c ->
            assertEquals(sentence, c.passage, "the passage is the sentence as printed")
            assertEquals("2023–24", c.asOf)
            val thing = c.about.single()
            assertEquals("Clark Elementary", thing.name); assertEquals("school", thing.kind); assertEquals("PPS", thing.parent, "the parent as printed: aliases apply when read")
        }
        assertEquals(1L, n("SELECT count(*) AS n FROM Phrase WHERE role = 'passage'"), "one passage for both values")
        assertEquals(2L, n("SELECT count(*) AS n FROM FOLLOWS WHERE relation = 'decomposition'"))
        assertEquals(0L, n("SELECT count(*) AS n FROM ASSERTS WHERE @in.role = 'passage'"), "a passage is never asserted")

        // Again, under another run: nothing is added twice.
        service().run(ana, s.uid, "ctx-2", model)
        assertEquals(1L, n("SELECT count(*) AS n FROM Phrase WHERE role = 'passage'"))
        assertEquals(2L, n("SELECT count(*) AS n FROM FOLLOWS WHERE relation = 'decomposition'"))
        assertEquals(2L, n("SELECT count(*) AS n FROM ABOUT WHERE writer = 'claims'"))

        val candidates = ConceptStore(db, resolver).candidates(ana, "clark elementary")
        assertEquals(2, candidates.single().claims)
        assertEquals(listOf(s.uid), candidates.single().sourceUids)
    }

    @Test
    fun `context is kept only where both passes give it and the chunk prints it, and never decides whether a claim is established`() = runBlocking<Unit> {
        val s = ingest(sentence)
        val q = "78% of Clark Elementary's students were students of color"
        val model = FakeClaimModel { _, pass ->
            if (pass == 1) listOf(value(q, "x", about = listOf(clark, ThingRef("Lincoln High", "school")), asOf = "2023–24"))
            else listOf(value(q, "x", about = listOf(clark), asOf = null, passage = "In 2023–24, 78% of Clark Elementary's students"))
        }
        service().run(ana, s.uid, "ctx-3", model)
        val c = claims.claims(ana, s.uid).single()
        assertEquals(listOf("Clark Elementary"), c.about.map { it.name }, "a thing only one pass names is left off")
        assertNull(c.asOf, "a period only one pass gives is left off")
        assertNull(c.passage, "the passes gave different passages")

        val chunk = ChunkRecord("c", "s", 0, "p", sentence, "h")
        val ctx = ClaimAgreement.context(chunk, value(q, "x", about = listOf(ThingRef("Clark Elementary"), ThingRef("Rose City Park"))), value(q, "x", about = listOf(ThingRef("Clark Elementary"), ThingRef("Rose City Park"))))
        assertEquals(listOf("Clark Elementary"), ctx.about.map { it.name }, "a thing the chunk does not print is never kept, even when both passes name it")
        assertEquals(sentence, ctx.passage); assertEquals("2023–24", ctx.asOf)
    }

    @Test
    fun `a thing is identified by name, kind and parent, and a namesake is linked as possibly the same, never merged`() {
        val concepts = ConceptStore(db, resolver)
        // A Concept is seen by its owner through a claim about it.
        fun seen(c: app.alfrd.engram.db.ConceptRef): app.alfrd.engram.db.ConceptRef {
            val phrase = UUID.randomUUID().toString()
            db.transaction { db.newVertex("Phrase").apply { set("uid", phrase); set("text", "c"); set("ownerEmail", ana); set("claimSourceUid", "s"); save() } }
            concepts.linkAbout(ana, phrase, c.uid, "r"); return c
        }
        val school = seen(concepts.resolve(ana, "Clark", "school", "PPS"))
        assertEquals(school.uid, concepts.resolve(ana, "clark  ", "School", "pps").uid, "same name, kind and parent (case and spacing aside)")
        val person = seen(concepts.resolve(ana, "Clark", "person", null))
        assertNotEquals(school.uid, person.uid)
        val other = seen(concepts.resolve(ana, "Clark", "school", "Vancouver Public Schools"))
        assertNotEquals(school.uid, other.uid)

        // A Concept made in conversation is its own thing too, and the Horizon never reuses a document Concept.
        val chatUid = UUID.randomUUID().toString()
        db.transaction { db.newVertex("Concept").set("uid", chatUid).set("name", "Clark").set("type", "topic").set("normalizedName", "clark").save() }
        seen(app.alfrd.engram.db.ConceptRef(chatUid, "Clark", "topic"))
        val cands = concepts.candidates(ana, "Clark")
        assertEquals(4, cands.size)
        assertEquals(1, cands.count { it.fromChat })
        val twins = cands.single { it.uid == other.uid }.possiblySame
        assertTrue(twins.any { it.uid == school.uid && it.reason.contains("parents differ") }, twins.toString())
        assertTrue(cands.single { it.uid == person.uid }.possiblySame.any { it.uid == school.uid && it.reason.contains("kinds differ") })
        assertEquals("other", ConceptStore.kindOf("spaceship")); assertEquals("public body", ConceptStore.kindOf("District"))
    }

    @Test
    fun `a school named as the parent of another school in the same claim is not its parent`() = runBlocking<Unit> {
        val text = "In 2023–24, 78% of Harrison Park K–5's students were students of color, and Clark's were 12%."
        val s = ingest(text)
        val model = FakeClaimModel { _, _ ->
            listOf(
                PassClaim("78% of Harrison Park K–5's students were students of color", null, "x", "number", passage = text,
                    about = listOf(ThingRef("Harrison Park K–5", "school", "Clark"), ThingRef("Clark", "school")), asOf = "2023–24"),
            )
        }
        service().run(ana, s.uid, "ctx-parent", model)
        val c = claims.claims(ana, s.uid).single()
        val harrison = c.about.single { it.name == "Harrison Park K–5" }
        assertNull(harrison.parent, "Clark is a school, so it was the page's heading, not a parent; no issuer was set")
        assertEquals("school", harrison.kind)
    }

    @Test
    fun `chat never reaches an established document claim, through a thing or otherwise`() = runBlocking<Unit> {
        val s = ingest(sentence)
        service().run(ana, s.uid, "ctx-4", FakeClaimModel { _, _ -> listOf(value("78% of Clark Elementary's students were students of color", "x")) })
        val structure = ArcadeHorizonStructureStore(db)
        assertTrue(structure.itemsAbout(ana, "Clark Elementary").isEmpty(), "a document claim about Clark is not one of the user's items about Clark")
        assertEquals(1, ConceptStore(db, resolver).candidates(ana, "Clark Elementary").single().claims, "but read time sees it as evidence")
    }
}
