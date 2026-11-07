package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.agentwork.CycleProcessor
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.agentwork.PlanRoute
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonAssembler
import com.arcadedb.database.Database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the debug surface shows about the tick: the latest marker and render, and when each happened. */
/**
 * No property has a default on purpose: the debug route's JSON does not write defaults, and a reader must see `changes: []` and
 * `ticks: 0` rather than a missing key that reads as "unknown".
 */
@Serializable
data class DirectorTickSnapshot(
    val enabled: Boolean,
    val identity: String?,
    val intervalSeconds: Long?,
    val ticks: Long,
    val lastTickAt: String?,
    val lastRenderAt: String?,
    val lastRenderReason: String?,
    val marker: ChangeMarker?,
    val changes: List<ChangeMarker.Change>,
    val lastError: String?,
    val render: String?,
    val disabledReason: String?,
) {
    companion object {
        fun disabled(reason: String) = DirectorTickSnapshot(false, null, null, 0, null, null, null, null, emptyList(), null, null, reason)
    }
}

/** What one [DirectorTick.tickOnce] did. Counts and timings only. */
data class TickReport(
    val marker: ChangeMarker?,
    val changes: List<ChangeMarker.Change>,
    val rendered: Boolean,
    val reason: String,
    val itemsInView: Int,
    val renderChars: Int,
    val markerMs: Long,
    val renderMs: Long,
    val error: String? = null,
)

/**
 * The Director's standing-context tick for one identity. Each tick reads a cheap [ChangeMarker]; when it differs from the previous tick's,
 * or an hour has passed since the last render, it assembles the Context Horizon with no message, wraps it in "Your situation"
 * ([DirectorPromptRenderer]) and writes the result to [outDir]. It calls no model and writes nothing to the graph.
 *
 * **Why the Horizon can be built here.** [HorizonAssembler.assemble] takes only an identity and a cycle number and reads: it makes no
 * write (no nudge, no focus change, no cycle number, no request record) and the seeder, the only thing that turns words into nudges, is
 * not on this path at all. The cycle number given is `lastCycleSeq + 1` and is never allocated: this is "the cycle that would come next",
 * so nothing reads as "just noted" and recent-evidence and nudge windows age by one cycle, which is the honest reading of "some time later,
 * nobody has spoken". A real turn still allocates that number itself.
 *
 * Output: `latest.md` (replaced atomically) and `tick-<UTC time>.md`, the newest [keep] of the latter. The files hold the identity's own
 * memory text, so the folder is mode 700 and the files 600. Logs carry counts and timings only.
 *
 * Scheduling follows the Notion poll: the first tick runs at once, a tick runs to completion, then the loop waits [interval].
 */
