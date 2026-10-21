package app.alfrd.engram.db

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.pipeline.horizon.SourceItemDraft
import com.arcadedb.database.Database
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
 * A later call that replays an item the earlier call recorded, but with a different quote, claimant or date, is refused: it must
 * not be answered with "already there" as if it were the same item. Quotes are checked against their chunk on every path,
 * including a replay. These are tests of the commit plumbing the claim extraction rests on, not of extraction itself.
 */
class SourceCommitChangedReplayTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private lateinit var structure: ArcadeHorizonStructureStore
    private val ana = "debug+changed-replay@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-changed-replay-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana)
        store = SourceStore(db)
        structure = ArcadeHorizonStructureStore(db)
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private fun service() = SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db))
    private fun n(type: String): Long = db.query("sql", "SELECT count(*) AS n FROM `$type`").use { (it.next().getProperty<Number>("n")).toLong() }

    private val input = SourceInput("document", "changed-replay", "Changed replay", "# Costs\nThe venue costs 3,000 euros. The board voted 5 to 2 on Tuesday.")
    private val fact = SourceItemDraft(ItemKind.FACT, "Venue cost", "The venue costs 3,000 euros.", 0, claimant = "Board")
    private val task = SourceItemDraft(ItemKind.TASK, "Pay the venue", "The venue costs 3,000 euros.", 0, claimant = "Board", dueAt = 1_000L)

    @Test
    fun `an identical replay is still accepted and adds nothing`() = runBlocking<Unit> {
        service().commit(ana, input, listOf(fact))
        val before = listOf("Phrase", "QUOTES", "Chunk", "Source").map { n(it) }
        val again = service().commit(ana, input, listOf(fact))
        assertEquals(0, again.itemsCreated)
        assertEquals(1, again.itemsAlreadyThere)
        assertEquals(before, listOf("Phrase", "QUOTES", "Chunk", "Source").map { n(it) })
    }

    @Test
    fun `a replay with a changed quote is refused and writes nothing`() = runBlocking<Unit> {
        service().commit(ana, input, listOf(fact))
        val before = listOf("Phrase", "QUOTES").map { n(it) }
        val e = assertThrows<IllegalArgumentException> { service().commit(ana, input, listOf(fact.copy(quote = "The venue costs"))) }
        assertTrue(e.message!!.contains("quote"), e.message)
        assertEquals(before, listOf("Phrase", "QUOTES").map { n(it) })
    }

    @Test
    fun `a replay with a changed claimant is refused`() = runBlocking<Unit> {
        service().commit(ana, input, listOf(fact))
        val e = assertThrows<IllegalArgumentException> { service().commit(ana, input, listOf(fact.copy(claimant = "Treasurer"))) }
        assertTrue(e.message!!.contains("claimant"), e.message)
        assertEquals(1L, n("QUOTES"))
    }

    @Test
    fun `a replay that drops or adds a claimant is refused`() = runBlocking<Unit> {
        service().commit(ana, input, listOf(fact))
        assertThrows<IllegalArgumentException> { service().commit(ana, input, listOf(fact.copy(claimant = null))) }
    }

    @Test
    fun `a replay with a changed due date is refused`() = runBlocking<Unit> {
        service().commit(ana, input, listOf(task))
        val e = assertThrows<IllegalArgumentException> { service().commit(ana, input, listOf(task.copy(dueAt = 2_000L))) }
        assertTrue(e.message!!.contains("date"), e.message)
        assertEquals(1L, n("Task"))
    }

    @Test
    fun `a replay of a task that matched one already on the list is accepted when the draft is the same`() = runBlocking<Unit> {
        val first = SourceItemDraft(ItemKind.TASK, "Book the conference room", "The venue costs 3,000 euros.", 0)
        service().commit(ana, input, listOf(first))
        val other = SourceInput("document", "second", "Second", "# Plan\nBook the conference room for the offsite.")
        val second = SourceItemDraft(ItemKind.TASK, "Book the conference room for the offsite", "Book the conference room for the offsite.", 0)
        val c = service().commit(ana, other, listOf(second))
        assertEquals(0, c.itemsCreated, "matched the task already on the list")
        val again = service().commit(ana, other, listOf(second))
        assertEquals(0, again.itemsCreated)
        assertTrue(again.items.single().applied)
    }

    @Test
    fun `a replay whose quote is not in the chunk is refused, not answered as already there`() = runBlocking<Unit> {
        service().commit(ana, input, listOf(fact))
        // The same key (kind, text, chunk) but a quote that is not in the chunk at all.
        val e = assertThrows<IllegalArgumentException> { service().commit(ana, input, listOf(fact.copy(quote = "Something the document never says."))) }
        assertTrue(e.message!!.contains("quote"), e.message)
    }

    @Test
    fun `a first commit whose quote is not in its chunk is still reported as not applied`() = runBlocking<Unit> {
        val r = service().commit(ana, input, listOf(fact.copy(quote = "Never said.")))
        assertFalse(r.items.single().applied)
        assertEquals(0L, n("Phrase"))
    }
}
