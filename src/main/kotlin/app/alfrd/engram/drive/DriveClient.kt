package app.alfrd.engram.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** A file or folder as Drive reports it. */
data class DriveFile(
    val id: String, val name: String, val mimeType: String, val size: Long? = null, val md5: String? = null, val version: String? = null,
    val headRevisionId: String? = null, val modifiedTime: String? = null, val webViewLink: String? = null, val driveId: String? = null,
    val trashed: Boolean = false,
) {
    val isFolder get() = mimeType == FOLDER
    companion object { const val FOLDER = "application/vnd.google-apps.folder" }
}

/**
 * Reads Google Drive, and only reads it: GET calls through [DriveHttp]'s read-only client. Every call is made for Shared Drives
 * (`supportsAllDrives=true`, and for listings `includeItemsFromAllDrives=true` with `corpora=allDrives`), so a file in a Shared Drive is found
 * the same as one in My Drive. A rate limit or server error is retried (four attempts, honouring Retry-After up to 30 seconds); a 401 refreshes the
 * token once. Errors carry the status only. Logs carry counts and ids, never names, contents or tokens.
 */
class DriveClient(
    private val tokens: GoogleAccessTokens,
    private val http: OkHttpClient,
    private val apiBase: String = "https://www.googleapis.com",
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val logger = LoggerFactory.getLogger(DriveClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** The configured folder: it has to exist, be visible to this account, and be a folder. */
    fun folder(id: String): DriveFile {
        val file = parseFile(json.parseToJsonElement(String(get(fileUrl(id).addQueryParameter("fields", FILE_FIELDS).build()))).jsonObject)
        if (!file.isFolder) throw DriveConfigException("the configured Drive id is not a folder")
        return file
    }

    /** Everything directly in [folderId] that is not in the trash, read to the last page. Sub-folders are listed, not entered. */
    fun children(folderId: String, pageSize: Int = 100): List<DriveFile> {
        requireId(folderId)
        val out = ArrayList<DriveFile>()
        var page: String? = null
        do {
            val url = "$apiBase/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'$folderId' in parents and trashed = false")
                .addQueryParameter("corpora", "allDrives")
                .addQueryParameter("supportsAllDrives", "true").addQueryParameter("includeItemsFromAllDrives", "true")
                .addQueryParameter("pageSize", pageSize.toString()).addQueryParameter("orderBy", "name")
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .apply { page?.let { addQueryParameter("pageToken", it) } }.build()
            val body = json.parseToJsonElement(String(get(url))).jsonObject
            (body["files"] as? JsonArray ?: JsonArray(emptyList())).forEach { out += parseFile(it.jsonObject) }
            page = (body["nextPageToken"] as? JsonPrimitive)?.content
        } while (page != null)
        logger.info("drive: listed {} item(s) in folder {}", out.size, folderId)
        return out.filter { !it.trashed }
    }

    /** The file's bytes, checked against the size and md5 Drive reported for it. */
    fun download(file: DriveFile): ByteArray {
        val bytes = get(fileUrl(file.id).addQueryParameter("alt", "media").build())
        if (file.size != null && bytes.size.toLong() != file.size) throw DriveApiException(200, "downloaded size does not match the size Drive reported")
        if (file.md5 != null) {
            val got = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!got.equals(file.md5, ignoreCase = true)) throw DriveApiException(200, "downloaded content does not match the md5 checksum Drive reported")
        }
        logger.info("drive: downloaded file {} ({} bytes)", file.id, bytes.size)
        return bytes
    }

    private fun fileUrl(id: String): HttpUrl.Builder {
        requireId(id)
        return "$apiBase/drive/v3/files".toHttpUrl().newBuilder().addPathSegment(id).addQueryParameter("supportsAllDrives", "true")
    }

    private fun requireId(id: String) { if (!ID.matches(id)) throw DriveConfigException("a Drive id has to be letters, digits, '-' and '_'") }

    private fun parseFile(o: JsonObject): DriveFile {
        fun s(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return DriveFile(
            id = s("id") ?: throw DriveApiException(200, "a Drive item had no id"), name = s("name").orEmpty(), mimeType = s("mimeType").orEmpty(),
            size = s("size")?.toLongOrNull(), md5 = s("md5Checksum"), version = s("version"), headRevisionId = s("headRevisionId"),
            modifiedTime = s("modifiedTime"), webViewLink = s("webViewLink"), driveId = s("driveId"),
            trashed = (o["trashed"] as? JsonPrimitive)?.content == "true",
        )
    }

    private fun get(url: HttpUrl): ByteArray {
        var attempt = 0
        var refreshed = false
        while (true) {
            attempt++
            val request = Request.Builder().url(url).get().header("Authorization", "Bearer ${tokens.token()}").build()
            val (status, retryAfter, body) = http.newCall(request).execute().use { Triple(it.code, it.header("Retry-After"), it.body?.bytes() ?: ByteArray(0)) }
            when {
                status in 200..299 -> return body
                status == 401 && !refreshed -> { refreshed = true; tokens.invalidate() }
                status in RETRYABLE && attempt < MAX_ATTEMPTS -> sleep(((retryAfter?.toLongOrNull()?.times(1000)) ?: (500L shl (attempt - 1))).coerceIn(0, 30_000))
                else -> throw DriveApiException(status, "drive request failed (status $status)")
            }
        }
    }

    private companion object {
        const val FILE_FIELDS = "id,name,mimeType,size,md5Checksum,version,headRevisionId,modifiedTime,webViewLink,driveId,trashed"
        val ID = Regex("[A-Za-z0-9_-]{1,200}")
        val RETRYABLE = setOf(429, 500, 502, 503, 504)
        const val MAX_ATTEMPTS = 4
    }
}
