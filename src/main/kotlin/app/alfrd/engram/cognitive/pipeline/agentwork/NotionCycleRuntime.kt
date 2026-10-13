package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.EnvAndFileSecretResolver
import app.alfrd.engram.notion.HttpNotionApi
import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionConfigException
import app.alfrd.engram.notion.NotionSetBinding
import app.alfrd.engram.notion.NotionSetRegistry
import app.alfrd.engram.notion.RateLimitedNotionApi
import app.alfrd.engram.notion.RequestLimiter
import app.alfrd.engram.notion.RetryPolicy
import app.alfrd.engram.notion.SecretResolver
import app.alfrd.engram.notion.TableAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.time.Duration

/** Whether the Notion channel runs in this process, and why not when it does not. */
sealed interface NotionCycleStartup {
    data class Running(val runtime: NotionCycleRuntime) : NotionCycleStartup

    /**
     * Not running. [misconfigured] is false when nobody asked for it (no configuration path set) and
     * true when somebody did and something is missing, which deserves a loud log line and not a quiet one.
     */
    data class Disabled(val reason: String, val misconfigured: Boolean) : NotionCycleStartup
}

/** What the debug surface shows about the channel's setup. No token, no token path, no id of a Notion page. */
@Serializable
data class NotionCycleInfo(
    val setName: String,
    val worker: String,
    val pollSeconds: Long,
    val leaseSeconds: Long,
    val readWriteTables: List<String>,
    val readOnlyTables: List<String>,
)

/**
 * The Director's Notion input channel, assembled: the shared table-set client (`HttpNotionApi` under
 * a rate limiter under the guard, the same stack every table set uses and no second client), the
 * planner, the cycle processor and the poll loop.
 *
 * It is opt-in and quiet when absent. With `NOTION_WORKER_CONFIG` unset, nothing here runs and
 * nothing is read. The token is never read here: the configuration holds a `file:` reference, and
 * the client asks the resolver for the token on every request, so a token Jacob places or rotates
 * after the service started is picked up on the next poll, and no token is ever copied into an
 * environment variable, a field, a log line or a trace.
 */
