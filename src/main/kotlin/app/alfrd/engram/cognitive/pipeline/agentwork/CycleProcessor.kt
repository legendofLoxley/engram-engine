package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.CreateOutcome
import app.alfrd.engram.notion.IdempotentCreator
import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionException
import app.alfrd.engram.notion.NotionFilter
import app.alfrd.engram.notion.NotionSort
import app.alfrd.engram.notion.NotionTableSet
import app.alfrd.engram.notion.queryAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The Director's Notion input channel for one poll: finds a cycle Jacob has queued, takes it with a
 * lease, plans it, writes the plan, and marks it done. **A dry run**: it reads Goals, Escalations and
 * PPS, writes only the Cycles and Agent Tasks tables (the guard enforces that), and executes nothing.
 *
 * Contract (the Director–Hermes execution and shared-state contract, October 11 amendment): a cycle
 * set to Queued is Jacob's request arriving through Notion. This is an input channel, not a scheduler:
 * it reacts only to Queued cycles on Active goals and to Running cycles whose lease has expired, and
 * it never queues anything itself. Hermes is not involved.
 *
 * What it relies on, and what it cannot do:
 * - Notion has no compare-and-swap. After claiming, it re-reads the cycle and proceeds only if it is
 *   still the worker on it. That is best effort against a second Director; the design assumes one.
 * - A lease is renewed while a cycle is being worked, so a long model call does not look like a crash.
 *   A cycle whose lease has run out is taken over, and the work is not repeated: the plan's rows are
 *   found by their Op key, and a half-written plan is failed on purpose, never completed from a guess.
 * - A model that is down fails the cycle. Nothing is re-queued automatically.
 */
