package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Real HTTP, to a local mock server: what the adapter sends and how it reads what Notion answers. */
class HttpNotionApiTest {

    private lateinit var server: MockWebServer
    private val secret = "ntn_SECRET_TOKEN_VALUE_123"
    private val ds = "d67376eb-af39-4ee5-bae1-76b7875e15f5"
    private val pageId = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val json = Json

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun api(client: OkHttpClient = HttpNotionApi.defaultClient(), token: () -> NotionToken = { NotionToken(secret) }) =
        HttpNotionApi(token, baseUrl = server.url("/").toString(), client = client)

    private fun reply(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        val response = MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")
        headers.forEach { (k, v) -> response.setHeader(k, v) }
        server.enqueue(response)
    }

    private fun taken(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request reached the server")
    private fun RecordedRequest.bodyJson(): JsonObject = json.parseToJsonElement(body.readUtf8()).jsonObject

    private fun pageJson(id: String = pageId, parentDs: String? = ds, props: String = "", archived: Boolean = false) = """
        {"object":"page","id":"$id","created_time":"2026-10-06T12:00:00.000Z","last_edited_time":"2026-10-06T12:05:00.000Z",
         "archived":$archived,"in_trash":false,
         "parent":${if (parentDs == null) """{"type":"page_id","page_id":"$other"}""" else """{"type":"data_source_id","data_source_id":"$parentDs","database_id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd"}"""},
         "properties":{$props}}
    """.trimIndent()

    private fun results(vararg pages: String, next: String? = null) =
        """{"object":"list","results":[${pages.joinToString(",")}],"next_cursor":${next?.let { "\"$it\"" } ?: "null"},"has_more":${next != null}}"""

    // ── Request shape ────────────────────────────────────────────────────────

    @Test
    fun `every request carries the token and the API version, and nothing else carries the token`() = runBlocking {
        reply(results())
        api().query(ds, NotionFilter.SelectEquals("Status", "Open"))
        val request = taken()
        assertEquals("Bearer $secret", request.getHeader("Authorization"))
        assertEquals("2025-09-03", request.getHeader("Notion-Version"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertFalse(request.path!!.contains(secret))
        assertFalse(request.body.readUtf8().contains(secret))
    }

    @Test
    fun `the token is read afresh for each request, so a rotated secret takes effect without a restart`() = runBlocking {
        var current = "ntn_first"
        val api = api(token = { NotionToken(current) })
        reply(results()); reply(results())
        api.query(ds)
        current = "ntn_second"
        api.query(ds)
        assertEquals("Bearer ntn_first", taken().getHeader("Authorization"))
        assertEquals("Bearer ntn_second", taken().getHeader("Authorization"))
    }

    @Test
    fun `a query posts to the data source with its filter, sorts, cursor and page size`() = runBlocking {
        reply(results(pageJson(), next = "cursor-2"))
        val page = api().query(
            ds,
            NotionFilter.And(listOf(NotionFilter.SelectEquals("Status", "Open"), NotionFilter.LastEditedOnOrAfter("2026-10-06T12:00:00Z"))),
            listOf(NotionSort.ByTimestamp(NotionSort.Timestamp.LAST_EDITED_TIME, ascending = false), NotionSort.ByProperty("Item")),
            startCursor = "cursor-1",
            pageSize = 25,
        )
        val request = taken()
        assertEquals("POST", request.method)
        assertEquals("/v1/data_sources/$ds/query", request.path)
        val body = request.bodyJson()
        assertEquals(25, body["page_size"]!!.jsonPrimitive.content.toInt())
        assertEquals("cursor-1", body["start_cursor"]!!.jsonPrimitive.content)
        val and = body["filter"]!!.jsonObject["and"]!!.jsonArray
        assertEquals("""{"property":"Status","select":{"equals":"Open"}}""", and[0].toString())
        assertEquals("""{"timestamp":"last_edited_time","last_edited_time":{"on_or_after":"2026-10-06T12:00:00Z"}}""", and[1].toString())
        val sorts = body["sorts"]!!.jsonArray
        assertEquals("""{"timestamp":"last_edited_time","direction":"descending"}""", sorts[0].toString())
        assertEquals("""{"property":"Item","direction":"ascending"}""", sorts[1].toString())
        assertEquals("cursor-2", page.nextCursor)
        assertEquals(1, page.pages.size)
    }

    @Test
    fun `each kind of filter compiles to the shape Notion documents`() = runBlocking {
        val cases = mapOf(
            NotionFilter.SelectDoesNotEqual("Status", "Done") to """{"property":"Status","select":{"does_not_equal":"Done"}}""",
            NotionFilter.SelectIsEmpty("Override") to """{"property":"Override","select":{"is_empty":true}}""",
            NotionFilter.SelectIsNotEmpty("Override") to """{"property":"Override","select":{"is_not_empty":true}}""",
            NotionFilter.CheckboxEquals("Focus", true) to """{"property":"Focus","checkbox":{"equals":true}}""",
            NotionFilter.DateOnOrBefore("Date", "2026-10-09") to """{"property":"Date","date":{"on_or_before":"2026-10-09"}}""",
            NotionFilter.DateOnOrAfter("Date", "2026-10-01") to """{"property":"Date","date":{"on_or_after":"2026-10-01"}}""",
            NotionFilter.DateIsEmpty("Date") to """{"property":"Date","date":{"is_empty":true}}""",
            NotionFilter.DateIsNotEmpty("Date") to """{"property":"Date","date":{"is_not_empty":true}}""",
            NotionFilter.RelationContains("Container", pageId) to """{"property":"Container","relation":{"contains":"$pageId"}}""",
            NotionFilter.RelationIsEmpty("Container") to """{"property":"Container","relation":{"is_empty":true}}""",
            NotionFilter.RelationIsNotEmpty("Container") to """{"property":"Container","relation":{"is_not_empty":true}}""",
            NotionFilter.TextEquals("Op key", "r:0") to """{"property":"Op key","rich_text":{"equals":"r:0"}}""",
            NotionFilter.TextEquals("Item", "x", NotionFilter.TextKind.TITLE) to """{"property":"Item","title":{"equals":"x"}}""",
            NotionFilter.TextContains("Item", "deck", NotionFilter.TextKind.TITLE) to """{"property":"Item","title":{"contains":"deck"}}""",
            NotionFilter.Or(listOf(NotionFilter.CheckboxEquals("Focus", false))) to """{"or":[{"property":"Focus","checkbox":{"equals":false}}]}""",
        )
        for ((filter, expected) in cases) {
            reply(results())
            api().query(ds, filter)
            assertEquals(expected, taken().bodyJson()["filter"].toString(), "for $filter")
        }
    }

    @Test
    fun `a page size outside 1 to 100 is refused before any request`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api().query(ds, pageSize = 0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api().query(ds, pageSize = 101) } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `creating a page names the data source as its parent and writes each kind of property`() = runBlocking {
        reply(pageJson())
        api().createPage(ds, mapOf(
            "Item" to NotionValue.Title("Send the deck"),
            "Said" to NotionValue.RichText("I need to send the deck"),
            "Kind" to NotionValue.Select("Task"),
            "Override" to NotionValue.Select(null),
            "Tentative" to NotionValue.Checkbox(true),
            "Date" to NotionValue.DateValue("2026-10-09"),
            "Due" to NotionValue.DateValue(null),
            "Container" to NotionValue.Relation(listOf(other)),
        ))
        val request = taken()
        assertEquals("POST", request.method)
        assertEquals("/v1/pages", request.path)
        val body = request.bodyJson()
        assertEquals("""{"type":"data_source_id","data_source_id":"$ds"}""", body["parent"].toString())
        val props = body["properties"]!!.jsonObject
        assertEquals("""{"title":[{"type":"text","text":{"content":"Send the deck"}}]}""", props["Item"].toString())
        assertEquals("""{"rich_text":[{"type":"text","text":{"content":"I need to send the deck"}}]}""", props["Said"].toString())
        assertEquals("""{"select":{"name":"Task"}}""", props["Kind"].toString())
        assertEquals("""{"select":null}""", props["Override"].toString())
        assertEquals("""{"checkbox":true}""", props["Tentative"].toString())
        assertEquals("""{"date":{"start":"2026-10-09"}}""", props["Date"].toString())
        assertEquals("""{"date":null}""", props["Due"].toString())
        assertEquals("""{"relation":[{"id":"$other"}]}""", props["Container"].toString())
    }

    @Test
    fun `long text is split into pieces of at most 2000 characters that join back exactly`() = runBlocking {
        val text = "abcdefghij".repeat(450) // 4,500 characters
        reply(pageJson())
        api().createPage(ds, mapOf("Said" to NotionValue.RichText(text)))
        val pieces = taken().bodyJson()["properties"]!!.jsonObject["Said"]!!.jsonObject["rich_text"]!!.jsonArray
            .map { it.jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(3, pieces.size)
        assertTrue(pieces.all { it.length <= 2000 })
        assertEquals(text, pieces.joinToString(""))
    }

    @Test
    fun `empty rich text is written as an empty list, which clears the property`() = runBlocking {
        reply(pageJson())
        api().updatePage(pageId, mapOf("Said" to NotionValue.RichText("")))
        val request = taken()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/pages/$pageId", request.path)
        assertEquals("""{"rich_text":[]}""", request.bodyJson()["properties"]!!.jsonObject["Said"].toString())
    }

    @Test
    fun `a read-only property cannot be written`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api().createPage(ds, mapOf("Created" to NotionValue.CreatedTime(Instant.now()))) }
        }
        assertEquals(0, server.requestCount)
    }

    // ── Reading pages ────────────────────────────────────────────────────────

    @Test
    fun `a page is read property by property, whatever its types, and what it does not know is kept as unsupported`() = runBlocking {
        reply(pageJson(props = """
            "Item":{"id":"title","type":"title","title":[{"plain_text":"Send "},{"plain_text":"the deck"}]},
            "Said":{"id":"a","type":"rich_text","rich_text":[{"plain_text":"I need "},{"plain_text":"to send it"}]},
            "Kind":{"id":"b","type":"select","select":{"id":"x","name":"Task","color":"blue"}},
            "Override":{"id":"c","type":"select","select":null},
            "Tentative":{"id":"d","type":"checkbox","checkbox":true},
            "Date":{"id":"e","type":"date","date":{"start":"2026-10-09","end":null,"time_zone":null}},
            "Empty date":{"id":"e2","type":"date","date":null},
            "Container":{"id":"f","type":"relation","relation":[{"id":"$other"}],"has_more":false},
            "Related":{"id":"g","type":"relation","relation":[{"id":"$other"}],"has_more":true},
            "Created":{"id":"h","type":"created_time","created_time":"2026-10-06T12:00:00.000Z"},
            "Owner":{"id":"i","type":"people","people":[{"object":"user","id":"$other"}]}
        """.trimIndent()))
        val page = api().retrievePage(pageId)
        assertEquals("GET", taken().method)
        assertEquals(pageId, page.id)
        assertEquals(ds, page.dataSourceId)
        assertEquals(Instant.parse("2026-10-06T12:05:00Z"), page.lastEditedTime)
        assertEquals("Send the deck", page.title("Item"))
        assertEquals("I need to send it", page.text("Said"))
        assertEquals("Task", page.select("Kind"))
        assertNull(page.select("Override"))
        assertTrue(page.checkbox("Tentative"))
        assertEquals("2026-10-09", page.date("Date")?.start)
        assertNull(page.date("Empty date"))
        assertEquals(listOf(other), page.relation("Container"))
        assertFalse(page.relationHasMore("Container"))
        assertTrue(page.relationHasMore("Related"))
        assertEquals(NotionValue.CreatedTime(Instant.parse("2026-10-06T12:00:00Z")), page.properties["Created"])
        // Formula, rollup, number, url and unique_id are modelled now (see HttpNotionApiPropertyTypesTest); people is not.
        assertEquals(NotionValue.Unsupported("people"), page.properties["Owner"])
    }

    @Test
    fun `an id comes back normalized and a plain page has no data source`() = runBlocking {
        reply(pageJson(id = pageId.replace("-", "").uppercase(), parentDs = null))
        val page = api().retrievePage(pageId)
        assertEquals(pageId, page.id)
        assertNull(page.dataSourceId)
    }

    @Test
    fun `an archived or trashed page says so`() = runBlocking {
        reply(pageJson(archived = true))
        assertTrue(api().retrievePage(pageId).archived)
    }

    @Test
    fun `a last page has no cursor even if the server echoes a stale one`() = runBlocking {
        reply("""{"results":[],"next_cursor":"stale","has_more":false}""")
        assertNull(api().query(ds).nextCursor)
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    @Test
    fun `appending blocks sends paragraphs with the bold label marked and the words plain`() = runBlocking {
        reply("""{"object":"list","results":[]}""")
        api().appendBlocks(pageId, listOf(
            NotionBlock.Paragraph(listOf(RichSegment("Person · 2026-10-06T14:02:11Z", bold = true))),
            NotionBlock.Paragraph(listOf(RichSegment("My dog is Norton"))),
        ))
        val request = taken()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/blocks/$pageId/children", request.path)
        val children = request.bodyJson()["children"]!!.jsonArray
        assertEquals(
            """{"object":"block","type":"paragraph","paragraph":{"rich_text":[{"type":"text","text":{"content":"Person · 2026-10-06T14:02:11Z"},"annotations":{"bold":true}}]}}""",
            children[0].toString(),
        )
        assertEquals(
            """{"object":"block","type":"paragraph","paragraph":{"rich_text":[{"type":"text","text":{"content":"My dog is Norton"}}]}}""",
            children[1].toString(),
        )
    }

    @Test
    fun `more than 100 blocks, or a segment over 2000 characters, is refused before any request`() {
        val one = NotionBlock.Paragraph(listOf(RichSegment("x")))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api().appendBlocks(pageId, List(101) { one }) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api().appendBlocks(pageId, listOf(NotionBlock.Paragraph(listOf(RichSegment("y".repeat(2001)))))) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `appending nothing sends nothing`() = runBlocking {
        api().appendBlocks(pageId, emptyList())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `listing blocks reads text, bold, and the cursor, and gives no text for a block that has none`() = runBlocking {
        reply("""
            {"object":"list","has_more":true,"next_cursor":"next-1","results":[
              {"id":"$other","type":"paragraph","created_time":"2026-10-06T14:00:00.000Z",
               "paragraph":{"rich_text":[{"plain_text":"Person · 2026-10-06T14:02:11Z","annotations":{"bold":true}}]}},
              {"id":"33333333-3333-4333-8333-333333333333","type":"paragraph","created_time":"2026-10-06T14:00:01.000Z",
               "paragraph":{"rich_text":[{"plain_text":"hello "},{"plain_text":"there","annotations":{"bold":false}}]}},
              {"id":"44444444-4444-4444-8444-444444444444","type":"divider","created_time":"2026-10-06T14:00:02.000Z","divider":{}}
            ]}
        """.trimIndent())
        val page = api().listBlocks(pageId, startCursor = "c0", pageSize = 50)
        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/v1/blocks/$pageId/children?page_size=50&start_cursor=c0", request.path)
        assertEquals("next-1", page.nextCursor)
        assertTrue(page.blocks[0].segments.single().bold)
        assertEquals("hello there", page.blocks[1].plainText)
        assertFalse(page.blocks[1].segments.any { it.bold })
        assertEquals("divider", page.blocks[2].type)
        assertEquals(emptyList<RichSegment>(), page.blocks[2].segments)
    }

    // ── Errors ───────────────────────────────────────────────────────────────

    @Test
    fun `a 429 becomes a rate limit with Retry-After in milliseconds, or none when absent`() {
        reply("""{"object":"error","status":429,"code":"rate_limited","message":"slow down"}""", 429, "Retry-After" to "2")
        val withHeader = assertThrows(NotionRateLimitedException::class.java) { runBlocking { api().query(ds) } }
        assertEquals(2_000L, withHeader.retryAfterMs)

        reply("""{"object":"error","status":429,"code":"rate_limited","message":"slow down"}""", 429)
        val without = assertThrows(NotionRateLimitedException::class.java) { runBlocking { api().query(ds) } }
        assertNull(without.retryAfterMs)
    }

    @Test
    fun `an error body becomes an API exception with Notion's status and code`() {
        reply("""{"object":"error","status":400,"code":"validation_error","message":"Status is expected to be select."}""", 400)
        val e = assertThrows(NotionApiException::class.java) { runBlocking { api().createPage(ds, mapOf("Item" to NotionValue.Title("x"))) } }
        assertEquals(400, e.status)
        assertEquals("validation_error", e.code)
        assertTrue(e.message!!.contains("validation_error"))
    }

    @Test
    fun `a non-JSON error and a malformed success are both reported, not crashed on`() {
        reply("<html>Bad gateway</html>", 502)
        val bad = assertThrows(NotionApiException::class.java) { runBlocking { api().query(ds) } }
        assertEquals(502, bad.status)
        assertNull(bad.code)

        reply("this is not json", 200)
        val malformed = assertThrows(NotionApiException::class.java) { runBlocking { api().query(ds) } }
        assertEquals("malformed_response", malformed.code)
    }

    @Test
    fun `an error message is capped, and no exception from any failure contains the token`() {
        val longDetail = "x".repeat(5_000)
        val failures = listOf(
            { reply("""{"object":"error","status":400,"code":"validation_error","message":"$longDetail"}""", 400) },
            { reply("""{"object":"error","status":401,"code":"unauthorized","message":"API token is invalid."}""", 401) },
            { reply("{}", 429, "Retry-After" to "1") },
            { reply("oops", 500) },
        )
        for (enqueue in failures) {
            enqueue()
            val e = assertThrows(NotionException::class.java) { runBlocking { api().query(ds) } }
            assertFalse(e.message!!.contains(secret), "an exception message carried the token")
            assertTrue(e.message!!.length < 500, "an exception message was ${e.message!!.length} characters")
            assertFalse(e.toString().contains(secret))
        }
    }

    @Test
    fun `a connection that cannot be made is reported as failing before the request left`() {
        val port = server.port
        server.shutdown()
        val api = HttpNotionApi({ NotionToken(secret) }, baseUrl = "http://127.0.0.1:$port")
        val e = assertThrows(NotionTransportException::class.java) { runBlocking { api.query(ds) } }
        assertFalse(e.requestMayHaveBeenProcessed)
        assertFalse(e.message!!.contains(secret))
    }

    @Test
    fun `a request that goes unanswered is reported as possibly processed`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val impatient = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(2, TimeUnit.SECONDS).build()
        val e = assertThrows(NotionTransportException::class.java) { runBlocking { api(impatient).createPage(ds, mapOf("Item" to NotionValue.Title("x"))) } }
        assertTrue(e.requestMayHaveBeenProcessed)
    }

    // ── The whole stack ──────────────────────────────────────────────────────

    @Test
    fun `behind the guard and the limiter, a page from a table that is not configured never gets through`() {
        val sources = NotionDataSources(ds, other, "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555", "66666666-6666-4666-8666-666666666666")
        val stack = AllowlistedNotionApi(RateLimitedNotionApi(api(), RequestLimiter(), RetryPolicy(jitter = { 0L })), sources)
        reply(results(pageJson(parentDs = "99999999-9999-4999-8999-999999999999")))
        val e = assertThrows(NotionGuardViolation::class.java) { runBlocking { stack.query(ds) } }
        assertTrue(e.message!!.contains(pageId))
        assertFalse(stack.isKnownPage(pageId))
    }

    @Test
    fun `behind the guard and the limiter, a page from a configured table is returned and becomes known`() = runBlocking {
        val sources = NotionDataSources(ds, other, "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555", "66666666-6666-4666-8666-666666666666")
        val stack = AllowlistedNotionApi(RateLimitedNotionApi(api(), RequestLimiter(), RetryPolicy(jitter = { 0L })), sources)
        reply(results(pageJson()))
        assertEquals(listOf(pageId), stack.query(ds).pages.map { it.id })
        assertTrue(stack.isKnownPage(pageId))
    }

    @Test
    fun `a 429 from the server is retried by the limiter and the caller sees only the success`() = runBlocking {
        val stack = RateLimitedNotionApi(api(), RequestLimiter(1000.0, 100), RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 5, jitter = { 0L }), sleep = {})
        reply("{}", 429, "Retry-After" to "0")
        reply(results(pageJson()))
        assertEquals(1, stack.query(ds).pages.size)
        assertEquals(2, server.requestCount)
        assertEquals(1L, stack.retriesMade)
    }
}
