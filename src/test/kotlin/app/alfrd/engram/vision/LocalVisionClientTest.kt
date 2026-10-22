package app.alfrd.engram.vision

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64

class LocalVisionClientTest {
    private lateinit var server: HttpServer
    private val bodies = ArrayList<String>()
    private var status = 200
    private var reply = """{"model":"Qwen3.8-27B","usage":{"completion_tokens":42},"choices":[{"finish_reason":"stop","message":{"content":"{\"sections\":[]}"}}]}"""

    @BeforeEach
    fun up() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            bodies += ex.requestBody.readBytes().decodeToString()
            val out = (if (status == 200) reply else "SECRET PROMPT ECHO").toByteArray()
            ex.sendResponseHeaders(status, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.start()
    }

    @AfterEach
    fun down() { server.stop(0) }

    private fun url() = "http://127.0.0.1:${server.address.port}/v1"

    @Test
    fun `one image and the prompt go in one user message, and the reported model, finish and tokens come back`() {
        val png = byteArrayOf(1, 2, 3)
        val a = LocalVisionClient(url()).read(png, "OCR:")
        assertEquals("{\"sections\":[]}", a.text); assertEquals("Qwen3.8-27B", a.model); assertEquals("stop", a.finishReason); assertEquals(42, a.completionTokens)
        val sent = Json.parseToJsonElement(bodies.single()).jsonObject
        val content = sent["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png), content[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("OCR:", content[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(sent.containsKey("chat_template_kwargs"), "the server's own thinking default is kept unless asked")
    }

    @Test
    fun `thinking can be switched off per request`() {
        LocalVisionClient(url(), thinking = false, temperature = 0.0).read(byteArrayOf(1), "x")
        val sent = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("false", sent["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals("0.0", sent["temperature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a server that is not on loopback is refused before anything is sent`() {
        assertThrows<IllegalArgumentException> { LocalVisionClient("https://api.example.com/v1") }
        assertThrows<IllegalArgumentException> { LocalVisionClient("http://192.168.0.106:8083/v1") }
        assertTrue(bodies.isEmpty())
    }

    @Test
    fun `an error answer is reported by status only, never by its body`() {
        status = 500
        val e = assertThrows<IllegalStateException> { LocalVisionClient(url()).read(byteArrayOf(1), "x") }
        assertTrue(e.message!!.contains("500") && !e.message!!.contains("SECRET"))
    }
}
