package app.alfrd.engram.vision

import app.alfrd.engram.scripts.VisionReadCli
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files

class VisionReadCliTest {
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val posted = ArrayList<String>()

    @BeforeEach
    fun up() {
        dir = Files.createTempDirectory("vision-read-cli-").toFile()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/debug/sources") { ex ->
            val body = ex.requestBody.readBytes().decodeToString(); posted += "${ex.requestURI.path} $body"
            val (code, out) = when {
                ex.requestURI.path.endsWith("/vision/chunks") -> 200 to """[{"ordinal":7,"readingKey":"s1|2|structure-v3","extractor":"vision:m","text":"SCENARIO A\nSCENARIO A: 14 schools close","entries":[]}]"""
                ex.requestURI.path.endsWith("/vision/plan") -> 200 to """[{"page":1,"reasons":[]},{"page":2,"reasons":["designed layout (40 blocks)"]},{"page":3,"reasons":["table"]}]"""
                body.contains("\"page\":3") -> 502 to """{"page":3,"outcome":"failed","failure":"vision server: IOException","ms":4}"""
                else -> 200 to """{"page":2,"outcome":"added","accepted":18,"rejected":1,"ms":101000,"model":"Qwen3.8-27B","rejectedValues":["SCENARIO B :: 13 — not on the page"]}"""
            }
            val b = out.toByteArray(); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
    }

    @AfterEach
    fun down() { server.stop(0); dir.deleteRecursively() }

    private fun run(vararg args: String, token: String = "t"): Triple<Int, String, String> {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        return Triple(VisionReadCli.run(args.toList(), PrintStream(o), PrintStream(e), token), o.toString(), e.toString())
    }

    private fun file() = File(dir, "doc.pdf").apply { writeBytes(byteArrayOf(1)) }.path
    private fun url() = "http://127.0.0.1:${server.address.port}"

    @Test
    fun `auto reads every routed page, reports what was kept and rejected, and exits 3 when a page failed`() {
        val (code, out, _) = run("--user", "v1", "--source", "s1", "--file", file(), "--url", url())
        assertEquals(3, code, out)
        assertTrue(out.contains("3 page(s); 2 routed to vision"), out)
        assertTrue(out.contains("p.2: added, kept 18, rejected 1") && out.contains("rejected: SCENARIO B :: 13"), out)
        assertTrue(out.contains("p.3: failed") && out.contains("IOException"), out)
        assertEquals(3, posted.size, "plan, then pages 2 and 3; page 1 stays with the text reader")
        assertTrue(posted.all { it.contains("\"syntheticUserId\":\"v1\"") && it.contains("doc.pdf") })
    }

    @Test
    fun `pages failing in a row stop the run instead of spending minutes on each`() {
        val (code, out, err) = run("--user", "v1", "--source", "s1", "--file", file(), "--url", url(), "--pages", "3,3,3,2", "--stop-after", "2")
        assertEquals(3, code, out)
        assertEquals(3, posted.size, "plan, then two failed pages; the rest are not tried")
        assertTrue(err.contains("stopped: 2 pages in a row failed; 2 page(s) not tried"), err)
        assertTrue(out.contains("done: 2 of 4 page(s)"), out)
    }

    @Test
    fun `show prints a stored vision chunk and needs no file`() {
        val (code, out, _) = run("--user", "v1", "--source", "s1", "--show", "2", "--url", url())
        assertEquals(0, code, out)
        assertTrue(out.contains("p.2: chunk 7 (s1|2|structure-v3)") && out.contains("SCENARIO A: 14 schools close"), out)
        assertTrue(posted.single().startsWith("/debug/sources/s1/vision/chunks") && posted.single().contains("\"page\":2"))
    }

    @Test
    fun `plan only prints the routing, and a page list reads just those pages`() {
        assertEquals(0, run("--user", "v1", "--source", "s1", "--file", file(), "--pages", "plan", "--url", url()).first)
        assertEquals(1, posted.size)
        posted.clear()
        assertEquals(0, run("--user", "v1", "--source", "s1", "--file", file(), "--pages", "2", "--no-ocr", "--url", url()).first)
        assertTrue(posted.last().contains("\"page\":2") && posted.last().contains("\"ocr\":false"))
    }

    @Test
    fun `usage, owner-jacob, a missing token and a non-loopback url are refused before anything is sent`() {
        assertEquals(64, run().first)
        assertEquals(64, run("--user", "owner-jacob", "--source", "s", "--file", file(), "--url", url()).first)
        assertEquals(64, run("--user", "v", "--source", "s", "--file", file(), "--url", url(), token = "").first)
        assertEquals(64, run("--user", "v", "--source", "s", "--file", file(), "--url", "http://example.com").first)
        assertEquals(64, run("--user", "v", "--source", "s", "--file", "/nope.pdf", "--url", url()).first)
        assertTrue(posted.isEmpty())
    }
}