class DirectorTick(
    private val email: String,
    private val label: String,
    val interval: Duration,
    private val outDir: Path,
    private val markers: ChangeMarkerReader,
    private val assembler: HorizonAssembler,
    private val probe: SelfStateProbe,
    private val notion: () -> NotionState,
    private val hermes: () -> Map<String, Int>,
    private val budgets: () -> BudgetDefaults,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val keep: Int = KEEP,
    private val renderEvery: Duration = Duration.ofHours(1),
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        require(keep >= 1) { "keep must be at least 1" }
    }

    private val logger = LoggerFactory.getLogger(DirectorTick::class.java)
    private val lock = Any()
    private var ticks = 0L
    private var previous: ChangeMarker? = null
    private var lastTickAt: Instant? = null
    private var lastRenderAt: Instant? = null
    private var lastRenderReason: String? = null
    private var lastMarker: ChangeMarker? = null
    private var lastChanges: List<ChangeMarker.Change> = emptyList()
    private var lastRender: String? = null
    private var lastError: String? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            tickOnce()
            delay(interval.toMillis())
        }
    }

    /** One tick. Never throws except to propagate cancellation. */
    suspend fun tickOnce(): TickReport {
        val now = clock()
        val t0 = System.nanoTime()
        val marker = try {
            withContext(Dispatchers.IO) { markers.read(email) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(now, "marker read failed: ${e::class.java.simpleName}")
        } ?: return failed(now, "identity has no User vertex")
        val markerMs = (System.nanoTime() - t0) / 1_000_000

        val prior = previous
        val changes = marker.changesSince(prior)
        val sinceRender = lastRenderAt?.let { Duration.between(it, now) }
        val reason = when {
            prior == null -> "first tick"
            changes.isNotEmpty() -> "marker changed"
            sinceRender == null || sinceRender >= renderEvery -> "hourly refresh"
            else -> null
        }
        if (reason == null) {
            synchronized(lock) { ticks++; previous = marker; lastMarker = marker; lastChanges = changes; lastTickAt = now; lastError = null }
            logger.debug("director-tick: no change; markerMs={}", markerMs)
            return TickReport(marker, changes, false, "no change", 0, 0, markerMs, 0)
        }

        val t1 = System.nanoTime()
        return try {
            val outcome = assembler.assemble(email, marker.lastCycleSeq + 1)
            val state = SelfState(
                tickEnabled = true,
                intervalSeconds = interval.seconds,
                tickAt = now.toString(),
                renderReason = reason,
                servers = withContext(Dispatchers.IO) { probe.servers() },
                memory = withContext(Dispatchers.IO) { probe.memory() },
                notion = notion(),
                hermesInFlight = hermes(),
                budgets = budgets(),
                marker = marker,
                changes = changes,
            )
            val text = DirectorPromptRenderer.render(state, outcome, zone, now)
            withContext(Dispatchers.IO) { write(text, now) }
            val items = (outcome as? AssembleOutcome.Assembled)?.horizon?.items?.size ?: 0
            val renderMs = (System.nanoTime() - t1) / 1_000_000
            val failure = if (outcome is AssembleOutcome.Assembled) null else "horizon: ${outcome::class.java.simpleName}"
            synchronized(lock) {
                ticks++; previous = marker; lastMarker = marker; lastChanges = changes; lastTickAt = now
                lastRenderAt = now; lastRenderReason = reason; lastRender = text; lastError = failure
            }
            logger.info(
                "director-tick: rendered reason={} changedParts={} items={} chars={} servers={}/{} markerMs={} renderMs={}{}",
                reason, changes.size, items, text.length, state.servers.count { it.up }, state.servers.size, markerMs, renderMs, failure?.let { " $it" } ?: "",
            )
            TickReport(marker, changes, true, reason, items, text.length, markerMs, renderMs, failure)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: an exception message from the graph could carry memory text.
            failed(now, "render failed: ${e::class.java.simpleName}", marker, changes)
        }
    }

    fun snapshot(): DirectorTickSnapshot = synchronized(lock) {
        DirectorTickSnapshot(
            enabled = true, identity = label, intervalSeconds = interval.seconds, ticks = ticks,
            lastTickAt = lastTickAt?.toString(), lastRenderAt = lastRenderAt?.toString(), lastRenderReason = lastRenderReason,
            marker = lastMarker, changes = lastChanges, lastError = lastError, render = lastRender, disabledReason = null,
        )
    }

    private fun failed(now: Instant, error: String, marker: ChangeMarker? = null, changes: List<ChangeMarker.Change> = emptyList()): TickReport {
        synchronized(lock) { ticks++; lastTickAt = now; lastError = error }
        logger.warn("director-tick: {}", error)
        return TickReport(marker, changes, false, "error", 0, 0, 0, 0, error)
    }

    private fun write(text: String, now: Instant) {
        Files.createDirectories(outDir)
        runCatching { Files.setPosixFilePermissions(outDir, PosixFilePermissions.fromString("rwx------")) }
        val owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val copy = outDir.resolve("tick-${FILE_STAMP.format(now)}.md")
        val tmp = Files.createTempFile(outDir, ".tick-", ".tmp", owner)
        try {
            Files.writeString(tmp, text)
            Files.copy(tmp, copy, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, outDir.resolve(LATEST), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        Files.newDirectoryStream(outDir, "tick-*.md").use { stream ->
            stream.map { it.fileName.toString() }.sortedDescending().drop(keep).forEach { Files.deleteIfExists(outDir.resolve(it)) }
        }
    }

    companion object {
        const val LATEST = "latest.md"
        const val KEEP = 50
        const val ENV_IDENTITY = "DIRECTOR_TICK_IDENTITY"
        const val ENV_INTERVAL = "DIRECTOR_TICK_INTERVAL_S"
        const val ENV_DIR = "DIRECTOR_TICK_DIR"
        const val DEFAULT_INTERVAL_SECONDS = 300L
        const val MIN_INTERVAL_SECONDS = 30L
        const val MAX_INTERVAL_SECONDS = 86_400L
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}

/** Whether the tick runs in this process, and why not when it does not. */
sealed interface DirectorTickStartup {
    data class Running(val tick: DirectorTick) : DirectorTickStartup
    data class Disabled(val reason: String, val misconfigured: Boolean) : DirectorTickStartup
}

private val bootLog = LoggerFactory.getLogger("app.alfrd.engram.director.DirectorTickBootstrap")

/**
 * Builds the tick from the environment and starts it in [scope], or says why not. Off unless `DIRECTOR_TICK_IDENTITY` is set. The value is an
 * id, resolved the way the debug routes resolve one. `owner-jacob` is allowed: the tick is read-only (no model call, no graph write), so it is
 * exempt from the guard that stops the debug fixture routes writing to that real-data identity. A bad interval leaves the tick off with a loud log line and never stops the engine.
 */
fun bootstrapDirectorTick(
    scope: CoroutineScope,
    db: Database,
    notionStartup: NotionCycleStartup,
    hermesActive: HermesActiveAssignmentRegistry,
    env: Map<String, String> = System.getenv(),
    probe: SelfStateProbe = SystemProbe(),
    start: Boolean = true,
): DirectorTickStartup {
    val raw = env[DirectorTick.ENV_IDENTITY]?.trim().orEmpty()
    if (raw.isEmpty()) return DirectorTickStartup.Disabled("${DirectorTick.ENV_IDENTITY} is not set", misconfigured = false).also { bootLog.info("director-tick: not running: {}", it.reason) }
    fun bad(reason: String) = DirectorTickStartup.Disabled(reason, misconfigured = true).also { bootLog.error("director-tick: NOT RUNNING: {}", reason) }

    val email = DebugConverseService.resolveUserId(raw)
    val seconds = env[DirectorTick.ENV_INTERVAL]?.trim()?.takeIf { it.isNotEmpty() }?.let {
        it.toLongOrNull()?.takeIf { s -> s in DirectorTick.MIN_INTERVAL_SECONDS..DirectorTick.MAX_INTERVAL_SECONDS }
            ?: return bad("${DirectorTick.ENV_INTERVAL} must be a whole number of seconds between ${DirectorTick.MIN_INTERVAL_SECONDS} and ${DirectorTick.MAX_INTERVAL_SECONDS}")
    } ?: DirectorTick.DEFAULT_INTERVAL_SECONDS
    val dir = env[DirectorTick.ENV_DIR]?.trim()?.takeIf { it.isNotEmpty() }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), "alfrd", "director-tick")

    val running = (notionStartup as? NotionCycleStartup.Running)?.runtime
    val tick = DirectorTick(
        email = email,
        label = raw,
        interval = Duration.ofSeconds(seconds),
        outDir = dir,
        markers = ChangeMarkerReader(db),
        assembler = ArcadeHorizonAssembler(db),
        probe = probe,
        notion = { notionState(notionStartup) },
        hermes = { hermesActive.activeByKind() },
        budgets = {
            val info = running?.info
            BudgetDefaults(
                taskBudget = CycleProcessor.DEFAULT_BUDGET,
                taskBudgetCeiling = CycleProcessor.MAX_BUDGET,
                planTimeoutSeconds = info?.planTimeoutSeconds ?: (PlanRoute.DEFAULT_TIMEOUT_MS / 1000),
                planMaxTokens = info?.planMaxTokens ?: PlanRoute.DEFAULT_MAX_TOKENS,
                configured = info?.planEndpoint != null,
            )
        },
    )
    if (start) tick.start(scope)
    bootLog.info("director-tick: running identity={} interval={}s dir={}", raw, seconds, dir)
    return DirectorTickStartup.Running(tick)
}

internal fun notionState(startup: NotionCycleStartup): NotionState = when (startup) {
    is NotionCycleStartup.Disabled -> NotionState(enabled = false, reason = startup.reason)
    is NotionCycleStartup.Running -> {
        val snapshot = startup.runtime.channel.snapshot()
        NotionState(true, null, startup.runtime.info.pollSeconds, snapshot.lastPollAt, snapshot.consecutiveFailures, startup.runtime.channel.heldCycles())
    }
}
