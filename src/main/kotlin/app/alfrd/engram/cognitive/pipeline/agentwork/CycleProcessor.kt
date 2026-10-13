package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.IdempotentCreator
import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionException
import app.alfrd.engram.notion.NotionFilter
import app.alfrd.engram.notion.NotionSort
import app.alfrd.engram.notion.NotionTableSet
import app.alfrd.engram.notion.normalizeNotionId
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
 * lease, **plans it, executes the plan's tasks in order (report-only), runs QC, and closes it**.
 *
 * Contract (the Director–Hermes execution and shared-state contract, October 11 amendment): a cycle set to
 * Queued is Jacob's request arriving through Notion. This is an input channel, not a scheduler: it reacts
 * only to Queued cycles on Active goals and to Running cycles whose lease has expired, and it never queues
 * anything itself.
 *
 * What it writes, and where the guard enforces it: **only Cycles and Agent Tasks** (a plan's tasks, their
 * Status and Result, the cycle's lease, Tasks Done, Summary, Goal Feedback). Goals, Escalations, PPS and
 * the alfrd memory tables are read-only in the configuration, so a bug here cannot write to them, and the
 * goal's own Status is never touched: a cycle only says whether the goal looks met.
 *
 * What it reads is bounded by the goal's **Scope**, which is machine-checked: only @-mentions of pages and
 * databases count (see [GoalScope]), and the allowed set is the mentioned pages, the mentioned databases
 * and the rows of a mentioned database ([AllowedScope]). A plan's links and every page a task reads are
 * checked against it in code, and again when the task runs.
 *
 * What it relies on, and what it cannot do:
 * - Notion has no compare-and-swap. After claiming, it re-reads the cycle and proceeds only if it is still
 *   the worker on it, and it re-checks that before every task. That is best effort against a second
 *   Director; the design assumes one.
 * - A lease is renewed while a cycle is being worked, so a long model call does not look like a crash. A
 *   cycle whose lease has run out is taken over and the work is not repeated: the plan's rows are found by
 *   their Op key, a task that is already Done, Blocked or Failed is never run again, and a half-written
 *   plan is failed on purpose, never completed from a guess.
 * - A model that is down fails the cycle's planning or its QC; a task whose model call fails is marked
 *   Failed and the cycle goes on. Nothing is re-queued automatically.
 * - Gate and replan are not here: a task that cannot be done with what Scope allows is Blocked, with its
 *   reason, and the cycle continues.
 */
class CycleProcessor(
    private val api: NotionApi,
    private val tableSet: NotionTableSet,
    private val director: CyclePlanDirector,
    private val executor: CycleTaskExecutor,
    private val qc: CycleQcDirector,
    private val traces: CycleTraceStore,
    private val worker: String,
    private val clock: () -> Instant = Instant::now,
    private val leaseDuration: Duration = Duration.ofMinutes(2),
    private val heartbeatInterval: Duration = leaseDuration.dividedBy(3),
    private val maxCyclesPerTick: Int = 5,
    private val maxAttemptsPerCycle: Int = 3,
    private val limits: ContentLimits = ContentLimits(),
    sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val logger = LoggerFactory.getLogger(CycleProcessor::class.java)

    private val cyclesTable = tableSet.table(AgentWorkTables.CYCLES).dataSourceId
    private val tasksTable = tableSet.table(AgentWorkTables.AGENT_TASKS).dataSourceId
    private val escalationsTable = tableSet.table(AgentWorkTables.ESCALATIONS).dataSourceId
    private val scopeReader = ScopeReader(api, tableSet)
    private val contentReader = PageContentReader(api, limits)
    private val taskCreator = IdempotentCreator(api, tableSet.table(AgentWorkTables.AGENT_TASKS), sleep)

    private val attempts = ConcurrentHashMap<String, Int>()
    private val reportedIgnored = ConcurrentHashMap.newKeySet<String>()

    /** Set when a cycle is about to be closed, so a heartbeat that wakes up late does not put a lease back on a finished cycle. */
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

    sealed interface TickResult {
        data class Finished(val cycle: String?, val status: CycleStatus, val summary: String) : TickResult

        /** Left Running on purpose, its lease running out, to be taken over by a later poll. */
        data class Aborted(val cycle: String?, val reason: String) : TickResult

        /** Another worker held the cycle after the claim (or took it mid-run); nothing further was written by this one. */
        data class LostClaim(val cycle: String?) : TickResult

        data class Error(val reason: String) : TickResult
    }

    private data class Selected(val cycle: Cycle, val goal: Goal?, val reclaim: Boolean)

    /** Another worker holds the cycle now. Thrown between steps so this one stops writing. */
    private class LeaseLost : RuntimeException("the cycle lease is no longer held by this worker")

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
                val summary = "Cycle abandoned: the Director has already tried this cycle $maxAttemptsPerCycle time${if (maxAttemptsPerCycle == 1) "" else "s"} without finishing it. Tasks already marked Done, Blocked or Failed keep their Results. Queue a new cycle to try again."
                return finish(cycle, trace, CycleStatus.FAILED, summary)
            }

            val claimed = claim(cycle, goal) ?: run {
                trace.outcome = "lost_claim"
                logger.info("notion-cycles: cycle {} was not claimed (changed or taken by another worker)", label ?: cycle.pageId)
                return TickResult.LostClaim(label)
            }
            logger.info("notion-cycles: claimed cycle {} goal {} reclaim={} attempt={}", label ?: cycle.pageId, goal?.displayId ?: "?", selected.reclaim, attempt)

            val guard = LeaseGuard().also { leaseGuards[claimed.pageId] = it }
            return coroutineScope {
                val heartbeat = launch {
                    while (isActive) {
                        delay(heartbeatInterval.toMillis())
                        if (guard.released) break
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
                }
            }
        } catch (e: CancellationException) {
            trace.outcome = "aborted"
            trace.notes += "cancelled"
            throw e
        } catch (e: LeaseLost) {
            // Another worker holds the cycle now. Do not write to it again.
            trace.outcome = "lost_claim"
            trace.notes += "lost the lease mid-run; stopped without further writes"
            logger.warn("notion-cycles: cycle {} lost its lease mid-run; stopping", label ?: cycle.pageId)
            return TickResult.LostClaim(label)
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
                finish(cycle, trace, CycleStatus.FAILED, "Cycle failed with an internal error (${e::class.java.simpleName}). Tasks already marked Done, Blocked or Failed keep their Results.")
            } catch (inner: NotionException) {
                trace.outcome = "aborted"
                TickResult.Aborted(label, "internal error, and the failure could not be recorded")
            }
        } finally {
            leaseGuards.remove(cycle.pageId)
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

    /** Before each task: is this worker still the one on the cycle? Two workers must never both run a task. */
    private suspend fun requireLease(cycle: Cycle) {
        val fresh = CycleMapper.fromPage(api.retrievePage(cycle.pageId))
        if (fresh.status != CycleStatus.RUNNING || fresh.worker != worker) throw LeaseLost()
    }

    private suspend fun work(cycle: Cycle, goal: Goal?, trace: CyclePlanTrace): TickResult {
        if (goal == null) return finish(cycle, trace, CycleStatus.FAILED, "Cycle failed: this cycle does not belong to exactly one goal that could be read. Nothing was written.")

        // ── 1. Scope: only @-mentions count; no mentions, no cycle ───────────────
        val spec = goal.scope
        if (!spec.hasMentions) return failNoMentions(cycle, goal, trace)
        val context = scopeReader.read(spec)
        trace.contextRows = context.rows.size
        context.outcomes.forEach { trace.links += scopeLinkTrace(it) }
        trace.scope = scopeTrace(goal, context)
        if (!context.usable) return failUnreadableScope(cycle, goal, trace, context)

        // ── 2. Plan, or take over the plan an earlier run wrote ──────────────────
        val existing = plannedTasksOf(cycle)
        val planned = if (existing.isNotEmpty()) takeOver(cycle, existing, trace) else plan(cycle, goal, context, trace)
        val tasks = when (planned) {
            is Planned.Stop -> return planned.result
            is Planned.Ready -> planned.tasks
        }

        // ── 3. Execute, in order, report-only ────────────────────────────────────
        val stager = TaskStager(context, contentReader, limits)
        val current = tasks.associateBy { it.order ?: 0 }.toMutableMap()
        val earlier = ArrayList<EarlierResult>()
        var tasksDone = tasks.count { it.status == TaskStatus.DONE }

        for (task in tasks.sortedBy { it.order ?: Int.MAX_VALUE }) {
            val order = task.order ?: 0
            if (task.settled) {
                trace.execution += TaskRunTrace(order, task.title, "already_settled", task.status?.label?.lowercase(), 0, 0, task.result.length, null)
                if (task.status != TaskStatus.SUPERSEDED) earlier += EarlierResult(order, task.title, task.status!!, task.result)
                continue
            }
            requireLease(cycle)
            val run = runTask(cycle, goal, task, tasks.size, stager, earlier, trace)
            current[order] = run.task
            earlier += EarlierResult(order, run.task.title, run.task.status ?: TaskStatus.FAILED, run.task.result)
            if (run.task.status == TaskStatus.DONE) {
                tasksDone++
                api.updatePage(cycle.pageId, CycleMapper.tasksDone(tasksDone))
            }
        }

        // ── 4. QC: one Director decision writes Summary and Goal Feedback ────────
        requireLease(cycle)
        val finalTasks = current.values.sortedBy { it.order ?: Int.MAX_VALUE }
        val rejected = trace.rejected.size
        val lines = finalTasks.filter { it.status != TaskStatus.SUPERSEDED }.map {
            QcTaskLine(it.order ?: 0, it.title, it.type?.label ?: "?", it.status ?: TaskStatus.FAILED, it.result)
        }
        val result = qc.decide(QcInput(goal, cycle, scopeLines(context), lines, rejected))
        trace.qc = QcTrace(result.modelCalled, result.latencyMs, result.rejectedReason, result.decision?.goalLooksMet, result.providerName, result.modelName, result.answer)

        val done = finalTasks.count { it.status == TaskStatus.DONE }
        val blocked = finalTasks.count { it.status == TaskStatus.BLOCKED }
        val failed = finalTasks.count { it.status == TaskStatus.FAILED }
        val decision = result.decision
        if (decision == null) {
            val why = result.rejectedReason?.let { explain(it) } ?: "no decision"
            return finish(
                cycle, trace, CycleStatus.FAILED,
                "Cycle failed at QC: $why. The ${lines.size} task${if (lines.size == 1) "" else "s"} ran ($done done, $blocked blocked, $failed failed) and their Results are on the Agent Tasks; " +
                    "no summary or goal feedback was written. Queue a new cycle to run again.",
                tasksDone = done,
            )
        }
        val summary = QcComposer.summary(done, blocked, failed, lines.size, decision) + extraNotes(trace)
        return finish(cycle, trace, CycleStatus.DONE, summary, tasksDone = done, feedback = QcComposer.feedback(decision))
    }

    // ── Failing before any work, with a reason and a suggestion ──────────────

    private suspend fun failNoMentions(cycle: Cycle, goal: Goal, trace: CyclePlanTrace): TickResult {
        trace.scope = scopeTrace(goal, ScopeContext.EMPTY)
        val prose = goal.scope.proseExcerpt.takeIf { goal.scope.proseChars > 0 }
        val summary = "Cycle failed: the goal's Scope has no page or database mentions, so there is nothing the Director may read. " +
            "Only @-mentions count; words and links in Scope are ignored. Nothing was planned and nothing else was written." +
            (prose?.let { " Scope held prose that was ignored: \"$it\"." }.orEmpty())
        val feedback = "Scope named nothing the Director may read. A goal's Scope is the pages and databases @-mentioned in it; text around them is ignored." +
            (prose?.let { " The prose that was ignored: \"$it\"." }.orEmpty()) +
            "\n\nSuggested rewrite of Scope: @-mention each page and database this goal may touch (a mentioned database includes its rows)."
        return finish(cycle, trace, CycleStatus.FAILED, summary, feedback = feedback)
    }

    private suspend fun failUnreadableScope(cycle: Cycle, goal: Goal, trace: CyclePlanTrace, context: ScopeContext): TickResult {
        val reasons = context.skipped.joinToString("; ") { "${it.mention.title.ifBlank { it.mention.id }}: ${it.reason.name.lowercase().replace('_', ' ')}" }
        val summary = "Cycle failed: none of the ${goal.scope.mentions.size} page or database mention${if (goal.scope.mentions.size == 1) "" else "s"} in the goal's Scope could be read ($reasons). Nothing was planned and nothing else was written."
        val feedback = "Scope mentioned things the Director cannot read: $reasons. A mentioned page must be a row of one of the configured tables or the allowlisted Persona page, and the integration must be connected to it; a mentioned database must be one of the configured tables.\n\n" +
            "Suggested rewrite of Scope: mention pages and databases from alfrd memory, or the Agent Work and PPS tables."
        return finish(cycle, trace, CycleStatus.FAILED, summary, feedback = feedback)
    }

    // ── Planning ─────────────────────────────────────────────────────────────

    private sealed interface Planned {
        data class Ready(val tasks: List<AgentTask>) : Planned
        data class Stop(val result: TickResult) : Planned
    }

    private suspend fun plan(cycle: Cycle, goal: Goal, context: ScopeContext, trace: CyclePlanTrace): Planned {
        val rawBudget = cycle.taskBudget ?: goal.cycleBudget ?: DEFAULT_BUDGET
        val budget = rawBudget.coerceIn(0, MAX_BUDGET)
        if (budget != rawBudget) trace.notes += "task budget $rawBudget limited to $budget"

        val previous = previousCycle(goal, cycle)
        val proposal = director.propose(
            PlanningInput(
                goal = goal,
                cycle = cycle,
                taskBudget = budget,
                escalations = escalationNotes(goal),
                previousCycleLabel = previous?.displayId,
                previousSummary = previous?.summary,
                context = context,
            ),
        )
        trace.model = ModelTrace(
            called = proposal.modelCalled, latencyMs = proposal.latencyMs, provider = proposal.providerName, model = proposal.modelName,
            rejectedReason = proposal.rejectedReason, tasksProposed = proposal.tasks.size, rationale = proposal.rationale?.take(400), answer = proposal.answer,
        )
        if (proposal.rejectedReason != null) {
            return Planned.Stop(finish(cycle, trace, CycleStatus.FAILED, "Cycle failed: ${explain(proposal.rejectedReason)}. Nothing was written."))
        }

        val plan = CyclePlanValidator.validate(proposal.tasks, budget, context.allowed)
        plan.tasks.forEach { trace.accepted += TaskTrace(it.order, it.title, it.type.label, it.targetLinks, it.instructions.take(300)) }
        plan.rejected.forEach { trace.rejected += RejectionTrace(it.index, it.title, it.reason.name, it.detail) }
        if (plan.tasks.isEmpty()) {
            return Planned.Stop(finish(cycle, trace, CycleStatus.FAILED, "Cycle failed: no valid tasks. ${rejectionSummary(proposal.tasks.size, plan.rejected)} Nothing was written."))
        }

        // Written before the first task, so a restart can tell a finished plan from a half-written one.
        api.updatePage(cycle.pageId, CycleMapper.progress("$MANIFEST_PREFIX${plan.tasks.size}$MANIFEST_SUFFIX"))

        val created = ArrayList<AgentTask>()
        for (task in plan.tasks) {
            val key = AgentTaskMapper.opKey(cycle, PLAN_VERSION, task.order)
            val result = taskCreator.ensure(key, AgentTaskMapper.createProperties(task, cycle, PLAN_VERSION))
            trace.writes += WriteTrace(key, result.outcome.name)
            created += AgentTaskMapper.fromPage(result.page)
        }
        if (plan.rejected.isNotEmpty()) trace.notes += rejectionSummary(proposal.tasks.size, plan.rejected)
        return Planned.Ready(created)
    }

    /** A cycle taken over after a restart that already has tasks. The model is not asked to plan again. */
    private suspend fun takeOver(cycle: Cycle, existing: List<AgentTask>, trace: CyclePlanTrace): Planned {
        val expected = MANIFEST.find(cycle.summary)?.groupValues?.get(1)?.toIntOrNull()
        val label = cycle.displayId ?: cycle.pageId
        val ours = existing.filter { it.planVersion == PLAN_VERSION && it.opKey.startsWith("cycle:$label:v$PLAN_VERSION:o") }
        val orders = ours.mapNotNull { it.order }.sorted()
        val complete = expected != null && ours.size == expected && orders == (1..expected).toList()
        trace.notes += "taken over with ${ours.size} planned task(s) already written; expected ${expected ?: "unknown"}"
        ours.forEach { trace.writes += WriteTrace(it.opKey, "ALREADY_EXISTED") }

        if (!complete) {
            return Planned.Stop(
                finish(
                    cycle, trace, CycleStatus.FAILED,
                    "Cycle failed: the Director restarted while the plan was being written, and ${ours.size} of ${expected ?: "an unknown number of"} planned tasks were found. " +
                        "The plan was not completed from a guess and nothing was executed. Queue a new cycle to plan again.",
                ),
            )
        }
        return Planned.Ready(ours.sortedBy { it.order })
    }

    // ── Running one task ─────────────────────────────────────────────────────

    private class TaskRun(val task: AgentTask)

    /**
     * Runs one Planned (or interrupted Running) task: mark it Running, stage its in-scope content, do the
     * work, and settle it in **one write** (Status, Result, Finished), so a crash cannot leave a Done task
     * without its Result. Every outcome is settled; nothing here is retried.
     */
    private suspend fun runTask(
        cycle: Cycle,
        goal: Goal,
        task: AgentTask,
        taskCount: Int,
        stager: TaskStager,
        earlier: List<EarlierResult>,
        trace: CyclePlanTrace,
    ): TaskRun {
        val order = task.order ?: 0
        api.updatePage(task.pageId, AgentTaskMapper.start(clock()))
        val staged = stager.stage(task.targetLinks)
        trace.content += staged.traces
        val problems = staged.problems.map(::describe)

        fun settle(status: TaskStatus, result: String, reason: String?, cited: Int, latencyMs: Long?, answer: AnswerTrace? = null): TaskRun {
            return TaskRun(task.copy(status = status, result = result))
                .also { trace.execution += TaskRunTrace(order, task.title, status.label.lowercase(), reason, staged.pages.size, cited, result.length, latencyMs, problems, answer) }
        }

        // Anything outside the goal's scope blocks the task before a word of it is read or sent to a model.
        if (staged.outOfScope.isNotEmpty()) {
            val result = "Blocked: this task names ${staged.outOfScope.size} page${if (staged.outOfScope.size == 1) "" else "s"} outside the goal's Scope (${staged.outOfScope.joinToString(", ") { it.link.take(120) }}). Nothing was read for it."
            api.updatePage(task.pageId, AgentTaskMapper.settle(TaskStatus.BLOCKED, result, clock()))
            return settle(TaskStatus.BLOCKED, result, "out_of_scope", 0, null)
        }
        if (task.targetLinks.isNotEmpty() && staged.pages.isEmpty()) {
            val result = "Blocked: none of the pages this task names could be read (${problems.joinToString("; ")})."
            api.updatePage(task.pageId, AgentTaskMapper.settle(TaskStatus.BLOCKED, result, clock()))
            return settle(TaskStatus.BLOCKED, result, "pages_unreadable", 0, null)
        }

        val outcome = executor.execute(TaskExecutionInput(goal, cycle, task, taskCount, staged, earlier))
        return when (val execution = outcome.execution) {
            is TaskExecution.Done -> {
                val text = composeResult(execution, staged)
                api.updatePage(task.pageId, AgentTaskMapper.settle(TaskStatus.DONE, text, clock()))
                val reason = if (execution.droppedCitations > 0) "${execution.droppedCitations} citation(s) not among the staged pages were dropped" else null
                settle(TaskStatus.DONE, text, reason, execution.pagesUsed.size, outcome.latencyMs, outcome.answer)
            }
            is TaskExecution.Blocked -> {
                val text = "Blocked: ${execution.reason}"
                api.updatePage(task.pageId, AgentTaskMapper.settle(TaskStatus.BLOCKED, text, clock()))
                settle(TaskStatus.BLOCKED, text, "insufficient_material", 0, outcome.latencyMs, outcome.answer)
            }
            is TaskExecution.Failed -> {
                val text = "Not executed: ${explain(execution.reason)}. The task was not retried."
                api.updatePage(task.pageId, AgentTaskMapper.settle(TaskStatus.FAILED, text, clock()))
                settle(TaskStatus.FAILED, text, execution.reason, 0, outcome.latencyMs, outcome.answer)
            }
        }
    }

    /** The Result as written: the worker's report, then a footer of the pages used, built by code from the validated citations. */
    private fun composeResult(done: TaskExecution.Done, staged: StagedContext): String = buildString {
        append(done.result.trim())
        val titles = staged.pages.associateBy({ notionPageIdInPath(it.url) }, { it.title })
        append("\n\n")
        if (done.pagesUsed.isNotEmpty()) {
            append("Pages used:")
            done.pagesUsed.forEach { url -> append("\n- ").append(titles[notionPageIdInPath(url)].orEmpty().ifBlank { "(untitled)" }).append(" ").append(url) }
        } else if (staged.pages.isNotEmpty()) {
            append("Pages used: none cited by the worker. Pages staged for this task:")
            staged.pages.forEach { append("\n- ").append(it.title).append(" ").append(it.url) }
        } else {
            append("Pages used: none (the task named no page).")
        }
        if (staged.problems.isNotEmpty()) append("\n\nNot read: ").append(staged.problems.joinToString("; ") { describe(it) })
        if (staged.truncated) append("\n\nNote: some page content was cut by a size limit.")
    }

    private fun describe(problem: StagingProblem): String = when (problem) {
        is StagingProblem.OutOfScope -> "${problem.link.take(120)} is outside the goal's Scope"
        is StagingProblem.Unreadable -> "${problem.link.take(120)} could not be read (${problem.detail})"
        is StagingProblem.OverBudget -> "${problem.link.take(120)} was not staged (${problem.detail})"
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

    private suspend fun finish(
        cycle: Cycle,
        trace: CyclePlanTrace,
        status: CycleStatus,
        summary: String,
        tasksDone: Int = 0,
        feedback: String? = null,
    ): TickResult {
        val guard = leaseGuards[cycle.pageId]
        if (guard == null) {
            api.updatePage(cycle.pageId, CycleMapper.finish(status, clock(), summary, tasksDone, feedback))
        } else {
            guard.mutex.withLock {
                guard.released = true
                api.updatePage(cycle.pageId, CycleMapper.finish(status, clock(), summary, tasksDone, feedback))
            }
        }
        attempts.remove(cycle.pageId)
        trace.outcome = status.label.lowercase()
        trace.summary = summary
        logger.info(
            "notion-cycles: cycle {} finished {} (planned={}, rejected={}, done={})",
            cycle.displayId ?: cycle.pageId, status.label, trace.accepted.size, trace.rejected.size, tasksDone,
        )
        return TickResult.Finished(cycle.displayId, status, summary)
    }

    private fun scopeTrace(goal: Goal, context: ScopeContext): ScopeTrace = ScopeTrace(
        mentions = goal.scope.mentions.size,
        readable = context.outcomes.count { it !is ScopeOutcome.Skipped },
        skipped = context.skipped.map { SkippedMentionTrace(it.mention.title, it.mention.kind.name.lowercase(), it.reason.name.lowercase(), it.detail) },
        proseChars = goal.scope.proseChars,
        proseExcerpt = goal.scope.proseExcerpt.takeIf { it.isNotEmpty() },
        ignoredLinks = goal.scope.ignoredLinks.take(10),
        serves = goal.serves.size,
        allowedPages = context.allowed.pageCount,
        allowedDatabases = context.allowed.databaseCount,
        allowedRows = context.allowed.rowCount,
        truncated = context.truncated,
    )

    private fun scopeLinkTrace(outcome: ScopeOutcome): LinkTrace {
        val link = notionPageUrl(outcome.mention.id)
        return when (outcome) {
            is ScopeOutcome.TableListed -> LinkTrace(link, "table", "${outcome.table}: ${outcome.rows} row(s)${if (outcome.truncated) ", more not read" else ""}")
            is ScopeOutcome.PageRead -> LinkTrace(link, "page", outcome.label)
            is ScopeOutcome.Skipped -> LinkTrace(link, "skipped", outcome.reason.name.lowercase() + (outcome.detail?.let { ": $it" } ?: ""))
        }
    }

    private fun scopeLines(context: ScopeContext): List<String> = context.outcomes.map { outcome ->
        val name = outcome.mention.title.ifBlank { outcome.mention.id }
        when (outcome) {
            is ScopeOutcome.TableListed -> "database $name: ${outcome.rows} row(s) read${if (outcome.truncated) ", more exist" else ""}"
            is ScopeOutcome.PageRead -> "page $name (${outcome.label}): read"
            is ScopeOutcome.Skipped -> "$name: could not be used (${outcome.reason.name.lowercase().replace('_', ' ')})"
        }
    }

    /** Facts about the run that QC cannot know, added by code to the Summary so they cannot be lost. */
    private fun extraNotes(trace: CyclePlanTrace): String = buildString {
        val scope = trace.scope
        if (scope != null && scope.proseChars > 0) append(" Scope held ${scope.proseChars} letters and digits of prose outside its mentions; it was ignored.")
        if (scope != null && scope.skipped.isNotEmpty()) append(" ${scope.skipped.size} Scope mention${if (scope.skipped.size == 1) " was" else "s were"} skipped (not readable).")
        if (trace.rejected.isNotEmpty()) append(" ${trace.rejected.size} proposed task${if (trace.rejected.size == 1) " was" else "s were"} rejected by validation.")
        trace.notes.filter { it.startsWith("task budget") }.forEach { append(" Task budget").append(it.removePrefix("task budget")).append('.') }
    }

    private fun explain(code: String): String = when {
        code == "no_llm_client" -> "no local model is available"
        code.startsWith("llm_call_failed") -> "the model call failed (${code.substringAfter(": ", "unknown error")})"
        code == "no_tool_call" -> "the model answered without the required tool call"
        code == "malformed_tool_input" -> "the model's answer was malformed"
        code == "empty_result" -> "the model returned an empty result"
        code == "unknown_outcome" -> "the model's answer had no usable outcome"
        else -> "the model step returned $code"
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

        private const val MANIFEST_PREFIX = "Cycle in progress: writing "
        private const val MANIFEST_SUFFIX = " planned tasks."
        private val MANIFEST = Regex("""writing (\d+) planned tasks""")

        /** What `Worker` says. Different after every restart, which is what lets a new process take over an old lease. */
        fun defaultWorkerId(): String {
            val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("arx")
            return "engram-dev@$host#${ProcessHandle.current().pid()}"
        }
    }
}
