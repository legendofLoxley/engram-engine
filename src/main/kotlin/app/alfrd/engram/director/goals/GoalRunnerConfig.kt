package app.alfrd.engram.director.goals

import java.nio.file.Path

/**
 * Everything the goal runner is configured by, in one place. **Off by default**: with `GOAL_RUNNER_ENABLED` unset the runner never starts a cycle on its own.
 * (The single-shot trigger, `POST /debug/goal-runner/run-once`, still works when it is off: it is a person asking for one cycle, not the runner choosing to.)
 *
 * | variable | default | meaning |
 * |---|---|---|
 * | `GOAL_RUNNER_ENABLED` | `false` | the switch: `true` lets the Director tick start cycles when the box is idle |
 * | `GOAL_RUNNER_KILL_FILE` | `~/alfrd/goal-runner/STOP` | while this file exists no cycle starts and a running one stops at the next step; remove it to allow work again |
 * | `GOAL_RUNNER_IDLE_MINUTES` | 10 | no conversation (owner Horizon call, chat turn) for this long before a cycle may start |
 * | `GOAL_RUNNER_MIN_GAP_MINUTES` | 30 | the least time between two cycles of one goal |
 * | `GOAL_RUNNER_PLAN_URL` | `http://127.0.0.1:8083/v1` | the strongest text model: plan and QC |
 * | `GOAL_RUNNER_STEP_URL` | `http://127.0.0.1:8081/v1` | the fast model: steps. Needs two slots; the runner uses one |
 * | `GOAL_RUNNER_MAX_STEPS` | 8 | the most steps a plan may have |
 * | `GOAL_RUNNER_REPLAN_CAP` | 2 | the most times a major impact may replan before the goal is paused |
 * | `GOAL_RUNNER_DEFAULT_MINUTES` / `_TOKENS` | 30 / 60000 | the budget of a goal that names none (or only one of the two) |
 * | `GOAL_RUNNER_MIN_FREE_RAM_MB` / `_GPU_MB` | 4096 / 2048 | no cycle starts below these |
 * | `GOAL_RUNNER_REASONING` | `on` | whether the plan and QC calls let the model reason; the Notion cycle code does, and a forced tool call after a pre-closed think block fails on long prompts |
 * | `GOAL_RUNNER_STEP_REASONING` | `on` | the same for step calls, so reasoning can be off for steps while it stays on for plan and QC |
 * | `GOAL_RUNNER_STEP_MAX_TOKENS`, `GOAL_RUNNER_STEP_TIMEOUT_SECONDS` | 16000, 900 | the most a step may generate (reasoning counts) and wait |
 * | `GOAL_RUNNER_MIN_EVIDENCE_SCORE` | 1.5 | the least relevance for a closed item to count as evidence |
 */
