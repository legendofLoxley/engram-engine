package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionConfigException
import app.alfrd.engram.notion.RequestLimiter
import app.alfrd.engram.notion.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class NotionCycleRuntimeTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var server: MockWebServer
    private val secret = "ntn_SECRET_WORKER_TOKEN_0123456789"
    private val rotated = "ntn_ROTATED_WORKER_TOKEN_9876543210"
    private val cyclesId = "545ccad7-35ac-4131-b69c-cdbd278fe774"
    private val committed = File("deploy/config/notion-agent-worker.json").readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun tokenFile(content: String = secret): File = dir.resolve(".notion-worker-token").toFile().apply {
        writeText(content + "\n")
        Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rw-------"))
    }

    private fun configFile(tokenPath: String): File = dir.resolve("worker.json").toFile().apply {
        writeText(committed.replace("file:/home/halo/development/hermes-webui-dev/.notion-worker-token", "file:$tokenPath"))
    }

    private fun env(config: File?, vararg extra: Pair<String, String>): Map<String, String> =
        buildMap {
            config?.let { put(NotionCycleRuntime.ENV_CONFIG, it.path) }
            putAll(extra)
        }

    private fun running(
        env: Map<String, String>,
        llm: ScriptedLlm? = AgentWorkFixture.readPlan("a"),
    ): NotionCycleRuntime {
        val startup = NotionCycleRuntime.fromEnvironment(
            env = env,
            llmClient = llm,
            baseUrl = server.url("/").toString(),
            limiter = RequestLimiter(100_000.0, 100_000),
            retryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 1, jitter = { 0 }),
            sleep = {},
        )
        return (startup as? NotionCycleStartup.Running)?.runtime ?: error("expected the channel to run, got $startup")
    }

    private fun emptyResults() = server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"object":"list","results":[],"has_more":false,"next_cursor":null}"""))
    private fun taken(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request reached the server")

    // ── Whether it runs ──────────────────────────────────────────────────────

    @Test
    fun `without a configuration path nothing runs and nothing is said loudly`() {
        val startup = NotionCycleRuntime.fromEnvironment(env = emptyMap(), llmClient = AgentWorkFixture.readPlan("a"))
        val disabled = startup as NotionCycleStartup.Disabled
        assertFalse(disabled.misconfigured, "an unconfigured channel is not an error")
        assertTrue(disabled.reason.contains(NotionCycleRuntime.ENV_CONFIG))
    }

    @Test
    fun `a configured channel without the local model does not start and says so as a misconfiguration`() {
        val startup = NotionCycleRuntime.fromEnvironment(env = env(configFile(tokenFile().path)), llmClient = null)
        val disabled = startup as NotionCycleStartup.Disabled
        assertTrue(disabled.misconfigured)
        assertTrue(disabled.reason.contains("LLM_PROVIDER=local"), disabled.reason)
    }

    @Test
    fun `LLM_PROVIDER=local selects the Director's local model`() {
        val startup = NotionCycleRuntime.fromEnvironment(env = env(configFile(tokenFile().path), "LLM_PROVIDER" to "local"))
        assertTrue(startup is NotionCycleStartup.Running, "got $startup")
    }

    @Test
    fun `a configuration file that is missing or breaks the dry-run shape is refused by name`() {
        val missing = runCatching { NotionCycleRuntime.fromEnvironment(env = env(File(dir.toFile(), "nope.json")), llmClient = AgentWorkFixture.readPlan("a")) }.exceptionOrNull()
        assertTrue(missing is NotionConfigException, "got $missing")

        val widenedText = committed.replace(Regex("""("name":\s*"claims".*?"access":\s*)"read-only""""), "$1\"read-write\"")
        assertTrue(widenedText != committed, "the fixture must really make claims writable")
        val widened = dir.resolve("widened.json").toFile().apply { writeText(widenedText) }
        val refused = runCatching { NotionCycleRuntime.fromEnvironment(env = env(widened), llmClient = AgentWorkFixture.readPlan("a")) }.exceptionOrNull()
        assertTrue(refused is NotionConfigException && refused.message!!.contains("exactly cycles and agent-tasks"), "got $refused")
    }

    @Test
    fun `poll and lease seconds are bounded and a bad value names the variable`() {
        val config = configFile(tokenFile().path)
        for ((name, value) in listOf(
            NotionCycleRuntime.ENV_POLL_SECONDS to "5", NotionCycleRuntime.ENV_POLL_SECONDS to "soon",
            NotionCycleRuntime.ENV_LEASE_SECONDS to "99999", NotionCycleRuntime.ENV_LEASE_SECONDS to "-1",
        )) {
            val refused = runCatching { NotionCycleRuntime.fromEnvironment(env = env(config, name to value), llmClient = AgentWorkFixture.readPlan("a")) }.exceptionOrNull()
            assertTrue(refused is NotionConfigException && refused.message!!.contains(name), "$name=$value: got $refused")
        }
        val info = running(env(config, NotionCycleRuntime.ENV_POLL_SECONDS to "45", NotionCycleRuntime.ENV_LEASE_SECONDS to "60")).info
        assertEquals(45, info.pollSeconds)
        assertEquals(60, info.leaseSeconds)
        assertEquals(180, running(env(config)).info.pollSeconds, "the default is three minutes")
    }

    @Test
    fun `the worker id always carries the process id so a restart never looks like the process it replaces`() {
        val config = configFile(tokenFile().path)
        val pid = ProcessHandle.current().pid()
        assertTrue(running(env(config, NotionCycleRuntime.ENV_WORKER_ID to "engram-dev")).info.worker == "engram-dev#$pid")
        assertTrue(running(env(config)).info.worker.endsWith("#$pid"))
    }

    @Test
    fun `the setup shown to the debug surface lists the tables by access and nothing else`() {
        val info = running(env(configFile(tokenFile().path))).info
        assertEquals(listOf("agent-tasks", "cycles"), info.readWriteTables)
        assertEquals(listOf("claims", "escalations", "evidence", "goals", "questions", "sources"), info.readOnlyTables)
        val text = Json.encodeToString(NotionCycleInfo.serializer(), info)
        assertFalse(text.contains(".notion-worker-token"), text)
        assertFalse(text.contains(secret), text)
    }

    // ── The token ────────────────────────────────────────────────────────────

    @Test
    fun `a poll sends the token from the file as a bearer header and asks for queued and running cycles`() = runBlocking {
        val runtime = running(env(configFile(tokenFile().path)))
        emptyResults()

        val results = runtime.channel.pollOnce()

        assertTrue(results.isEmpty())
        val request = taken()
        assertEquals("POST", request.method)
        assertEquals("/v1/data_sources/$cyclesId/query", request.path)
        assertEquals("Bearer $secret", request.getHeader("Authorization"))
        assertEquals("2025-09-03", request.getHeader("Notion-Version"))
        val statuses = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["filter"]!!.jsonObject["or"]!!.jsonArray
            .map { it.jsonObject["select"]!!.jsonObject["equals"]!!.jsonPrimitive.content }
        assertEquals(listOf("Queued", "Running"), statuses)
        assertEquals(1, runtime.requestsMade)
    }

    @Test
    fun `a token placed or rotated after startup is used on the next poll without a restart`() = runBlocking {
        val token = File(dir.toFile(), ".notion-worker-token")
        val runtime = running(env(configFile(token.path)))

        // Not placed yet: the poll fails without a request, and says so by class, not by content.
        val first = runtime.channel.pollOnce()
        assertTrue(first.single() is CycleProcessor.TickResult.Error, "got $first")
        assertEquals(0, server.requestCount, "no request can be made without a token")
        assertEquals("could not list cycles: NotionConfigException", runtime.channel.snapshot().lastError)

        token.writeText("$secret\n")
        emptyResults()
        runtime.channel.pollOnce()
        assertEquals("Bearer $secret", taken().getHeader("Authorization"))
        assertEquals(0, runtime.channel.snapshot().consecutiveFailures)

        token.writeText("$rotated\n")
        emptyResults()
        runtime.channel.pollOnce()
        assertEquals("Bearer $rotated", taken().getHeader("Authorization"))
    }

    @Test
    fun `tokenReadable reflects the file and never returns it`() {
        val token = File(dir.toFile(), ".notion-worker-token")
        val e = env(configFile(token.path))
        assertFalse(NotionCycleRuntime.tokenReadable(e))
        token.writeText("$secret\n")
        assertTrue(NotionCycleRuntime.tokenReadable(e))
        assertFalse(NotionCycleRuntime.tokenReadable(emptyMap()))
    }

    @Test
    fun `the token appears in no status, setup, trace or log-bound string the runtime produces`() = runBlocking {
        val runtime = running(env(configFile(tokenFile().path)))
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody("""{"object":"error","status":401,"code":"unauthorized","message":"API token is invalid."}"""))
        runtime.channel.pollOnce()

        val everything = listOf(
            Json.encodeToString(ChannelSnapshot.serializer(), runtime.channel.snapshot()),
            Json.encodeToString(NotionCycleInfo.serializer(), runtime.info),
            runtime.traces.recent().toString(),
            runtime.toString(), runtime.channel.toString(), runtime.info.toString(),
        )
        everything.forEach { assertFalse(it.contains(secret), "the token leaked into: $it") }
        assertTrue(runtime.channel.snapshot().consecutiveFailures == 1)
    }

    // ── Bootstrap ────────────────────────────────────────────────────────────

    @Test
    fun `bootstrap starts the poll loop in the scope it is given`() {
        val runtime = running(env(configFile(tokenFile().path)))
        emptyResults()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val startup = bootstrapNotionCycles(scope, env(configFile(tokenFile().path))) { NotionCycleStartup.Running(runtime) }
            assertTrue(startup is NotionCycleStartup.Running)
            val deadline = System.currentTimeMillis() + 10_000
            while (runtime.channel.snapshot().polls == 0L && System.currentTimeMillis() < deadline) runBlocking { delay(20) }
            assertTrue(runtime.channel.snapshot().polls >= 1, "bootstrap must start the first poll at once")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `bootstrap turns a configuration error into a disabled channel instead of an exception`() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val bad = bootstrapNotionCycles(scope, emptyMap()) { throw NotionConfigException("table 'x' is wrong") }
            assertTrue(bad is NotionCycleStartup.Disabled && bad.misconfigured && bad.reason.contains("table 'x' is wrong"), "got $bad")

            val unexpected = bootstrapNotionCycles(scope, emptyMap()) { throw IllegalStateException("do not log this message") }
            assertTrue(unexpected is NotionCycleStartup.Disabled && unexpected.misconfigured, "got $unexpected")
            assertFalse((unexpected as NotionCycleStartup.Disabled).reason.contains("do not log this message"), "only the class name may be reported")

            val absent = bootstrapNotionCycles(scope, emptyMap())
            assertTrue(absent is NotionCycleStartup.Disabled && !absent.misconfigured, "got $absent")
        } finally {
            scope.cancel()
        }
    }
}
