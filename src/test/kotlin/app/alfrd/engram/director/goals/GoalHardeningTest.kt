package app.alfrd.engram.director.goals

import app.alfrd.engram.cognitive.pipeline.horizon.*
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * The two Phase B review items that are about ranking, pinned: a link the Director wrote can lift an owner's item only by a bounded amount, and what the Director
 * wrote adds nothing to a project's pressure or to the owner's counts. Both are what stop one cycle from reordering the owner's Horizon.
 */
class GoalHardeningTest {
    // ── the link cap, through the real selector ──────────────────────────────

    private lateinit var dbManager: DatabaseManager
    private lateinit var structure: HorizonStructureStore
    private lateinit var applier: HorizonOperationApplier
    private lateinit var writer: DirectorWriter
    private lateinit var path: String
    private lateinit var user: String
    private val zone = ZoneOffset.UTC
    private var seq = 50L

    @BeforeEach
    fun setUp() {
        path = "./data/test-goal-hardening-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        structure = ArcadeHorizonStructureStore(db)
        val messages = ArcadeDirectorMessageStore(db)
        applier = HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), directorMessages = messages)
        writer = DirectorWriter(structure, applier, messages = messages)
        user = "hardening-${UUID.randomUUID()}@example.test"
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString()); set("username", "h"); set("email", user)
                set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(path).deleteRecursively()
    }

    private fun scores(policy: HorizonPolicy): Triple<Double, Double, Double> = runBlocking {
        val project = structure.createContainer(user, 1, ContainerKind.PROJECT, "Williams")!!
        val untouched = structure.createItem(user, 1, ItemKind.TASK, "Untouched owner task alpha", projectUid = project.uid)!!
        val byDirector = structure.createItem(user, 1, ItemKind.TASK, "Owner task bravo linked by the Director", projectUid = project.uid)!!
        val byOwner = structure.createItem(user, 1, ItemKind.TASK, "Owner task charlie linked by the owner", projectUid = project.uid)!!
        val goal = structure.createItem(user, 1, ItemKind.GOAL, "Review", projectUid = project.uid, extras = ItemExtras(doneWhen = "done"))!!
        structure.approveGoal(user, 2, goal.uid, "go"); structure.setState(user, 3, goal.uid, WorkState.ACTIVE)
        val made = writer.apply(user, goal.uid, 1, ++seq, listOf(RecordItem(ItemKind.TASK, "Director task delta, written this cycle")), zone).single()
        check(made.applied) { made.summary }
        check(writer.apply(user, goal.uid, 1, seq, listOf(LinkItems(made.resultUid!!, byDirector.uid, "delta concerns bravo")), zone).single().applied)
        check(applier.apply(user, seq, "owner-link", listOf(LinkItems(made.resultUid!!, byOwner.uid, "delta concerns charlie")), zone).single().applied)
        val horizon = (ArcadeHorizonAssembler(dbManager.getDatabase(), policy = policy).assemble(user, seq) as AssembleOutcome.Assembled).horizon
        fun score(text: String) = horizon.items.firstOrNull { it.text.text.contains(text) }?.score ?: 0.0
        Triple(score("alpha"), score("bravo"), score("charlie"))
    }

    @Test
    fun `a Director link adds nothing when its ceiling is zero while the same link from the owner still lifts`() {
        val (untouched, byDirector, byOwner) = scores(HorizonPolicy().copy(directorLinkMaxActivation = 0.0))
        assertEquals(untouched, byDirector, 1e-9, "a link the Director wrote does not lift the item it points at")
        assertTrue(byOwner > untouched, "the owner's link is not capped: $byOwner against $untouched")
    }

    @Test
    fun `a Director link lifts no more than the same link uncapped and the default ceiling is small`() {
        val capped = scores(HorizonPolicy())
        assertTrue(HorizonPolicy().directorLinkMaxActivation in 0.0..0.2, "the default ceiling is small")
        assertTrue(capped.second - capped.first <= capped.third - capped.first + 1e-9, "the Director's link lifts no more than the owner's: $capped")
    }

    // ── per-project pressure and the counts line ─────────────────────────────

    private val day = 86_400_000L
    private val now = ZonedDateTime.of(2026, 11, 11, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val policy = HorizonPolicy()

    private fun item(uid: String, text: String, dueInDays: Double?, hard: Boolean = false, byDirector: Boolean = false, state: WorkState = WorkState.OPEN) = BoardItem(
        uid = uid, text = text, kind = "task", state = state, dueAt = dueInDays?.let { now + (it * day).toLong() }, dueHard = hard, dueHasTime = false, boundToMoment = false,
        nudgeAt = null, waitingOn = null, pinned = false, evidenceAt = now - day, createdAt = now - 30 * day, byDirector = byDirector,
    )

    private fun data(projects: List<BoardProject>, changes: List<BoardChange> = emptyList()) =
        BoardData(now, now - day, false, (1..3).map { BoardArea("a$it", "Area $it", false, 0.0) }, projects, emptyList(), changes, 0)

    private fun render(d: BoardData): String {
        val plan = BoardComposer(policy, ZoneOffset.UTC).plan(d)
        val refs = LinkedHashMap<String, String>()
        (d.areas.map { it.uid } + d.projects.map { it.uid }).forEachIndexed { i, uid -> refs[uid] = "c${i + 1}" }
        plan.itemUids.forEachIndexed { i, uid -> refs[uid] = "i${i + 1}" }
        return plan.render(refs).text
    }

    private fun project(uid: String, name: String, vararg items: BoardItem) = BoardProject(uid, name, "a1", false, 0.0, null, items.toList())

    @Test
    fun `items the Director wrote do not count toward which project ranks first`() {
        // Alpha: one owner item that is only soft overdue (pressure 0.63), plus five Director items that are hard overdue (they would make it 0.9).
        val alpha = project("alpha", "Alpha", item("a0", "Owner soft overdue", -2.0), *(1..5).map { item("ad$it", "Director item $it", -3.0, hard = true, byDirector = true) }.toTypedArray())
        // Beta: one owner item due soon (pressure 0.7), which outranks Alpha's owner-only 0.63.
        val beta = project("beta", "Beta", item("b0", "Owner due soon", 1.0, hard = true))
        val text = render(data(listOf(alpha, beta)))
        assertTrue(text.indexOf("Beta") in 0 until text.indexOf("Alpha"), "Beta first, as it would be without the Director's items:\n$text")
    }

    @Test
    fun `the counts line shows the Director's items separately and keeps them out of the owner's numbers`() {
        val alpha = project("alpha", "Alpha", item("a0", "Owner soft overdue", -2.0), *(1..5).map { item("ad$it", "Director item $it", -3.0, hard = true, byDirector = true) }.toTypedArray())
        val text = render(data(listOf(alpha)))
        assertTrue(text.contains("1 open, +5 from the Director"), text)
        assertTrue(text.contains("!1"), "one urgent item: the owner's own")
        assertFalse(text.contains("!6"), text)
    }

    @Test
    fun `a project with only the Director's items overdue stays quiet`() {
        val gamma = project("gamma", "Gamma", item("g0", "Owner quiet task", 40.0), item("gd", "Director overdue", -3.0, hard = true, byDirector = true))
        val beta = project("beta", "Beta", item("b0", "Owner due soon", 1.0, hard = true))
        val text = render(data(listOf(gamma, beta)))
        assertFalse(text.contains("Gamma"), "no line for a project whose only pressure is the Director's:\n$text")
    }

    @Test
    fun `a change to something the Director wrote adds no pressure to its project`() {
        val delta = project("delta", "Delta", item("d0", "Owner quiet task", 40.0), item("dd", "Director item", 40.0, byDirector = true))
        val beta = project("beta", "Beta", item("b0", "Owner due soon", 1.0, hard = true))
        val change = BoardChange("dd", "Director item", "dd", "delta", "state", "open", "done", now - day / 2)
        assertFalse(render(data(listOf(delta, beta), listOf(change))).contains("Delta"), "a change to the Director's own item is not a change to the owner's world")
        val ownerChange = BoardChange("d0", "Owner quiet task", "d0", "delta", "state", "open", "done", now - day / 2)
        assertTrue(render(data(listOf(delta, beta), listOf(ownerChange))).contains("Delta"), "the same change to the owner's item still counts")
    }

    @Test
    fun `with no Director items the board is as it was`() {
        val alpha = project("alpha", "Alpha", item("a0", "Owner soft overdue", -2.0))
        val text = render(data(listOf(alpha)))
        assertTrue(text.contains("1 open"), text)
        assertFalse(text.contains("from the Director"))
    }

    // ── config names ─────────────────────────────────────────────────────────

    @Test
    fun `the runner's environment names are the documented ones`() {
        val c = GoalRunnerConfig.fromEnvironment(mapOf(
            "GOAL_RUNNER_ENABLED" to "true", "GOAL_RUNNER_IDLE_MINUTES" to "3", "GOAL_RUNNER_MAX_STEPS" to "4", "GOAL_RUNNER_REPLAN_CAP" to "1",
            "GOAL_RUNNER_PLAN_URL" to "http://127.0.0.1:9/v1/", "GOAL_RUNNER_STEP_URL" to "http://127.0.0.1:8/v1", "GOAL_RUNNER_KILL_FILE" to "/tmp/stop-x",
            "GOAL_RUNNER_MIN_FREE_RAM_MB" to "8000", "GOAL_RUNNER_DEFAULT_TOKENS" to "1234",
        ))
        assertTrue(c.enabled)
        assertEquals(3L, c.idleMinutes); assertEquals(4, c.maxSteps); assertEquals(1, c.replanCap)
        assertEquals("http://127.0.0.1:9/v1", c.planUrl); assertEquals("http://127.0.0.1:8/v1", c.stepUrl)
        assertEquals("/tmp/stop-x", c.killFile.toString()); assertEquals(8000L, c.minFreeRamMb); assertEquals(1234L, c.defaultTokens)
        // A nonsense value falls back to the default rather than turning a guard off.
        assertEquals(4096L, GoalRunnerConfig.fromEnvironment(mapOf("GOAL_RUNNER_MIN_FREE_RAM_MB" to "0")).minFreeRamMb)
    }
}
