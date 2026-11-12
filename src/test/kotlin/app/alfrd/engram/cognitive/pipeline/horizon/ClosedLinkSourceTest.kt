package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.cognitive.pipeline.HorizonItemsRenderer
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import java.util.UUID

/**
 * A relevance link whose source item is done or dropped is not new evidence about its target, so it no longer surfaces the target ("an automatic relevance
 * link surfaced this again, separate from: <a closed task>"). The filter is on read: nothing is written, and a link from an open item still surfaces.
 */
class ClosedLinkSourceTest {
    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var graph: HorizonGraphStore
    private lateinit var assembler: HorizonAssembler
    private lateinit var path: String
    private lateinit var user: String

    @BeforeEach
    fun setUp() {
        path = "./data/test-closed-link-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        structure = ArcadeHorizonStructureStore(db)
        graph = ArcadeHorizonGraphStore(db)
        assembler = ArcadeHorizonAssembler(db, zone = ZoneOffset.UTC)
        user = "closed-link-${UUID.randomUUID()}@example.test"
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "c"); set("email", user)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(path).deleteRecursively()
    }

    private fun task(text: String) = runBlocking { structure.createItem(user, 1, ItemKind.TASK, text)!! }
    private fun fact(text: String) = runBlocking { structure.createItem(user, 1, ItemKind.FACT, text)!! }
    private fun link(from: ItemRecord, to: ItemRecord, cycle: Long) = runBlocking { assertTrue(graph.markRelevant(user, cycle, from.uid, to.uid)) }
    private fun state(item: ItemRecord, state: WorkState) = runBlocking { structure.setState(user, 2, item.uid, state) }
    private fun horizon(cycle: Long): ContextHorizon =
        (runBlocking { assembler.assemble(user, cycle) } as AssembleOutcome.Assembled).horizon

    private fun surfacing(h: ContextHorizon, item: ItemRecord) = h.items.first { it.sourceRefs.first().phraseUid == item.uid }.surfacing
    private fun line(h: ContextHorizon, item: ItemRecord) =
        HorizonItemsRenderer.render(h, ZoneOffset.UTC).map { it.renderedLine }.first { it.contains("\"${item.text}\"") }

    @Test
    fun `an open item is not surfaced by a link whose only source is done`() {
        val target = task("Review the Williams safety triggers")
        val source = task("Order the Williams safety triggers")
        link(source, target, 5)
        assertTrue(surfacing(horizon(5), target) is SurfacingReason.ActiveReactivation, "while the source is open, the link surfaces it")
        state(source, WorkState.DONE)
        val after = horizon(5)
        assertFalse(surfacing(after, target) is SurfacingReason.ActiveReactivation, "once the source is done it does not")
        assertFalse("separate from" in line(after, target), line(after, target))
    }

    @Test
    fun `a link from an open source still surfaces its target, with the source named`() {
        val target = task("Review the Williams safety triggers")
        val source = task("Order the Williams safety triggers")
        link(source, target, 5)
        val h = horizon(5)
        val info = (surfacing(h, target) as SurfacingReason.ActiveReactivation).info
        assertEquals(source.uid, info.triggeringPhraseUid)
        assertTrue("separate from: \"Order the Williams safety triggers\"" in line(h, target), line(h, target))
    }

    @Test
    fun `an item closed after it was linked stops surfacing from that link, and reopened it surfaces again`() {
        val target = task("Review the Williams safety triggers")
        val source = task("Order the Williams safety triggers")
        link(source, target, 5)
        assertTrue(surfacing(horizon(5), target) is SurfacingReason.ActiveReactivation)
        state(source, WorkState.DROPPED)
        assertFalse(surfacing(horizon(5), target) is SurfacingReason.ActiveReactivation, "dropped after the link: gone")
        state(source, WorkState.OPEN)
        assertTrue(surfacing(horizon(5), target) is SurfacingReason.ActiveReactivation, "the edge was never touched, so reopening brings the link back")
    }

    @Test
    fun `done and dropped are both skipped, and an older link from an open item is not masked by newer closed ones`() {
        val target = task("Review the Williams safety triggers")
        val open = task("Check the Williams safety triggers wiring")
        val done = task("Finish the Williams safety triggers survey")
        val dropped = task("Drop the Williams safety triggers rework")
        // The open source linked first (older); the two that were closed linked later (newer), so a newest-first query meets them first.
        link(open, target, 4); link(done, target, 5); link(dropped, target, 5)
        state(done, WorkState.DONE); state(dropped, WorkState.DROPPED)
        val info = (surfacing(horizon(6), target) as SurfacingReason.ActiveReactivation).info
        assertEquals(open.uid, info.triggeringPhraseUid, "the open source is the one named, not a closed one")

        // With the open source gone too, nothing surfaces it.
        state(open, WorkState.DONE)
        assertFalse(surfacing(horizon(6), target) is SurfacingReason.ActiveReactivation)
    }

    @Test
    fun `closed sources do not use up the query's limit, so an open one among many is still found`() {
        val target = task("Review the Williams safety triggers")
        val open = task("Check the Williams safety triggers wiring")
        link(open, target, 4)
        // More than the query's window (the pool limit is several dozen rows), all newer than the open source's link.
        (1..80).forEach { i ->
            val closed = task("Closed Williams safety triggers step $i")
            link(closed, target, 5)
            state(closed, WorkState.DONE)
        }
        // Filtered in the query, the closed rows never fill the window; filtered afterwards, the open one's older row would have been cut off.
        val info = (surfacing(horizon(6), target) as SurfacingReason.ActiveReactivation).info
        assertEquals(open.uid, info.triggeringPhraseUid, "the source named is the open one, not one of the 80 closed ones")
    }

    @Test
    fun `a plain fact has no status to close, so a link from it still surfaces`() {
        val target = task("Review the Williams safety triggers")
        val source = fact("The Williams safety triggers were replaced in March")
        link(source, target, 5)
        assertTrue(surfacing(horizon(5), target) is SurfacingReason.ActiveReactivation)
    }

    @Test
    fun `twenty closed items sharing words with an open one leave its rendering unchanged`() {
        val target = task("Review the Williams safety triggers")
        val before = line(horizon(5), target)
        val closed = (1..20).map { i -> task("Williams safety triggers task $i").also { link(it, target, 5); state(it, WorkState.DONE) } }
        assertEquals(20, closed.size)
        val after = horizon(5)
        assertEquals(before, line(after, target), "the open item reads exactly as it did before any link existed")
        assertFalse("separate from" in line(after, target))
        assertFalse(after.items.any { it.sourceRefs.first().phraseUid in closed.map { c -> c.uid } }, "and the closed ones are not in view")
    }
}