class NotionCycleRuntime(
    val channel: NotionCycleChannel,
    val traces: CycleTraceStore,
    val info: NotionCycleInfo,
    private val rateLimited: RateLimitedNotionApi,
    /** The guarded client the processor uses, exposed to tests so they can check what the assembled stack refuses. Nothing outside this package and its tests touches it. */
    internal val api: NotionApi? = null,
) {
    val requestsMade: Long get() = rateLimited.requestsMade
    val retriesMade: Long get() = rateLimited.retriesMade

    fun start(scope: CoroutineScope): Job = channel.start(scope)

    companion object {
        const val ENV_CONFIG = "NOTION_WORKER_CONFIG"
        const val ENV_POLL_SECONDS = "NOTION_WORKER_POLL_SECONDS"
        const val ENV_LEASE_SECONDS = "NOTION_WORKER_LEASE_SECONDS"
        const val ENV_WORKER_ID = "NOTION_WORKER_ID"

        const val DEFAULT_POLL_SECONDS = 180L
        const val DEFAULT_LEASE_SECONDS = 120L
        const val MIN_SECONDS = 30L
        const val MAX_SECONDS = 3_600L

        /**
         * Builds the channel from the environment, or says why it is not running. A configuration that
         * is present but wrong throws [NotionConfigException]: its message names what is wrong and
         * never repeats a token (there is none to repeat; the file holds only a reference).
         *
         * The model is the Director's own: the local model client, selected the way the cognitive
         * pipeline selects it (`LLM_PROVIDER=local`). Without it the channel does not start, because a
         * channel that claimed cycles it could not plan would turn Jacob's Queued rows into Failed
         * ones. Goal and page text goes to that local model and nowhere else: it plans, does each task
         * and writes the QC close-out ([DirectorTaskExecutor], [CycleQcDirector]).
         *
         * [otherSets] are the other Notion table sets this process is configured with (for example a
         * Notion-backed memory store). They are checked against this one by [NotionSetRegistry] before the
         * channel is built: two sets may not share a token reference, a table, a database or a page, and
         * an overlap is a [NotionConfigException] (the channel stays off and the reason is logged).
         */
        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            llmClient: LlmClient? = if (env["LLM_PROVIDER"]?.lowercase() == "local") LocalLlmClient() else null,
            resolver: SecretResolver = EnvAndFileSecretResolver(env),
            baseUrl: String = HttpNotionApi.DEFAULT_BASE_URL,
            httpClient: OkHttpClient = HttpNotionApi.defaultClient(),
            limiter: RequestLimiter = RequestLimiter(),
            retryPolicy: RetryPolicy = RetryPolicy(),
            sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
            otherSets: List<NotionSetBinding> = emptyList(),
        ): NotionCycleStartup {
            val path = env[ENV_CONFIG]?.trim().orEmpty()
            if (path.isEmpty()) return NotionCycleStartup.Disabled("$ENV_CONFIG is not set", misconfigured = false)

            val pollSeconds = seconds(env, ENV_POLL_SECONDS, DEFAULT_POLL_SECONDS)
            val leaseSeconds = seconds(env, ENV_LEASE_SECONDS, DEFAULT_LEASE_SECONDS)
            val config = AgentWorkConfig.load(path)
            NotionSetRegistry(listOf<NotionSetBinding>(config) + otherSets) // throws on any overlap

            if (llmClient == null) {
                return NotionCycleStartup.Disabled(
                    "the Director's local model is not configured (LLM_PROVIDER=local); the Notion channel will not claim cycles it cannot plan",
                    misconfigured = true,
                )
            }

            // #pid is always appended: a restarted process must not look like the one whose lease it is taking over.
            val worker = workerId(env[ENV_WORKER_ID])

            val http = HttpNotionApi({ resolver.resolve(config.tokenRef) }, baseUrl, httpClient)
            val rateLimited = RateLimitedNotionApi(http, limiter, retryPolicy, sleep)
            val guarded = AllowlistedNotionApi(rateLimited, config.tableSet)

            val traces = CycleTraceStore()
            val processor = CycleProcessor(
                api = guarded,
                tableSet = config.tableSet,
                director = CyclePlanDirector(llmClient),
                executor = DirectorTaskExecutor(llmClient),
                qc = CycleQcDirector(llmClient),
                traces = traces,
                worker = worker,
                leaseDuration = Duration.ofSeconds(leaseSeconds),
                sleep = sleep,
            )
            val channel = NotionCycleChannel(processor, Duration.ofSeconds(pollSeconds))
            val tables = config.tableSet.tables
            val info = NotionCycleInfo(
                setName = config.tableSet.name,
                worker = worker,
                pollSeconds = pollSeconds,
                leaseSeconds = leaseSeconds,
                readWriteTables = tables.filter { it.access == TableAccess.READ_WRITE }.map { it.name }.sorted(),
                readOnlyTables = tables.filter { it.access == TableAccess.READ_ONLY }.map { it.name }.sorted(),
            )
            return NotionCycleStartup.Running(NotionCycleRuntime(channel, traces, info, rateLimited, guarded))
        }

        /**
         * Whether the token can be read right now, without keeping it. The channel does not need this
         * to start (the file may be placed later); it exists so startup can say so once.
         */
        fun tokenReadable(env: Map<String, String>, resolver: SecretResolver = EnvAndFileSecretResolver(env)): Boolean {
            val path = env[ENV_CONFIG]?.trim().orEmpty()
            if (path.isEmpty()) return false
            return try {
                resolver.resolve(AgentWorkConfig.load(path).tokenRef)
                true
            } catch (_: NotionConfigException) {
                false
            }
        }

        private fun seconds(env: Map<String, String>, name: String, default: Long): Long {
            val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            val value = raw.toLongOrNull()
            if (value == null || value !in MIN_SECONDS..MAX_SECONDS) {
                throw NotionConfigException("$name must be a whole number of seconds between $MIN_SECONDS and $MAX_SECONDS")
            }
            return value
        }

        private fun workerId(configured: String?): String {
            val base = configured?.trim()?.takeIf { it.isNotEmpty() }
                ?: return CycleProcessor.defaultWorkerId()
            if (base.length > 60 || base.any { it.isISOControl() }) {
                throw NotionConfigException("$ENV_WORKER_ID must be at most 60 characters and contain no control characters")
            }
            return "$base#${ProcessHandle.current().pid()}"
        }
    }
}

private val bootLog = org.slf4j.LoggerFactory.getLogger("app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleBootstrap")

/**
 * Starts the Notion channel in [scope] if it is configured, and logs why not if it is not. It never
 * throws: a missing or broken Notion configuration must not stop the engine from serving chat, so a
 * configuration error is logged and the channel stays off.
 */
fun bootstrapNotionCycles(
    scope: CoroutineScope,
    env: Map<String, String> = System.getenv(),
    build: () -> NotionCycleStartup = { NotionCycleRuntime.fromEnvironment(env) },
): NotionCycleStartup {
    val startup = try {
        build()
    } catch (e: NotionConfigException) {
        // The message was written to be logged: it names what is wrong and never a token.
        NotionCycleStartup.Disabled("invalid Notion worker configuration: ${e.message}", misconfigured = true)
    } catch (e: Exception) {
        NotionCycleStartup.Disabled("Notion worker could not be set up: ${e::class.java.simpleName}", misconfigured = true)
    }
    when (startup) {
        is NotionCycleStartup.Disabled ->
            if (startup.misconfigured) bootLog.error("notion-cycles: NOT RUNNING: {}", startup.reason) else bootLog.info("notion-cycles: not running: {}", startup.reason)
        is NotionCycleStartup.Running -> {
            val info = startup.runtime.info
            if (!NotionCycleRuntime.tokenReadable(env)) {
                bootLog.warn("notion-cycles: the worker token is not readable yet; polls will report it until it is (the file is read on every request, no restart needed once it is placed)")
            }
            startup.runtime.start(scope)
            bootLog.info(
                "notion-cycles: running set={} worker={} poll={}s lease={}s read-write={} read-only={}",
                info.setName, info.worker, info.pollSeconds, info.leaseSeconds, info.readWriteTables, info.readOnlyTables,
            )
        }
    }
    return startup
}
