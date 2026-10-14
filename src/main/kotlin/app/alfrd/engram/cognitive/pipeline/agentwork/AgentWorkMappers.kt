package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionMention
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.fitTitle
import app.alfrd.engram.notion.normalizeNotionId
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// ── Records ──────────────────────────────────────────────────────────────────

/**
 * What a goal's Scope says, read from its @-mentions and from nothing else. A page or database mention is
 * the only thing that puts something in scope; the words around the mentions are prose, which is ignored
 * and reported ([proseChars], [proseExcerpt]); a hyperlink or URL that is not a mention is ignored and
 * reported too ([ignoredLinks]).
 */
data class GoalScope(
    val mentions: List<NotionMention>,
    /** Letters and digits left in the Scope text once the mentions' own titles are taken out. */
    val proseChars: Int,
    /** The leftover words, collapsed to one line and shortened, for the trace. */
    val proseExcerpt: String,
    val ignoredLinks: List<String>,
) {
    val hasMentions: Boolean get() = mentions.isNotEmpty()

    companion object {
        val EMPTY = GoalScope(emptyList(), 0, "", emptyList())
        private const val EXCERPT_CHARS = 200

        /** Scope as the Director reads it from the property's rich text. A missing property reads as empty scope. */
        fun fromRichText(value: NotionValue.RichText?): GoalScope {
            if (value == null) return EMPTY
            val mentions = value.mentions.filter { normalizeNotionId(it.id) != null }.distinctBy { it.id }
            val mentionIds = mentions.map { it.id }.toSet()

            // The text of each mention is its title, which is part of the value's text. Take each title out once, in order.
            var remaining = value.text
            for (mention in value.mentions) {
                if (mention.title.isEmpty()) continue
                val at = remaining.indexOf(mention.title)
                if (at >= 0) remaining = remaining.removeRange(at, at + mention.title.length)
            }
            val prose = remaining.replace(Regex("\\s+"), " ").trim()
            val proseChars = prose.count { it.isLetterOrDigit() }
            val excerpt = if (proseChars == 0) "" else if (prose.length <= EXCERPT_CHARS) prose else prose.take(EXCERPT_CHARS - 1) + "…"

            // A link that points at one of the mentioned pages is the mention's own href, not a separate link.
            val ignored = (value.links + urlsInText(value.text)).distinct().filter { normalizeNotionId(it) !in mentionIds }
            return GoalScope(mentions, proseChars, excerpt, ignored)
        }
    }
}

/** A goal, as the Director reads it. Goals are read-only here: nothing in this file builds a write for one. */
data class Goal(
    val pageId: String,
    /** The one sentence Jacob wrote. */
    val title: String,
    /** `GOAL-1`. */
    val displayId: String?,
    val status: GoalStatus?,
    /** A check anyone could apply. */
    val doneWhen: String,
    val scope: GoalScope,
    /** The Containers in alfrd memory this goal is for, as page ids. Read and recorded; not dereferenced, because Scope does not mention them. */
    val serves: List<String>,
    val cycleBudget: Int?,
    val replanCap: Int?,
    val deadline: String?,
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
    /** Every URL on the row's Target Links: what the task would read. Read from the row, because a person can edit it in Notion. */
    val targetLinks: List<String>,
    val result: String,
    val opKey: String,
    val cyclePageIds: List<String>,
) {
    /** Done, Blocked, Failed and Superseded are final: a restart never runs such a task again. */
    val settled: Boolean get() = status == TaskStatus.DONE || status == TaskStatus.BLOCKED || status == TaskStatus.FAILED || status == TaskStatus.SUPERSEDED
}

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

/** Notion's own datetime format (`2026-10-12T10:02:00.000+00:00`). `OffsetDateTime.toString()` would drop the seconds when they are zero. */
private val NOTION_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")

fun dateTimeValue(at: Instant?): NotionValue.DateValue = NotionValue.DateValue(at?.let { NOTION_DATETIME.format(OffsetDateTime.ofInstant(it, ZoneOffset.UTC)) })

private fun NotionPage.int(property: String): Int? = number(property)?.let { if (it % 1.0 == 0.0) it.toInt() else null }

// ── Mappers ──────────────────────────────────────────────────────────────────

object GoalMapper {
    fun fromPage(page: NotionPage): Goal = with(AgentWorkSchema.Goals) {
        Goal(
            pageId = page.id,
            title = page.title(GOAL),
            displayId = page.uniqueId(GOAL_ID)?.display,
            status = GoalStatus.fromLabel(page.select(STATUS)),
            doneWhen = page.text(DONE_WHEN),
            scope = GoalScope.fromRichText(page.properties[SCOPE] as? NotionValue.RichText),
            serves = page.relation(SERVES),
            cycleBudget = page.int(CYCLE_BUDGET),
            replanCap = page.int(REPLAN_CAP),
            deadline = page.date(DEADLINE)?.start,
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

    /** How many of the cycle's tasks are Done. Set from a count, never incremented blind, so a repeat after a restart cannot overcount. */
    fun tasksDone(count: Int): Map<String, NotionValue> =
        mapOf(AgentWorkSchema.Cycles.TASKS_DONE to NotionValue.NumberValue(count.toDouble()))

    /**
     * The cycle is over: [status] is Done or Failed, the lease is released, [tasksDone] says how many tasks
     * finished, and [goalFeedback] (when there is any) is QC's note on the goal. The goal's own Status is
     * never touched: goals are read-only here.
     */
    fun finish(status: CycleStatus, now: Instant, summary: String, tasksDone: Int, goalFeedback: String? = null): Map<String, NotionValue> = with(AgentWorkSchema.Cycles) {
        require(status == CycleStatus.DONE || status == CycleStatus.FAILED) { "a cycle finishes as Done or Failed" }
        buildMap {
            put(STATUS, NotionValue.Select(status.label))
            put(FINISHED, dateTimeValue(now))
            put(TASKS_DONE, NotionValue.NumberValue(tasksDone.toDouble()))
            put(SUMMARY, NotionValue.RichText(summary))
            goalFeedback?.let { put(GOAL_FEEDBACK, NotionValue.RichText(it)) }
            put(LEASE_UNTIL, NotionValue.DateValue(null))
        }
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
            targetLinks = page.allLinks(TARGET_LINKS),
            result = page.text(RESULT),
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

    /** The task starts: Running, and when. The Result is empty until it finishes. */
    fun start(now: Instant): Map<String, NotionValue> = with(AgentWorkSchema.Tasks) {
        mapOf(STATUS to NotionValue.Select(TaskStatus.RUNNING.label), STARTED to dateTimeValue(now))
    }

    /** One write that settles the task: its final Status, its Result, and when it finished. Never two writes, so a crash cannot leave a Done task without its Result. */
    fun settle(status: TaskStatus, result: String, now: Instant): Map<String, NotionValue> = with(AgentWorkSchema.Tasks) {
        require(status == TaskStatus.DONE || status == TaskStatus.BLOCKED || status == TaskStatus.FAILED) { "a task settles as Done, Blocked or Failed" }
        mapOf(
            STATUS to NotionValue.Select(status.label),
            RESULT to NotionValue.RichText(result),
            FINISHED to dateTimeValue(now),
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
