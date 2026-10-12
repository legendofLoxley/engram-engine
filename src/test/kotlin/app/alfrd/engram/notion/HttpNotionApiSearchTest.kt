package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HttpNotionApiSearchTest {

    private lateinit var server: MockWebServer
    private val secret = "ntn_SEARCH_SECRET_0123456789"

    @BeforeEach
    fun setUp() { server = MockWebServer().also { it.start() } }

    @AfterEach
    fun tearDown() { runCatching { server.shutdown() } }

    private fun api() = HttpNotionApi({ NotionToken(secret) }, baseUrl = server.url("/").toString())

    private fun reply(body: String) = server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))

    @Test
    fun `it asks for data sources only and reads their ids and titles across pages`() = runBlocking {
        reply(
            """{"object":"list","has_more":true,"next_cursor":"c2","results":[
                {"object":"data_source","id":"2ee6db65-bade-4662-a3e5-0246bd5b733f","title":[{"plain_text":"Goals"},{"plain_text":" (Agent Work)"}]},
                {"object":"page","id":"11111111-1111-4111-8111-111111111111","properties":{}}]}""",
        )
        reply(
            """{"object":"list","has_more":false,"next_cursor":null,"results":[
                {"object":"data_source","id":"545ccad735ac4131b69cfa5ee8f0c3e1","title":[{"plain_text":"Cycles"}]}]}""",
        )

        val found = api().searchDataSources()

        assertEquals(listOf(VisibleDataSource("2ee6db65-bade-4662-a3e5-0246bd5b733f", "Goals (Agent Work)"), VisibleDataSource("545ccad7-35ac-4131-b69c-fa5ee8f0c3e1", "Cycles")), found)

        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", first.method)
        assertEquals("/v1/search", first.path)
        assertEquals("Bearer $secret", first.getHeader("Authorization"))
        val body = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        assertEquals("data_source", body["filter"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("object", body["filter"]!!.jsonObject["property"]!!.jsonPrimitive.content)
        val second = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals("c2", second["start_cursor"]!!.jsonPrimitive.content, "the second page is fetched with the cursor")
    }

    @Test
    fun `it stops after the page limit instead of walking a huge workspace`() = runBlocking {
        repeat(3) { reply("""{"object":"list","has_more":true,"next_cursor":"more","results":[]}""") }
        assertTrue(api().searchDataSources(maxPages = 3).isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a Notion error surfaces as an API exception that does not carry the token`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody("""{"object":"error","status":401,"code":"unauthorized","message":"API token is invalid."}"""))
        val e = runCatching { api().searchDataSources() }.exceptionOrNull()
        assertTrue(e is NotionApiException && e.status == 401, "got $e")
        assertTrue(!e!!.message!!.contains(secret))
    }
}
