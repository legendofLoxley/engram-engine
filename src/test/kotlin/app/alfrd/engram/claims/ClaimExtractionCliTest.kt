package app.alfrd.engram.claims

import app.alfrd.engram.scripts.ClaimExtractionCli
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress

/** The CLI against a fake engine on loopback. */
class ClaimExtractionCliTest {
    private lateinit var server: HttpServer
    private val seen = ArrayList<String>()
    private var polls = 0
    private var finalStatus = "complete"
    private var failedChunks = 0

    @BeforeEach
    fun up() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) { val b = body.toByteArray(); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.createContext("/debug/sources") { ex ->
            seen += "${ex.requestMethod} ${ex.requestURI}" + (if (ex.requestMethod == "POST") " " + ex.requestBody.readBytes().decodeToString() else "")
            when {
                ex.requestURI.path == "/debug/sources" -> reply(ex, 200, """[{"uid":"u-old","locator":"gdrive:abc","contentHash":"1111111111111111"},{"uid":"u-new","locator":"gdrive:abc","contentHash":"2222222222222222"}]""")
                ex.requestURI.path.endsWith("/claims/extract") -> reply(ex, 202, """{"runId":"cli-run","sourceUid":"u-new","state":"started"}""")
                else -> reply(ex, 200, """[{"quote":"q"}]""")
            }
        }
        server.createContext("/debug/claim-runs") { ex ->
            seen += "${ex.requestMethod} ${ex.requestURI}"
            polls++
            val done = polls >= 3
            val status = if (done) finalStatus else "running"
            val report = if (done) ""","report":"{\"runId\":\"cli-run\",\"chunksFailed\":$failedChunks,\"claimsEstablished\":4,\"failures\":[\"model refused\"]}"""" else ""
            reply(ex, 200, """{"runId":"cli-run","status":"$status","modelName":"Qwen3.6-35B-A3B","chunksDone":"${if (done) 6 else polls}","chunksTotal":"6","inProgress":"${!done}"$report}""")
        }
        server.start()
    }

    @AfterEach
    fun down() { server.stop(0) }

    private fun url() = "http://127.0.0.1:${server.address.port}"
    private fun run(vararg args: String, token: String = "test-token"): Triple<Int, String, String> {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val code = ClaimExtractionCli.run(args.toList(), PrintStream(o), PrintStream(e), { }, token)
        return Triple(code, o.toString(), e.toString())
    }

    @Test
    fun `usage mistakes, owner-jacob and a non-loopback url are refused before anything is sent`() {
        assertEquals(64, run().first)
        assertEquals(64, run("--user", "x").first)
        assertEquals(64, run("--user", "owner-jacob", "--source", "u", "--url", url()).first)
        assertEquals(64, run("--user", "x", "--source", "u", "--url", "http://example.com").first)
        assertEquals(64, run("--user", "x", "--source", "u", "--url", url(), token = "").first, "no token")
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a locator picks the latest source with it, starts the run, follows it to the end and prints the report`() {
        val (code, out, _) = run("--user", "cli-1", "--locator", "gdrive:abc", "--run-id", "cli-run", "--url", url())
        assertEquals(0, code)
        assertTrue(out.contains("source u-new"), out)
        assertTrue(out.contains("complete: chunks 6/6"), out)
        assertTrue(out.contains("\"claimsEstablished\": 4"), out)
        val post = seen.single { it.startsWith("POST") }
        assertTrue(post.contains("/debug/sources/u-new/claims/extract") && post.contains("\"runId\":\"cli-run\"") && post.contains("\"syntheticUserId\":\"cli-1\""), post)
        assertTrue(!post.contains("anyModel"), "the model check stays on by default")
    }

    @Test
    fun `any-model and expect-model are passed on, and a stopped run exits 2 while failed chunks exit 3`() {
        finalStatus = "stopped"
        val (code, _, err) = run("--user", "cli-1", "--source", "u-new", "--any-model", "--url", url())
        assertTrue(seen.any { it.contains("\"anyModel\":true") })
        assertEquals(2, code); assertTrue(err.contains("model refused"), err)
        finalStatus = "complete"; failedChunks = 2; polls = 0
        assertEquals(3, run("--user", "cli-1", "--source", "u-new", "--expect-model", "Qwen3.6", "--url", url()).first)
        assertTrue(seen.any { it.contains("\"expectModel\":\"Qwen3.6\"") })
    }

    @Test
    fun `show prints claims or disagreements without starting a run`() {
        val (code, out, _) = run("--user", "cli-1", "--source", "u-new", "--show", "claims", "--url", url())
        assertEquals(0, code); assertTrue(out.contains("\"quote\""), out)
        assertTrue(seen.none { it.startsWith("POST") })
        assertEquals(64, run("--user", "cli-1", "--source", "u-new", "--show", "bananas", "--url", url()).first)
    }
}
