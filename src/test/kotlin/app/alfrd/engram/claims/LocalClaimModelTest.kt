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
}
