package app.alfrd.engram.drive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

const val FAKE_CLIENT_ID = "client-id-123.apps.example"
const val FAKE_CLIENT_SECRET = "SECRET-CLIENT-9f8e7d"
const val FAKE_REFRESH = "REFRESH-TOKEN-xyz-1a2b3c"

/** A stand-in for the two Google endpoints the connector uses: the token endpoint and Drive v3 files (list, metadata, media). */
class FakeGoogle : Dispatcher() {
    class Entry(
        val id: String, var name: String, var mime: String, var bytes: ByteArray?, var version: Long = 1,
        var trashed: Boolean = false, val parents: MutableList<String> = mutableListOf(), var driveId: String? = "0AShared",
    )

    val files = LinkedHashMap<String, Entry>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    var tokenScope = "https://www.googleapis.com/auth/drive.readonly"
    var tokenCalls = 0
    var expiresIn = 3600
    /** Statuses to answer with, once each, before a files request succeeds. */
    val failures = ArrayDeque<Int>()
    /** Files whose media is served with a flipped byte, so its md5 no longer matches. */
    val corrupt = mutableSetOf<String>()
    var currentToken = ""

    fun folder(id: String, name: String = "Evidence") = Entry(id, name, "application/vnd.google-apps.folder", null).also { files[id] = it }
    fun pdf(id: String, name: String, bytes: ByteArray, parent: String, version: Long = 1) =
        Entry(id, name, "application/pdf", bytes, version, parents = mutableListOf(parent)).also { files[id] = it }

    private fun meta(e: Entry): JsonObject = JsonObject(
        buildMap {
            put("id", JsonPrimitive(e.id)); put("name", JsonPrimitive(e.name)); put("mimeType", JsonPrimitive(e.mime))
            put("trashed", JsonPrimitive(e.trashed)); put("version", JsonPrimitive(e.version.toString()))
            put("modifiedTime", JsonPrimitive("2026-10-11T16:19:04.195Z"))
            put("webViewLink", JsonPrimitive("https://drive.google.com/file/d/${e.id}/view?usp=drivesdk"))
            put("driveId", e.driveId?.let { JsonPrimitive(it) } ?: JsonNull)
            e.bytes?.let { put("size", JsonPrimitive(it.size.toString())); put("md5Checksum", JsonPrimitive(md5(it))); put("headRevisionId", JsonPrimitive("rev-${e.version}")) }
        },
    )

    fun mediaRequests() = requests.filter { it.requestUrl?.queryParameter("alt") == "media" }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val url = request.requestUrl!!
        if (url.encodedPath == "/token") {
            if (request.method != "POST") return MockResponse().setResponseCode(405)
            val form = request.body.clone().readUtf8().split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            if (form["grant_type"] != "refresh_token" || form["refresh_token"] != FAKE_REFRESH || form["client_id"] != FAKE_CLIENT_ID || form["client_secret"] != FAKE_CLIENT_SECRET)
                return MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"echo $FAKE_REFRESH"}""")
            tokenCalls++; currentToken = "ACCESS-$tokenCalls"
            return MockResponse().setBody("""{"access_token":"$currentToken","expires_in":$expiresIn,"scope":"$tokenScope","token_type":"Bearer"}""")
        }
        if (request.getHeader("Authorization") != "Bearer $currentToken" || currentToken.isEmpty()) return MockResponse().setResponseCode(401).setBody("{}")
        if (request.method != "GET") return MockResponse().setResponseCode(405)
        failures.removeFirstOrNull()?.let { return MockResponse().setResponseCode(it).setHeader("Retry-After", "0").setBody("{}") }
        if (url.encodedPath == "/drive/v3/files") {
            val q = url.queryParameter("q").orEmpty()
            val parent = Regex("'([^']+)' in parents").find(q)?.groupValues?.get(1)
            val hideTrashed = q.contains("trashed = false") || q.contains("trashed=false")
            val matching = files.values.filter { it.bytes != null || it.mime.endsWith("folder") }.filter { parent == null || parent in it.parents }.filter { !(hideTrashed && it.trashed) }
            val size = url.queryParameter("pageSize")?.toInt() ?: 100
            val start = url.queryParameter("pageToken")?.toInt() ?: 0
            val page = matching.drop(start).take(size)
            val next = if (start + size < matching.size) "${start + size}" else null
            return MockResponse().setBody(JsonObject(buildMap { put("files", JsonArray(page.map(::meta))); next?.let { put("nextPageToken", JsonPrimitive(it)) } }).toString())
        }
        val id = url.encodedPathSegments.last()
        val e = files[id] ?: return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404}}""")
        if (url.queryParameter("alt") == "media") {
            val b = e.bytes ?: return MockResponse().setResponseCode(403)
            val out = if (id in corrupt) b.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() } else b
            return MockResponse().setBody(Buffer().write(out))
        }
        return MockResponse().setBody(meta(e).toString())
    }
}
