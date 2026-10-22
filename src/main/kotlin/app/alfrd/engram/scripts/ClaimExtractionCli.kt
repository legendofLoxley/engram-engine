package app.alfrd.engram.scripts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

/**
 * Runs the baseline claim extraction over a stored Source and reports what it did.
 *
 * ```
 * ClaimExtractionCli --user <label> (--source <uid> | --locator <locator>) [--run-id R] [--expect-model S | --any-model]
 *                    [--aliases aliases.json] [--prompts prompts.json] [--reader text|structure] [--url http://127.0.0.1:8080] [--poll-seconds 15] [--no-wait]
 * ClaimExtractionCli --user <label> --source <uid> --show claims|disagreements [--run-id R] [--url ...]
 * ```
 * Goes through the running engine's debug routes (the embedded graph has one owner at a time), under the synthetic identity
 * debug+<label>@..., needs DEBUG_CONVERSE_TOKEN, loopback only, never owner-jacob. The engine runs the passes against its own local model; this
 * tool cannot name a model server. The default expected model is Qwen3.6-35B-A3B; `--any-model` runs without that check and the run says so.
 * `--reader structure` makes claims straight from the source's vision chunks (see VisionReadCli) instead of running the text passes.
 * `--prompts` is a JSON object `{"pass1": "...", "pass2": "..."}` with the two system prompts to try instead of the built-in ones; the run's record names
 * them `custom-<hash>` (the text itself is never stored). A run id that finished prints its stored report and starts nothing; one that stopped continues. `--aliases` is a JSON object mapping a
 * canonical claimant to the other names it goes by. Exit status: 0 the run completed with no failed chunk, 3 completed with failed chunks,
 * 2 stopped, unreachable or refused, 64 usage.
 */
object ClaimExtractionCli {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()

