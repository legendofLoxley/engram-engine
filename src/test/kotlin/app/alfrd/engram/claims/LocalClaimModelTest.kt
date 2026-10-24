package app.alfrd.engram.claims

import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.SourceRecord
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress

/** The local model adapter against a fake llama-server on loopback: the wire shape, the model name the server reports, and unreadable answers. */
class LocalClaimModelTest {

    private lateinit var server: HttpServer
    private val bodies = ArrayList<String>()
    private var reply: () -> String = { "{}" }

    @BeforeEach
    fun up() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            bodies += ex.requestBody.readBytes().decodeToString()
            val out = reply().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.start()
    }

    @AfterEach
    fun down() { server.stop(0) }

    private val source = SourceRecord("s", "document", "gdrive:x", "District wide scenarios", "full", null, "h", 0, 1)
    private val chunk = ChunkRecord("c", "s", 0, "pdf p. 2", "About 80% of students attend a sustainable school.", "h", page = 2, section = "Scenario A")

    private fun toolReply(model: String, arguments: String) = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        Json.parseToJsonElement(
            """{"model":${Json.encodeToString(kotlinx.serialization.serializer<String>(), model)},"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"id":"1","type":"function","function":{"name":"report_claims","arguments":${Json.encodeToString(kotlinx.serialization.serializer<String>(), arguments)}}}]}}]}""",
        ).jsonObject,
    )

    private fun model() = LocalClaimModel(LocalLlmClient(baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "configured-label"), "http://127.0.0.1:${server.address.port}/v1")

    @Test
    fun `a pass sends one forced tool call with the passage, and reads the claims and the model name the server reports`() = runBlocking<Unit> {
        reply = { toolReply("Qwen3.6-35B-A3B-UD-Q4_K_M.gguf", """{"claims":[{"quote":"About 80% of students attend a sustainable school.","claimant":"PPS","subject":"students at sustainable schools","kind":"number","place":"SCENARIO A"}]}""") }
        val r = model().extract(source, chunk, 1)
        assertEquals("Qwen3.6-35B-A3B-UD-Q4_K_M.gguf", r.modelName, "the stamp is what the server reported, not the configured label")
        val c = r.claims.single()
        assertEquals("PPS", c.claimant); assertEquals("number", c.kind); assertEquals("SCENARIO A", c.place)

        val sent = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("required", sent["tool_choice"]!!.jsonPrimitive.content)
        assertEquals("report_claims", sent["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val user = sent["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(user.contains("About 80% of students") && user.contains("pdf p. 2") && user.contains("District wide scenarios"))
    }

    @Test
    fun `the two passes are separate requests with different instructions and neither carries the other's answer`() = runBlocking<Unit> {
        reply = { toolReply("Qwen3.6-35B-A3B", """{"claims":[{"quote":"About 80% of students attend a sustainable school.","claimant":"SECRET-ANSWER-OF-PASS-ONE","kind":"number"}]}""") }
        model().extract(source, chunk, 1); model().extract(source, chunk, 2)
        assertEquals(2, bodies.size)
        fun system(i: Int) = Json.parseToJsonElement(bodies[i]).jsonObject["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertNotEquals(system(0), system(1))
        assertTrue(bodies.all { !it.contains("SECRET-ANSWER-OF-PASS-ONE") }, "pass 2's request does not contain pass 1's answer")
        assertEquals(2, bodies.size)
    }

    @Test
    fun `an empty claims list is a valid answer, a missing one is not`() = runBlocking<Unit> {
        reply = { toolReply("Qwen3.6-35B-A3B", """{"claims":[]}""") }
        assertTrue(model().extract(source, chunk, 1).claims.isEmpty())
        reply = { toolReply("Qwen3.6-35B-A3B", """{"nothing":true}""") }
        assertThrows<IllegalStateException> { runBlocking { model().extract(source, chunk, 1) } }
    }

    @Test
    fun `a claim with no quote makes the answer unreadable rather than being skipped`() {
        reply = { toolReply("Qwen3.6-35B-A3B", """{"claims":[{"quote":"","kind":"fact"}]}""") }
        assertThrows<IllegalStateException> { runBlocking { model().extract(source, chunk, 1) } }
    }

    @Test
    fun `an answer that names no model is stamped with the client's configured label, which the run's model check refuses`() = runBlocking<Unit> {
        reply = { """{"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"function":{"name":"report_claims","arguments":"{\"claims\":[]}"}}]}}]}""" }
        val name = model().extract(source, chunk, 1).modelName
        assertEquals("configured-label", name)
        assertTrue(!name.lowercase().filter { it.isLetterOrDigit() }.contains("qwen3635ba3b"))
    }

    @Test
    fun `the adapter takes the local client type, so a hosted client cannot be passed`() {
        assertTrue(LocalClaimModel::class.java.constructors.none { app.alfrd.engram.cognitive.providers.LlmClient::class.java == it.parameterTypes.firstOrNull() })
        assertTrue(LocalClaimModel::class.java.constructors.all { it.parameterTypes.first() == LocalLlmClient::class.java })
    }

    @Test
    fun `the built-in prompts are claims-v5 and carry the rules for tables, labelled numbers, headings and page furniture`() {
        val d = PromptSet.DEFAULT
        assertEquals("claims-v5", d.version)
        assertNotEquals(d.pass1, d.pass2)
        for (p in listOf(d.pass1, d.pass2)) {
            assertTrue(p.contains("the whole row") && p.contains("never a single cell"), "tables: whole row, column header as place")
            assertTrue(p.contains("what it counts") && p.contains("never \"38\" alone"), "a number carries its label")
            assertTrue(p.contains("page furniture") && p.contains("Updated <date>"), "no footers or date stamps")
            assertTrue(p.contains("Quote the whole sentence when it makes one assertion") && p.contains("give one claim per value"), "a sentence of several values is split by value")
            assertTrue(p.contains("passage: the whole sentence") && p.contains("as_of:") && p.contains("leave it empty rather than guess"), "context: passage, about, period, never guessed")
            assertTrue(p.contains("outermost one that names a scenario"))
            assertTrue(p.contains("one claim per list item") && p.contains("Never give a whole list as one quote"), "lists are split into items")
            assertTrue(p.contains("Never reorder them and never add a word that is not there"), "quotes keep the passage's order")
            assertTrue(p.contains("never the name of a card, box, list or program inside it") && p.contains("always give one"), "scenario heading is the place; card names go in the subject")
        }
    }

    @Test
    fun `custom prompts are named by a hash of both texts, and are validated`() {
        val a = PromptSet.custom("one", "two"); val b = PromptSet.custom("one", "two"); val c = PromptSet.custom("one", "three")
        assertEquals(a.version, b.version); assertNotEquals(a.version, c.version)
        assertTrue(Regex("custom-[0-9a-f]{16}").matches(a.version))
        assertThrows<IllegalArgumentException> { PromptSet.custom(" ", "two") }
        assertThrows<IllegalArgumentException> { PromptSet.custom("one", "x".repeat(PromptSet.MAX_CHARS + 1)) }
    }

    @Test
    fun `a model built with custom prompts sends them, one per pass, and names them in its version`() = runBlocking<Unit> {
        reply = { toolReply("Qwen3.6-35B-A3B", """{"claims":[]}""") }
        val m = LocalClaimModel(LocalLlmClient(baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "x"), null, PromptSet.custom("FIRST-WORDING", "SECOND-WORDING"))
        m.extract(source, chunk, 1); m.extract(source, chunk, 2)
        fun system(i: Int) = Json.parseToJsonElement(bodies[i]).jsonObject["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("FIRST-WORDING", system(0)); assertEquals("SECOND-WORDING", system(1))
        assertTrue(m.promptVersion.startsWith("custom-"))
        assertEquals("claims-v5", LocalClaimModel(LocalLlmClient(baseUrl = "http://127.0.0.1:1/v1"), null).promptVersion)
    }

    @Test
    fun `how the server says the answer ended is carried on the pass result`() = runBlocking<Unit> {
        reply = { """{"model":"Qwen3.6-35B-A3B","usage":{"completion_tokens":321},"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"function":{"name":"report_claims","arguments":"{\"claims\":[]}"}}]}}]}""" }
        val r = model().extract(source, chunk, 1)
        assertEquals("tool_calls", r.finishReason); assertEquals(321, r.completionTokens)
    }

    @Test
    fun `the context a pass gives is read, and context in another shape is left out without refusing the answer`() {
        val input = kotlinx.serialization.json.Json.parseToJsonElement("""{"claims": [
            {"quote": "78% of Clark's students were students of color", "kind": "number", "passage": "In 2023–24, 78% of Clark's students were students of color.",
             "about": [{"name": "Clark Elementary", "kind": "school", "parent": "PPS"}, "not an object", {"kind": "no name"}], "as_of": "2023–24"},
            {"quote": "The Board voted 5 to 2.", "kind": "vote", "about": "PPS"}
        ]}""") as kotlinx.serialization.json.JsonObject
        val claims = LocalClaimModel.parseClaims(input)
        assertEquals(listOf(ThingRef("Clark Elementary", "school", "PPS")), claims[0].about)
        assertEquals("2023–24", claims[0].asOf)
        assertEquals("In 2023–24, 78% of Clark's students were students of color.", claims[0].passage)
        assertTrue(claims[1].about.isEmpty() && claims[1].passage == null && claims[1].asOf == null)
        val schema = LocalClaimModel.REPORT_TOOL.inputSchema.toString()
        assertTrue(schema.contains("\"passage\"") && schema.contains("\"as_of\"") && schema.contains("\"about\""))
    }
}
