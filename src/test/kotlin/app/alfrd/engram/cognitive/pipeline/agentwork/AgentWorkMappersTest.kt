package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.NotionValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class AgentWorkMappersTest {

    private val set = AgentWorkConfig.load("deploy/config/notion-agent-worker.json").tableSet
    private lateinit var fake: FakeNotionApi
    private lateinit var t: AgentWorkTemplate

    private val sourcesDatabase = "https://www.notion.so/6e9ea25de7334d899d7a52783509ff54"
    private val howItWorks = "https://www.notion.so/3f6d0721d4e4814d9f91f290a1eea863"

    @BeforeEach
    fun setUp() {
        fake = FakeNotionApi()
        t = fake.installAgentWorkTemplate(set)
    }

    /** A goal shaped like the real "PPS: read before lock": prose Scope, Target Links made of page mentions, budget 10, cap 3. */
    private fun realisticGoal(status: String = "Draft") = runBlocking {
        fake.createPage(
            t.goals,
            mapOf(
                "Goal" to NotionValue.Title("PPS: read before lock"),
                "Goal Type" to NotionValue.Select("Work"),
                "Status" to NotionValue.Select(status),
                "Intent" to NotionValue.RichText("Read the primary PPS documents the locking protocol requires before answer standards are locked."),
                "Scope" to NotionValue.RichText("The 10 listed primary documents and the A/B comparison, and the PPS claims, sources and evidence rows they bear on. Nothing else in the ledger. No answer standards or locking."),
                "Metric" to NotionValue.RichText("Of these 10 items, how many are read in full."),
                "Done Criteria" to NotionValue.RichText("All 10 items done."),
                "Target Space" to NotionValue.Select("PPS Evidence Ledger"),
                "Target Links" to NotionValue.RichText(
                    "PPS Sources: Sources ; How this ledger works (locking protocol): How this ledger works ; Claims, Answer standards view",
                    links = listOf(sourcesDatabase, howItWorks),
                ),
                "Cycle Budget" to NotionValue.NumberValue(10.0),
                "Replan Cap" to NotionValue.NumberValue(3.0),
                "Deadline" to NotionValue.DateValue("2026-10-24"),
            ),
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Test
    fun `urls typed into text are found, without the punctuation that ends a sentence, and not repeated`() {
        assertEquals(
            listOf("https://example.org/a.pdf", "http://example.org/b?x=1"),
            urlsInText("See https://example.org/a.pdf, then (http://example.org/b?x=1). Again https://example.org/a.pdf!"),
        )
        assertEquals(emptyList<String>(), urlsInText("no links here, just example.org and ftp://nope"))
    }

    @Test
    fun `dates become moments, a plain date meaning the start of that day in UTC`() {
        assertEquals(Instant.parse("2026-10-12T14:00:00Z"), NotionValue.DateValue("2026-10-12T10:00:00.000-04:00").toInstant())
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), NotionValue.DateValue("2026-10-12").toInstant())
        assertNull(NotionValue.DateValue(null).toInstant())
        assertNull(NotionValue.DateValue("not a date").toInstant())
        assertNull((null as NotionValue.DateValue?).toInstant())
    }

    @Test
    fun `a moment is written in Notion's own format, seconds included even when they are zero`() {
        assertEquals("2026-10-12T10:02:00.000+00:00", dateTimeValue(Instant.parse("2026-10-12T10:02:00Z")).start)
        assertEquals("2026-10-12T10:02:30.250+00:00", dateTimeValue(Instant.parse("2026-10-12T10:02:30.250Z")).start)
        assertEquals("2026-10-12T00:00:00.000+00:00", dateTimeValue(Instant.parse("2026-10-12T00:00:00Z")).start)
    }

    @Test
    fun `a moment is written as a UTC datetime that reads back to the same moment`() {
        val at = Instant.parse("2026-10-12T10:15:30Z")
        val written = dateTimeValue(at)
        assertTrue(written.hasTime)
        assertEquals(at, written.toInstant())
        assertNull(dateTimeValue(null).start)
    }

    // ── Goal ─────────────────────────────────────────────────────────────────

    @Test
    fun `a goal is read the way the real one is laid out`() = runBlocking {
        val row = realisticGoal()
        val goal = GoalMapper.fromPage(fake.retrievePage(row.id))

        assertEquals("PPS: read before lock", goal.title)
        assertEquals("GOAL-1", goal.displayId)
        assertEquals(GoalType.WORK, goal.type)
        assertEquals(GoalStatus.DRAFT, goal.status)
        assertEquals(10, goal.cycleBudget)
        assertEquals(3, goal.replanCap)
        assertEquals("2026-10-24", goal.deadline)
        assertEquals("PPS Evidence Ledger", goal.targetSpace)
        assertTrue(goal.intent.startsWith("Read the primary PPS documents"))
    }

    @Test
    fun `the links of a goal are the hrefs its Target Links carry, because the text shows only titles`() = runBlocking {
        val goal = GoalMapper.fromPage(fake.retrievePage(realisticGoal().id))
        assertTrue(goal.targetLinksText.contains("PPS Sources: Sources"))
        assertEquals(listOf(sourcesDatabase, howItWorks), goal.targetLinks)
    }

    @Test
    fun `Scope is prose, so it contributes no links`() = runBlocking {
        val goal = GoalMapper.fromPage(fake.retrievePage(realisticGoal().id))
        assertTrue(goal.scope.startsWith("The 10 listed primary documents"))
        assertFalse(goal.targetLinks.any { goal.scope.contains(it) })
    }

    @Test
    fun `urls typed into Target Links are links too, after the attached ones`() = runBlocking {
        val row = runBlocking {
            fake.createPage(
                t.goals,
                mapOf("Goal" to NotionValue.Title("g"), "Target Links" to NotionValue.RichText("mention + typed https://example.org/memo.pdf.", links = listOf(sourcesDatabase))),
            )
        }
        assertEquals(listOf(sourcesDatabase, "https://example.org/memo.pdf"), GoalMapper.fromPage(fake.retrievePage(row.id)).targetLinks)
    }

    @Test
    fun `an unknown status or type is read as null rather than failing`() = runBlocking {
        val row = fake.createPage(t.goals, mapOf("Goal" to NotionValue.Title("bare")))
        val goal = GoalMapper.fromPage(fake.retrievePage(row.id))
        assertNull(goal.status)
        assertNull(goal.type)
        assertNull(goal.cycleBudget)
        assertEquals("", goal.scope)
        assertEquals(emptyList<String>(), goal.targetLinks)
    }

    // ── Cycle ────────────────────────────────────────────────────────────────

    private fun cycleRow(status: String = "Queued", extra: Map<String, NotionValue> = emptyMap()) = runBlocking {
        fake.createPage(t.cycles, mapOf("Cycle" to NotionValue.Title("Cycle A"), "Status" to NotionValue.Select(status)) + extra)
    }

    @Test
    fun `a cycle is read with its status, goal, budget and lease`() = runBlocking {
        val goal = realisticGoal()
        val row = cycleRow(
            "Running",
            mapOf(
                "Goal" to NotionValue.Relation(listOf(goal.id)),
                "Task Budget" to NotionValue.NumberValue(6.0),
                "Worker" to NotionValue.RichText("arx-1"),
                "Lease Until" to NotionValue.DateValue("2026-10-12T10:05:00.000+00:00"),
                "Started" to NotionValue.DateValue("2026-10-12T10:00:00.000+00:00"),
                "Summary" to NotionValue.RichText("in progress"),
            ),
        )
        val cycle = CycleMapper.fromPage(fake.retrievePage(row.id))

        assertEquals(CycleStatus.RUNNING, cycle.status)
        assertEquals("CYCLE-1", cycle.displayId)
        assertEquals(goal.id, cycle.goalPageId)
        assertEquals(1, cycle.goalCount)
        assertEquals(6, cycle.taskBudget)
        assertEquals("arx-1", cycle.worker)
        assertEquals(Instant.parse("2026-10-12T10:05:00Z"), cycle.leaseUntil)
        assertEquals("2026-10-12T10:00:00.000+00:00", cycle.started)
        assertEquals("in progress", cycle.summary)
    }

    @Test
    fun `a cycle with no goal or no lease reads as such`() = runBlocking {
        val cycle = CycleMapper.fromPage(fake.retrievePage(cycleRow().id))
        assertNull(cycle.goalPageId)
        assertEquals(0, cycle.goalCount)
        assertNull(cycle.leaseUntil)
        assertNull(cycle.taskBudget)
    }

    private val now = Instant.parse("2026-10-12T10:00:00Z")
    private val lease = Instant.parse("2026-10-12T10:02:00Z")

    @Test
    fun `claiming a fresh cycle makes it Running, sets the worker and lease, the start and the budget`() = runBlocking {
        val cycle = CycleMapper.fromPage(fake.retrievePage(cycleRow().id))
        val props = CycleMapper.claim(cycle, "arx-1", now, lease, goalBudget = 10)

        assertEquals(NotionValue.Select("Running"), props["Status"])
        assertEquals(NotionValue.RichText("arx-1"), props["Worker"])
        assertEquals(lease, (props["Lease Until"] as NotionValue.DateValue).toInstant())
        assertEquals(now, (props["Started"] as NotionValue.DateValue).toInstant())
        assertEquals(NotionValue.NumberValue(10.0), props["Task Budget"])
    }

    @Test
    fun `reclaiming keeps the original start and a budget the cycle set for itself`() = runBlocking {
        val row = cycleRow("Running", mapOf("Started" to NotionValue.DateValue("2026-10-12T09:00:00.000+00:00"), "Task Budget" to NotionValue.NumberValue(4.0)))
        val props = CycleMapper.claim(CycleMapper.fromPage(fake.retrievePage(row.id)), "arx-2", now, lease, goalBudget = 10)

        assertFalse("Started" in props, "a reclaim overwrote the original start")
        assertFalse("Task Budget" in props, "a reclaim overwrote the cycle's own budget")
        assertEquals(NotionValue.RichText("arx-2"), props["Worker"])
    }

    @Test
    fun `a heartbeat touches only the lease, and progress only the summary`() {
        assertEquals(setOf("Lease Until"), CycleMapper.heartbeat(lease).keys)
        assertEquals(setOf("Summary"), CycleMapper.progress("writing 3 tasks").keys)
    }

    @Test
    fun `finishing as Done records the end, zero tasks done, the summary, and releases the lease`() {
        val props = CycleMapper.finish(CycleStatus.DONE, now, "Dry run: 3 planned tasks.")
        assertEquals(NotionValue.Select("Done"), props["Status"])
        assertEquals(NotionValue.NumberValue(0.0), props["Tasks Done"])
        assertEquals(NotionValue.RichText("Dry run: 3 planned tasks."), props["Summary"])
        assertEquals(now, (props["Finished"] as NotionValue.DateValue).toInstant())
        assertNull((props["Lease Until"] as NotionValue.DateValue).start, "the lease must be cleared")
        assertFalse("Worker" in props, "who ran the cycle is kept")
    }

    @Test
    fun `a cycle can finish only as Done or Failed`() {
        CycleMapper.finish(CycleStatus.FAILED, now, "x")
        for (bad in listOf(CycleStatus.QUEUED, CycleStatus.RUNNING, CycleStatus.PAUSED, CycleStatus.CANCELLED)) {
            assertThrows(IllegalArgumentException::class.java) { CycleMapper.finish(bad, now, "x") }
        }
    }

    @Test
    fun `no cycle write carries a computed value`() = runBlocking {
        val cycle = CycleMapper.fromPage(fake.retrievePage(cycleRow().id))
        val all = CycleMapper.claim(cycle, "w", now, lease, 10) + CycleMapper.heartbeat(lease) + CycleMapper.progress("p") + CycleMapper.finish(CycleStatus.DONE, now, "s")
        assertTrue(all.values.none { it.isReadOnly })
    }

    // ── Agent Task ───────────────────────────────────────────────────────────

    private fun plannedTask(order: Int = 1, links: List<String> = listOf("https://www.notion.so/aaaaaaaaaaaa4aaa8aaa000000000001")) =
        PlannedTask(order, "Read the Oct 6 Board memo", TaskType.READ, "Read it in full and record findings for each required claim.", links)

    @Test
    fun `an Op key names the cycle, plan version and order`() = runBlocking {
        val cycle = CycleMapper.fromPage(fake.retrievePage(cycleRow().id))
        assertEquals("cycle:CYCLE-1:v1:o3", AgentTaskMapper.opKey(cycle, 1, 3))
        assertEquals("cycle:CYCLE-1:v2:o1", AgentTaskMapper.opKey(cycle, 2, 1))
    }

    @Test
    fun `a cycle without an id cannot key its tasks`() {
        val noId = Cycle("p", "t", null, null, null, 0, null, null, "", null, null, "", Instant.EPOCH, Instant.EPOCH)
        assertThrows(IllegalStateException::class.java) { AgentTaskMapper.opKey(noId, 1, 1) }
    }

    @Test
    fun `a planned task becomes a Planned row of version 1 linked to its cycle, with its links one per line`() = runBlocking {
        val cycle = CycleMapper.fromPage(fake.retrievePage(cycleRow().id))
        val props = AgentTaskMapper.createProperties(plannedTask(links = listOf("https://a.example/1", "https://a.example/2")), cycle, 1)

        assertEquals(NotionValue.Title("Read the Oct 6 Board memo"), props["Task"])
        assertEquals(NotionValue.Select("Read"), props["Type"])
        assertEquals(NotionValue.NumberValue(1.0), props["Order"])
        assertEquals(NotionValue.Select("Planned"), props["Status"])
        assertEquals(NotionValue.NumberValue(1.0), props["Plan Version"])
        assertEquals(NotionValue.RichText("https://a.example/1\nhttps://a.example/2"), props["Target Links"])
        assertEquals(NotionValue.Relation(listOf(cycle.pageId)), props["Cycle"])
        assertFalse("Op key" in props, "the Op key is added by the idempotent creator, once")
        assertTrue(props.values.none { it.isReadOnly })
    }

    @Test
    fun `a task row survives a write and a read`() = runBlocking {
        val cycleRow = cycleRow()
        val api = AllowlistedNotionApi(fake, set)
        // The guard only lets a relation point at a page it has seen: the Director reads the cycle through it first.
        val cycle = CycleMapper.fromPage(api.retrievePage(cycleRow.id))
        val created = api.createPage(t.tasks, AgentTaskMapper.createProperties(plannedTask(order = 2), cycle, 1) + ("Op key" to NotionValue.RichText(AgentTaskMapper.opKey(cycle, 1, 2))))

        val task = AgentTaskMapper.fromPage(fake.retrievePage(created.id))
        assertEquals("Read the Oct 6 Board memo", task.title)
        assertEquals(TaskType.READ, task.type)
        assertEquals(TaskStatus.PLANNED, task.status)
        assertEquals(2, task.order)
        assertEquals(1, task.planVersion)
        assertEquals("cycle:CYCLE-1:v1:o2", task.opKey)
        assertEquals(listOf(cycleRow.id), task.cyclePageIds)
        assertEquals("TASK-1", task.displayId)
        assertEquals(listOf(created.id), fake.page(cycleRow.id).relation("Tasks"))
    }

    @Test
    fun `a very long title is shortened to one line`() = runBlocking {
        val cycle = CycleMapper.fromPage(fake.retrievePage(cycleRow().id))
        val props = AgentTaskMapper.createProperties(plannedTask().copy(title = "line one\nline two ".repeat(300)), cycle, 1)
        val title = (props["Task"] as NotionValue.Title).text
        assertFalse(title.contains('\n'))
        assertTrue(title.length <= 2000)
    }

    // ── Escalation ───────────────────────────────────────────────────────────

    @Test
    fun `an escalation is read with its resolution and what it belongs to`() = runBlocking {
        val goal = realisticGoal()
        val row = fake.createPage(
            t.escalations,
            mapOf(
                "Escalation" to NotionValue.Title("Check the A/B boxes"),
                "Kind" to NotionValue.Select("Error found"),
                "Severity" to NotionValue.Select("Digest"),
                "Status" to NotionValue.Select("Resolved"),
                "Ask" to NotionValue.RichText("Which version is right?"),
                "Resolution" to NotionValue.RichText("Version B. Use it."),
                "Goal" to NotionValue.Relation(listOf(goal.id)),
            ),
        )
        val escalation = EscalationMapper.fromPage(fake.retrievePage(row.id))
        assertEquals("Version B. Use it.", escalation.resolution)
        assertEquals("Error found", escalation.kind)
        assertEquals("Resolved", escalation.status)
        assertEquals("Which version is right?", escalation.ask)
        assertEquals(listOf(goal.id), escalation.goalPageIds)
        assertEquals("ESC-1", escalation.displayId)
    }
}
