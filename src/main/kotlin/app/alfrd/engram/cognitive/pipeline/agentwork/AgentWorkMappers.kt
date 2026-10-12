package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.fitTitle
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// ── Records ──────────────────────────────────────────────────────────────────

/** A goal, as the Director reads it. Goals are read-only here: nothing in this file builds a write for one. */
data class Goal(
    val pageId: String,
    val title: String,
    /** `GOAL-1`. */
    val displayId: String?,
    val type: GoalType?,
    val status: GoalStatus?,
    val intent: String,
    /** Prose. Not a list of URLs, so it cannot be matched against a link; see [PlanningContext]. */
    val scope: String,
    val metric: String,
    val doneCriteria: String,
    /** The `Target Links` text as people see it. A page mention shows only its title here. */
    val targetLinksText: String,
    /** Every URL in `Target Links`: the hrefs of mentions and hyperlinks, and URLs typed as text. */
    val targetLinks: List<String>,
    val cycleBudget: Int?,
    val replanCap: Int?,
    val deadline: String?,
    val targetSpace: String?,
)

data class Cycle(
    val pageId: String,
    val title: String,
    /** `CYCLE-7`: the auto-increment id, as people see it. Part of every task's Op key. */
    val displayId: String?,
    val status: CycleStatus?,
    /** The first related goal. A cycle belongs to exactly one. */
    val goalPageId: String?,
    val goalCount: Int,
    val taskBudget: Int?,
    val tasksDone: Int?,
    val worker: String,
    val leaseUntil: Instant?,
    val started: String?,
    val summary: String,
    val createdTime: Instant,
    val lastEdited: Instant,
)

data class AgentTask(
    val pageId: String,
    val title: String,
    val displayId: String?,
    val type: TaskType?,
    val status: TaskStatus?,
    val order: Int?,
    val planVersion: Int?,
    val instructions: String,
    val targetLinksText: String,
    val opKey: String,
    val cyclePageIds: List<String>,
)

data class Escalation(
    val pageId: String,
    val title: String,
    val displayId: String?,
    val kind: String?,
    val severity: String?,
    val status: String?,
    val ask: String,
    val resolution: String,
    val goalPageIds: List<String>,
    val cyclePageIds: List<String>,
)

// ── Small helpers ────────────────────────────────────────────────────────────

private val URL_IN_TEXT = Regex("""https?://[^\s<>"'`)\]]+""")

/** URLs typed into a text, without the punctuation that ends a sentence. */
fun urlsInText(text: String): List<String> =
    URL_IN_TEXT.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.filter { it.isNotEmpty() }.distinct().toList()

/** Every link a rich-text property carries: attached hrefs first, then URLs typed in the text. */
private fun NotionPage.allLinks(property: String): List<String> = (links(property) + urlsInText(text(property))).distinct()

/** A Notion date as a moment. A date without a time is read as the start of that day in UTC, which for a lease means "long expired". */
fun NotionValue.DateValue?.toInstant(): Instant? {
    val start = this?.start ?: return null
    return try {
        if (start.contains('T')) OffsetDateTime.parse(start).toInstant() else LocalDate.parse(start).atStartOfDay(ZoneOffset.UTC).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}

fun dateTimeValue(at: Instant?): NotionValue.DateValue = NotionValue.DateValue(at?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC).toString() })

private fun NotionPage.int(property: String): Int? = number(property)?.let { if (it % 1.0 == 0.0) it.toInt() else null }

// ── Mappers ──────────────────────────────────────────────────────────────────

object GoalMapper {
    fun fromPage(page: NotionPage): Goal = with(AgentWorkSchema.Goals) {
        Goal(
            pageId = page.id,
            title = page.title(GOAL),
            displayId = page.uniqueId(GOAL_ID)?.display,
            type = GoalType.fromLabel(page.select(TYPE)),
            status = GoalStatus.fromLabel(page.select(STATUS)),
            intent = page.text(INTENT),
            scope = page.text(SCOPE),
            metric = page.text(METRIC),
            doneCriteria = page.text(DONE_CRITERIA),
            targetLinksText = page.text(TARGET_LINKS),
            targetLinks = page.allLinks(TARGET_LINKS),
            cycleBudget = page.int(CYCLE_BUDGET),
            replanCap = page.int(REPLAN_CAP),
            deadline = page.date(DEADLINE)?.start,
            targetSpace = page.select(TARGET_SPACE),
        )
    }
}

object CycleMapper {
    fun fromPage(page: NotionPage): Cycle = with(AgentWorkSchema.Cycles) {
        val goals = page.relation(GOAL)
        Cycle(
            pageId = page.id,
            title = page.title(CYCLE),
            displayId = page.uniqueId(CYCLE_ID)?.display,
            status = CycleStatus.fromLabel(page.select(STATUS)),
            goalPageId = goals.firstOrNull(),
            goalCount = goals.size,
            taskBudget = page.int(TASK_BUDGET),
            tasksDone = page.int(TASKS_DONE),
            worker = page.text(WORKER),
            leaseUntil = page.date(LEASE_UNTIL).toInstant(),
            started = page.date(STARTED)?.start,
            summary = page.text(SUMMARY),
            createdTime = page.createdTime,
            lastEdited = page.lastEditedTime,
        )
    }

