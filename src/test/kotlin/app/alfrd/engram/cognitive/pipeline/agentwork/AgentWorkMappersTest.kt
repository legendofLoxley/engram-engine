package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.MentionKind
import app.alfrd.engram.notion.NotionMention
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

    private val sourcesDatabaseId = "6e9ea25d-e733-4d89-9d7a-52783509ff54"
    private val personaId = "3f1d0721-d4e4-81d5-88c6-f320f5fa25f8"
    private val sourcesMention = NotionMention(MentionKind.DATABASE, sourcesDatabaseId, "PPS Sources")
    private val personaMention = NotionMention(MentionKind.PAGE, personaId, "Persona")

    @BeforeEach
    fun setUp() {
        fake = FakeNotionApi()
        t = fake.installAgentWorkTemplate(set)
    }

    /** A goal shaped like the converged schema: a sentence, a check, a Scope of mentions with some prose around them, a budget and a cap. */
    private fun convergedGoal(status: String = "Draft", scope: NotionValue.RichText = scopeOf("Look only at", sourcesMention, personaMention)) = runBlocking {
        fake.createPage(
            t.goals,
            mapOf(
                "Goal" to NotionValue.Title("Reconcile Persona with what alfrd currently knows"),
                "Status" to NotionValue.Select(status),
                "Done when" to NotionValue.RichText("Every claim in Persona is marked supported, contradicted or unchecked."),
                "Scope" to scope,
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
    fun `a goal is read the way the converged schema lays it out`() = runBlocking {
        val goal = GoalMapper.fromPage(fake.retrievePage(convergedGoal().id))

        assertEquals("Reconcile Persona with what alfrd currently knows", goal.title)
        assertEquals("GOAL-1", goal.displayId)
        assertEquals(GoalStatus.DRAFT, goal.status)
        assertEquals("Every claim in Persona is marked supported, contradicted or unchecked.", goal.doneWhen)
        assertEquals(10, goal.cycleBudget)
        assertEquals(3, goal.replanCap)
        assertEquals("2026-10-24", goal.deadline)
        assertEquals(emptyList<String>(), goal.serves)
    }

    @Test
    fun `Serves is read as the Containers the goal is for, by id`() = runBlocking {
        val container = fake.createPage(t.containers, mapOf("Name" to NotionValue.Title("alfrd")))
        val row = fake.createPage(t.goals, mapOf("Goal" to NotionValue.Title("g"), "Serves" to NotionValue.Relation(listOf(container.id))))
        assertEquals(listOf(container.id), GoalMapper.fromPage(fake.retrievePage(row.id)).serves)
    }

    @Test
    fun `only the page and database mentions in Scope count, in order`() = runBlocking {
        val scope = GoalMapper.fromPage(fake.retrievePage(convergedGoal().id)).scope
        assertEquals(listOf(sourcesMention, personaMention), scope.mentions)
        assertTrue(scope.hasMentions)
    }

    @Test
    fun `prose in Scope is ignored and reported, not turned into scope`() = runBlocking {
        val scope = GoalMapper.fromPage(fake.retrievePage(convergedGoal().id)).scope
        assertEquals("Look only at", scope.proseExcerpt)
        assertEquals("Lookonlyat".length, scope.proseChars)

        val onlyProse = GoalScope.fromRichText(NotionValue.RichText("The 10 listed primary documents, nothing about locking."))
        assertFalse(onlyProse.hasMentions)
        assertTrue(onlyProse.proseChars > 0)
    }

    @Test
    fun `text made only of mentions has no prose to report`() {
        val scope = GoalScope.fromRichText(scopeOf("", sourcesMention, personaMention))
        assertEquals(0, scope.proseChars)
        assertEquals("", scope.proseExcerpt)
        assertEquals(emptyList<String>(), scope.ignoredLinks)
    }

    @Test
    fun `a hyperlink or a URL typed in Scope is not a mention and is reported, never counted`() {
        val scope = GoalScope.fromRichText(
            NotionValue.RichText(
                "PPS Sources see https://example.org/spec and https://www.notion.so/99999999999949998999999999999999",
                links = listOf("https://www.notion.so/99999999999949998999999999999999", sourcesDatabaseId.let { "https://www.notion.so/${it.replace("-", "")}" }),
                mentions = listOf(sourcesMention),
            ),
        )
        assertEquals(listOf(sourcesMention), scope.mentions)
        assertTrue("https://example.org/spec" in scope.ignoredLinks)
        assertTrue("https://www.notion.so/99999999999949998999999999999999" in scope.ignoredLinks, "a Notion link that is not a mention is still not scope")
        assertFalse(scope.ignoredLinks.any { it.contains(sourcesDatabaseId.replace("-", "")) }, "the mention's own href is not a separate link")
    }

    @Test
    fun `a missing or empty Scope is no scope at all`() = runBlocking {
        assertFalse(GoalScope.fromRichText(null).hasMentions)
        val bare = GoalMapper.fromPage(fake.retrievePage(fake.createPage(t.goals, mapOf("Goal" to NotionValue.Title("bare"))).id))
        assertFalse(bare.scope.hasMentions)
        assertEquals(0, bare.scope.proseChars)
    }

    @Test
    fun `the retired goal fields are not read, whatever they hold`() = runBlocking {
        val sentinel = "RETIRED-FIELD-SENTINEL"
        val row = fake.createPage(
            t.goals,
            mapOf(
                "Goal" to NotionValue.Title("g"), "Scope" to scopeOf("", sourcesMention),
                "Intent" to NotionValue.RichText(sentinel), "Metric" to NotionValue.RichText(sentinel), "Metric Note" to NotionValue.RichText(sentinel),
                "Target Links" to NotionValue.RichText(sentinel, links = listOf("https://www.notion.so/99999999999949998999999999999999")),
                "Target Space" to NotionValue.Select("PPS Evidence Ledger"), "Goal Type" to NotionValue.Select("Review"),
            ),
        )
        val goal = GoalMapper.fromPage(fake.retrievePage(row.id))
        assertFalse(goal.toString().contains(sentinel), "a retired field leaked into the goal: $goal")
        assertEquals(listOf(sourcesMention), goal.scope.mentions)
    }

    @Test
    fun `an unknown status is read as null rather than failing, and the design's own words are known too`() = runBlocking {
        val row = fake.createPage(t.goals, mapOf("Goal" to NotionValue.Title("bare")))
        val goal = GoalMapper.fromPage(fake.retrievePage(row.id))
        assertNull(goal.status)
        assertNull(goal.cycleBudget)
        assertEquals("", goal.doneWhen)
        assertEquals(GoalStatus.DONE, GoalStatus.fromLabel("Done"))
        assertEquals(GoalStatus.STOPPED, GoalStatus.fromLabel("Stopped"))
        assertEquals(GoalStatus.MET, GoalStatus.fromLabel("Met"))
    }

    // ── Cycle ────────────────────────────────────────────────────────────────

    private fun cycleRow(status: String = "Queued", extra: Map<String, NotionValue> = emptyMap()) = runBlocking {
        fake.createPage(t.cycles, mapOf("Cycle" to NotionValue.Title("Cycle A"), "Status" to NotionValue.Select(status)) + extra)
    }

    @Test
    fun `a cycle is read with its status, goal, budget and lease`() = runBlocking {
        val goal = convergedGoal()
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
    fun `finishing as Done records the end, the tasks done, the summary and the goal feedback, and releases the lease`() {
        val props = CycleMapper.finish(CycleStatus.DONE, now, "Executed 3 tasks.", tasksDone = 3, goalFeedback = "Done when was vague.")
        assertEquals(NotionValue.Select("Done"), props["Status"])
        assertEquals(NotionValue.NumberValue(3.0), props["Tasks Done"])
        assertEquals(NotionValue.RichText("Executed 3 tasks."), props["Summary"])
        assertEquals(NotionValue.RichText("Done when was vague."), props["Goal Feedback"])
        assertEquals(now, (props["Finished"] as NotionValue.DateValue).toInstant())
        assertNull((props["Lease Until"] as NotionValue.DateValue).start, "the lease must be cleared")
        assertFalse("Worker" in props, "who ran the cycle is kept")
        assertFalse("Status" in CycleMapper.tasksDone(2) || "Summary" in CycleMapper.tasksDone(2))
    }

    @Test
    fun `finishing without feedback leaves Goal Feedback untouched`() {
        assertFalse("Goal Feedback" in CycleMapper.finish(CycleStatus.FAILED, now, "x", tasksDone = 0))
    }

    @Test
    fun `Tasks Done is set from a count, so a repeat after a restart cannot overcount`() {
        assertEquals(mapOf("Tasks Done" to NotionValue.NumberValue(4.0)), CycleMapper.tasksDone(4))
        assertEquals(CycleMapper.tasksDone(4), CycleMapper.tasksDone(4))
    }

    @Test
    fun `a cycle can finish only as Done or Failed`() {
        CycleMapper.finish(CycleStatus.FAILED, now, "x", 0)
        for (bad in listOf(CycleStatus.QUEUED, CycleStatus.RUNNING, CycleStatus.PAUSED, CycleStatus.CANCELLED)) {
            assertThrows(IllegalArgumentException::class.java) { CycleMapper.finish(bad, now, "x", 0) }
        }
    }

    @Test
    fun `no cycle write carries a computed value`() = runBlocking {
        val cycle = CycleMapper.fromPage(fake.retrievePage(cycleRow().id))
        val all = CycleMapper.claim(cycle, "w", now, lease, 10) + CycleMapper.heartbeat(lease) + CycleMapper.progress("p") + CycleMapper.finish(CycleStatus.DONE, now, "s", 1, "f") + CycleMapper.tasksDone(1)
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

    @Test
    fun `starting a task sets Running and when, and nothing else`() {
        val props = AgentTaskMapper.start(now)
        assertEquals(setOf("Status", "Started"), props.keys)
        assertEquals(NotionValue.Select("Running"), props["Status"])
    }

    @Test
    fun `a task settles in one write - its final Status, its Result and when it finished`() {
        for (status in listOf(TaskStatus.DONE, TaskStatus.BLOCKED, TaskStatus.FAILED)) {
            val props = AgentTaskMapper.settle(status, "what was found", now)
            assertEquals(setOf("Status", "Result", "Finished"), props.keys, status.label)
            assertEquals(NotionValue.Select(status.label), props["Status"])
            assertEquals(NotionValue.RichText("what was found"), props["Result"])
            assertTrue(props.values.none { it.isReadOnly })
        }
        for (bad in listOf(TaskStatus.PLANNED, TaskStatus.RUNNING, TaskStatus.SUPERSEDED)) {
            assertThrows(IllegalArgumentException::class.java) { AgentTaskMapper.settle(bad, "x", now) }
        }
    }

    @Test
    fun `a task row is read with its Result and the links on its row, and Done Blocked Failed and Superseded are settled`() = runBlocking {
        val row = fake.createPage(
            t.tasks,
            mapOf(
                "Task" to NotionValue.Title("t"), "Status" to NotionValue.Select("Done"), "Result" to NotionValue.RichText("found"),
                "Target Links" to NotionValue.RichText("https://www.notion.so/aaaaaaaaaaaa4aaa8aaa000000000001\nhttps://www.notion.so/bbbbbbbbbbbb4bbb8bbb000000000002"),
            ),
        )
        val task = AgentTaskMapper.fromPage(fake.retrievePage(row.id))
        assertEquals("found", task.result)
        assertEquals(2, task.targetLinks.size)
        assertTrue(task.settled)
        assertTrue(task.copy(status = TaskStatus.BLOCKED).settled && task.copy(status = TaskStatus.FAILED).settled && task.copy(status = TaskStatus.SUPERSEDED).settled)
        assertFalse(task.copy(status = TaskStatus.PLANNED).settled || task.copy(status = TaskStatus.RUNNING).settled, "a Running task was interrupted and runs again")
    }

    @Test
    fun `an escalation is read with its resolution and what it belongs to`() = runBlocking {
        val goal = convergedGoal()
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
