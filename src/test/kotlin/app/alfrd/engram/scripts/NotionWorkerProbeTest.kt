package app.alfrd.engram.scripts

import app.alfrd.engram.notion.NotionConfigException
import app.alfrd.engram.notion.RequestLimiter
import app.alfrd.engram.notion.RetryPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path

class NotionWorkerProbeTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var server: MockWebServer
    private val secret = "ntn_PROBE_SECRET_TOKEN_0123456789"
    private val committed = File("deploy/config/notion-agent-worker.json").readText()

    /** name -> (data source id, database id), read from the committed configuration so the test cannot drift from it. */
    private val tables: Map<String, Pair<String, String>> = Regex(""""name":\s*"([a-z-]+)",\s*"dataSourceId":\s*"([0-9a-f-]+)",\s*"databaseId":\s*"([0-9a-f]+)"""")
        .findAll(committed).associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }

    /** The allowlisted Persona page, from the committed configuration. */
    private val personaId: String = Regex(""""name":\s*"persona",\s*"pageId":\s*"([0-9a-f]+)"""").find(committed)!!.groupValues[1].let { normalize(it) }

    // Behavior knobs the dispatcher reads.
    /** The other allowlisted (reading) pages in the committed configuration. */
    private val readingIds: List<String> = Regex(""""name":\s*"reading-[^"]*",\s*"pageId":\s*"([0-9a-f]+)"""").findAll(committed).map { normalize(it.groupValues[1]) }.toList()
    private var personaMissing = false
    private var missing: Set<String> = emptySet()
    private var databaseIdAccepted = false
    private var extraDataSource: String? = null
    private var unauthorized = false
    private var searchRateLimitedOnce = false
    private var lastEdited = "2026-10-12T10:02:00.000Z"
    private val seen = java.util.concurrent.CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        assertEquals(14, tables.size, "the committed configuration must carry all fourteen tables")
        server = MockWebServer().also { s ->
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += "${request.method} ${request.path}"
                    return respond(request)
                }
            }
            s.start()
        }
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun pageJson(id: String, dataSource: String, withFormula: Boolean) = """
        {"object":"page","id":"$id","created_time":"2026-10-06T12:00:00.000Z","last_edited_time":"$lastEdited","archived":false,"in_trash":false,
         "parent":{"type":"data_source_id","data_source_id":"$dataSource","database_id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd"},
         "properties":{"Name":{"id":"title","type":"title","title":[{"plain_text":"SENSITIVE ROW TITLE","text":{"content":"SENSITIVE ROW TITLE"}}]}
         ${if (withFormula) ""","Score":{"id":"f","type":"formula","formula":{"type":"number","number":3}}""" else ""}}}
    """.trimIndent()

    private fun respond(request: RecordedRequest): MockResponse {
        if (unauthorized) return json(401, """{"object":"error","status":401,"code":"unauthorized","message":"API token is invalid."}""")
        val path = request.path.orEmpty()
        if (request.method == "POST" && path == "/v1/search") {
            if (searchRateLimitedOnce) { searchRateLimitedOnce = false; return json(429, """{"object":"error","status":429,"code":"rate_limited","message":"slow down"}""").setHeader("Retry-After", "2") }
            val results = (tables.entries.filter { it.key !in missing }.map { it.value.first to it.key } + listOfNotNull(extraDataSource?.let { it to "Somebody else's data" }))
                .joinToString(",") { (id, title) -> """{"object":"data_source","id":"$id","title":[{"plain_text":"$title"}]}""" }
            return json(200, """{"object":"list","results":[$results],"has_more":false,"next_cursor":null}""")
        }
        val query = Regex("""/v1/data_sources/([0-9a-f-]+)/query""").matchEntire(path)
        if (request.method == "POST" && query != null) {
            val id = query.groupValues[1]
            tables.entries.firstOrNull { it.value.first == id }?.let { (name, ids) ->
                return if (name in missing) json(404, """{"object":"error","status":404,"code":"object_not_found","message":"Could not find data_source with ID: $id. Make sure the relevant pages and databases are shared with your integration."}""")
                else json(200, """{"object":"list","results":[${pageJson("11111111-1111-4111-8111-1111111111${tables.keys.indexOf(name).toString().padStart(2, '0')}", ids.first, withFormula = false)}],"has_more":false,"next_cursor":null}""")
            }
            val asDatabase = tables.entries.firstOrNull { normalize(it.value.second) == id }
            if (asDatabase != null) {
                return if (databaseIdAccepted) json(200, """{"object":"list","results":[],"has_more":false,"next_cursor":null}""")
                else json(400, """{"object":"error","status":400,"code":"validation_error","message":"Provided ID $id is a database, not a data source. Use the data source id."}""")
            }
            return json(404, """{"object":"error","status":404,"code":"object_not_found","message":"unknown"}""")
        }
        if (request.method == "GET" && path.removePrefix("/v1/pages/") in readingIds) {
            return json(200, """{"object":"page","id":"${path.removePrefix("/v1/pages/")}","created_time":"2026-10-06T12:00:00.000Z","last_edited_time":"$lastEdited","archived":false,"parent":{"type":"page_id","page_id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd"},"properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"SENSITIVE PERSONA TITLE","text":{"content":"SENSITIVE PERSONA TITLE"}}]}}}""")
        }
        if (request.method == "GET" && path == "/v1/pages/$personaId") {
            return if (personaMissing) json(404, """{"object":"error","status":404,"code":"object_not_found","message":"Could not find page with ID: $personaId."}""")
            else json(200, """{"object":"page","id":"$personaId","created_time":"2026-10-06T12:00:00.000Z","last_edited_time":"$lastEdited","archived":false,"parent":{"type":"page_id","page_id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd"},"properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"SENSITIVE PERSONA TITLE","text":{"content":"SENSITIVE PERSONA TITLE"}}]}}}""")
        }
        val retrieve = Regex("""/v1/pages/([0-9a-f-]+)""").matchEntire(path)
        if (request.method == "GET" && retrieve != null) {
            val id = retrieve.groupValues[1]
            val index = id.takeLast(2).toInt()
            val ds = tables.values.toList()[index].first
            return json(200, pageJson(id, ds, withFormula = true))
        }
        return json(500, """{"object":"error","status":500,"code":"internal_server_error","message":"unexpected request in test: ${request.method} $path"}""")
    }

    private fun normalize(bare: String) = if (bare.contains('-')) bare else "${bare.substring(0, 8)}-${bare.substring(8, 12)}-${bare.substring(12, 16)}-${bare.substring(16, 20)}-${bare.substring(20)}"

    private fun tokenFile(content: String = secret): File = File(dir.toFile(), ".notion-worker-token").apply { writeText("$content\n") }
    private fun configFile(tokenPath: String): File = File(dir.toFile(), "worker.json").apply {
        writeText(committed.replace("file:/home/halo/development/hermes-webui-dev/.notion-worker-token", "file:$tokenPath"))
    }

    private class Outcome(val status: Int, val text: String)

    private fun probe(vararg args: String, config: File? = configFile(tokenFile().path), sleeps: MutableList<Long> = mutableListOf()): Outcome {
        val bytes = ByteArrayOutputStream()
        val argv = buildList { config?.let { add("--config"); add(it.path) }; addAll(args) }
        val status = runBlocking {
            NotionWorkerProbe.run(
                args = argv, env = emptyMap(), out = PrintStream(bytes, true, "UTF-8"),
                baseUrl = server.url("/").toString(),
                limiter = RequestLimiter(100_000.0, 100_000),
                retryPolicy = RetryPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 1, jitter = { 0 }),
                sleep = { sleeps += it },
            )
        }
        return Outcome(status, bytes.toString("UTF-8"))
    }

    // ── The report ───────────────────────────────────────────────────────────

    @Test
    fun `all fourteen data sources and the Persona page reachable exits zero and reports each assumption`() {
        val result = probe()
        assertEquals(NotionWorkerProbe.OK, result.status, result.text)
        assertEquals(16, Regex("VISIBLE").findAll(result.text).count(), result.text)
        assertTrue(Regex("VISIBLE\\s+persona\\s+$personaId").containsMatchIn(result.text), result.text)
        for (name in tables.keys) assertTrue(Regex("VISIBLE\\s+${Regex.escape(name)}\\s").containsMatchIn(result.text), "$name is not reported VISIBLE: ${result.text}")
        assertFalse(result.text.contains("MISSING  "), result.text)
        assertTrue(result.text.contains("== The 14 data sources =="), result.text)
        assertTrue(result.text.contains("1. collection:// UUID accepted by the data source endpoints: YES"), result.text)
        assertTrue(result.text.contains("2. a database id is rejected where a data source id is expected: YES, rejected for all 14"), result.text)
        assertTrue(result.text.contains("3. parent.data_source_id present on returned pages: YES"), result.text)
        assertTrue(result.text.contains("whole minute on all 14 sampled pages"), result.text)
        assertTrue(result.text.contains("only on a page read, not in a query: ") && result.text.contains("Score (formula)"), result.text)
        assertTrue(result.text.contains("6. select option behavior") && result.text.contains("NOT PROBED"), result.text)
        assertTrue(result.text.contains("RESULT: all 14 data sources and 2 allowlisted page(s) are reachable. Nothing was written."), result.text)
    }

    @Test
    fun `the data sources the committed configuration expects to be visible are exactly the Agent Work, PPS and alfrd memory tables`() {
        assertEquals(
            setOf(
                "goals", "cycles", "agent-tasks", "escalations", "questions", "findings", "sources",
                "memory-containers", "memory-items", "memory-entities", "memory-connections", "memory-sessions", "memory-sources", "memory-template-changes",
            ),
            tables.keys,
        )
        assertFalse("evidence" in tables.keys || "claims" in tables.keys, "Evidence is archived and Claims is Findings")
    }

    @Test
    fun `it only reads, whatever the workspace holds, and reads only the properties of the Persona page`() {
        probe()
        val writes = seen.filter { it.startsWith("PATCH") || it.startsWith("DELETE") || it.startsWith("PUT") || it == "POST /v1/pages" }
        assertTrue(writes.isEmpty(), "a read-only probe made: $writes")
        assertTrue(seen.none { it.contains("/blocks") }, "the probe read page content: $seen")
        assertTrue(seen.all { it == "POST /v1/search" || Regex("""POST /v1/data_sources/[0-9a-f-]+/query""").matches(it) || Regex("""GET /v1/pages/[0-9a-f-]+""").matches(it) }, seen.toString())
    }

    @Test
    fun `a missing data source stops the run with a non-zero status naming it`() {
        missing = setOf("findings")
        val result = probe()
        assertEquals(NotionWorkerProbe.MISSING, result.status, result.text)
        assertTrue(Regex("MISSING\\s+findings").containsMatchIn(result.text), result.text)
        assertTrue(result.text.contains("1 of 16 data source(s) and page(s) are not reachable with this token: findings"), result.text)
        assertTrue(result.text.contains("Stop here"), result.text)
        assertTrue(result.text.contains("HTTP 404 object_not_found"), result.text)
    }

    @Test
    fun `a missing alfrd memory table stops the run too, because Jacob connects the integration to his top alfrd page`() {
        missing = setOf("memory-items", "memory-sessions")
        val result = probe()
        assertEquals(NotionWorkerProbe.MISSING, result.status, result.text)
        assertTrue(result.text.contains("2 of 16 data source(s) and page(s) are not reachable") && result.text.contains("memory-items, memory-sessions"), result.text)
    }

    @Test
    fun `a Persona page the token cannot read stops the run, naming the page`() {
        personaMissing = true
        val result = probe()
        assertEquals(NotionWorkerProbe.MISSING, result.status, result.text)
        assertTrue(Regex("MISSING\\s+persona").containsMatchIn(result.text), result.text)
        assertTrue(result.text.contains("1 of 16 data source(s) and page(s) are not reachable with this token: page:persona"), result.text)
        assertTrue(result.text.contains("HTTP 404 object_not_found"), result.text)
    }

    @Test
    fun `a data source beyond the configured ones is listed and flagged, not used`() {
        extraDataSource = "99999999-9999-4999-8999-999999999999"
        val result = probe()
        assertEquals(NotionWorkerProbe.OK, result.status, result.text)
        assertTrue(result.text.contains("99999999-9999-4999-8999-999999999999") && result.text.contains("Somebody else's data"), result.text)
        assertTrue(seen.none { it.contains("99999999-9999-4999-8999-999999999999") }, "the probe must not read a data source it does not use: $seen")
    }

    @Test
    fun `a database id that Notion accepts is reported as a broken assumption`() {
        databaseIdAccepted = true
        val result = probe()
        assertTrue(result.text.contains("2. a database id is rejected where a data source id is expected: NO: accepted for"), result.text)
    }

    @Test
    fun `last_edited_time finer than a minute is reported as finer`() {
        lastEdited = "2026-10-12T10:02:37.123Z"
        val result = probe()
        assertTrue(result.text.contains("finer than a minute on all 14 sampled pages"), result.text)
    }

    @Test
    fun `a 429 on the listing honors Retry-After and carries on`() {
        searchRateLimitedOnce = true
        val sleeps = mutableListOf<Long>()
        val result = probe(sleeps = sleeps)
        assertEquals(NotionWorkerProbe.OK, result.status, result.text)
        assertTrue(2_000L in sleeps, "expected a 2000ms wait from Retry-After, saw $sleeps")
    }

    // ── The token ────────────────────────────────────────────────────────────

    @Test
    fun `an unreadable token exits 2 before any request is made`() {
        val result = probe(config = configFile(File(dir.toFile(), "not-placed").path))
        assertEquals(NotionWorkerProbe.TOKEN, result.status, result.text)
        assertTrue(result.text.contains("NOT readable"), result.text)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a token Notion rejects exits 2`() {
        unauthorized = true
        val result = probe()
        assertEquals(NotionWorkerProbe.TOKEN, result.status, result.text)
        assertTrue(result.text.contains("REJECTED"), result.text)
    }

    @Test
    fun `check-token reads the file and makes no request`() {
        val result = probe("--check-token")
        assertEquals(NotionWorkerProbe.OK, result.status, result.text)
        assertTrue(result.text.contains("token: readable by this process"), result.text)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `neither the token nor any row content ever reaches the output`() {
        for (scenario in listOf<() -> Unit>({}, { missing = setOf("goals") }, { unauthorized = true }, { personaMissing = true })) {
            missing = emptySet(); unauthorized = false; personaMissing = false
            scenario()
            val text = probe().text
            assertFalse(text.contains(secret), "the token leaked: $text")
            assertFalse(text.contains("SENSITIVE ROW TITLE") || text.contains("SENSITIVE PERSONA TITLE"), "row content leaked: $text")
            assertFalse(text.contains("Bearer"), text)
        }
    }

    // ── Usage ────────────────────────────────────────────────────────────────

    @Test
    fun `without a configuration the probe says how to give one`() {
        val result = probe(config = null)
        assertEquals(NotionWorkerProbe.USAGE, result.status)
        assertTrue(result.text.contains("NOTION_WORKER_CONFIG"), result.text)
    }

    @Test
    fun `a configuration that grants more than the cycle channel needs is refused`() {
        val widened = File(dir.toFile(), "w.json").apply {
            writeText(committed.replace(Regex("""("name":\s*"goals".*?"access":\s*)"read-only""""), "$1\"read-write\""))
        }
        assertTrue(widened.readText() != committed)
        val result = probe(config = widened)
        assertEquals(NotionWorkerProbe.USAGE, result.status, result.text)
        assertTrue(result.text.contains("configuration:"), result.text)
        assertEquals(0, server.requestCount)
    }
}