data class GoalRunnerConfig(
    val enabled: Boolean = false,
    val killFile: Path = Path.of(System.getProperty("user.home"), "alfrd", "goal-runner", "STOP"),
    val idleMinutes: Long = 10,
    val minGapMinutes: Long = 30,
    val planUrl: String = "http://127.0.0.1:8083/v1",
    val stepUrl: String = "http://127.0.0.1:8081/v1",
    val maxSteps: Int = 8,
    val replanCap: Int = 2,
    val defaultMinutes: Int = 30,
    val defaultTokens: Long = 60_000L,
    val minFreeRamMb: Long = 4096L,
    val minFreeGpuMb: Long = 2048L,
    /** Whether plan and QC calls let the model reason. */
    val reasoning: Boolean = true,
    /** Whether step calls let the model reason. On by default, as the Notion cycle's calls are; off is for measurement and is safe only because claims are checked in code. */
    val stepReasoning: Boolean = true,
    /** The least relevance (shared words and concepts) for a closed item to count as evidence for an open one. */
    val minEvidenceScore: Double = 1.5,
    /** How often a running model call checks whether a conversation has started. */
    val yieldPollMillis: Long = 1_500L,
    /** Items of the goal's project shown to the planner, and per step. */
    val scopeItems: Int = 60,
    val stepItems: Int = 8,
    /** Related closed items and notes handed to a step with each open item, and the size its prompt may grow to before evidence is trimmed and items left out. */
    val evidencePerItem: Int = 4,
    val stepPromptChars: Int = 12_000,
    val maxItemsPerStep: Int = 5,
    val sampleSize: Int = 3,
    val planMaxTokens: Int = 12_000,
    val stepMaxTokens: Int = 16_000,
    val qcMaxTokens: Int = 8_000,
    val planTimeoutMs: Long = 600_000L,
    val stepTimeoutMs: Long = 900_000L,
) {
    companion object {
        const val ENV_ENABLED = "GOAL_RUNNER_ENABLED"
        const val ENV_KILL_FILE = "GOAL_RUNNER_KILL_FILE"

        fun fromEnvironment(env: Map<String, String> = System.getenv()): GoalRunnerConfig {
            val base = GoalRunnerConfig()
            fun text(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun long(name: String, default: Long, range: LongRange): Long = text(name)?.toLongOrNull()?.takeIf { it in range } ?: default
            return base.copy(
                enabled = text(ENV_ENABLED)?.lowercase() in setOf("true", "on", "1", "yes"),
                killFile = text(ENV_KILL_FILE)?.let(Path::of) ?: base.killFile,
                idleMinutes = long("GOAL_RUNNER_IDLE_MINUTES", base.idleMinutes, 1L..1_440L),
                minGapMinutes = long("GOAL_RUNNER_MIN_GAP_MINUTES", base.minGapMinutes, 0L..10_080L),
                planUrl = text("GOAL_RUNNER_PLAN_URL")?.trimEnd('/') ?: base.planUrl,
                stepUrl = text("GOAL_RUNNER_STEP_URL")?.trimEnd('/') ?: base.stepUrl,
                maxSteps = long("GOAL_RUNNER_MAX_STEPS", base.maxSteps.toLong(), 1L..20L).toInt(),
                replanCap = long("GOAL_RUNNER_REPLAN_CAP", base.replanCap.toLong(), 0L..5L).toInt(),
                defaultMinutes = long("GOAL_RUNNER_DEFAULT_MINUTES", base.defaultMinutes.toLong(), 1L..1_440L).toInt(),
                defaultTokens = long("GOAL_RUNNER_DEFAULT_TOKENS", base.defaultTokens, 1_000L..5_000_000L),
                minFreeRamMb = long("GOAL_RUNNER_MIN_FREE_RAM_MB", base.minFreeRamMb, 512L..262_144L),
                minFreeGpuMb = long("GOAL_RUNNER_MIN_FREE_GPU_MB", base.minFreeGpuMb, 0L..262_144L),
                reasoning = text("GOAL_RUNNER_REASONING")?.lowercase() !in setOf("off", "false", "0", "no"),
                stepReasoning = text("GOAL_RUNNER_STEP_REASONING")?.lowercase() !in setOf("off", "false", "0", "no"),
                minEvidenceScore = text("GOAL_RUNNER_MIN_EVIDENCE_SCORE")?.toDoubleOrNull()?.takeIf { it in 0.0..20.0 } ?: base.minEvidenceScore,
                stepMaxTokens = long("GOAL_RUNNER_STEP_MAX_TOKENS", base.stepMaxTokens.toLong(), 1_000L..64_000L).toInt(),
                stepTimeoutMs = long("GOAL_RUNNER_STEP_TIMEOUT_SECONDS", base.stepTimeoutMs / 1000, 30L..7_200L) * 1000,
            )
        }
    }
}

/** What a goal may spend: wall-clock minutes of runner work and model tokens, summed over all its cycles. */
data class GoalBudget(val minutes: Int, val tokens: Long) {
    companion object {
        private val MINUTES = Regex("""(\d+(?:\.\d+)?)\s*(?:minutes?|mins?|m(?!\s*tokens?))\b""", RegexOption.IGNORE_CASE)
        private val HOURS = Regex("""(\d+(?:\.\d+)?)\s*(?:hours?|hrs?|h)\b""", RegexOption.IGNORE_CASE)
        private val TOKENS = Regex("""(\d[\d,]*(?:\.\d+)?)\s*(k|m)?\s*tokens?\b""", RegexOption.IGNORE_CASE)

        /**
         * Reads a goal's free-text budget ("30 minutes, 50k tokens", "2 hours"). What the text does not say falls back to the configured default; a figure that is absurd
         * is clamped. The text is the owner's, frozen at approval, so this is read each time and never stored.
         */
        fun parse(text: String?, config: GoalRunnerConfig): GoalBudget {
            val t = text.orEmpty()
            val minutes = MINUTES.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()
                ?: HOURS.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 60).toInt() }
                ?: config.defaultMinutes
            val tokens = TOKENS.find(t)?.let { m ->
                val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@let null
                val scale = when (m.groupValues[2].lowercase()) { "k" -> 1_000.0; "m" -> 1_000_000.0; else -> 1.0 }
                (n * scale).toLong()
            } ?: config.defaultTokens
            return GoalBudget(minutes.coerceIn(1, 1_440), tokens.coerceIn(1_000L, 5_000_000L))
        }
    }
}
