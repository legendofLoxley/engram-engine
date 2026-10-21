package app.alfrd.engram.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** The connector is not set up correctly. The message names the rule that was broken and never repeats anything read from a credentials file. */
class DriveConfigException(message: String) : RuntimeException(message)

/** Google answered with an error. The message carries the status (and an error code where Google gave one), never a response body. */
class DriveApiException(val status: Int, message: String) : RuntimeException(message)

/**
 * A Google service-account key, the JSON file Google gives when a key is created: `type` (`service_account`), `client_email`, `private_key`
 * (PKCS#8 PEM, RSA) and `token_uri`. Other fields are ignored.
 *
 * The file belongs outside the repository, beside the box's other secrets, and must be readable by its owner only (mode 600; any group or other
 * permission bit is refused before the file is read). The key is held as a parsed [PrivateKey], never as text; it is never logged, never put in an
 * exception message and never printed: [toString] is masked, and every error names the rule that was broken, not the content.
 */
class GoogleCredentials private constructor(val clientEmail: String, private val key: PrivateKey, val tokenUri: String) {
    override fun toString() = "GoogleCredentials(***)"

    /**
     * The signed assertion Google exchanges for an access token: an RS256 JWT whose claims are exactly `iss` (this account), `scope`, `aud` (the token
     * endpoint), `iat` and `exp` (an hour later). There is no `sub`: nothing is done on behalf of a user.
     */
    internal fun signedAssertion(scope: String, nowSeconds: Long): String {
        fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = buildJsonObject {
            put("iss", clientEmail); put("scope", scope); put("aud", tokenUri); put("iat", nowSeconds); put("exp", nowSeconds + ASSERTION_LIFETIME_SECONDS)
        }.toString()
        val signingInput = b64(header.toByteArray()) + "." + b64(claims.toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(key); update(signingInput.toByteArray(Charsets.US_ASCII)); sign() }
        return signingInput + "." + b64(signature)
    }

    companion object {
        private const val ASSERTION_LIFETIME_SECONDS = 3600L
        private const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
        private val ALLOWED_MODE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        /** Where the box keeps the key unless told otherwise. */
        const val DEFAULT_RELATIVE_PATH = ".config/engram/google-drive-sa.json"

        /** `GOOGLE_DRIVE_CREDENTIALS_FILE` if set (a path, not the key), else the box default under [home] if a file is there, else null. */
        fun credentialsPath(
            env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home").orEmpty(), exists: (String) -> Boolean = { File(it).isFile },
        ): String? {
            env["GOOGLE_DRIVE_CREDENTIALS_FILE"]?.takeIf { it.isNotBlank() }?.let { return it }
            return "$home/$DEFAULT_RELATIVE_PATH".takeIf(exists)
        }

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
            fun field(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (field("type") != "service_account") throw DriveConfigException("the credentials file is not a service-account key (\"type\" must be service_account)")
            val email = field("client_email")?.takeIf { it.contains('@') && !it.contains(' ') } ?: throw DriveConfigException("the credentials file lacks a valid client_email")
            val tokenUri = if (obj.containsKey("token_uri")) field("token_uri")?.takeIf { it.toHttpUrlOrNull() != null } ?: throw DriveConfigException("the token_uri in the credentials file is not a valid URL") else DEFAULT_TOKEN_URI
            val pem = field("private_key")?.takeIf { it.isNotEmpty() } ?: throw DriveConfigException("the credentials file lacks a private_key")
            return GoogleCredentials(email, privateKeyOf(pem), tokenUri)
        }

        private fun privateKeyOf(pem: String): PrivateKey {
            val unreadable = DriveConfigException("the private_key in the credentials file is not a readable PKCS#8 RSA key")
            val body = pem.removePrefix("-----BEGIN PRIVATE KEY-----").removeSuffix("-----END PRIVATE KEY-----").takeIf { it.length < pem.length - 20 } ?: throw unreadable
            return try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body.filterNot { it.isWhitespace() })))
            } catch (e: Exception) {
                throw unreadable
            }
        }
    }
}

/**
 * Access tokens for the service account: a signed assertion ([GoogleCredentials.signedAssertion]) is exchanged at the key's `token_uri` and the token is kept
 * until a minute before it expires. The token endpoint is the only place a POST goes (see [DriveHttp]).
 *
 * **Read-only.** The scope asked for is the constant [SCOPE] (`drive.readonly`); there is no way to ask for another. A service-account response
 * normally reports no scope, since it was fixed in the request; if one is reported and it reaches beyond read-only, the token is refused. (The
 * account is also a Viewer of the Shared Drive, so Drive would refuse a write on that ground too.)
 */
class GoogleAccessTokens(
    private val credentials: GoogleCredentials,
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cached: String? = null
    private var expiresAt = 0L

    @Synchronized
    fun token(): String = cached?.takeIf { clock() < expiresAt - 60_000 } ?: refresh()

    /** Forget the cached token, so the next [token] signs a new assertion (after Google refused it). */
    @Synchronized
    fun invalidate() { cached = null }

    private fun refresh(): String {
        val form = FormBody.Builder().add("grant_type", JWT_BEARER).add("assertion", credentials.signedAssertion(SCOPE, clock() / 1000)).build()
        val (status, body) = http.newCall(Request.Builder().url(credentials.tokenUri).post(form).build()).execute().use { it.code to (it.body?.string().orEmpty()) }
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        fun str(name: String) = (json?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (status != 200) {
            val code = str("error")?.takeIf { Regex("[a-z_]{1,40}").matches(it) }
            throw DriveApiException(status, "token request failed (status $status${code?.let { ", error $it" }.orEmpty()})")
        }
        val beyond = str("scope")?.split(' ')?.filter { it.isNotBlank() && it !in READ_ONLY_SCOPES }.orEmpty()
        if (beyond.isNotEmpty()) throw DriveConfigException("the token's scope goes beyond read-only (${beyond.joinToString(" ")}); only drive.readonly is used")
        val token = str("access_token") ?: throw DriveApiException(status, "token response had no access token")
        val ttl = (json?.get("expires_in") as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L
        cached = token; expiresAt = clock() + ttl * 1000
        return token
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        val READ_ONLY_SCOPES = setOf(
            SCOPE, "https://www.googleapis.com/auth/drive.metadata.readonly", "https://www.googleapis.com/auth/gmail.readonly",
            // Identity scopes a consent adds on its own; they grant no access to any data.
            "openid", "email", "profile", "https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile",
        )
    }
}
