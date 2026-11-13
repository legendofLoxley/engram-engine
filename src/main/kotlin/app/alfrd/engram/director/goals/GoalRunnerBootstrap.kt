package app.alfrd.engram.director.goals

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeDirectorMessageStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorCycles
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorWriter
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import app.alfrd.engram.director.ChangeMarkerReader
import app.alfrd.engram.director.DirectorTick
import com.arcadedb.database.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

sealed interface GoalRunnerStartup {
    data class Ready(val runner: GoalRunner) : GoalRunnerStartup
    data class Disabled(val reason: String) : GoalRunnerStartup
}

private val bootLog = LoggerFactory.getLogger("app.alfrd.engram.director.goals.GoalRunnerBootstrap")

/**
 * Builds the goal runner for the Director tick's identity. The runner exists whenever the tick's identity is set and the local model is configured, so the
 * single-shot trigger works; it starts cycles on its own only when `GOAL_RUNNER_ENABLED=true` (see [GoalRunnerConfig]). Either way, a goal a crash left running is
 * paused at startup, with a message and a change record.
 */
fun bootstrapGoalRunner(
    scope: CoroutineScope,
    db: Database,
    hermesActive: HermesActiveAssignmentRegistry,
    env: Map<String, String> = System.getenv(),
): GoalRunnerStartup {
    val raw = env[DirectorTick.ENV_IDENTITY]?.trim().orEmpty()
    if (raw.isEmpty()) return GoalRunnerStartup.Disabled("${DirectorTick.ENV_IDENTITY} is not set").also { bootLog.info("goal-runner: not built: {}", it.reason) }
    if (env["LLM_PROVIDER"]?.lowercase() != "local") return GoalRunnerStartup.Disabled("LLM_PROVIDER is not local").also { bootLog.info("goal-runner: not built: {}", it.reason) }
    val config = GoalRunnerConfig.fromEnvironment(env)
    val email = DebugConverseService.resolveUserId(raw)
    val structure = ArcadeHorizonStructureStore(db)
    val messages = ArcadeDirectorMessageStore(db)
    val writer = DirectorWriter(structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), directorMessages = messages), messages = messages)
    val markers = ChangeMarkerReader(db)
    val runner = GoalRunner(
        email = email, structure = structure, messages = messages, writer = writer, cycles = DirectorCycles(structure, messages),
        planner = GoalPlanDirector(LocalLlmClient(baseUrl = config.planUrl), config),
        stepper = GoalStepExecutor(LocalLlmClient(baseUrl = config.stepUrl), config),
        qc = GoalQcDirector(LocalLlmClient(baseUrl = config.planUrl), config),
        config = config, resources = SystemResourceProbe(), slots = LlamaSlotProbe(), activity = ConversationActivity.shared,
        hermesBusy = { hermesActive.activeByKind().values.sum() },
        cycleSeq = { markers.read(email)?.lastCycleSeq ?: 0L },
    )
    scope.launch {
        try { runner.recoverAfterRestart() } catch (e: Exception) { bootLog.warn("goal-runner: restart recovery failed: {}", e::class.java.simpleName) }
    }
    bootLog.info(
        "goal-runner: built identity={} enabled={} plan={} step={} kill-file={} (cycles start on their own only when GOAL_RUNNER_ENABLED=true)",
        raw, config.enabled, config.planUrl, config.stepUrl, config.killFile,
    )
    return GoalRunnerStartup.Ready(runner)
}
