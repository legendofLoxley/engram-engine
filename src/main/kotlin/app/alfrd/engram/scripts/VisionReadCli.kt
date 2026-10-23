package app.alfrd.engram.scripts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Reads the pages of a stored PDF Source that need vision, through the running engine, one page per request.
 *
 * ```
 * VisionReadCli --user <label> --source <uid> --file <the source's pdf on this machine> [--pages auto|plan|2,146] [--no-ocr] [--stop-after 3]
 *               [--url http://127.0.0.1:8080]
 * ```
 * `--pages auto` (default) reads every page the engine routes to vision; `plan` only prints the routing; a list reads those pages; `--show N`
 * prints the stored vision chunk of page N (its text and entries) and reads nothing. The file is
 * passed by path, so it must be on the engine's machine and readable by the engine's process (not a private /tmp); the engine checks it is the source's own file (SHA-256). A page already read is
 * answered from its chunk without a model call, so a stopped run is resumed by running the same command. After `--stop-after` pages in a row
 * fail (default 3: a server that is down fails every page), the run stops rather than spend minutes per page on nothing. Needs DEBUG_CONVERSE_TOKEN; loopback
 * only; synthetic identities only, never owner-jacob. Exit status: 0 every page read or already there, 3 some pages failed, 2 refused or
 * unreachable, 64 usage.
 */
object VisionReadCli {
    private val json = Json { ignoreUnknownKeys = true }

    fun run(
        args: List<String>, out: java.io.PrintStream = System.out, err: java.io.PrintStream = System.err,
        token: String = System.getenv("DEBUG_CONVERSE_TOKEN").orEmpty(),
    ): Int {
        fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val label = opt("--user"); val source = opt("--source"); val file = opt("--file")?.let(::File)
        if (label.isNullOrBlank() || source.isNullOrBlank() || (file == null && opt("--show") == null)) {
            err.println("usage: VisionReadCli --user <label> --source <uid> --file <file.pdf> [--pages auto|plan|N,N] [--show N] [--no-ocr] [--stop-after N] [--url URL]"); return 64
        }
        if (label.contains("owner-jacob")) { err.println("refused: that identity is real data and is never used by the debug routes"); return 64 }
        if (file != null && !file.isFile) { err.println("--file: no such file"); return 64 }
        if (token.isBlank()) { err.println("DEBUG_CONVERSE_TOKEN is not set"); return 64 }
        val base = URI.create((opt("--url") ?: "http://127.0.0.1:8080").trimEnd('/'))
        if (base.host !in setOf("127.0.0.1", "localhost", "::1", "[::1]")) { err.println("refused: the debug routes are loopback only"); return 64 }
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val ocr = "--no-ocr" !in args
        val stopAfter = (opt("--stop-after") ?: "3").toIntOrNull()?.takeIf { it > 0 } ?: run { err.println("--stop-after: not a positive number"); return 64 }

        fun post(path: String, page: Int?): Pair<Int, String>? = try {
            val body = JsonObject(buildMap {
                put("syntheticUserId", JsonPrimitive(label)); put("path", JsonPrimitive(file!!.absolutePath)); put("ocr", JsonPrimitive(ocr))
                page?.let { put("page", JsonPrimitive(it)) }
            })
            http.send(
                HttpRequest.newBuilder(URI.create("$base/debug/sources/$source/vision/$path")).timeout(Duration.ofMinutes(20))
                    .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).let { it.statusCode() to it.body() }
        } catch (e: java.io.IOException) { err.println("could not reach the engine at ${base.host}:${base.port}"); null }

        opt("--show")?.let { shown ->
            val page = shown.toIntOrNull() ?: run { err.println("--show: not a page number"); return 64 }
            val r = try {
                http.send(
                    HttpRequest.newBuilder(URI.create("$base/debug/sources/$source/vision/chunks")).timeout(Duration.ofMinutes(1))
                        .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JsonObject(mapOf("syntheticUserId" to JsonPrimitive(label), "page" to JsonPrimitive(page))).toString())).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (e: java.io.IOException) { err.println("could not reach the engine at ${base.host}:${base.port}"); return 2 }
            if (r.statusCode() != 200) { err.println("engine answered ${r.statusCode()}: ${r.body().take(300)}"); return 2 }
            val chunks = json.parseToJsonElement(r.body()).jsonArray
            if (chunks.isEmpty()) out.println("p.$page: no vision chunk")
            chunks.forEach { c ->
                val o = c.jsonObject
                out.println("p.$page: chunk ${o["ordinal"]!!.jsonPrimitive.content} (${o["readingKey"]?.jsonPrimitive?.contentOrNull}), ${o["extractor"]?.jsonPrimitive?.contentOrNull}")
                out.println(o["text"]!!.jsonPrimitive.content)
            }
            return 0
        }
        val (code, body) = post("plan", null) ?: return 2
        if (code != 200) { err.println("engine answered $code: ${body.take(300)}"); return 2 }
        val plan = json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        val routed = plan.filter { (it["reasons"] as JsonArray).isNotEmpty() }
        out.println("${plan.size} page(s); ${routed.size} routed to vision")
        routed.forEach { r -> out.println("  p.${r["page"]!!.jsonPrimitive.content}: ${(r["reasons"] as JsonArray).joinToString(", ") { it.jsonPrimitive.content }}") }
        val pagesArg = opt("--pages") ?: "auto"
        if (pagesArg == "plan") return 0
        val pages = if (pagesArg == "auto") routed.map { it["page"]!!.jsonPrimitive.content.toInt() } else {
            pagesArg.split(',').map { it.trim().toIntOrNull() ?: run { err.println("--pages: not a page list"); return 64 } }
        }
        var failed = 0; var kept = 0; var rejected = 0; var inARow = 0; var done = 0
        for (page in pages) {
            if (inARow >= stopAfter) {
                err.println("stopped: $inARow pages in a row failed; ${pages.size - done} page(s) not tried. Fix the cause and run the same command again to resume.")
                break
            }
            done++
            val (c, b) = post("page", page) ?: return 2
            val r = runCatching { json.parseToJsonElement(b).jsonObject }.getOrNull()
            if (r == null || (c != 200 && c != 502)) { err.println("p.$page: engine answered $c: ${b.take(300)}"); failed++; inARow++; continue }
            val outcome = r["outcome"]?.jsonPrimitive?.content
            val acc = r["accepted"]?.jsonPrimitive?.intOrNull ?: 0; val rej = r["rejected"]?.jsonPrimitive?.intOrNull ?: 0
            kept += acc; rejected += rej
            if (outcome == "failed") { failed++; inARow++ } else inARow = 0
            out.println("p.$page: $outcome, kept $acc, rejected $rej, ${r["ms"]?.jsonPrimitive?.content} ms, model ${r["model"]?.jsonPrimitive?.contentOrNull ?: "-"}" +
                (r["failure"]?.jsonPrimitive?.contentOrNull?.let { " — $it" } ?: ""))
            (r["rejectedValues"] as? JsonArray)?.forEach { out.println("    rejected: ${it.jsonPrimitive.content}") }
        }
        out.println("done: $done of ${pages.size} page(s), $kept entr(ies) kept, $rejected rejected, $failed failed")
        return if (failed > 0) 3 else 0
    }
}

fun main(args: Array<String>) {
    exitProcess(VisionReadCli.run(args.toList()))
}
