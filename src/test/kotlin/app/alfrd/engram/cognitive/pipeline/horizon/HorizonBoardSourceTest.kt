package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import java.util.UUID

/** The board's reader against a real scratch graph: what it counts, what it leaves out, and "since last time" per channel. */
class HorizonBoardSourceTest {
    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var path: String
    private val day = 86_400_000L
    private lateinit var user: String

    @BeforeEach
    fun setUp() {
        path = "./data/test-board-source-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        structure = ArcadeHorizonStructureStore(dbManager.getDatabase())
        user = "board-${UUID.randomUUID()}@example.test"
        val db = dbManager.getDatabase()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "b"); set("email", user)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(path).deleteRecursively()
    }

    private fun source(clock: () -> Long = System::currentTimeMillis) = ArcadeBoardSource(dbManager.getDatabase(), HorizonPolicy(), ZoneOffset.UTC, clock, structure)
    private fun domain(name: String) = runBlocking { structure.createContainer(user, 1, ContainerKind.DOMAIN, name)!! }
    private fun project(name: String, domain: ContainerRecord? = null) = runBlocking { structure.createContainer(user, 1, ContainerKind.PROJECT, name, parentDomainUid = domain?.uid)!! }
    private fun task(text: String, project: ContainerRecord? = null, due: Long? = null, extras: ItemExtras = ItemExtras()) =
        runBlocking { structure.createItem(user, 1, ItemKind.TASK, text, dueAt = due, projectUid = project?.uid, extras = extras)!! }
    private fun sql(command: String, params: Map<String, Any?>) { val db = dbManager.getDatabase(); db.transaction { db.command("sql", command, params) } }

    @Test
    fun `the board counts what the zoomed view lists, and leaves out done, dropped, suppressed and silenced things`() {
        val home = domain("Home")
        val garden = project("Garden", home)
        val a = task("Order the soil", garden); val b = task("Plant beans", garden); val c = task("Old idea", garden); val d = task("Not wanted", garden); val e = task("Hidden", garden)
        runBlocking {
            structure.setState(user, 2, b.uid, WorkState.ACTIVE)
            structure.setState(user, 3, c.uid, WorkState.DONE)
            structure.setState(user, 4, d.uid, WorkState.DROPPED)
            structure.setAttention(user, 5, e.uid, AttentionDirective.SUPPRESSED)
        }
        val silenced = project("Silenced"); task("In a silenced project", silenced); runBlocking { structure.setAttention(user, 6, silenced.uid, AttentionDirective.SUPPRESSED) }
        val unfiled = task("Loose end")

        val data = runBlocking { source().read(user, "claude", 10) }
        assertNotNull(data); data!!
        assertEquals(listOf("Garden"), data.projects.map { it.name }, "the silenced project is out")
        val g = data.projects.single()
        assertEquals(setOf(a.uid, b.uid), g.items.map { it.uid }.toSet(), "done, dropped and suppressed items are out")
        assertEquals(WorkState.ACTIVE, g.items.first { it.uid == b.uid }.state)
        assertEquals(listOf(unfiled.uid), data.unfiled.map { it.uid })
        assertEquals(home.uid, g.areaUid)
        // The same project, zoomed into, lists the same items: the board's count is the view's count.
        val view = runBlocking { structure.itemsIn(user, garden.uid) }.filter { it.workState?.closed != true && it.attention != AttentionDirective.SUPPRESSED }
        assertEquals(view.size, g.items.size)
    }

    @Test
    fun `since last time is kept per channel and only moves when a channel is shown a board`() {
        val p = project("Garden"); val t = task("Order the soil", p)
        val s = source()
        val first = runBlocking { s.read(user, "claude", 10) }!!
        assertTrue(first.sinceIsLookback, "nothing on record yet, so it looks back")
        val mark = System.currentTimeMillis()
        s.markRead("claude", mark)
        Thread.sleep(5)
        runBlocking { structure.setState(user, 2, t.uid, WorkState.ACTIVE) }

        val claude = runBlocking { s.read(user, "claude", 10) }!!
        assertFalse(claude.sinceIsLookback)
        assertEquals(mark, claude.sinceAt)
        assertEquals(listOf("state"), claude.changes.map { it.field }, "the change after the mark is there")
        assertEquals("Order the soil", claude.changes.single().label)
        assertEquals(p.uid, claude.changes.single().containerUid)

        // Another channel has its own marker: it has read nothing, so it still looks back and sees the change too.
        val tick = runBlocking { s.read(user, "director-tick", 10) }!!
        assertTrue(tick.sinceIsLookback)
        assertEquals(1, tick.changes.size)

        // After the channel is shown the board, the change is not shown again.
        s.markRead("claude", System.currentTimeMillis() + 1)
        assertTrue(runBlocking { s.read(user, "claude", 10) }!!.changes.isEmpty())
        assertEquals(1, runBlocking { s.read(user, "director-tick", 10) }!!.changes.size, "the other channel's marker did not move")
    }

    @Test
    fun `a done item that changed is still named in since last time, and a new item is counted`() {
        val p = project("Garden"); val t = task("Order the soil", p)
        val s = source()
        Thread.sleep(5); s.markRead("claude", System.currentTimeMillis()); Thread.sleep(5)
        runBlocking { structure.setState(user, 2, t.uid, WorkState.DONE) }
        task("A brand new thing", p)
        val d = runBlocking { s.read(user, "claude", 10) }!!
        assertEquals("Order the soil", d.changes.single { it.field == "state" }.label, "looked up by uid, though it is no longer open")
        assertEquals(1, d.newItems, "only the item made after the mark counts")
    }

    @Test
    fun `a casual mention of an area reaches its projects, and a pinned area is read as pinned`() {
        val home = domain("Home"); val garden = project("Garden", home); project("Elsewhere")
        runBlocking {
            structure.setFocus(user, 2, home.uid, FocusMode.NUDGE, null, 0.8)
            structure.setAttention(user, 3, home.uid, AttentionDirective.PINNED)
        }
        val data = runBlocking { source().read(user, "claude", 3) }!!
        assertTrue(data.projects.first { it.uid == garden.uid }.focusWeight > 0.0, "the area was mentioned, so its project is warm")
        assertEquals(0.0, data.projects.first { it.name == "Elsewhere" }.focusWeight)
        assertTrue(data.areas.single().pinned)
    }

    @Test
    fun `a synthetic stale item reaches the board as one still-live question`() {
        val p = project("Williams")
        val stale = (1..3).map { task("Stale safety trigger $it", p, due = System.currentTimeMillis() - 60 * day) }
        stale.forEach { sql("UPDATE Task SET lastEvidenceAt = :e WHERE uid = :u", mapOf("e" to System.currentTimeMillis() - 45 * day, "u" to it.uid)) }
        task("A fresh one", p)
        val s = source()
        val data = runBlocking { s.read(user, "claude", 10) }!!
        val plan = BoardComposer(s.policy, s.zone).plan(data)
        val text = plan.render(plan.itemUids.withIndex().associate { (i, u) -> u to "i${i + 1}" } + (p.uid to "c1")).text
        assertEquals(1, text.lines().count { "Still live?" in it }, text)
        assertTrue("?3" in text && "4 open" in text, text)
    }

    // ── a casual mention fades the way the selector fades it ─────────────────

    private fun focusWeightOf(uid: String, cycle: Long): Double =
        runBlocking { source().read(user, "claude", cycle) }!!.projects.first { it.uid == uid }.focusWeight

    @Test
    fun `the board fades a nudge exactly as the selector does, for the same age`() {
        val policy = HorizonPolicy()
        val p = project("Mentioned once")
        runBlocking { structure.setFocus(user, 2, p.uid, FocusMode.NUDGE, null, 0.8) }
        for (age in listOf(0L, 1L, 3L, 5L, 6L, 7L, 12L, 25L)) {
            val cycle = 2 + age
            val board = focusWeightOf(p.uid, cycle)
            assertEquals(policy.nudgeStrength(0.8, age), board, 1e-12, "age $age: the board uses the policy's own fade")
            // And the selector, asked at the same cycle, scores that (empty) project with the same number.
            val selected = HorizonSelector(dbManager.getDatabase(), policy).select(user, cycle, System.currentTimeMillis(), emptyList())
            val score = selected.containers.firstOrNull { it.uid == p.uid }?.score ?: 0.0
            assertEquals(score, board, 1e-9, "age $age: the selector gives the same weight")
        }
        assertTrue(focusWeightOf(p.uid, 2) > 0.4, "fresh, it is strong")
        assertEquals(0.0, focusWeightOf(p.uid, 2 + 7), "seven cycles on it is below the floor and contributes nothing")
    }

    @Test
    fun `a project mentioned once is on the board while the mention is fresh and quiet again once it has faded`() {
        val p = project("Mentioned once"); (1..3).forEach { task("Quiet task $it", p) }
        project("Never mentioned").let { q -> (1..3).forEach { task("Other quiet task $it", q) } }
        runBlocking { structure.setFocus(user, 5, p.uid, FocusMode.NUDGE, null, 0.8) }
        val s = source()
        fun lines(cycle: Long): List<String> {
            val data = runBlocking { s.read(user, "claude", cycle) }!!
            val plan = BoardComposer(s.policy, s.zone).plan(data)
            return plan.render(plan.itemUids.withIndex().associate { (i, u) -> u to "i${i + 1}" } + data.projects.withIndex().associate { (i, q) -> q.uid to "c${i + 1}" })
                .text.lines().filter { it.startsWith("- ") }
        }
        assertTrue(lines(5).any { "Mentioned once" in it }, "fresh: it has a line")
        assertTrue(lines(6).any { "Mentioned once" in it }, "a turn later it still does")
        assertFalse(lines(5 + 30).any { "Mentioned once" in it }, "thirty turns on it is quiet again and rolled up: ${lines(35)}")
        assertFalse(lines(5 + 30).any { "Never mentioned" in it })
    }

    @Test
    fun `a pinned focus never fades, and reading the board writes nothing`() {
        val pinned = project("Pinned focus"); val nudged = project("Nudged")
        runBlocking {
            structure.setFocus(user, 3, pinned.uid, FocusMode.PINNED)
            structure.setFocus(user, 3, nudged.uid, FocusMode.NUDGE, null, 0.8)
        }
        val db = dbManager.getDatabase()
        fun count(sql: String) = db.query("sql", sql).use { it.next().getProperty<Number>("n").toLong() }
        val edgesBefore = count("SELECT count(*) AS n FROM FOCUSES_ON"); val changesBefore = count("SELECT count(*) AS n FROM ChangeRecord")
        val cycleBefore = runBlocking { structure.getFocus(user) }.map { it.targetUid to it.setCycleSeq }.toSet()

        assertEquals(1.0, focusWeightOf(pinned.uid, 3), "a pin counts at full weight")
        assertEquals(1.0, focusWeightOf(pinned.uid, 3 + 500), "and still does five hundred cycles later")
        assertEquals(0.0, focusWeightOf(nudged.uid, 3 + 500), "while a nudge of the same age is gone")

        assertEquals(edgesBefore, count("SELECT count(*) AS n FROM FOCUSES_ON"), "no focus edge was added or pruned")
        assertEquals(changesBefore, count("SELECT count(*) AS n FROM ChangeRecord"), "no change was recorded")
        assertEquals(cycleBefore, runBlocking { structure.getFocus(user) }.map { it.targetUid to it.setCycleSeq }.toSet(), "no edge was touched")
    }
}
