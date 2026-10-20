package app.alfrd.engram.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** The connector is not set up correctly. The message names the rule that was broken and never repeats anything read from a credentials file. */
class DriveConfigException(message: String) : RuntimeException(message)

/** Google answered with an error. The message carries the status (and an error code where Google gave one), never a response body. */
class DriveApiException(val status: Int, message: String) : RuntimeException(message)

/**
 * The OAuth client and refresh token of the dedicated read-only Google user, read from one JSON file:
 * `{"client_id": "...", "client_secret": "...", "refresh_token": "..."}`.
 *
 * The file must be readable by its owner only (mode 600; anything wider is refused before it is read) and belongs outside the repository,
 * beside the box's other secrets. The values are never logged, never put in an exception message and never printed: [toString] is masked.
 */
class GoogleCredentials private constructor(val clientId: String, private val clientSecret: String, private val refreshToken: String) {
    override fun toString() = "GoogleCredentials(***)"

    internal fun refreshForm() = FormBody.Builder()
        .add("grant_type", "refresh_token").add("client_id", clientId).add("client_secret", clientSecret).add("refresh_token", refreshToken).build()

    companion object {
        private val ALLOWED_MODE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        fun load(path: String): GoogleCredentials {
            val file = File(path)
            if (!file.isFile) throw DriveConfigException("the credentials file '${path.take(200)}' does not exist")
            val mode = try { Files.getPosixFilePermissions(file.toPath()) } catch (e: Exception) { throw DriveConfigException("the credentials file's permissions cannot be checked here; it must be mode 600") }
            if (!ALLOWED_MODE.containsAll(mode)) throw DriveConfigException("the credentials file must be mode 600 (owner read and write only); run chmod 600 on it")
            val text = try { file.readText() } catch (e: Exception) { throw DriveConfigException("the credentials file could not be read") }
            return parse(text)
        }

        fun parse(json: String): GoogleCredentials {
            val obj = try { Json.parseToJsonElement(json).jsonObject } catch (e: Exception) { throw DriveConfigException("the credentials file is not a JSON object") }
            fun field(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            val missing = listOf("client_id", "client_secret", "refresh_token").filter { field(it) == null }
            if (missing.isNotEmpty()) throw DriveConfigException("the credentials file lacks: ${missing.joinToString(", ")}")
            return GoogleCredentials(field("client_id")!!, field("client_secret")!!, field("refresh_token")!!)
        }
    }
}

/**
 * Access tokens from the refresh token, kept until a minute before they expire. The token endpoint is the only place a POST goes. A token whose
 * scope reaches beyond read-only is refused: the account has to be authorized for `drive.readonly` (and `gmail.readonly`) and nothing else.
 */
class GoogleAccessTokens(
    private val credentials: GoogleCredentials,
    private val http: OkHttpClient,
    val tokenUrl: String = "https://oauth2.googleapis.com/token",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cached: String? = null
    private var expiresAt = 0L

    @Synchronized
    fun token(): String = cached?.takeIf { clock() < expiresAt - 60_000 } ?: refresh()

    /** Forget the cached token, so the next [token] fetches a fresh one (after Google refused it). */
    @Synchronized
    fun invalidate() { cached = null }

    private fun refresh(): String {
        val request = Request.Builder().url(tokenUrl).post(credentials.refreshForm()).build()
        val (status, body) = http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun str(name: String) = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (status != 200) {
            val code = str("error")?.takeIf { Regex("[a-z_]{1,40}").matches(it) }
            throw DriveApiException(status, "token request failed (status $status${code?.let { ", error $it" }.orEmpty()})")
        }
        val scopes = str("scope")?.split(' ')?.filter { it.isNotBlank() }
            ?: throw DriveConfigException("Google did not report the scope of the token; it must be read-only")
        val beyond = scopes.filter { it !in READ_ONLY_SCOPES }
        if (beyond.isNotEmpty()) throw DriveConfigException("the credentials grant more than read-only access (${beyond.joinToString(" ")}); authorize the account for drive.readonly only")
        val token = str("access_token") ?: throw DriveApiException(status, "token response had no access token")
        val ttl = (json["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L
        cached = token; expiresAt = clock() + ttl * 1000
        return token
    }

    companion object {
        val READ_ONLY_SCOPES = setOf(
            "https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/drive.metadata.readonly",
            "https://www.googleapis.com/auth/gmail.readonly",
            // Identity scopes a consent flow adds on its own; they grant no access to any data.
            "openid", "email", "profile", "https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile",
        )
    }
}
