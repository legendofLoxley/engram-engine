package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Reading what a goal's Scope mentions and what a page says: typed mentions out of rich text, block types
 * with their children, and the guard's rule about which blocks may be listed. HTTP shapes are Notion's own
 * (2025-09-03), served by a local mock.
 */
class NotionBlockReadingTest {

    private lateinit var server: MockWebServer
    private val secret = "ntn_BLOCK_TEST_SECRET_0123456789"
    private val ds = "d67376eb-af39-4ee5-bae1-76b7875e15f5"
    private val pageId = "11111111-1111-4111-8111-111111111111"
    private val sourcesDatabase = "6e9ea25d-e733-4d89-9d7a-52783509ff54"
    private val persona = "3f1d0721-d4e4-81d5-88c6-f320f5fa25f8"

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun api() = HttpNotionApi({ NotionToken(secret) }, baseUrl = server.url("/").toString())

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json"))
    }

    private fun taken(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun pageWith(props: String) = """
        {"object":"page","id":"$pageId","created_time":"2026-10-06T12:00:00.000Z","last_edited_time":"2026-10-06T12:05:00.000Z","archived":false,
         "parent":{"type":"data_source_id","data_source_id":"$ds","database_id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd"},"properties":{$props}}
    """.trimIndent()

    private fun scopeProperty(vararg runs: String) = """"Scope":{"id":"s","type":"rich_text","rich_text":[${runs.joinToString(",")}]}"""
    private fun text(content: String, link: String? = null) =
        """{"type":"text","text":{"content":"$content","link":${link?.let { """{"url":"$it"}""" } ?: "null"}},"plain_text":"$content","href":${link?.let { "\"$it\"" } ?: "null"}}"""
    private fun mention(kind: String, id: String?, title: String, href: String? = null) =
        """{"type":"mention","mention":{"type":"$kind",${if (id != null) "\"$kind\":{\"id\":\"$id\"}" else "\"$kind\":{}"}},"plain_text":"$title","href":${href?.let { "\"$it\"" } ?: "null"}}"""

    private suspend fun scopeOf(vararg runs: String): NotionValue.RichText {
        reply(pageWith(scopeProperty(*runs)))
        return api().retrievePage(pageId).properties.getValue("Scope") as NotionValue.RichText
    }

    // ── Mentions ─────────────────────────────────────────────────────────────

    @Test
    fun `page and database mentions are read as typed mentions, in order, with their titles`() = runBlocking {
        val scope = scopeOf(
            text("Look only at "),
            mention("database", sourcesDatabase, "PPS Sources", "https://www.notion.so/6e9ea25de7334d899d7a52783509ff54"),
            text(" and "),
            mention("page", persona, "Persona", "https://www.notion.so/3f1d0721d4e481d588c6f320f5fa25f8"),
        )
        assertEquals(
            listOf(NotionMention(MentionKind.DATABASE, sourcesDatabase, "PPS Sources"), NotionMention(MentionKind.PAGE, persona, "Persona")),
            scope.mentions,
        )
        assertEquals("Look only at PPS Sources and Persona", scope.text, "the text includes each mention's title")
    }

    @Test
    fun `a user, a date and a link preview are mentions of nothing a goal could point at, and are left out`() = runBlocking {
        val scope = scopeOf(
            mention("user", "99999999-9999-4999-8999-999999999999", "@Jacob"),
            mention("date", null, "2026-10-20"),
            mention("link_preview", null, "a preview"),
            mention("page", persona, "Persona"),
        )
        assertEquals(listOf(NotionMention(MentionKind.PAGE, persona, "Persona")), scope.mentions)
    }

    @Test
    fun `a hyperlink and a URL typed as text are links, not mentions`() = runBlocking {
        val scope = scopeOf(text("the spec", link = "https://example.org/spec"), text(" https://www.notion.so/3f1d0721d4e481d588c6f320f5fa25f8"))
        assertTrue(scope.mentions.isEmpty(), "only an @-mention is a mention: ${scope.mentions}")
        assertEquals(listOf("https://example.org/spec"), scope.links)
    }

    @Test
    fun `a mention with no id of its own falls back to the id in its href, and one with neither is dropped`() = runBlocking {
        val scope = scopeOf(
            mention("page", null, "From href", "https://www.notion.so/3f1d0721d4e481d588c6f320f5fa25f8"),
            mention("page", null, "Nowhere", null),
        )
        assertEquals(listOf(NotionMention(MentionKind.PAGE, persona, "From href")), scope.mentions)
    }

    @Test
    fun `the same page mentioned twice is one mention`() = runBlocking {
        val scope = scopeOf(mention("page", persona, "Persona"), text(" again "), mention("page", persona, "Persona"))
        assertEquals(1, scope.mentions.size)
    }

    @Test
    fun `a value with no mentions has none, and a value written back never carries them`() = runBlocking {
        assertTrue(scopeOf(text("plain words")).mentions.isEmpty())
        // Writing a rich-text value sends its text only; mentions and links are read-only information.
        reply(pageWith(""))
        api().updatePage(pageId, mapOf("Scope" to NotionValue.RichText("x", links = listOf("https://example.org"), mentions = listOf(NotionMention(MentionKind.PAGE, persona, "Persona")))))
        val body = taken().body.readUtf8()
        assertFalse(body.contains("mention") || body.contains("example.org"), body)
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    private fun block(id: String, type: String, text: String, hasChildren: Boolean = false, extra: String = "") =
        """{"object":"block","id":"$id","type":"$type","has_children":$hasChildren,"created_time":"2026-10-06T12:00:00.000Z",
            "$type":{"rich_text":[{"type":"text","text":{"content":"$text"},"plain_text":"$text"}]$extra}}"""

    @Test
    fun `block types, their children flag and a to-do's checked state are read`() = runBlocking {
        reply(
            """{"object":"list","results":[
                ${block("aaaaaaaa-aaaa-4aaa-8aaa-000000000001", "heading_2", "Plan")},
                ${block("aaaaaaaa-aaaa-4aaa-8aaa-000000000002", "to_do", "Send deck", extra = ""","checked":true""")},
                ${block("aaaaaaaa-aaaa-4aaa-8aaa-000000000003", "to_do", "Call Priya", extra = ""","checked":false""")},
                ${block("aaaaaaaa-aaaa-4aaa-8aaa-000000000004", "toggle", "Details", hasChildren = true)},
                ${block("aaaaaaaa-aaaa-4aaa-8aaa-000000000005", "callout", "Remember")},
                {"object":"block","id":"aaaaaaaa-aaaa-4aaa-8aaa-000000000006","type":"image","has_children":false,"created_time":"2026-10-06T12:00:00.000Z","image":{}}
            ],"has_more":false,"next_cursor":null}""",
        )
        val blocks = api().listBlocks(pageId).blocks

        assertEquals(listOf("heading_2", "to_do", "to_do", "toggle", "callout", "image"), blocks.map { it.type })
        assertEquals(listOf(null, true, false, null, null, null), blocks.map { it.checked })
        assertEquals(listOf(false, false, false, true, false, false), blocks.map { it.hasChildren })
        assertEquals("Send deck", blocks[1].plainText)
        assertEquals("", blocks[5].plainText, "a block with no text reads as empty")
    }

    @Test
    fun `the children of a block are listed by the block's own id, with the token and nothing else in the path`() = runBlocking {
        reply("""{"object":"list","results":[${block("bbbbbbbb-bbbb-4bbb-8bbb-000000000001", "paragraph", "inside")}],"has_more":false,"next_cursor":null}""")
        val children = api().listBlocks("aaaaaaaa-aaaa-4aaa-8aaa-000000000004")
        val request = taken()
        assertEquals("GET", request.method)
        assertTrue(request.path!!.startsWith("/v1/blocks/aaaaaaaa-aaaa-4aaa-8aaa-000000000004/children"), request.path)
        assertEquals("Bearer $secret", request.getHeader("Authorization"))
        assertFalse(request.path!!.contains(secret))
        assertEquals("inside", children.blocks.single().plainText)
    }

    // ── The guard and child blocks ───────────────────────────────────────────

    private class World {
        val fake = FakeNotionApi()
        val itemsDs = fake.defineDataSource(mapOf("Item" to FakeProperty.Title))
        val personaId = "3f1d0721-d4e4-81d5-88c6-f320f5fa25f8"
        val set = NotionTableSet(
            "w",
            listOf(NotionTable("items", itemsDs, TableAccess.READ_ONLY)),
            readablePages = listOf(NotionReadablePage("persona", personaId)),
        )
        val api = AllowlistedNotionApi(fake, set)

        init {
            fake.createPlainPageWithId(
                personaId, "Persona",
                listOf(FakeBlock("toggle", "Rules", children = listOf(FakeBlock("paragraph", "Never loud."))), FakeBlock("paragraph", "Top level.")),
            )
        }

        suspend fun toggleOf(page: String): NotionBlockRead = fake.listBlocks(page).blocks.first { it.hasChildren }
    }

    @Test
    fun `a block listed under a readable page can itself be listed, one level at a time`() = runBlocking {
        val w = World()
        val top = w.api.listBlocks(w.personaId).blocks
        val toggle = top.first { it.hasChildren }
        val inside = w.api.listBlocks(toggle.id).blocks
        assertEquals(listOf("Never loud."), inside.map { it.plainText })
    }

    @Test
    fun `a block id the guard never returned is refused, even a real one`() = runBlocking {
        val w = World()
        val realButUnseen = w.toggleOf(w.personaId).id // learned from the fake directly, not through the guard
        val before = w.fake.requestCount("listBlocks")

        val e = assertThrows(NotionGuardViolation::class.java) { runBlocking { w.api.listBlocks(realButUnseen) } }
        assertTrue(e.message!!.contains("not a readable page"), e.message)
        assertThrows(NotionGuardViolation::class.java) { runBlocking { w.api.listBlocks("00000000-0000-4000-8000-000000000000") } }
        assertEquals(before, w.fake.requestCount("listBlocks"), "a refused list reached Notion")
    }

    @Test
    fun `the children of a page the guard never let through stay out of reach`() = runBlocking<Unit> {
        val w = World()
        val stranger = w.fake.createPlainPageWithId("99999999-9999-4999-8999-999999999999", "Private", listOf(FakeBlock("toggle", "Secret", children = listOf(FakeBlock("paragraph", "hidden")))))
        assertThrows(NotionGuardViolation::class.java) { runBlocking { w.api.listBlocks(stranger) } }
        val toggle = w.toggleOf(stranger)
        assertThrows(NotionGuardViolation::class.java) { runBlocking { w.api.listBlocks(toggle.id) } }
    }

    @Test
    fun `knowing a block never makes it writable`() = runBlocking {
        val w = World()
        val toggle = w.api.listBlocks(w.personaId).blocks.first { it.hasChildren }
        val before = w.fake.requestCount()
        assertThrows(NotionGuardViolation::class.java) {
            runBlocking { w.api.appendBlocks(toggle.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("injected"))))) }
        }
        assertThrows(NotionGuardViolation::class.java) { runBlocking { w.api.updatePage(toggle.id, mapOf("Item" to NotionValue.Title("x"))) } }
        assertEquals(before, w.fake.requestCount(), "a write to a block reached Notion")
    }

    @Test
    fun `the fake nests blocks and reports types, children and checked state the way the real service does`() = runBlocking {
        val fake = FakeNotionApi()
        val id = fake.createPlainPageWithId("12345678-1234-4234-8234-123456789012", "P", listOf(FakeBlock("to_do", "Do it", checked = true), FakeBlock("numbered_list_item", "One", children = listOf(FakeBlock("bulleted_list_item", "sub")))))
        val top = fake.listBlocks(id).blocks
        assertEquals(listOf("to_do", "numbered_list_item"), top.map { it.type })
        assertEquals(true, top[0].checked)
        assertNull(top[1].checked)
        assertTrue(top[1].hasChildren && !top[0].hasChildren)
        assertEquals("sub", fake.listBlocks(top[1].id).blocks.single().plainText)
    }
}
