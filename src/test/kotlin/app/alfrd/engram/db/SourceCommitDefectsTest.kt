package app.alfrd.engram.db

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.CommitFailedException
import app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.PerUserCycleLock
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.pipeline.horizon.SourceItemDraft
import app.alfrd.engram.cognitive.pipeline.horizon.withHorizonWriteLock
import com.arcadedb.database.Database
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Four defects found in review of the Source and chunk commit, each pinned by a test that failed before its fix:
 * a matched duplicate left no key behind (so a replay allocated a cycle, or made a second item); the key came from the
 * item's position, not its content; two commits for one user ran without the per-user cycle lock; and a failed cycle
 * allocation was papered over with cycle 0.
 */
class SourceCommitDefectsTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private lateinit var structure: ArcadeHorizonStructureStore
    private val ana = "debug+defects-a@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-defects-test-").toFile()
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
    private fun cycleSeq(): Long = db.query("sql", "SELECT lastCycleSeq FROM User WHERE email = :e", mapOf("e" to ana)).use { (it.next().getProperty<Number>("lastCycleSeq") ?: 0).toLong() }
    private fun counts() = listOf("Source", "Chunk", "Phrase", "Task", "Event", "ASSERTS", "QUOTES", "CONTAINS").associateWith { n(it) }

    private val pageA = SourceInput("notion_page", "page-a", "Plan", "# Plan\nBook the venue for the conference by Friday.")
    private val taskA = SourceItemDraft(ItemKind.TASK, "Book the conference venue", "Book the venue for the conference by Friday.", 0)
    private val mailB = SourceInput("email_message", "<m9@x>", "Reminder", "Reminder: book the venue for the conference by Friday please.", threadId = "t")
    private val taskB = SourceItemDraft(ItemKind.TASK, "Book the venue for the conference", "book the venue for the conference by Friday", 0, claimant = "Ana")

    // ── 1. A matched duplicate leaves its key behind ─────────────────────────

    private fun matchedDuplicate(): String = runBlocking {
        service().commit(ana, pageA, listOf(taskA))
        val b = service().commit(ana, mailB, listOf(taskB))
        assertEquals(1, n("Task"), "the second source's task matched the first")
        b.items.single().uid!!
    }

    @Test
    fun `replaying a commit that matched an existing task is a no-op and allocates no cycle`() = runBlocking<Unit> {
        matchedDuplicate()
        val before = counts(); val seq = cycleSeq()
        val again = service().commit(ana, mailB, listOf(taskB))
        assertEquals(0, again.itemsCreated)
        assertEquals(before, counts())
        assertEquals(seq, cycleSeq(), "a replay allocated a cycle")
    }

    @Test
    fun `a replay after the matched task was resolved makes no second task`() = runBlocking<Unit> {
        val uid = matchedDuplicate()
        assertTrue(structure.resolve(ana, 1L, uid, resolved = true))
        val before = counts(); val seq = cycleSeq()
        val again = service().commit(ana, mailB, listOf(taskB))
        assertEquals(1, n("Task"))
        assertEquals(uid, again.items.single().uid)
        assertEquals(before, counts()); assertEquals(seq, cycleSeq())
    }

    @Test
    fun `a replay after the matched task fell outside the duplicate scan makes no second task`() = runBlocking<Unit> {
        val uid = matchedDuplicate()
        repeat(60 + 5 /* the applier scans the 60 most recent items */) { structure.createItem(ana, 1L, ItemKind.FACT, "filler fact number $it about nothing", opKey = "filler:$it") }
        val before = counts(); val seq = cycleSeq()
        val again = service().commit(ana, mailB, listOf(taskB))
        assertEquals(1, n("Task"))
        assertEquals(uid, again.items.single().uid)
        assertEquals(before, counts()); assertEquals(seq, cycleSeq())
    }

    // ── 2. The key comes from the content, and a known key must match ────────

    private val facts = SourceInput("document", "facts-doc", "Facts", "# Facts\nThe venue costs 3,000 euros.\n\nThe walkthrough is on 20 November.")
    private val f1 = SourceItemDraft(ItemKind.FACT, "Venue costs 3,000 euros", "The venue costs 3,000 euros.", 0)
    private val f2 = SourceItemDraft(ItemKind.FACT, "Walkthrough on 20 November", "The walkthrough is on 20 November.", 0)

    @Test
    fun `the same drafts in another order are the same items, not new ones`() = runBlocking<Unit> {
        val first = service().commit(ana, facts, listOf(f1, f2))
        val before = counts()
        val reordered = service().commit(ana, facts, listOf(f2, f1))
        assertEquals(0, reordered.itemsCreated)
        assertEquals(first.items[0].uid, reordered.items[1].uid)
        assertEquals(first.items[1].uid, reordered.items[0].uid)
        assertEquals(before, counts())
    }

    @Test
    fun `a changed draft is a new item and is never linked to the old one at its position`() = runBlocking<Unit> {
        val first = service().commit(ana, facts, listOf(f1, f2))
        val changed = f2.copy(text = "Walkthrough moved to 21 November", quote = "The walkthrough is on 20 November.")
        val second = service().commit(ana, facts, listOf(f1, changed))
        assertEquals(1, second.itemsCreated)
        assertEquals(first.items[0].uid, second.items[0].uid)
        assertNotEquals(first.items[1].uid, second.items[1].uid)
        assertEquals(3L, n("QUOTES"), "f1, the old f2 and the changed f2")
        assertEquals(3L, n("ASSERTS") - 0L, "three items, each asserted once")
    }

    @Test
    fun `an operation key already used by a different item is never returned for another`() = runBlocking<Unit> {
        val a = structure.createItem(ana, 1L, ItemKind.FACT, "The venue costs 3,000 euros", opKey = "k:1")!!
        assertEquals(a.uid, structure.createItem(ana, 2L, ItemKind.FACT, " The venue costs 3,000 euros ", opKey = "k:1")!!.uid, "the same item again")
        assertNull(structure.createItem(ana, 2L, ItemKind.FACT, "The venue costs 4,000 euros", opKey = "k:1"), "a different text under a known key")
        assertNull(structure.createItem(ana, 2L, ItemKind.TASK, "The venue costs 3,000 euros", opKey = "k:1"), "a different kind under a known key")
    }

    // ── 3. The per-user cycle lock ───────────────────────────────────────────

    @Test
    fun `a commit waits for the per-user cycle lock like every other cycle writer`() = runBlocking<Unit> {
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) { PerUserCycleLock.withLock(ana) { held.complete(Unit); release.await() } }
        held.await()
        val commit = async(Dispatchers.Default) { service().commit(ana, facts, listOf(f1)) }
        assertNull(withTimeoutOrNull(400) { commit.await() }, "the commit did not wait for the lock")
        assertEquals(0, n("Phrase"), "nothing was written while the lock was held")
        release.complete(Unit); holder.join()
        assertEquals(1, commit.await().itemsCreated)
    }

    @Test
    fun `two concurrent commits for one user both complete, with distinct cycles and no lost or doubled item`() = runBlocking<Unit> {
        val svc = service()
        val a = async(Dispatchers.Default) { svc.commit(ana, facts, listOf(f1, f2)) }
        val b = async(Dispatchers.Default) { svc.commit(ana, pageA, listOf(taskA)) }
        val c = async(Dispatchers.Default) { svc.commit(ana, facts, listOf(f1, f2)) }
        val all = listOf(a, b, c).awaitAll()
        assertEquals(3, all.sumOf { it.itemsCreated }, "f1, f2 and the task, each once")
        assertEquals(2, all.count { it.sourceCreated })
        assertEquals(3L, n("QUOTES"))
        val seqs = db.query("sql", "SELECT DISTINCT cycleSeq FROM ASSERTS WHERE cycleSeq IS NOT NULL AND @in.@type <> 'Chunk'").use { rs -> val l = ArrayList<Long>(); while (rs.hasNext()) l += rs.next().getProperty<Number>("cycleSeq").toLong(); l }
        assertTrue(seqs.none { it == 0L })
    }

    // ── 4. A failed cycle allocation fails the commit ────────────────────────

    private val nullCycles = object : CycleSequencer { override suspend fun allocateCycle(userEmail: String): Long? = null }
    private val brokenCycles = object : CycleSequencer { override suspend fun allocateCycle(userEmail: String): Long? = throw IllegalStateException("database down") }

    @Test
    fun `when no cycle can be allocated the commit fails and applies no item, never using cycle 0`() = runBlocking<Unit> {
        for (cycles in listOf(nullCycles, brokenCycles)) {
            val e = assertThrows<CommitFailedException> { service(cycles).commit(ana, facts, listOf(f1, f2)) }
            assertTrue(e.message!!.contains("cycle"), e.message)
            assertEquals(0, n("Phrase"), "no item applied")
            assertEquals(0, n("QUOTES"))
            assertEquals(0, db.query("sql", "SELECT FROM ASSERTS WHERE cycleSeq = 0").use { rs -> var c = 0; while (rs.hasNext()) { rs.next(); c++ }; c })
        }
        // A retry with a working sequencer then completes, with a real cycle.
        val ok = service().commit(ana, facts, listOf(f1, f2))
        assertEquals(2, ok.itemsCreated)
        assertTrue(cycleSeq() >= 1)
    }

    @Test
    fun `a Horizon lock timeout during allocation fails the commit the same way`() = runBlocking<Unit> {
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = Thread { withHorizonWriteLock(db, 5_000) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }.also { it.start() }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        try {
            assertThrows<CommitFailedException> { service(ArcadeCycleSequencer(db, lockTimeoutMs = 50)).commit(ana, facts, listOf(f1)) }
            assertEquals(0, n("Phrase"))
        } finally {
            release.countDown(); holder.join()
        }
    }

    @Test
    fun `a replay needs no cycle, so it still succeeds when allocation is down`() = runBlocking<Unit> {
        service().commit(ana, facts, listOf(f1, f2))
        val again = service(brokenCycles).commit(ana, facts, listOf(f1, f2))
        assertEquals(0, again.itemsCreated); assertEquals(2, again.itemsAlreadyThere)
    }

    // ── readMode ─────────────────────────────────────────────────────────────

    @Test
    fun `readMode must be full, partial or ocr`() {
        for (ok in listOf("full", "partial", "ocr")) store.ingest(ana, SourceInput("document", "rm-$ok", "t", "text $ok", readMode = ok))
        assertThrows<IllegalArgumentException> { store.ingest(ana, SourceInput("document", "rm-bad", "t", "text bad", readMode = "everything")) }
        assertThrows<IllegalArgumentException> { store.ingest(ana, SourceInput("document", "rm-blank", "t", "text blank", readMode = "")) }
        assertEquals(3, store.sources(ana).size)
    }
}
