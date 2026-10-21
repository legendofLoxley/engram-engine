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

const val JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer"

/** A stand-in for the two Google endpoints the connector uses: the token endpoint and Drive v3 files (list, metadata, media). */
class FakeGoogle : Dispatcher() {
    class Entry(
        val id: String, var name: String, var mime: String, var bytes: ByteArray?, var version: Long = 1,
        var trashed: Boolean = false, val parents: MutableList<String> = mutableListOf(), var driveId: String? = "0AShared",
    )

    val files = LinkedHashMap<String, Entry>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    /** What the token response reports as its scope; a real service-account response reports none, so the default is none. */
    var tokenScope: String? = null
    /** The claims of every assertion that was verified, in order. */
    val claims = CopyOnWriteArrayList<JsonObject>()
    val headers = CopyOnWriteArrayList<JsonObject>()
    var publicKey: java.security.PublicKey = TestKeys.pair.public
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
            fun bad(code: String) = MockResponse().setResponseCode(400).setBody("""{"error":"$code","error_description":"echo ${form["assertion"]?.take(30)}"}""")
            if (form["grant_type"] != JWT_BEARER) return bad("unsupported_grant_type")
            val parts = form["assertion"]?.split('.') ?: return bad("invalid_request")
            if (parts.size != 3) return bad("invalid_grant")
            val signature = runCatching {
                java.security.Signature.getInstance("SHA256withRSA").run {
                    initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray()); verify(java.util.Base64.getUrlDecoder().decode(parts[2]))
                }
            }.getOrDefault(false)
            if (!signature) return bad("invalid_grant")
            val header = kotlinx.serialization.json.Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(parts[0]))) as JsonObject
            val claim = kotlinx.serialization.json.Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(parts[1]))) as JsonObject
            if ((claim["iss"] as? JsonPrimitive)?.content != FAKE_SA_EMAIL) return bad("invalid_grant")
            if ((claim["aud"] as? JsonPrimitive)?.content != request.requestUrl.toString()) return bad("invalid_grant")
            if ((claim["scope"] as? JsonPrimitive)?.content != "https://www.googleapis.com/auth/drive.readonly") return bad("invalid_scope")
            headers += header; claims += claim
            tokenCalls++; currentToken = "ACCESS-$tokenCalls"
            val scope = tokenScope?.let { ""","scope":"$it"""" }.orEmpty()
            return MockResponse().setBody("""{"access_token":"$currentToken","expires_in":$expiresIn$scope,"token_type":"Bearer"}""")
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
