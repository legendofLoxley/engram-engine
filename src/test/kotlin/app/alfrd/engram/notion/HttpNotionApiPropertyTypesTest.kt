package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The property types the Agent Work and PPS databases use: how they cross the wire, in both directions. */
class HttpNotionApiPropertyTypesTest {

    private lateinit var server: MockWebServer
    private val ds = "d67376eb-af39-4ee5-bae1-76b7875e15f5"
    private val pageId = "11111111-1111-4111-8111-111111111111"
    private val mentioned = "6e9ea25d-e733-4d89-9d7a-52783509ff54"
    private val json = Json

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun api() = HttpNotionApi({ NotionToken("ntn_test") }, baseUrl = server.url("/").toString())

    private fun reply(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "application/json"))
    }

    private fun taken(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun pageWith(props: String) = """
        {"object":"page","id":"$pageId","created_time":"2026-10-06T12:00:00.000Z","last_edited_time":"2026-10-06T12:05:00.000Z",
         "parent":{"type":"data_source_id","data_source_id":"$ds","database_id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd"},
         "properties":{$props}}
    """.trimIndent()

    private suspend fun read(props: String): NotionPage {
        reply(pageWith(props))
        return api().retrievePage(pageId)
    }

    @Test
    fun `a text run whose hyperlink arrives without an href still yields its link`() = runBlocking {
        val page = read(
            """"Notes":{"id":"n","type":"rich_text","rich_text":[
                {"type":"text","plain_text":"see here","text":{"content":"see here","link":{"url":"https://example.org/a"}}}]}""",
        )
        assertEquals("see here", page.text("Notes"))
        assertEquals(listOf("https://example.org/a"), page.links("Notes"))
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    @Test
    fun `numbers and urls are written in Notion's shapes, and an empty one clears the property`() = runBlocking {
        reply(pageWith(""))
        api().createPage(
            ds,
            mapOf(
                "Order" to NotionValue.NumberValue(3.0),
                "Plan Version" to NotionValue.NumberValue(null),
                "URL" to NotionValue.UrlValue("https://example.org/a?b=1"),
                "Other URL" to NotionValue.UrlValue(null),
            ),
        )
        val properties = json.parseToJsonElement(taken().body.readUtf8()).jsonObject["properties"]!!.jsonObject
        assertEquals(3.0, properties["Order"]!!.jsonObject["number"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(JsonNull, properties["Plan Version"]!!.jsonObject["number"])
        assertEquals("https://example.org/a?b=1", properties["URL"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, properties["Other URL"]!!.jsonObject["url"])
    }

    @Test
    fun `a value Notion computes cannot be serialized at all, so nothing is sent`() {
        val computed = mapOf(
            "Goal ID" to NotionValue.UniqueId("GOAL", 1),
            "Confidence" to NotionValue.Formula("number", number = 0.5),
            "Best Support" to NotionValue.Rollup("number", number = 2.0),
            "Created" to NotionValue.CreatedTime(Instant.EPOCH),
            "Mystery" to NotionValue.Unsupported("people"),
        )
        for ((name, value) in computed) {
            val e = assertThrows(IllegalArgumentException::class.java) { runBlocking { api().updatePage(pageId, mapOf(name to value)) } }
            assertTrue(e.message!!.contains("'$name'") && e.message!!.contains("read-only"), e.message)
        }
        assertEquals(0, server.requestCount, "a request was sent for a value that cannot be written")
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    @Test
    fun `numbers, urls and auto-increment ids are read`() = runBlocking {
        val page = read(
            """
            "Order":{"id":"a","type":"number","number":4},
            "Empty number":{"id":"b","type":"number","number":null},
            "URL":{"id":"c","type":"url","url":"https://example.org/doc.pdf"},
            "Empty URL":{"id":"d","type":"url","url":null},
            "Goal ID":{"id":"e","type":"unique_id","unique_id":{"prefix":"GOAL","number":12}},
            "Bare ID":{"id":"f","type":"unique_id","unique_id":{"prefix":null,"number":7}}
            """.trimIndent(),
        )
        assertEquals(4.0, page.number("Order"))
        assertNull(page.number("Empty number"))
        assertEquals("https://example.org/doc.pdf", page.url("URL"))
        assertNull(page.url("Empty URL"))
        assertEquals(NotionValue.UniqueId("GOAL", 12), page.uniqueId("Goal ID"))
        assertEquals("GOAL-12", page.uniqueId("Goal ID")!!.display)
        assertEquals("7", page.uniqueId("Bare ID")!!.display)
    }

    @Test
    fun `a malformed unique id is kept as unsupported rather than failing the read`() = runBlocking {
        val page = read(
            """
            "Goal ID":{"id":"e","type":"unique_id","unique_id":{"prefix":"GOAL"}}
            """.trimIndent(),
        )
        assertEquals(NotionValue.Unsupported("unique_id"), page.properties["Goal ID"])
    }

    @Test
    fun `formulas are read by their result type`() = runBlocking {
        val page = read(
            """
            "Text":{"id":"a","type":"formula","formula":{"type":"string","string":"Claim"}},
            "Score":{"id":"b","type":"formula","formula":{"type":"number","number":0.75}},
            "Whole":{"id":"c","type":"formula","formula":{"type":"number","number":3}},
            "Flag":{"id":"d","type":"formula","formula":{"type":"boolean","boolean":true}},
            "When":{"id":"e","type":"formula","formula":{"type":"date","date":{"start":"2026-10-24","end":null}}},
            "Broken":{"id":"f","type":"formula","formula":null}
            """.trimIndent(),
        )
        assertEquals("Claim", page.formula("Text")!!.display)
        assertEquals(0.75, page.formula("Score")!!.number)
        assertEquals("0.75", page.formula("Score")!!.display)
        assertEquals("3", page.formula("Whole")!!.display)
        assertEquals(true, page.formula("Flag")!!.boolean)
        assertEquals("2026-10-24", page.formula("When")!!.display)
        assertEquals(NotionValue.Unsupported("formula"), page.properties["Broken"])
        assertTrue(page.properties.getValue("Score").isReadOnly)
    }

    @Test
    fun `rollups are read as a number, a date or an array of values`() = runBlocking {
        val page = read(
            """
            "Total":{"id":"a","type":"rollup","rollup":{"type":"number","number":4,"function":"sum"}},
            "Latest":{"id":"b","type":"rollup","rollup":{"type":"date","date":{"start":"2026-10-01"},"function":"latest_date"}},
            "Best":{"id":"c","type":"rollup","rollup":{"type":"array","function":"show_original","array":[
                {"type":"number","number":2},
                {"type":"title","title":[{"type":"text","text":{"content":"Memo"},"plain_text":"Memo"}]},
                {"type":"formula","formula":{"type":"number","number":5}}]}},
            "Broken":{"id":"d","type":"rollup","rollup":null}
            """.trimIndent(),
        )
        assertEquals(4.0, page.rollup("Total")!!.number)
        assertEquals("sum", page.rollup("Total")!!.function)
        assertEquals("2026-10-01", page.rollup("Latest")!!.date!!.start)
        val items = page.rollup("Best")!!.items
        assertEquals(NotionValue.NumberValue(2.0), items[0])
        assertEquals(NotionValue.Title("Memo"), items[1])
        assertEquals(5.0, (items[2] as NotionValue.Formula).number)
        assertEquals(NotionValue.Unsupported("rollup"), page.properties["Broken"])
    }

    // ── Links in rich text ───────────────────────────────────────────────────

    @Test
    fun `the url a page mention points at is kept, because its plain text is only a title`() = runBlocking {
        val page = read(
            """
            "Target Links":{"id":"a","type":"rich_text","rich_text":[
              {"type":"text","text":{"content":"PPS Sources: "},"plain_text":"PPS Sources: ","href":null},
              {"type":"mention","mention":{"type":"page","page":{"id":"$mentioned"}},"plain_text":"Sources","href":"https://www.notion.so/${mentioned.replace("-", "")}"},
              {"type":"text","text":{"content":" ; see "},"plain_text":" ; see ","href":null},
              {"type":"text","text":{"content":"the memo","link":{"url":"https://example.org/memo.pdf"}},"plain_text":"the memo","href":"https://example.org/memo.pdf"}
            ]}
            """.trimIndent(),
        )
        assertEquals("PPS Sources: Sources ; see the memo", page.text("Target Links"))
        assertEquals(listOf("https://www.notion.so/${mentioned.replace("-", "")}", "https://example.org/memo.pdf"), page.links("Target Links"))
    }

    @Test
    fun `a mention without an href gets a url built from the mentioned id, and repeats are dropped`() = runBlocking {
        val page = read(
            """
            "Target Links":{"id":"a","type":"rich_text","rich_text":[
              {"type":"mention","mention":{"type":"page","page":{"id":"$mentioned"}},"plain_text":"A"},
              {"type":"mention","mention":{"type":"database","database":{"id":"$pageId"}},"plain_text":"B"},
              {"type":"mention","mention":{"type":"page","page":{"id":"$mentioned"}},"plain_text":"A again"},
              {"type":"mention","mention":{"type":"user","user":{"id":"$pageId"}},"plain_text":"@someone"}
            ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf("https://www.notion.so/${mentioned.replace("-", "")}", "https://www.notion.so/${pageId.replace("-", "")}"),
            page.links("Target Links"),
        )
    }

    @Test
    fun `plain rich text has no links, and a value read with links still compares equal to one without`() = runBlocking {
        val page = read(
            """
            "Notes":{"id":"a","type":"rich_text","rich_text":[{"type":"text","text":{"content":"hello"},"plain_text":"hello","href":null}]}
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), page.links("Notes"))
        assertEquals(NotionValue.RichText("hello"), page.properties["Notes"])
    }
}
