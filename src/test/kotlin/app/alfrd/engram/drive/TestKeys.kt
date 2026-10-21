package app.alfrd.engram.drive

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

const val FAKE_SA_EMAIL = "alfrd-drive-reader@alfrd-box.iam.gserviceaccount.com"

/** A throwaway RSA key made for the tests (never a real key), and service-account key files built from it the way Google writes them. */
object TestKeys {
    val pair: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private val body: String by lazy { Base64.getEncoder().encodeToString(pair.private.encoded) }
    val pem: String by lazy { "-----BEGIN PRIVATE KEY-----\n" + body.chunked(64).joinToString("\n") + "\n-----END PRIVATE KEY-----\n" }

    /** Pieces of the key that must never turn up in a log, a message or a listing. */
    val leakMarkers: List<String> by lazy { listOf("PRIVATE KEY", body.substring(60, 100), body.substring(400, 440), "assertion=") }

    fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")

    /** A service-account key file: `type`, `client_email`, `private_key` and `token_uri`, with the other fields Google adds. */
    fun keyJson(tokenUri: String?, email: String = FAKE_SA_EMAIL, type: String = "service_account", privateKey: String = pem): String = buildString {
        append("{")
        append("\"type\":\"$type\",\"project_id\":\"alfrd-box\",\"private_key_id\":\"0123456789abcdef\",")
        append("\"private_key\":\"${escape(privateKey)}\",\"client_email\":\"$email\",\"client_id\":\"112233445566778899\",")
        append("\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\",")
        tokenUri?.let { append("\"token_uri\":\"$it\",") }
        append("\"universe_domain\":\"googleapis.com\"}")
    }
}
