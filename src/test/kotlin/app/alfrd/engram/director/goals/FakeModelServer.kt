package app.alfrd.engram.director.goals

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A deterministic stand-in for a llama.cpp server: `POST /v1/chat/completions` answers with a forced tool call whose arguments a test scripted, and `GET /slots`
 * reports slots a test set. The real [app.alfrd.engram.cognitive.providers.local.LocalLlmClient] talks to it, so the HTTP path, the forced-tool request and the
 * cancellation of a call are the production ones. Which tool is being asked for is read from the request's `tool_choice`, so one server can play the planner, the
 * stepper and QC.
 */
class FakeModelServer {
    /** What to answer one call with. [arguments] is the JSON the "model" puts in the tool call (or any text, to play a model that returns garbage). */
    class Reply(val arguments: String, val completionTokens: Int = 100, val delayMillis: Long = 0, val noToolCall: Boolean = false, val emptyLength: Boolean = false)

    class Seen(val tool: String, val body: String)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val handlers = HashMap<String, (Int) -> Reply>()
    private val counts = HashMap<String, AtomicInteger>()
    val seen = CopyOnWriteArrayList<Seen>()
    @Volatile var slotTotal = 2
    @Volatile var slotsBusy = 0
    @Volatile var onCall: (String, Int) -> Unit = { _, _ -> }
    @Volatile var abandoned = AtomicInteger(0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/v1/chat/completions") { ex -> chat(ex) }
        server.createContext("/slots") { ex ->
            val body = (0 until slotTotal).joinToString(",", "[", "]") { """{"id":$it,"is_processing":${it < slotsBusy}}""" }
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.length.toLong()); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    fun on(tool: String, reply: (Int) -> Reply) { handlers[tool] = reply }
    fun calls(tool: String): Int = counts[tool]?.get() ?: 0
    fun requests(tool: String): List<String> = seen.filter { it.tool == tool }.map { it.body }
    fun stop() = server.stop(0)

    private fun chat(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val tool = Regex(""""tool_choice"\s*:\s*\{[^}]*"name"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: Regex(""""tool_choice"\s*:\s*"required"""").find(body)?.let { Regex(""""tools"\s*:\s*\[\s*\{[^]]*?"name"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) }
            ?: "unknown"
        seen += Seen(tool, body)
        val n = counts.getOrPut(tool) { AtomicInteger() }.incrementAndGet()
        onCall(tool, n)
        val reply = handlers[tool]?.invoke(n) ?: Reply("{}")
        try {
            if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
            val message = if (reply.emptyLength) """{"role":"assistant","content":""}"""
            else if (reply.noToolCall) """{"role":"assistant","content":"I would rather chat than call the tool."}"""
            else """{"role":"assistant","content":"","tool_calls":[{"id":"c$n","type":"function","function":{"name":"$tool","arguments":${quote(reply.arguments)}}}]}"""
            val out = """{"id":"x","model":"fake-model","choices":[{"index":0,"finish_reason":"${if (reply.emptyLength) "length" else if (reply.noToolCall) "stop" else "tool_calls"}","message":$message}],"usage":{"prompt_tokens":50,"completion_tokens":${reply.completionTokens}}}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            val bytes = out.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } catch (e: Exception) {
            // The client went away (a cancelled call closes the connection): that is what a yielding runner does to llama.cpp.
            abandoned.incrementAndGet()
        }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