    fun run(args: List<String>, out: java.io.PrintStream = System.out, err: java.io.PrintStream = System.err, sleep: (Long) -> Unit = { Thread.sleep(it) }, token: String = System.getenv("DEBUG_CONVERSE_TOKEN").orEmpty()): Int {
        fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val label = opt("--user")
        val uidArg = opt("--source"); val locator = opt("--locator")
        if (label.isNullOrBlank() || (uidArg == null && locator == null)) {
            err.println("usage: ClaimExtractionCli --user <label> (--source <uid> | --locator <locator>) [--run-id R] [--expect-model S | --any-model] [--aliases file.json] [--url URL] [--poll-seconds N] [--no-wait]\n       ClaimExtractionCli --user <label> --source <uid> --show claims|disagreements [--run-id R]")
            return 64
        }
        if (label.contains("owner-jacob")) { err.println("refused: that identity is real data and is never used by the debug routes"); return 64 }
        if (token.isBlank()) { err.println("DEBUG_CONVERSE_TOKEN is not set"); return 64 }
        val base = URI.create((opt("--url") ?: "http://127.0.0.1:8080").trimEnd('/'))
        if (base.host !in setOf("127.0.0.1", "localhost", "::1", "[::1]")) { err.println("refused: the debug routes are loopback only"); return 64 }
        val q = "syntheticUserId=" + URLEncoder.encode(label, Charsets.UTF_8)

        fun call(method: String, path: String, body: String? = null): Pair<Int, String>? = try {
            val b = HttpRequest.newBuilder(URI.create(base.toString() + path)).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
            val r = if (body == null) b.GET().build() else b.POST(HttpRequest.BodyPublishers.ofString(body)).build()
            http.send(r, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
        } catch (e: java.io.IOException) { err.println("could not reach the engine at ${base.host}:${base.port}"); null }
        fun pretty(s: String) = runCatching { json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(s)) }.getOrDefault(s)

        val sourceUid = uidArg ?: run {
            val (code, body) = call("GET", "/debug/sources?$q") ?: return 2
            if (code != 200) { err.println("engine answered $code: ${body.take(300)}"); return 2 }
            val match = json.parseToJsonElement(body).jsonArray.map { it.jsonObject }.lastOrNull { it["locator"]?.jsonPrimitive?.content == locator }
            if (match == null) { err.println("no source with locator $locator for this identity"); return 2 }
            match["uid"]!!.jsonPrimitive.content.also { out.println("source $it (content hash ${match["contentHash"]?.jsonPrimitive?.content?.take(12)})") }
        }

        opt("--show")?.let { what ->
            val path = when (what) {
                "claims" -> "/debug/sources/$sourceUid/claims?$q"
                "disagreements" -> "/debug/sources/$sourceUid/claim-disagreements?$q" + (opt("--run-id")?.let { "&runId=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
                else -> { err.println("--show takes claims or disagreements"); return 64 }
            }
            val (code, body) = call("GET", path) ?: return 2
            if (code != 200) { err.println("engine answered $code: ${body.take(300)}"); return 2 }
            out.println(pretty(body)); return 0
        }

        val aliases: JsonObject = opt("--aliases")?.let { f ->
            val file = File(f)
            if (!file.isFile) { err.println("--aliases: no such file"); return 64 }
            try { json.parseToJsonElement(file.readText()).jsonObject } catch (e: Exception) { err.println("--aliases: not a JSON object of name lists"); return 64 }
        } ?: JsonObject(emptyMap())
        val prompts: JsonObject? = opt("--prompts")?.let { f ->
            val file = File(f)
            if (!file.isFile) { err.println("--prompts: no such file"); return 64 }
            try {
                val o = json.parseToJsonElement(file.readText()).jsonObject
                JsonObject(mapOf("pass1" to JsonPrimitive(o["pass1"]!!.jsonPrimitive.content), "pass2" to JsonPrimitive(o["pass2"]!!.jsonPrimitive.content)))
            } catch (e: Exception) { err.println("--prompts: expected a JSON object with string fields pass1 and pass2"); return 64 }
        }
        val request = JsonObject(buildMap {
            put("syntheticUserId", JsonPrimitive(label))
            opt("--run-id")?.let { put("runId", JsonPrimitive(it)) }
            opt("--expect-model")?.let { put("expectModel", JsonPrimitive(it)) }
            if ("--any-model" in args) put("anyModel", JsonPrimitive(true))
            opt("--reader")?.let { put("reader", JsonPrimitive(it)) }
            put("aliases", aliases)
            prompts?.let { put("prompts", it) }
        })
        val (code, body) = call("POST", "/debug/sources/$sourceUid/claims/extract", request.toString()) ?: return 2
        if (code != 200 && code != 202) { err.println("engine answered $code: ${body.take(300)}"); return 2 }
        val started = json.parseToJsonElement(body).jsonObject
        val runId = started["runId"]!!.jsonPrimitive.content
        out.println("run $runId: ${started["state"]!!.jsonPrimitive.content}")
        if ("--no-wait" in args && code == 202) return 0

        var last = ""
        val pollMs = (opt("--poll-seconds")?.toLongOrNull() ?: 15L) * 1000
        while (true) {
            val (c, b) = call("GET", "/debug/claim-runs/$runId?$q") ?: return 2
            if (c == 404) { sleep(pollMs); continue } // the engine has not written the run's record yet
            if (c != 200) { err.println("engine answered $c: ${b.take(300)}"); return 2 }
            val run = json.parseToJsonElement(b).jsonObject
            val status = run["status"]!!.jsonPrimitive.content
            val progress = "chunks ${run["chunksDone"]?.jsonPrimitive?.content}/${run["chunksTotal"]?.jsonPrimitive?.content}"
            if (progress != last) { out.println("$status: $progress model=${run["modelName"]?.jsonPrimitive?.content.orEmpty().ifBlank { "-" }}"); last = progress }
            if (status == "complete" || status == "stopped") {
                val report = run["report"]?.jsonPrimitive?.content?.let { json.parseToJsonElement(it).jsonObject }
                out.println(report?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "no report was stored")
                if (status == "stopped") { err.println("the run stopped: ${report?.get("failures")?.let { (it as? JsonArray)?.joinToString("; ") { f -> f.jsonPrimitive.content } }}"); return 2 }
                return if ((report?.get("chunksFailed")?.jsonPrimitive?.content?.toIntOrNull() ?: 0) > 0) 3 else 0
            }
            if (run["inProgress"]?.jsonPrimitive?.content == "false" && "--no-wait" !in args && last.isNotEmpty() && status == "running" && stalled(run)) {
                err.println("the run is not in progress in the engine (it stopped without finishing); start it again with the same --run-id to continue"); return 2
            }
            sleep(pollMs)
        }
    }

    /** A run the engine is not working on: its record says running but no job holds it. Allowed one poll of grace for the job to start. */
    private var graceFor: String? = null
    private fun stalled(run: JsonObject): Boolean {
        val id = run["runId"]!!.jsonPrimitive.content
        if (graceFor != id) { graceFor = id; return false }
        return true
    }
}

fun main(args: Array<String>) {
    exitProcess(ClaimExtractionCli.run(args.toList()))
}
