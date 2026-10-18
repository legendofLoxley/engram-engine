package app.alfrd.engram.db

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.CommitFailedException
import app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.pipeline.horizon.SourceItemDraft
import com.arcadedb.database.Database
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.random.Random

/** The second review round on the Source commit: identity of the key, one link per draft key, merging, healing, and a randomized replay. */
class SourceCommitReplayTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private lateinit var structure: ArcadeHorizonStructureStore
    private val ana = "debug+replay-a@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-replay-test-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana)
        store = SourceStore(db)
        structure = ArcadeHorizonStructureStore(db)
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private fun service(cycles: CycleSequencer = ArcadeCycleSequencer(db)) =
        SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), cycles)

    private fun n(type: String): Long = db.query("sql", "SELECT count(*) AS n FROM `$type`").use { (it.next().getProperty<Number>("n")).toLong() }
    private fun cycleSeq(user: String = ana): Long = db.query("sql", "SELECT lastCycleSeq FROM User WHERE email = :e", mapOf("e" to user)).use { (it.next().getProperty<Number>("lastCycleSeq") ?: 0).toLong() }
    private fun counts(user: String = ana) = listOf("Source", "Chunk", "Phrase", "Task", "Event", "Question", "User", "ASSERTS", "QUOTES", "CONTAINS", "TRUSTS").associateWith { n(it) } + ("cycleSeq" to cycleSeq(user))

    // ── 1. The key includes the source ───────────────────────────────────────

    private val venueFact = SourceItemDraft(ItemKind.FACT, "Venue costs 3,000 euros", "The venue costs 3,000 euros.", 0)
    private fun doc(locator: String) = SourceInput("document", locator, locator, "# Costs\nThe venue costs 3,000 euros.")

    @Test
    fun `identical content under a second locator is its own source and its items are tied to its own chunks`() = runBlocking<Unit> {
        val a = service().commit(ana, doc("page-one"), listOf(venueFact))
        val b = service().commit(ana, doc("page-two"), listOf(venueFact))
        assertTrue(a.sourceCreated && b.sourceCreated)
        assertEquals(1, b.itemsCreated, "the second source's item is not mistaken for a replay of the first's")
        assertNotEquals(a.items.single().uid, b.items.single().uid)
        assertEquals(2L, n("QUOTES"))
        val trace = store.provenanceOf(ana, b.items.single().uid!!).single()
        assertEquals("page-two", trace.sourceLocator)
        assertEquals("page-one", store.provenanceOf(ana, a.items.single().uid!!).single().sourceLocator)
    }

    // ── 2. One link per draft key ────────────────────────────────────────────

    private val book = SourceInput("document", "plan", "Plan", "# Plan\nBook the venue for the conference by Friday.")
    private val bookQuote = "Book the venue for the conference by Friday."
    private val bookA = SourceItemDraft(ItemKind.TASK, "Book the conference venue", bookQuote, 0)
    private val bookB = SourceItemDraft(ItemKind.TASK, "Book the venue for conference", bookQuote, 0)

    private fun existingTask() = runBlocking { structure.createItem(ana, 1L, ItemKind.TASK, "Book the venue for the conference", opKey = "pre:1")!!.uid }

    @Test
    fun `two drafts from one chunk that match the same existing task both leave a key, so a replay is a no-op`() = runBlocking<Unit> {
        val taskUid = existingTask()
        val first = service().commit(ana, book, listOf(bookA, bookB))
        assertEquals(1, n("Task"), "both matched the task already on the list")
        assertEquals(listOf(taskUid, taskUid), first.items.map { it.uid })
        val before = counts()
        val again = service().commit(ana, book, listOf(bookB, bookA))
        assertEquals(0, again.itemsCreated)
        assertEquals(before, counts(), "a replay allocated a cycle or wrote something")
    }

    @Test
    fun `after that task is resolved a replay still makes no second task`() = runBlocking<Unit> {
        val taskUid = existingTask()
        service().commit(ana, book, listOf(bookA, bookB))
        assertTrue(structure.resolve(ana, 2L, taskUid, resolved = true))
        val before = counts()
        service().commit(ana, book, listOf(bookA, bookB))
        assertEquals(1, n("Task"))
        assertEquals(before, counts())
        // The task shows the chunk once, whichever drafts led to it.
        assertEquals(1, store.provenanceOf(ana, taskUid).size)
    }

    // ── The merge rule for drafts that normalize to the same item ────────────

    @Test
    fun `identical drafts collapse into one item, and drafts that differ in quote, claimant or date are refused`() = runBlocking<Unit> {
        val a = SourceItemDraft(ItemKind.FACT, "Venue costs 3,000 euros", "The venue costs 3,000 euros.", 0, claimant = "Ana")
        val same = a.copy(text = "  VENUE costs   3,000 euros ")
        val report = service().commit(ana, doc("p"), listOf(a, same))
        assertEquals(1, report.itemsCreated)
        assertEquals(report.items[0].uid, report.items[1].uid)
        assertEquals(listOf(true, false), report.items.map { it.created }, "only the first of the pair is the creation")

        val before = counts()
        for (clash in listOf(same.copy(quote = "The venue costs 3,000 euros"), same.copy(claimant = "Ben"), same.copy(dueAt = 1_800_000_000_000L))) {
            val e = assertThrows<IllegalArgumentException> { service().commit(ana, doc("q"), listOf(a, clash)) }
            assertTrue(e.message!!.contains("same item"), e.message)
        }
        assertEquals(before, counts(), "a refused commit writes nothing, not even the source")
    }

    // ── 503 text, healed items, normalized key check ─────────────────────────

    @Test
    fun `the failure message says what is and is not stored`() = runBlocking<Unit> {
        val none = object : CycleSequencer { override suspend fun allocateCycle(userEmail: String): Long? = null }
        val e = assertThrows<CommitFailedException> { service(none).commit(ana, doc("p"), listOf(venueFact)) }
        assertTrue(e.message!!.contains("no item was recorded"), e.message)
        assertTrue(e.message!!.contains("source and its chunks are stored"), e.message)
        assertEquals(1L, n("Source"), "the source is stored, as the message says")
        assertEquals(1L, n("Chunk"))
        assertEquals(0L, n("QUOTES"))
    }

    @Test
    fun `an item created earlier and linked now is reported as healed, not created`() = runBlocking<Unit> {
        val first = service().commit(ana, doc("p"), listOf(venueFact))
        db.transaction { db.command("sql", "DELETE FROM QUOTES") } // the link was lost after the item was written
        assertEquals(0L, n("QUOTES"))
        val seq = cycleSeq()
        val healed = service().commit(ana, doc("p"), listOf(venueFact))
        assertEquals(0, healed.itemsCreated)
        assertEquals(1, healed.itemsHealed)
        assertEquals(first.items.single().uid, healed.items.single().uid)
        assertTrue(healed.items.single().healed && healed.items.single().linked && !healed.items.single().created)
        assertEquals(1L, n("QUOTES"))
        assertEquals(seq, cycleSeq(), "healing needs no cycle")
        assertEquals(1L, n("Phrase"), "no second item")
    }

    @Test
    fun `a retry that differs only in case or spacing is the same item for createItem`() = runBlocking<Unit> {
        val a = structure.createItem(ana, 1L, ItemKind.FACT, "The venue costs 3,000 euros", opKey = "k:1")!!
        for (variant in listOf("the VENUE costs 3,000 euros", "  The   venue costs\n3,000 euros ")) {
            assertEquals(a.uid, structure.createItem(ana, 2L, ItemKind.FACT, variant, opKey = "k:1")?.uid, variant)
        }
    }

    // ── Randomized replay ────────────────────────────────────────────────────

    private data class Sentence(val text: String, val kind: ItemKind, val variants: List<String>, val claimant: String?, val dueAt: Long? = null)
    private val sentences = listOf(
        Sentence("Book the venue for the conference by Friday.", ItemKind.TASK, listOf("Book the conference venue", "Book the venue for the conference", "BOOK the  venue for the conference"), "Ana"),
        Sentence("Send the invoice to the supplier next week.", ItemKind.TASK, listOf("Send the supplier invoice", "Send the invoice to the supplier"), null),
        Sentence("The venue costs 3,000 euros.", ItemKind.FACT, listOf("Venue costs 3,000 euros", "venue COSTS 3,000 euros"), "Marta"),
        Sentence("Who confirms the catering?", ItemKind.QUESTION, listOf("Who confirms the catering?"), "Ana"),
        Sentence("The garden soil arrives on Tuesday.", ItemKind.EVENT, listOf("Garden soil arrives Tuesday", "Soil delivery on Tuesday"), null, 1_800_000_000_000L),
    )
    private val existing = listOf("Book the venue for the conference", "Send the invoice to the supplier", "Order the soil for the garden")

    @Test
    fun `randomized replay - after the first commit of a batch nothing new is created, however it is repeated`() = runBlocking<Unit> {
        var checks = 0
        for (seed in 1..24) {
            val rnd = Random(seed)
            val user = "debug+replay-rand-$seed@test.alfrd.internal"
            DebugConverseService.ensureSyntheticUser(db, user)
            existing.filter { rnd.nextBoolean() }.forEachIndexed { i, t -> structure.createItem(user, 1L, ItemKind.TASK, t, opKey = "pre:$i") }

            // 1 to 3 sources; two of them may carry identical content under different locators.
            val sharedPick = sentences.indices.filter { rnd.nextInt(3) > 0 }.ifEmpty { listOf(0) }
            val batches = (1..rnd.nextInt(1, 4)).map { s ->
                val pick = if (s <= 2 && rnd.nextBoolean()) sharedPick else sentences.indices.filter { rnd.nextBoolean() }.ifEmpty { listOf(rnd.nextInt(sentences.size)) }
                val text = pick.joinToString("\n\n") { "# S$it\n${sentences[it].text}" }
                val input = SourceInput(if (rnd.nextInt(4) == 0) "document" else "notion_page", "loc-$seed-${if (pick == sharedPick) "x$s" else "y$s"}", "src $s", text)
                // A chunk's ordinal is its place among the picked sentences.
                val drafts = pick.flatMapIndexed { chunk, idx ->
                    val st = sentences[idx]
                    val chosen = st.variants.shuffled(rnd).take(rnd.nextInt(1, st.variants.size + 1)).let { if (rnd.nextBoolean()) it + it.first() else it }
                    chosen.map { SourceItemDraft(st.kind, it, st.text, chunk, st.claimant, st.dueAt) }
                }
                input to drafts
            }

            for ((input, drafts) in batches.shuffled(rnd)) service().commit(user, input, drafts.shuffled(rnd))

            if (rnd.nextBoolean()) { // resolve every task
                db.query("sql", "SELECT uid FROM Task WHERE ownerEmail = :o", mapOf("o" to user)).use { rs -> val uids = ArrayList<String>(); while (rs.hasNext()) uids += rs.next().toMap()["uid"] as String; uids }
                    .forEach { structure.resolve(user, 3L, it, resolved = true) }
            }
            if (rnd.nextBoolean()) repeat(70) { structure.createItem(user, 4L, ItemKind.FACT, "unrelated filler $seed number $it", opKey = "fill:$it") }

            val settled = counts(user)
            repeat(3) { round ->
                for ((input, drafts) in batches.shuffled(rnd)) {
                    val r = service().commit(user, input, drafts.shuffled(rnd))
                    val where = "seed=$seed round=$round locator=${input.locator}"
                    assertTrue(!r.sourceCreated && r.chunksCreated == 0 && r.itemsCreated == 0 && r.itemsHealed == 0, "$where: $r")
                    assertEquals(settled, counts(user), where)
                    checks++
                }
            }
        }
        assertTrue(checks >= 24 * 3, "ran $checks replays")
        assertNotNull(checks)
    }
}
