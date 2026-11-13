package app.alfrd.engram.director.goals

import app.alfrd.engram.cognitive.pipeline.agentwork.PromptText
import app.alfrd.engram.cognitive.pipeline.horizon.ChangeContext
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorCycle
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorCycles
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorMessage
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorMessageKind
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorMessageStore
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorWriter
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.ItemRecord
import app.alfrd.engram.cognitive.pipeline.horizon.ItemWords
import app.alfrd.engram.cognitive.pipeline.horizon.LinkItems
import app.alfrd.engram.cognitive.pipeline.horizon.RecordItem
import app.alfrd.engram.cognitive.pipeline.horizon.WorkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.ZoneId

/**
 * The only two things the runner may do to a goal's state. The type has no way to say done or stopped: closing a goal is the owner's, through the channel, and
 * the runner reports that a goal looks finished with a message instead.
 */
enum class RunnerState(val to: WorkState) { RUNNING(WorkState.ACTIVE), PAUSED(WorkState.WAITING) }

@Serializable
enum class RunOutcome { NO_GOAL, SKIPPED, COMPLETED, PAUSED, YIELDED }

@Serializable
data class StepReport(
    val number: Int, val title: String, val kind: String, val outcome: String, val millis: Long, val tokens: Long, val completionTokens: Long,
    val wrote: Int, val refused: Int, val covered: Int, val discarded: Int, val impact: String, val note: String,
)

/** What one cycle did. Counts, timings and the Director's own proposals; nothing from the owner's memory beyond what the Director itself wrote. */
@Serializable
data class RunReport(
    val outcome: RunOutcome,
    val reason: String,
    val goalUid: String? = null,
    val cycle: Int? = null,
    val steps: List<StepReport> = emptyList(),
    val planMillis: Long = 0, val planTokens: Long = 0, val planCompletionTokens: Long = 0,
    val qcMillis: Long = 0, val qcTokens: Long = 0, val qcCompletionTokens: Long = 0,
    val totalMillis: Long = 0, val totalTokens: Long = 0,
    val replans: Int = 0,
    val looksMet: String? = null,
    val proposals: List<String> = emptyList(),
    val messages: List<String> = emptyList(),
    val planRejected: List<String> = emptyList(),
)

private data class Written(val applied: Int, val refused: Int, val covered: Int) { val attempted get() = applied + refused }

private sealed interface Yielded<out T> {
    data class Done<T>(val value: T) : Yielded<T>
    data class Stopped(val why: String) : Yielded<Nothing>
}

/**
 * The goal runner: one cycle of one approved goal, when the box is idle. **Code, not the model**: it chooses the goal, moves its state, keeps the budget, decides when
 * to stop, and writes only through [DirectorWriter]. The models plan, do steps and judge; none of them sees a uid, a verb or a state, and none can move a goal.
 *
 * ### Idle
 * A cycle may start only when every one of these holds (the single-shot trigger waives only the first):
 *  1. no conversation for [GoalRunnerConfig.idleMinutes] (owner Horizon call, chat turn, debug conversation: [ConversationActivity]);
 *  2. no Hermes assignment in flight;
 *  3. the kill switch is off ([GoalRunnerConfig.killFile] does not exist);
 *  4. free RAM at least [GoalRunnerConfig.minFreeRamMb] and, where it can be read, free GPU memory at least [GoalRunnerConfig.minFreeGpuMb];
 *  5. the step server has at least two free slots (the runner takes one; conversation always keeps one) and the plan server a free slot.
 * While a cycle runs it re-checks 1, 2, 3 and the step server's other slot between steps and every [GoalRunnerConfig.yieldPollMillis] during a model call; when any
 * fails it cancels the call (which closes the connection and stops generation), pauses the goal and sends a quiet message. What it wrote before stays, and
 * `retract_cycle` removes it.
 */
