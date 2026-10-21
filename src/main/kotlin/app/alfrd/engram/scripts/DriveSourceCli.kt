package app.alfrd.engram.scripts

import app.alfrd.engram.drive.DriveApiException
import app.alfrd.engram.drive.DriveClient
import app.alfrd.engram.drive.DriveConfigException
import app.alfrd.engram.drive.DriveHttp
import app.alfrd.engram.drive.GoogleAccessTokens
import app.alfrd.engram.drive.GoogleCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

/**
 * The Google Drive shelf from the command line.
 *
 * ```
 * DriveSourceCli --check
 *     Lists the PDFs in the configured folder (GOOGLE_DRIVE_FOLDER_ID) with the service-account key (GOOGLE_DRIVE_CREDENTIALS_FILE, or the default file): name, id, size, version,
 *     md5, link. Reads nothing but the listing; no download, no graph. The first thing to run on the box.
 * DriveSourceCli --user <label> [--url http://127.0.0.1:8080]
 *     Asks the running engine to fetch the folder and commit each PDF as a Source under the synthetic identity debug+<label>@... (POST /debug/sources/drive;
 *     needs DEBUG_CONVERSE_TOKEN; loopback URLs only; never owner-jacob). The engine reads Drive, on the box.
 * ```
 * The key file is a path; its contents are never printed. Exit status: 0 done, 2 Drive or the engine refused or could not be
 * reached, 64 usage or configuration.
 */
object DriveSourceCli {
    private val json = Json { prettyPrint = true }

    /** The token endpoint is the one named in the service-account key file; the guard in [DriveHttp] decides which hosts can be reached. */
    class Endpoints(val apiBase: String, val http: OkHttpClient) {
        companion object { fun production() = Endpoints("https://www.googleapis.com", DriveHttp.production()) }
    }

    fun run(
        args: List<String>, out: PrintStream = System.out, err: PrintStream = System.err,
        env: Map<String, String> = System.getenv(), endpoints: () -> Endpoints = Endpoints::production,
    ): Int {
        fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        if ("--check" in args) {
            val creds = GoogleCredentials.credentialsPath(env)
            val folder = env["GOOGLE_DRIVE_FOLDER_ID"]?.takeIf { it.isNotBlank() }
            if (creds == null || folder == null) {
                err.println("set GOOGLE_DRIVE_FOLDER_ID, and put the service-account key at ~/${GoogleCredentials.DEFAULT_RELATIVE_PATH} or set GOOGLE_DRIVE_CREDENTIALS_FILE")
                return 64
            }
            return try {
                val e = endpoints()
                val drive = DriveClient(GoogleAccessTokens(GoogleCredentials.load(creds), e.http), e.http, e.apiBase)
                val f = drive.folder(folder)
                out.println("folder ${f.id} \"${f.name}\" shared drive ${f.driveId ?: "(none: My Drive)"}")
                val children = drive.children(folder)
                children.forEach {
                    out.println(
                        (if (it.mimeType == "application/pdf") "  PDF     " else "  skipped ") +
                            "\"${it.name}\" id=${it.id} mime=${it.mimeType} size=${it.size ?: "-"} version=${it.version ?: "-"} md5=${it.md5 ?: "-"} modified=${it.modifiedTime ?: "-"} link=${it.webViewLink ?: "-"}",
                    )
                }
                out.println("${children.count { it.mimeType == "application/pdf" }} PDF(s) in the folder")
                0
            } catch (e: DriveConfigException) { err.println("configuration: ${e.message}"); 64 }
            catch (e: DriveApiException) { err.println("drive: ${e.message}"); 2 }
            catch (e: IOException) { err.println("drive could not be reached (${e.javaClass.simpleName})"); 2 }
        }
        val label = opt("--user")
        if (label.isNullOrBlank()) { err.println("usage: DriveSourceCli --check\n       DriveSourceCli --user <label> [--url URL]"); return 64 }
        if (label.contains("owner-jacob")) { err.println("refused: that identity is real data and is never used by the debug routes"); return 64 }
        val token = env["DEBUG_CONVERSE_TOKEN"].orEmpty()
        if (token.isBlank()) { err.println("DEBUG_CONVERSE_TOKEN is not set"); return 64 }
        val url = URI.create((opt("--url") ?: "http://127.0.0.1:8080").trimEnd('/') + "/debug/sources/drive")
        if (url.host !in setOf("127.0.0.1", "localhost", "::1", "[::1]")) { err.println("refused: the debug route is loopback only"); return 64 }
        val response = try {
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(url).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonObject(mapOf("syntheticUserId" to JsonPrimitive(label))).toString())).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: IOException) { err.println("could not reach the engine at ${url.host}:${url.port}"); return 2 }
        if (response.statusCode() != 200) { err.println("engine answered ${response.statusCode()}: ${response.body().take(300)}"); return 2 }
        out.println(runCatching { json.encodeToString(JsonObject.serializer(), json.parseToJsonElement(response.body()) as JsonObject) }.getOrDefault(response.body()))
        return 0
    }
}

fun main(args: Array<String>) {
    exitProcess(DriveSourceCli.run(args.toList()))
}