class CycleProcessor(
    private val api: NotionApi,
    private val tableSet: NotionTableSet,
    private val director: CyclePlanDirector,
    private val traces: CycleTraceStore,
    private val worker: String,
    private val clock: () -> Instant = Instant::now,
    private val leaseDuration: Duration = Duration.ofMinutes(2),
    private val heartbeatInterval: Duration = leaseDuration.dividedBy(3),
    private val maxCyclesPerTick: Int = 5,
    private val maxAttemptsPerCycle: Int = 3,
    sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val logger = LoggerFactory.getLogger(CycleProcessor::class.java)

    private val cyclesTable = tableSet.table(AgentWorkTables.CYCLES).dataSourceId
    private val tasksTable = tableSet.table(AgentWorkTables.AGENT_TASKS).dataSourceId
    private val escalationsTable = tableSet.table(AgentWorkTables.ESCALATIONS).dataSourceId
    private val reader = LinkedContextReader(api, tableSet)
    private val taskCreator = IdempotentCreator(api, tableSet.table(AgentWorkTables.AGENT_TASKS), sleep)

    /**
     * Orders the heartbeat against the terminal write of the same cycle. Both are `PATCH`es of one page; if
     * a heartbeat is still in flight when the cycle finishes, it could land after the finish and put a future
     * lease back on a Done or Failed cycle. [finish] takes this lock, so it waits for a heartbeat in flight,
     * and sets [released] so none starts after it.
     */
    private class LeaseGuard {
        val mutex = Mutex()
        var released = false
    }

    private val leaseGuards = ConcurrentHashMap<String, LeaseGuard>()
    private val attempts = ConcurrentHashMap<String, Int>()
    private val reportedIgnored = ConcurrentHashMap.newKeySet<String>()

    sealed interface TickResult {
        data class Finished(val cycle: String?, val status: CycleStatus, val summary: String) : TickResult

        /** Left Running on purpose, its lease running out, to be taken over by a later poll. */
        data class Aborted(val cycle: String?, val reason: String) : TickResult

        /** Another worker held the cycle after the claim; nothing was planned. */
        data class LostClaim(val cycle: String?) : TickResult

        data class Error(val reason: String) : TickResult
    }

    private data class Selected(val cycle: Cycle, val goal: Goal?, val reclaim: Boolean)

    /** One poll: works through the cycles that are ready, oldest first, up to [maxCyclesPerTick]. Returns what happened to each. */
    suspend fun runOnce(): List<TickResult> {
        val results = ArrayList<TickResult>()
        repeat(maxCyclesPerTick) {
            val next = try {
                selectNext()
            } catch (e: NotionException) {
                logger.warn("notion-cycles: could not list cycles: {}", e::class.java.simpleName)
                results += TickResult.Error("could not list cycles: ${e::class.java.simpleName}")
                return results
            } ?: return results
            results += process(next)
        }
        return results
    }

    // ── Choosing what to work on ─────────────────────────────────────────────

    private suspend fun selectNext(): Selected? {
        val filter = NotionFilter.Or(
            listOf(
                NotionFilter.SelectEquals(AgentWorkSchema.Cycles.STATUS, CycleStatus.QUEUED.label),
                NotionFilter.SelectEquals(AgentWorkSchema.Cycles.STATUS, CycleStatus.RUNNING.label),
            ),
        )
        val cycles = api.queryAll(cyclesTable, filter, listOf(NotionSort.ByTimestamp(NotionSort.Timestamp.CREATED_TIME, ascending = true)), maxPages = 3)
            .pages.map(CycleMapper::fromPage)
        val now = clock()

        // One cycle at a time per goal: a goal with a live lease is busy.
        val busyGoals = cycles.filter { it.status == CycleStatus.RUNNING && !leaseExpired(it, now) }.mapNotNull { it.goalPageId }.toSet()
        val goals = HashMap<String, Goal>()

        for (cycle in cycles) {
            when (cycle.status) {
                CycleStatus.RUNNING -> if (leaseExpired(cycle, now)) return Selected(cycle, goal = null, reclaim = true)
                CycleStatus.QUEUED -> {
                    val goalId = cycle.goalPageId
                    if (goalId == null || cycle.goalCount != 1) { ignore(cycle, "it does not belong to exactly one goal"); continue }
                    if (goalId in busyGoals) { ignore(cycle, "its goal already has a cycle running"); continue }
                    val goal = goals.getOrPut(goalId) { GoalMapper.fromPage(api.retrievePage(goalId)) }
                    if (goal.status != GoalStatus.ACTIVE) { ignore(cycle, "its goal is ${goal.status?.label ?: "not set"}, not Active"); continue }
                    return Selected(cycle, goal, reclaim = false)
                }
                else -> Unit
            }
        }
        return null
    }

    private fun leaseExpired(cycle: Cycle, now: Instant): Boolean = cycle.leaseUntil == null || !cycle.leaseUntil.isAfter(now)

    /** Logged once per cycle and reason, not on every poll. Ids only. */
    private fun ignore(cycle: Cycle, reason: String) {
        if (reportedIgnored.add("${cycle.pageId}:$reason")) logger.info("notion-cycles: ignoring queued cycle {}: {}", cycle.displayId ?: cycle.pageId, reason)
    }

    // ── Working one cycle ────────────────────────────────────────────────────

    private suspend fun process(selected: Selected): TickResult {
        val cycle = selected.cycle
        val label = cycle.displayId
        val attempt = attempts.merge(cycle.pageId, 1, Int::plus) ?: 1
        val trace = CyclePlanTrace(
            cycle = label, cyclePageId = cycle.pageId, goal = null, goalTitle = null, worker = worker,
            startedAt = clock().toString(), reclaimed = selected.reclaim,
        )
        try {
            val goal = selected.goal ?: loadGoal(cycle)
            trace.goal = goal?.displayId
            trace.goalTitle = goal?.title

            if (attempt > maxAttemptsPerCycle) {
                val summary = "Dry run abandoned: the Director has already tried this cycle $maxAttemptsPerCycle time${if (maxAttemptsPerCycle == 1) "" else "s"} without finishing it. Nothing further was written. Queue a new cycle to try again."
                return finish(cycle, trace, CycleStatus.FAILED, summary)
            }

            val claimed = claim(cycle, goal) ?: run {
                trace.outcome = "lost_claim"
                logger.info("notion-cycles: cycle {} was not claimed (changed or taken by another worker)", label ?: cycle.pageId)
                return TickResult.LostClaim(label)
            }
            logger.info("notion-cycles: claimed cycle {} goal {} reclaim={} attempt={}", label ?: cycle.pageId, goal?.displayId ?: "?", selected.reclaim, attempt)

            return coroutineScope {
                val guard = LeaseGuard().also { leaseGuards[claimed.pageId] = it }
                val heartbeat = launch {
                    while (isActive) {
                        delay(heartbeatInterval.toMillis())
                        try {
                            guard.mutex.withLock {
                                if (!guard.released) api.updatePage(claimed.pageId, CycleMapper.heartbeat(clock().plus(leaseDuration)))
                            }
                        } catch (e: NotionException) {
                            logger.warn("notion-cycles: heartbeat for cycle {} failed: {}", label ?: claimed.pageId, e::class.java.simpleName)
                        }
                    }
                }
                try {
                    work(claimed, goal, trace)
                } finally {
                    heartbeat.cancel()
                    leaseGuards.remove(claimed.pageId)
                }
            }
        } catch (e: CancellationException) {
            trace.outcome = "aborted"
            trace.notes += "cancelled"
            throw e
        } catch (e: NotionException) {
            // Notion could not be reached or answered with an error. Leave the cycle Running: its lease
            // runs out, and a later poll takes it over and finds what was already written.
            logger.warn("notion-cycles: cycle {} aborted: {}", label ?: cycle.pageId, e::class.java.simpleName)
            trace.outcome = "aborted"
            trace.notes += "aborted: ${e::class.java.simpleName}"
            return TickResult.Aborted(label, e::class.java.simpleName)
        } catch (e: Exception) {
            // A bug, not Notion. Say so on the cycle if Notion will take it, so it cannot loop.
            logger.error("notion-cycles: cycle {} failed unexpectedly: {}", label ?: cycle.pageId, e::class.java.simpleName)
            return try {
                finish(cycle, trace, CycleStatus.FAILED, "Dry run failed with an internal error (${e::class.java.simpleName}). Nothing further was written.")
            } catch (inner: NotionException) {
                trace.outcome = "aborted"
                TickResult.Aborted(label, "internal error, and the failure could not be recorded")
            }
        } finally {
            trace.finishedAt = clock().toString()
            traces.add(trace)
        }
    }

    private suspend fun loadGoal(cycle: Cycle): Goal? {
        val goalId = cycle.goalPageId?.takeIf { cycle.goalCount == 1 } ?: return null
        return GoalMapper.fromPage(api.retrievePage(goalId))
    }

    /** Takes the cycle, and returns it as it stands after the claim, or null if it was not taken. */
    private suspend fun claim(cycle: Cycle, goal: Goal?): Cycle? {
        val fresh = CycleMapper.fromPage(api.retrievePage(cycle.pageId))
        val now = clock()
        val stillReady = fresh.status == CycleStatus.QUEUED || (fresh.status == CycleStatus.RUNNING && leaseExpired(fresh, now))
        if (!stillReady) return null

        api.updatePage(cycle.pageId, CycleMapper.claim(fresh, worker, now, now.plus(leaseDuration), goal?.cycleBudget ?: DEFAULT_BUDGET))
        val after = CycleMapper.fromPage(api.retrievePage(cycle.pageId))
        return after.takeIf { it.status == CycleStatus.RUNNING && it.worker == worker }
    }

    private suspend fun work(cycle: Cycle, goal: Goal?, trace: CyclePlanTrace): TickResult {
        if (goal == null) return finish(cycle, trace, CycleStatus.FAILED, "Dry run failed: this cycle does not belong to exactly one goal that could be read. Nothing was written.")

        val existing = plannedTasksOf(cycle)
        if (existing.isNotEmpty()) return resume(cycle, existing, trace)

        val rawBudget = cycle.taskBudget ?: goal.cycleBudget ?: DEFAULT_BUDGET
        val budget = rawBudget.coerceIn(0, MAX_BUDGET)
        if (budget != rawBudget) trace.notes += "task budget $rawBudget limited to $budget"

        val context = reader.read(goal)
        trace.contextRows = context.rows.size
        context.outcomes.forEach { trace.links += linkTrace(it) }

        val proposal = director.propose(
            PlanningInput(
                goal = goal,
                cycle = cycle,
                taskBudget = budget,
                escalations = escalationNotes(goal),
                previousCycleLabel = null,
                previousSummary = null,
                context = context,
            ).let { base ->
                val previous = previousCycle(goal, cycle)
                base.copy(previousCycleLabel = previous?.displayId, previousSummary = previous?.summary)
            },
        )
        trace.model = ModelTrace(
            called = proposal.modelCalled, latencyMs = proposal.latencyMs, provider = proposal.providerName, model = proposal.modelName,
            rejectedReason = proposal.rejectedReason, tasksProposed = proposal.tasks.size, rationale = proposal.rationale?.take(400),
        )
        if (proposal.rejectedReason != null) {
            return finish(cycle, trace, CycleStatus.FAILED, "Dry run failed: ${explain(proposal.rejectedReason)}. Nothing was written.")
        }

        val plan = CyclePlanValidator.validate(proposal.tasks, budget, context.allowed)
        plan.tasks.forEach { trace.accepted += TaskTrace(it.order, it.title, it.type.label, it.targetLinks, it.instructions.take(300)) }
        plan.rejected.forEach { trace.rejected += RejectionTrace(it.index, it.title, it.reason.name, it.detail) }
        if (plan.tasks.isEmpty()) {
            return finish(cycle, trace, CycleStatus.FAILED, "Dry run failed: no valid tasks. ${rejectionSummary(proposal.tasks.size, plan.rejected)} Nothing was written.")
        }

        // Written before the first task, so a restart can tell a finished plan from a half-written one.
        api.updatePage(cycle.pageId, CycleMapper.progress("$MANIFEST_PREFIX${plan.tasks.size}$MANIFEST_SUFFIX"))

        for (task in plan.tasks) {
            val key = AgentTaskMapper.opKey(cycle, PLAN_VERSION, task.order)
            val result = taskCreator.ensure(key, AgentTaskMapper.createProperties(task, cycle, PLAN_VERSION))
            trace.writes += WriteTrace(key, result.outcome.name)
        }

        val skipped = context.skipped.size
        val summary = buildString {
            append("Dry run: ${plan.tasks.size} planned task${if (plan.tasks.size == 1) "" else "s"} written; nothing was executed.")
            if (plan.rejected.isNotEmpty()) append(" ").append(rejectionSummary(proposal.tasks.size, plan.rejected))
            if (skipped > 0) append(" $skipped linked page${if (skipped == 1) " was" else "s were"} skipped (not a row of a configured table, or unreadable).")
            if (budget != rawBudget) append(" Task budget $rawBudget was limited to $budget.")
        }
        return finish(cycle, trace, CycleStatus.DONE, summary)
    }

    /** A cycle taken over after a restart that already has tasks. The model is not asked again. */
    private suspend fun resume(cycle: Cycle, existing: List<AgentTask>, trace: CyclePlanTrace): TickResult {
        val expected = MANIFEST.find(cycle.summary)?.groupValues?.get(1)?.toIntOrNull()
        val label = cycle.displayId ?: cycle.pageId
        val ours = existing.filter { it.planVersion == PLAN_VERSION && it.opKey.startsWith("cycle:$label:v$PLAN_VERSION:o") }
        val orders = ours.mapNotNull { it.order }.sorted()
        val complete = expected != null && ours.size == expected && orders == (1..expected).toList()
        trace.notes += "taken over with ${ours.size} planned task(s) already written; expected ${expected ?: "unknown"}"
        ours.forEach { trace.writes += WriteTrace(it.opKey, "ALREADY_EXISTED") }

        return if (complete) {
            finish(cycle, trace, CycleStatus.DONE, "Dry run: $expected planned task${if (expected == 1) "" else "s"} written; nothing was executed. Resumed after a restart; the plan had already been written in full.")
        } else {
            finish(
                cycle, trace, CycleStatus.FAILED,
                "Dry run failed: the Director restarted while the plan was being written, and ${ours.size} of ${expected ?: "an unknown number of"} planned tasks were found. " +
                    "The plan was not completed from a guess and nothing was executed. Queue a new cycle to plan again.",
            )
        }
    }

    // ── Reading what the planner needs ───────────────────────────────────────

    private suspend fun plannedTasksOf(cycle: Cycle): List<AgentTask> =
        api.queryAll(
            tasksTable,
            NotionFilter.RelationContains(AgentWorkSchema.Tasks.CYCLE, cycle.pageId),
            listOf(NotionSort.ByProperty(AgentWorkSchema.Tasks.ORDER)),
            maxPages = 2,
        ).pages.map(AgentTaskMapper::fromPage)

    private suspend fun escalationNotes(goal: Goal): List<EscalationNote> =
        api.query(
            escalationsTable,
            NotionFilter.RelationContains(AgentWorkSchema.Escalations.GOAL, goal.pageId),
            listOf(NotionSort.ByTimestamp(NotionSort.Timestamp.CREATED_TIME, ascending = false)),
            pageSize = ESCALATIONS_READ,
        ).pages.map(EscalationMapper::fromPage).map { EscalationNote(it.kind, it.status, it.ask, it.resolution) }

    /** The most recent finished cycle of this goal other than this one. */
    private suspend fun previousCycle(goal: Goal, current: Cycle): Cycle? =
        api.query(
            cyclesTable,
            NotionFilter.And(
                listOf(
                    NotionFilter.RelationContains(AgentWorkSchema.Cycles.GOAL, goal.pageId),
                    NotionFilter.Or(
                        listOf(
                            NotionFilter.SelectEquals(AgentWorkSchema.Cycles.STATUS, CycleStatus.DONE.label),
                            NotionFilter.SelectEquals(AgentWorkSchema.Cycles.STATUS, CycleStatus.FAILED.label),
                        ),
                    ),
                ),
            ),
            listOf(NotionSort.ByTimestamp(NotionSort.Timestamp.CREATED_TIME, ascending = false)),
            pageSize = 5,
        ).pages.map(CycleMapper::fromPage).firstOrNull { it.pageId != current.pageId && it.summary.isNotBlank() }

    // ── Finishing and describing ─────────────────────────────────────────────

    private suspend fun finish(cycle: Cycle, trace: CyclePlanTrace, status: CycleStatus, summary: String): TickResult {
        val guard = leaseGuards[cycle.pageId]
        if (guard == null) {
            api.updatePage(cycle.pageId, CycleMapper.finish(status, clock(), summary))
        } else {
            guard.mutex.withLock {
                guard.released = true
                api.updatePage(cycle.pageId, CycleMapper.finish(status, clock(), summary))
            }
        }
        attempts.remove(cycle.pageId)
        trace.outcome = status.label.lowercase()
        trace.summary = summary
        logger.info(
            "notion-cycles: cycle {} finished {} (planned={}, rejected={})",
            cycle.displayId ?: cycle.pageId, status.label, trace.accepted.size, trace.rejected.size,
        )
        return TickResult.Finished(cycle.displayId, status, summary)
    }

    private fun linkTrace(outcome: LinkOutcome): LinkTrace = when (outcome) {
        is LinkOutcome.TableListed -> LinkTrace(outcome.link, "table", "${outcome.table}: ${outcome.rows} row(s)${if (outcome.truncated) ", more not read" else ""}")
        is LinkOutcome.RowRead -> LinkTrace(outcome.link, "row", outcome.table)
        is LinkOutcome.External -> LinkTrace(outcome.link, "external")
        is LinkOutcome.Skipped -> LinkTrace(outcome.link, "skipped", outcome.reason.name.lowercase() + (outcome.detail?.let { ": $it" } ?: ""))
    }

    private fun explain(code: String): String = when {
        code == "no_llm_client" -> "no local model is available to plan with"
        code.startsWith("llm_call_failed") -> "the model call failed (${code.substringAfter(": ", "unknown error")})"
        code == "no_tool_call" -> "the model did not return a plan"
        code == "malformed_tool_input" -> "the model's plan was malformed"
        else -> "the planner returned $code"
    }

    private fun rejectionSummary(proposed: Int, rejected: List<Rejection>): String {
        if (rejected.isEmpty()) return "$proposed proposed."
        val reasons = rejected.groupingBy { it.reason }.eachCount().entries
            .joinToString(", ") { (reason, count) -> "${reason.name.lowercase().replace('_', ' ')} x$count" }
        return "${rejected.size} of $proposed proposed task${if (proposed == 1) " was" else "s were"} rejected by validation ($reasons)."
    }

    companion object {
        const val PLAN_VERSION = 1
        const val DEFAULT_BUDGET = 10

        /** A hard ceiling, so a mistyped budget cannot turn one cycle into hundreds of writes. */
        const val MAX_BUDGET = 25
        private const val ESCALATIONS_READ = 20

        private const val MANIFEST_PREFIX = "Dry run in progress: writing "
        private const val MANIFEST_SUFFIX = " planned tasks."
        private val MANIFEST = Regex("""writing (\d+) planned tasks""")

        /** What `Worker` says. Different after every restart, which is what lets a new process take over an old lease. */
        fun defaultWorkerId(): String {
            val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("arx")
            return "engram-dev@$host#${ProcessHandle.current().pid()}"
        }
    }
}