class GoalRunner(
    private val email: String,
    private val structure: HorizonStructureStore,
    private val messages: DirectorMessageStore,
    private val writer: DirectorWriter,
    private val cycles: DirectorCycles,
    private val planner: GoalPlanDirector,
    private val stepper: GoalStepExecutor,
    private val qc: GoalQcDirector,
    val config: GoalRunnerConfig,
    private val resources: ResourceProbe,
    private val slots: SlotProbe,
    private val activity: ConversationActivity,
    private val hermesBusy: () -> Int,
    private val cycleSeq: suspend () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val logger = LoggerFactory.getLogger(GoalRunner::class.java)
    private val gate = Mutex()
    @Volatile private var last: RunReport? = null
    @Volatile private var runningGoal: String? = null

    fun lastReport(): RunReport? = last
    fun runningGoalUid(): String? = runningGoal

    /** Called by the Director tick after each tick. Starts a cycle in the background when the runner is on and none is running. */
    fun maybeStart(scope: CoroutineScope) {
        if (!config.enabled || gate.isLocked) return
        scope.launch {
            try { runOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { logger.warn("goal-runner: cycle failed: {}", e::class.java.simpleName) }
        }
    }

    // ── Restart ──────────────────────────────────────────────────────────────

    /**
     * A goal a cycle was running when the process died is paused, with a visible message and a change record (actor `director`). Never resumed: the owner decides.
     * A goal that is running but between cycles (its last cycle ended) is left as it is. Returns the goals it paused.
     */
    suspend fun recoverAfterRestart(): List<String> {
        val paused = ArrayList<String>()
        for (goal in structure.listGoals(email)) {
            if (goal.approval != "approved" || goal.workState != WorkState.ACTIVE) continue
            val thread = messages.thread(email, goal.uid)
            val latest = thread.maxOfOrNull { it.cycle } ?: continue
            val ended = thread.any { it.cycle == latest && it.usageMillis > 0 }
            if (ended) continue
            move(goal, RunnerState.PAUSED, "restart_recovery")
            messages.post(email, goal.uid, latest, DirectorMessageKind.FAILURE,
                "The engine restarted while cycle $latest of this goal was running. The goal is paused and nothing was resumed. What the cycle wrote so far is kept; retract_cycle removes it. Resume the goal when you want it to continue.",
                usageMillis = 1)
            paused += goal.uid
            logger.warn("goal-runner: goal {} was running when the engine stopped; paused", goal.uid)
        }
        return paused
    }

    // ── One cycle ────────────────────────────────────────────────────────────

    /**
     * Runs at most one cycle now. [manual] is the single-shot trigger: it skips the idle wait (a person is asking) but nothing else. [goalUid] names the goal to run;
     * it still has to be an approved goal that is not paused, stopped or done.
     */
    suspend fun runOnce(manual: Boolean = false, goalUid: String? = null): RunReport {
        if (!gate.tryLock()) return skipped("a cycle is already running")
        return try { cycle(manual, goalUid).also { last = it } } finally { runningGoal = null; gate.unlock() }
    }

    private fun skipped(why: String) = RunReport(RunOutcome.SKIPPED, why)

    private fun killed(): Boolean = runCatching { Files.exists(config.killFile) }.getOrDefault(false)

    private suspend fun preflight(manual: Boolean): String? {
        if (killed()) return "the kill switch is on (${config.killFile.fileName} exists)"
        if (!manual) {
            val quietFor = clock() - activity.lastAt()
            if (activity.lastAt() > 0 && quietFor < config.idleMinutes * 60_000) return "a conversation was ${quietFor / 60_000} min ago; the box is not idle for ${config.idleMinutes} min"
        }
        if (hermesBusy() > 0) return "a Hermes assignment is in flight"
        resources.freeRamMb()?.let { if (it < config.minFreeRamMb) return "free RAM is $it MiB, below ${config.minFreeRamMb}" }
        resources.freeGpuMb()?.let { if (it < config.minFreeGpuMb) return "free GPU memory is $it MiB, below ${config.minFreeGpuMb}" }
        val step = slots.slots(config.stepUrl) ?: return "the step model server cannot be reached"
        if (step.total < 2 || step.free < 2) return "the step model server has ${step.free} of ${step.total} slots free; the runner needs two free so conversation always keeps one"
        if (config.planUrl != config.stepUrl) {
            val plan = slots.slots(config.planUrl) ?: return "the plan model server cannot be reached"
            if (plan.free < 1) return "the plan model server has no free slot"
        }
        return null
    }

    private suspend fun choose(goalUid: String?, manual: Boolean): Pair<ItemRecord?, String> {
        val all = structure.listGoals(email)
        if (goalUid != null) {
            val g = all.firstOrNull { it.uid == goalUid } ?: return null to "no such goal"
            return eligibility(g, requireGap = false).let { why -> if (why == null) g to "" else null to why }
        }
        val eligible = all.filter { eligibility(it, requireGap = !manual) == null }
            .sortedWith(compareBy<ItemRecord>({ it.approvedAt ?: Long.MAX_VALUE }, { it.createdAt }))
        return eligible.firstOrNull() to if (eligible.isEmpty()) "no approved goal is waiting for work" else ""
    }

    /** Null when the runner may work this goal now; otherwise why not. Only an approved goal that is open or running, in an active project, with no finished message waiting for the owner. */
    private suspend fun eligibility(g: ItemRecord, requireGap: Boolean): String? {
        if (g.kind != ItemKind.GOAL) return "that is not a goal"
        if (g.approval != "approved") return "the goal is only proposed; the owner has not approved it"
        when (g.workState) {
            WorkState.OPEN, WorkState.ACTIVE -> Unit
            WorkState.WAITING -> return "the goal is paused; the owner resumes it"
            WorkState.DONE, WorkState.DROPPED -> return "the goal is done or stopped"
            else -> return "the goal is not in a state the runner works"
        }
        val projectUid = g.projectUid ?: return "the goal is not filed under a project"
        if (structure.getContainer(email, projectUid)?.status != "active") return "the goal's project is no longer active"
        val thread = messages.thread(email, g.uid).filter { !it.retracted }
        if (thread.lastOrNull { it.kind != DirectorMessageKind.PROGRESS || it.usageMillis > 0 }?.kind == DirectorMessageKind.FINISHED) {
            return "the goal looks finished and waits for the owner to close it"
        }
        if (requireGap) {
            val lastAt = thread.maxOfOrNull { it.createdAt } ?: 0L
            if (lastAt > 0 && clock() - lastAt < config.minGapMinutes * 60_000) return "the goal had a cycle less than ${config.minGapMinutes} min ago"
        }
        return null
    }

    private suspend fun cycle(manual: Boolean, goalUid: String?): RunReport {
        preflight(manual)?.let { return skipped(it) }
        val (goal, why) = choose(goalUid, manual)
        if (goal == null) return RunReport(RunOutcome.NO_GOAL, why)
        runningGoal = goal.uid
        val startedAt = clock()
        val budget = GoalBudget.parse(goal.budget, config)
        val prior = messages.usage(email, goal.uid)
        if (prior.first >= budget.tokens || prior.second >= budget.minutes * 60_000L) {
            val n = maxOf(messages.latestCycle(email, goal.uid), 1)
            move(goal, RunnerState.PAUSED, "budget_spent")
            val text = "The goal's budget is spent (${prior.first} of ${budget.tokens} tokens, ${prior.second / 60_000} of ${budget.minutes} min). It is paused; raise the budget by approving a replacement goal, or close this one."
            messages.post(email, goal.uid, n, DirectorMessageKind.FAILURE, text, usageMillis = 1)
            return RunReport(RunOutcome.PAUSED, "budget spent", goal.uid, n, messages = listOf(text))
        }

        if (goal.workState == WorkState.OPEN) move(goal, RunnerState.RUNNING, "goal_runner_start")
        val cycle = cycles.begin(email, goal.uid) ?: return skipped("a cycle number could not be taken")
        logger.info("goal-runner: cycle {} of goal {} starting", cycle.number, goal.uid)
        return Cycle(goal, cycle, budget, prior, startedAt).run()
    }

    // ── The cycle's state ────────────────────────────────────────────────────

    private inner class Cycle(val goal: ItemRecord, val cycle: DirectorCycle, val budget: GoalBudget, val prior: Pair<Long, Long>, val startedAt: Long) {
        var usage = CallUsage.NONE
        val stepReports = ArrayList<StepReport>()
        val stepLines = ArrayList<String>()
        val sentMessages = ArrayList<String>()
        val proposals = ArrayList<String>()
        val sourceOf = HashMap<String, String>()
        var planMillis = 0L; var planTokens = 0L; var planCompletion = 0L
        var qcMillis = 0L; var qcTokens = 0L; var qcCompletion = 0L
        var replans = 0
        var looksMet: String? = null
        var planRejected: List<String> = emptyList()
        var questionsSent = 0

        fun elapsed() = clock() - startedAt
        fun spent(): String? = when {
            prior.first + usage.tokens >= budget.tokens -> "tokens (${prior.first + usage.tokens} of ${budget.tokens})"
            prior.second + elapsed() >= budget.minutes * 60_000L -> "time (${(prior.second + elapsed()) / 60_000} of ${budget.minutes} min)"
            else -> null
        }

        fun report(outcome: RunOutcome, reason: String) = RunReport(
            outcome, reason, goal.uid, cycle.number, stepReports, planMillis, planTokens, planCompletion, qcMillis, qcTokens, qcCompletion,
            elapsed(), usage.tokens, replans, looksMet, proposals, sentMessages, planRejected,
        )

        suspend fun seq() = cycleSeq()

        /** Why this cycle must stop now, or null. Checked between steps and, by [yieldable], during a call. */
        suspend fun mustStop(during: Boolean): String? {
            if (killed()) return "the kill switch was turned on"
            if (activity.since(startedAt)) return "a conversation started"
            if (hermesBusy() > 0) return "a Hermes assignment started"
            slots.slots(config.stepUrl)?.let { if (it.free < (if (during) it.total - 1 else it.total)) return "the model server's other slot was taken" }
            return null
        }

        suspend fun <T> yieldable(block: suspend () -> T): Yielded<T> = coroutineScope {
            var why: String? = null
            val work = async { block() }
            val watcher = launch {
                while (isActive) {
                    delay(config.yieldPollMillis)
                    val stop = mustStop(during = true)
                    if (stop != null) { why = stop; work.cancel(); return@launch }
                }
            }
            try {
                Yielded.Done(work.await())
            } catch (e: CancellationException) {
                if (why != null) Yielded.Stopped(why!!) else throw e
            } finally {
                watcher.cancel()
            }
        }

        suspend fun end(kind: DirectorMessageKind, text: String): DirectorMessage? {
            val message = writer.send(email, cycle, kind, PromptText.excerpt(text, 560), usageTokens = usage.tokens.coerceAtLeast(1), usageMillis = elapsed().coerceAtLeast(1))
            sentMessages += text
            return message
        }

        suspend fun interrupted(why: String): RunReport {
            move(goal, RunnerState.PAUSED, "cycle_interrupted")
            end(DirectorMessageKind.PROGRESS, "Cycle ${cycle.number} was interrupted: $why. The goal is paused. What it wrote so far is kept; retract_cycle removes it.")
            return report(RunOutcome.YIELDED, why)
        }

        suspend fun failedAndPaused(cause: String, text: String): RunReport {
            move(goal, RunnerState.PAUSED, cause)
            end(DirectorMessageKind.FAILURE, text)
            return report(RunOutcome.PAUSED, text)
        }

        suspend fun run(): RunReport {
            return try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("goal-runner: cycle {} of goal {} failed: {}", cycle.number, goal.uid, e::class.java.simpleName)
                failedAndPaused("cycle_failed", "Cycle ${cycle.number} failed (${e::class.java.simpleName}). The goal is paused.")
            }
        }

        suspend fun work(): RunReport {
            val project = structure.getContainer(email, goal.projectUid!!)!!
            val scopeItems = loadScope()
            val scope = ScopeMap(scopeItems.first)
            val previous = messages.thread(email, goal.uid).filter { !it.retracted && it.cycle < cycle.number && it.usageMillis > 0 }.lastOrNull()?.text

            fun planPrompt(done: List<String>, reason: String?) =
                GoalPrompts.plan(goal, project.name, scope, scope.entries.map { it.value }, previous, config.maxSteps, done, reason, scopeItems.second)

            mustStop(false)?.let { return interrupted(it) }
            var plan: GoalPlan = when (val r = yieldable { planner.plan(planPrompt(emptyList(), null), 1, scope) }) {
                is Yielded.Stopped -> return interrupted(r.why)
                is Yielded.Done -> r.value.also { accountPlan(it.usage) }.let { o -> o.plan ?: return failedAndPaused("plan_failed", "Cycle ${cycle.number} could not make a plan (${o.failure}). The goal is paused.") }
            }
            planRejected = plan.rejected
            var index = 0
            var failures = 0
            val ranTitles = ArrayList<String>()
            while (index < plan.steps.size) {
                mustStop(false)?.let { return interrupted(it) }
                spent()?.let { return failedAndPaused("budget_spent", "Cycle ${cycle.number} stopped: the goal's budget is spent on $it. The goal is paused.") }
                val step = plan.steps[index]
                val items = step.refs.mapNotNull { r -> scope.resolve(r)?.let { r to it } }.take(config.stepItems)
                val prompt = GoalPrompts.step(goal, step, items, stepLines, config.maxItemsPerStep)
                val result = when (val r = yieldable { stepper.execute(prompt, scope) }) {
                    is Yielded.Stopped -> return interrupted(r.why)
                    is Yielded.Done -> r.value
                }
                usage += result.usage
                val written = writeStep(step, result, scope)
                ranTitles += step.title
                stepLines += "step ${step.number} ${step.title}: ${result.summary.ifEmpty { result.failure ?: "" }}"
                if (result.outcome == StepOutcome.FAILED) failures++
                // The gate. The model's word on impact is the floor; code raises it when a step's writes were mostly refused, because the plan then wants what the Director may not do.
                var impact = result.impact
                var note = result.impactReason
                if (written.refused >= 2 && written.refused * 2 >= written.attempted) { impact = Impact.MAJOR; note = "the step's writes were mostly refused" }
                if (result.outcome == StepOutcome.FAILED && impact == Impact.MAJOR) impact = Impact.MINOR // a failed call has no view of the plan
                stepReports += StepReport(
                    step.number, step.title, step.kind.label, result.outcome.name.lowercase(), result.usage.millis, result.usage.tokens, result.usage.completionTokens,
                    written.applied, written.refused, written.covered, result.discarded, impact.name.lowercase(), note,
                )
                if (failures >= 3 || (failures >= 2 && failures == stepReports.size)) {
                    return failedAndPaused("model_failed", "Cycle ${cycle.number} stopped: the model gave no usable answer for $failures steps. The goal is paused.")
                }
                if (impact == Impact.MAJOR) {
                    if (replans >= config.replanCap) {
                        return failedAndPaused("replan_cap", "Cycle ${cycle.number} stopped: the plan changed in a major way ${replans + 1} times (the limit is ${config.replanCap}). The goal is paused for the owner to look at.")
                    }
                    replans++
                    mustStop(false)?.let { return interrupted(it) }
                    val version = plan.version + 1
                    plan = when (val r = yieldable { planner.plan(planPrompt(ranTitles, note), version, scope) }) {
                        is Yielded.Stopped -> return interrupted(r.why)
                        is Yielded.Done -> r.value.also { accountPlan(it.usage) }.let { o -> o.plan ?: return failedAndPaused("plan_failed", "Cycle ${cycle.number} could not replan (${o.failure}). The goal is paused.") }
                    }
                    index = 0
                    continue
                }
                index++
            }
            return qcAndClose(scope)
        }

        fun accountPlan(u: CallUsage) { usage += u; planMillis += u.millis; planTokens += u.tokens; planCompletion += u.completionTokens }

        /** The goal's project as the models see it: open and running things first, then the rest newest first; goals themselves are never shown. */
        suspend fun loadScope(): Pair<List<ItemRecord>, Int> {
            val all = structure.itemsIn(email, goal.projectUid!!).filter { it.kind != ItemKind.GOAL && !(it.directorGoalUid != null && it.workState == WorkState.DROPPED) }
            val live = { i: ItemRecord -> i.workState == null || i.workState == WorkState.OPEN || i.workState == WorkState.ACTIVE || i.workState == WorkState.WAITING }
            val sorted = all.sortedWith(compareBy<ItemRecord>({ if (live(it)) 0 else 1 }, { -it.createdAt }))
            return sorted.take(config.scopeItems) to all.size
        }

        suspend fun writeStep(step: PlanStep, result: StepResult, scope: ScopeMap): Written {
            var applied = 0; var refused = 0; var covered = 0
            val known = scope.entries.map { ItemWords.normalize(it.value.text) }.toHashSet()
            val sequence = seq()
            for (item in result.items) {
                val norm = ItemWords.normalize(item.text)
                if (!known.add(norm)) { covered++; continue } // the graph already says this
                val made = writer.apply(email, cycle, sequence, listOf(RecordItem(item.kind, item.text)), zone).single()
                if (!made.applied || made.resultUid == null) { refused++; continue }
                applied++
                proposals += "${item.kind.name.lowercase()}: ${item.text}"
                val target = scope.resolve(item.aboutRef)
                if (target != null) {
                    sourceOf[made.resultUid!!] = target.text
                    val link = writer.apply(email, cycle, sequence, listOf(LinkItems(made.resultUid!!, target.uid, item.text)), zone).single()
                    if (!link.applied) refused++
                }
                if (step.kind == StepKind.ASK && item.kind == ItemKind.QUESTION && questionsSent < MAX_QUESTIONS_PER_CYCLE) {
                    questionsSent++
                    end2(DirectorMessageKind.QUESTION, item.text, made.resultUid)
                }
            }
            for (s in result.suggestions) {
                val target = scope.resolve(s.targetRef) ?: continue
                val out = writer.suggestStateChange(email, cycle, sequence, target.uid, s.state, s.reason, zone)
                if (out.all { it.applied }) { applied++; proposals += "suggest ${s.state.wire()}: ${target.text.take(80)} — ${s.reason}" } else refused++
            }
            return Written(applied, refused, covered)
        }

        suspend fun end2(kind: DirectorMessageKind, text: String, answers: String?) {
            writer.send(email, cycle, kind, PromptText.excerpt(text, 560), answers)
            sentMessages += text
        }

        suspend fun qcAndClose(scope: ScopeMap): RunReport {
            mustStop(false)?.let { return interrupted(it) }
            val mine = structure.directorItems(email, goal.uid, cycle.number)
            val sampleItems = sample(mine, config.sampleSize)
            val samples = sampleItems.mapIndexed { i, item -> "s${i + 1}" to (item to (sourceOf[item.uid] ?: item.sourceQuote ?: "(no source item was named)")) }
            val prompt = GoalPrompts.qc(
                goal, cycle.number, stepLines, mine.map { "${it.kind.name.lowercase()}: ${it.text}" },
                samples.map { (ref, p) -> ref to (p.first.text to p.second) },
            )
            val outcome = when (val r = yieldable { qc.decide(prompt, samples.map { it.first }) }) {
                is Yielded.Stopped -> return interrupted(r.why)
                is Yielded.Done -> r.value
            }
            usage += outcome.usage; qcMillis = outcome.usage.millis; qcTokens = outcome.usage.tokens; qcCompletion = outcome.usage.completionTokens
            if (outcome.failure != null) {
                end(DirectorMessageKind.FAILURE, "Cycle ${cycle.number} finished its steps but the close-out could not run (${outcome.failure}). The goal is still running.")
                return report(RunOutcome.COMPLETED, "qc failed: ${outcome.failure}")
            }
            // The model may not say "met" over an item its own check found unsupported.
            val unsupported = samples.filter { (ref, _) -> outcome.verdicts[ref] == "unsupported" }
            looksMet = if (outcome.looksMet == "yes" && unsupported.isNotEmpty()) "partly" else outcome.looksMet
            val sequence = seq()
            if (outcome.unclear.isNotBlank() && !outcome.unclear.equals("none", ignoreCase = true)) {
                writer.apply(email, cycle, sequence, listOf(RecordItem(ItemKind.FACT, "Left unclear by the goal: ${outcome.unclear}")), zone)
            }
            unsupported.forEach { (_, p) -> writer.apply(email, cycle, sequence, listOf(RecordItem(ItemKind.FACT, "Check this: its source does not support \"${p.first.text.take(160)}\"")), zone) }
            val counts = "${stepReports.count { it.outcome == "done" }} of ${stepReports.size} steps done, ${mine.size} items written"
            if (looksMet == "yes") {
                end(DirectorMessageKind.FINISHED, "The done-when looks met after cycle ${cycle.number} ($counts): ${outcome.summary} Only you can close the goal.")
            } else {
                end(DirectorMessageKind.PROGRESS, "Cycle ${cycle.number}: $counts. Goal looks met: $looksMet. ${outcome.summary}")
            }
            return report(RunOutcome.COMPLETED, "cycle complete")
        }

        fun sample(items: List<ItemRecord>, n: Int): List<ItemRecord> {
            val real = items.filter { it.kind != ItemKind.FACT || !it.text.startsWith("Suggestion:") }
            if (real.size <= n) return real
            return (0 until n).map { real[it * real.size / n] }
        }
    }

    // ── State moves ──────────────────────────────────────────────────────────

    /** Moves a goal's state with a change record whose actor is `director` and whose cause names why. The only state moves the runner makes; see [RunnerState]. */
    private suspend fun move(goal: ItemRecord, to: RunnerState, cause: String) {
        val ok = withContext(ChangeContext("goal_runner:$cause", "director")) {
            structure.setState(email, cycleSeq(), goal.uid, to.to, waitingOn = if (to == RunnerState.PAUSED) "the owner" else null)
        }
        if (!ok) logger.warn("goal-runner: could not move goal {} to {}", goal.uid, to)
    }

    companion object {
        const val MAX_QUESTIONS_PER_CYCLE = 2
    }
}