    /**
     * Takes the cycle: Running, held by [worker] until [leaseUntil]. `Started` is set only the first
     * time (a reclaim keeps the original start), and `Task Budget` is copied from the goal only if the
     * cycle did not set its own, as the column's description says.
     */
    fun claim(cycle: Cycle, worker: String, now: Instant, leaseUntil: Instant, goalBudget: Int): Map<String, NotionValue> = with(AgentWorkSchema.Cycles) {
        buildMap {
            put(STATUS, NotionValue.Select(CycleStatus.RUNNING.label))
            put(WORKER, NotionValue.RichText(worker))
            put(LEASE_UNTIL, dateTimeValue(leaseUntil))
            if (cycle.started == null) put(STARTED, dateTimeValue(now))
            if (cycle.taskBudget == null) put(TASK_BUDGET, NotionValue.NumberValue(goalBudget.toDouble()))
        }
    }

    fun heartbeat(leaseUntil: Instant): Map<String, NotionValue> =
        mapOf(AgentWorkSchema.Cycles.LEASE_UNTIL to dateTimeValue(leaseUntil))

    /** What the cycle says while it writes its plan. Read back after a restart to tell a finished plan from a partial one. */
    fun progress(summary: String): Map<String, NotionValue> =
        mapOf(AgentWorkSchema.Cycles.SUMMARY to NotionValue.RichText(summary))

    /** The cycle is over: [status] is Done or Failed, the lease is released, and nothing was executed (`Tasks Done` is 0). */
    fun finish(status: CycleStatus, now: Instant, summary: String): Map<String, NotionValue> = with(AgentWorkSchema.Cycles) {
        require(status == CycleStatus.DONE || status == CycleStatus.FAILED) { "a cycle finishes as Done or Failed" }
        mapOf(
            STATUS to NotionValue.Select(status.label),
            FINISHED to dateTimeValue(now),
            TASKS_DONE to NotionValue.NumberValue(0.0),
            SUMMARY to NotionValue.RichText(summary),
            LEASE_UNTIL to NotionValue.DateValue(null),
        )
    }
}

object AgentTaskMapper {
    fun fromPage(page: NotionPage): AgentTask = with(AgentWorkSchema.Tasks) {
        AgentTask(
            pageId = page.id,
            title = page.title(TASK),
            displayId = page.uniqueId(TASK_ID)?.display,
            type = TaskType.fromLabel(page.select(TYPE)),
            status = TaskStatus.fromLabel(page.select(STATUS)),
            order = page.int(ORDER),
            planVersion = page.int(PLAN_VERSION),
            instructions = page.text(INSTRUCTIONS),
            targetLinksText = page.text(TARGET_LINKS),
            opKey = page.text(AgentWorkTables.OP_KEY_PROPERTY),
            cyclePageIds = page.relation(CYCLE),
        )
    }

    /** `cycle:<Cycle ID>:v<Plan Version>:o<Order>`. The same work on the same cycle always gets the same key. */
    fun opKey(cycle: Cycle, planVersion: Int, order: Int): String {
        val id = cycle.displayId ?: error("a cycle without an auto-increment id cannot key its tasks")
        return "cycle:$id:v$planVersion:o$order"
    }

    /** A Planned task of [planVersion], linked to [cycle]. The Op key is added by the idempotent creator. */
    fun createProperties(task: PlannedTask, cycle: Cycle, planVersion: Int): Map<String, NotionValue> = with(AgentWorkSchema.Tasks) {
        mapOf(
            TASK to NotionValue.Title(fitTitle(task.title)),
            TYPE to NotionValue.Select(task.type.label),
            ORDER to NotionValue.NumberValue(task.order.toDouble()),
            STATUS to NotionValue.Select(TaskStatus.PLANNED.label),
            PLAN_VERSION to NotionValue.NumberValue(planVersion.toDouble()),
            INSTRUCTIONS to NotionValue.RichText(task.instructions),
            TARGET_LINKS to NotionValue.RichText(task.targetLinks.joinToString("\n")),
            CYCLE to NotionValue.Relation(listOf(cycle.pageId)),
        )
    }
}

object EscalationMapper {
    fun fromPage(page: NotionPage): Escalation = with(AgentWorkSchema.Escalations) {
        Escalation(
            pageId = page.id,
            title = page.title(ESCALATION),
            displayId = page.uniqueId(ESCALATION_ID)?.display,
            kind = page.select(KIND),
            severity = page.select(SEVERITY),
            status = page.select(STATUS),
            ask = page.text(ASK),
            resolution = page.text(RESOLUTION),
            goalPageIds = page.relation(GOAL),
            cyclePageIds = page.relation(CYCLE),
        )
    }
}
