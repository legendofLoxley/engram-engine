package app.alfrd.engram.drive

import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.util.Base64

class GoogleCredentialsTest {
    private val tokenUri = "https://oauth2.googleapis.com/token"
    private fun json(uri: String? = tokenUri) = TestKeys.keyJson(uri)

    private fun file(content: String, mode: String): File {
        val f = Files.createTempFile("gsa-", ".json")
        Files.writeString(f, content); Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(mode)); f.toFile().deleteOnExit()
        return f.toFile()
    }

    private fun assertNoKeyMaterial(text: String?) { for (m in TestKeys.leakMarkers) assertFalse(text.orEmpty().contains(m), "message contains key material: ${m.take(12)}") }

    private fun withGoogle(block: (FakeGoogle, MockWebServer, GoogleCredentials) -> Unit) {
        val g = FakeGoogle(); val server = MockWebServer(); server.dispatcher = g; server.start()
        try { block(g, server, GoogleCredentials.parse(TestKeys.keyJson(server.url("/token").toString()))) } finally { server.shutdown() }
    }

    // ── the key file ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a service-account key readable by its owner only loads, extra fields are ignored, and it prints masked`() {
        val c = GoogleCredentials.load(file(json(), "rw-------").path)
        assertEquals(FAKE_SA_EMAIL, c.clientEmail); assertEquals(tokenUri, c.tokenUri)
        assertEquals("GoogleCredentials(***)", c.toString()); assertNoKeyMaterial(c.toString())
    }

    @Test
    fun `token_uri defaults to Google's when the file has none`() {
        assertEquals("https://oauth2.googleapis.com/token", GoogleCredentials.parse(json(null)).tokenUri)
    }

    @Test
    fun `a file with any group or other permission bit is refused before it is read, naming the rule and not the contents`() {
        for (mode in listOf("rw-r--r--", "rw-r-----", "rwx------", "rw-rw----", "rw------x", "r--r--r--")) {
            val e = assertThrows<DriveConfigException>(mode) { GoogleCredentials.load(file(json(), mode).path) }
            assertTrue(e.message!!.contains("600"), e.message); assertNoKeyMaterial(e.message)
        }
        // Owner-only modes are accepted, including read-only.
        assertEquals(FAKE_SA_EMAIL, GoogleCredentials.load(file(json(), "r--------").path).clientEmail)
    }

    @Test
    fun `a missing file is an error that names the path and nothing else`() {
        val e = assertThrows<DriveConfigException> { GoogleCredentials.load("/nonexistent/dir/google-drive-sa.json") }
        assertTrue(e.message!!.contains("/nonexistent/dir/google-drive-sa.json"))
    }

    @Test
    fun `a malformed key file is a configuration error that never repeats what was in the file`() {
        val ec = KeyPairGenerator.getInstance("EC").generateKeyPair().private.encoded
        val ecPem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(ec) + "\n-----END PRIVATE KEY-----\n"
        val good = TestKeys.pem
        val cases = mapOf(
            "not json at all ${TestKeys.pem.take(60)}" to "JSON",
            "[\"a\"]" to "JSON",
            "{}" to "service_account",
            """{"type":"authorized_user","client_id":"a","client_secret":"s","refresh_token":"r"}""" to "service_account",
            TestKeys.keyJson(tokenUri, type = "service_account").replace("\"client_email\":\"$FAKE_SA_EMAIL\",", "") to "client_email",
            TestKeys.keyJson(tokenUri, email = "not-an-address") to "client_email",
            TestKeys.keyJson(tokenUri, privateKey = "") to "private_key",
            TestKeys.keyJson(tokenUri, privateKey = "this is not a pem key") to "private_key",
            TestKeys.keyJson(tokenUri, privateKey = "-----BEGIN PRIVATE KEY-----\n@@@@ not base64 @@@@\n-----END PRIVATE KEY-----\n") to "private_key",
            TestKeys.keyJson(tokenUri, privateKey = good.substring(0, 120) + "\n-----END PRIVATE KEY-----\n") to "private_key",
            TestKeys.keyJson(tokenUri, privateKey = ecPem) to "RSA",
            TestKeys.keyJson(tokenUri, privateKey = good.replace("BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY").replace("END PRIVATE KEY", "END RSA PRIVATE KEY")) to "private_key",
            TestKeys.keyJson("not a url") to "token_uri",
            TestKeys.keyJson("") to "token_uri",
        )
        for ((content, mentions) in cases) {
            val e = assertThrows<DriveConfigException>(content.take(80)) { GoogleCredentials.load(file(content, "rw-------").path) }
            assertTrue(e.message!!.contains(mentions), "expected '$mentions' in: ${e.message}")
            assertNoKeyMaterial(e.message); assertFalse(e.message!!.contains(FAKE_SA_EMAIL), e.message)
        }
    }

    @Test
    fun `where the key is looked for, the environment first and then the box default if the file is there`() {
        val home = "/home/halo"; val def = "/home/halo/.config/engram/google-drive-sa.json"
        assertEquals("/etc/other.json", GoogleCredentials.credentialsPath(mapOf("GOOGLE_DRIVE_CREDENTIALS_FILE" to "/etc/other.json"), home) { false })
        assertEquals(def, GoogleCredentials.credentialsPath(emptyMap(), home) { it == def })
        assertEquals(def, GoogleCredentials.credentialsPath(mapOf("GOOGLE_DRIVE_CREDENTIALS_FILE" to "  "), home) { it == def })
        assertNull(GoogleCredentials.credentialsPath(emptyMap(), home) { false })
    }

    // ── the signed assertion ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the assertion is an RS256 JWT with exactly the claims Google asks for, drive readonly only, and the signature verifies`() = withGoogle { g, server, creds ->
        val now = 1_700_000_000_000L
        assertEquals("ACCESS-1", GoogleAccessTokens(creds, DriveHttp.client(setOf(server.hostName))) { now }.token())
        val claim = g.claims.single()
        assertEquals(setOf("iss", "scope", "aud", "iat", "exp"), claim.keys, "no sub (no delegation), nothing else")
        assertEquals(FAKE_SA_EMAIL, (claim["iss"] as JsonPrimitive).content)
        assertEquals("https://www.googleapis.com/auth/drive.readonly", (claim["scope"] as JsonPrimitive).content)
        assertEquals(server.url("/token").toString(), (claim["aud"] as JsonPrimitive).content)
        assertEquals(1_700_000_000L, (claim["iat"] as JsonPrimitive).content.toLong())
        assertEquals(1_700_003_600L, (claim["exp"] as JsonPrimitive).content.toLong())
        val header = g.headers.single()
        assertEquals("RS256", (header["alg"] as JsonPrimitive).content); assertEquals("JWT", (header["typ"] as JsonPrimitive).content)
        // The fake verified the signature against the public key before it issued the token; a wrong key is refused (next test).
        val assertion = g.requests.first { it.requestUrl!!.encodedPath == "/token" }.body.clone().readUtf8()
        assertTrue(assertion.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"), assertion.take(120))
        val jwt = java.net.URLDecoder.decode(assertion.substringAfter("assertion="), "UTF-8")
        assertTrue(jwt.split('.').size == 3 && jwt.none { it == '=' || it == '+' || it == '/' }, "base64url without padding")
    }

    @Test
    fun `the scope is fixed to drive readonly and cannot be widened by the caller`() {
        assertEquals("https://www.googleapis.com/auth/drive.readonly", GoogleAccessTokens.SCOPE)
        assertTrue(GoogleAccessTokens.SCOPE in GoogleAccessTokens.READ_ONLY_SCOPES)
        assertTrue(GoogleAccessTokens.READ_ONLY_SCOPES.none { it.endsWith("/auth/drive") || it.endsWith("drive.file") || it.contains("gmail.send") || it.contains("gmail.modify") })
    }

    @Test
    fun `a signature made with another key is refused by Google, and the error carries the code only`() = withGoogle { g, server, creds ->
        g.publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val e = assertThrows<DriveApiException> { GoogleAccessTokens(creds, DriveHttp.client(setOf(server.hostName))).token() }
        assertEquals(400, e.status)
        assertTrue(e.message!!.contains("invalid_grant"), e.message)
        assertFalse(e.message!!.contains("echo")); assertNoKeyMaterial(e.message)
    }

    // ── the token ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the token is reused until a minute before it expires, then a new assertion is signed`() = withGoogle { g, server, creds ->
        var now = 1_000_000L
        val tokens = GoogleAccessTokens(creds, DriveHttp.client(setOf(server.hostName))) { now }
        assertEquals("ACCESS-1", tokens.token()); assertEquals("ACCESS-1", tokens.token())
        assertEquals(1, g.tokenCalls)
        now += 3_539_000; assertEquals("ACCESS-1", tokens.token())
        now += 2_000; assertEquals("ACCESS-2", tokens.token())
        assertEquals(2, g.tokenCalls)
        assertEquals(listOf(1_000L, 4_541L), g.claims.map { (it["iat"] as JsonPrimitive).content.toLong() })
    }

    @Test
    fun `invalidate forgets the token so the next call signs again`() = withGoogle { g, server, creds ->
        val tokens = GoogleAccessTokens(creds, DriveHttp.client(setOf(server.hostName)))
        tokens.token(); tokens.invalidate(); assertEquals("ACCESS-2", tokens.token()); assertEquals(2, g.tokenCalls)
    }

    @Test
    fun `a service-account response reports no scope and is accepted, since the scope was fixed in the request`() = withGoogle { g, server, creds ->
        g.tokenScope = null
        assertEquals("ACCESS-1", GoogleAccessTokens(creds, DriveHttp.client(setOf(server.hostName))).token())
    }

    @Test
    fun `a response whose scope goes beyond read-only is refused, naming the scope and not the token`() = withGoogle { g, server, creds ->
        for (scope in listOf("https://www.googleapis.com/auth/drive", "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/gmail.send", "https://www.googleapis.com/auth/drive.file")) {
            g.tokenScope = scope
            val e = assertThrows<DriveConfigException>(scope) { GoogleAccessTokens(creds, DriveHttp.client(setOf(server.hostName))).token() }
            assertTrue(e.message!!.contains("read-only") && e.message!!.contains("auth/"), e.message)
            assertFalse(e.message!!.contains("ACCESS-")); assertNoKeyMaterial(e.message)
        }
    }

    @Test
    fun `read-only scopes in the response are accepted, with the identity scopes a consent adds`() = withGoogle { g, server, creds ->
        g.tokenScope = "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/gmail.readonly openid https://www.googleapis.com/auth/userinfo.email"
        assertEquals("ACCESS-1", GoogleAccessTokens(creds, DriveHttp.client(setOf(server.hostName))).token())
    }

    @Test
    fun `the key's token_uri is only reached through the read-only guard, so a key file cannot send the signed assertion to another host`() = withGoogle { _, _, _ ->
        val evil = GoogleCredentials.parse(TestKeys.keyJson("https://evil.example.com/token"))
        val e = assertThrows<java.io.IOException> { GoogleAccessTokens(evil, DriveHttp.production()).token() }
        assertTrue(e.message!!.contains("read-only"), e.message); assertNoKeyMaterial(e.message)
        val wrongPath = GoogleCredentials.parse(TestKeys.keyJson("https://oauth2.googleapis.com/revoke"))
        assertTrue(assertThrows<java.io.IOException> { GoogleAccessTokens(wrongPath, DriveHttp.production()).token() }.message!!.contains("read-only"))
    }

    // ── the http layer ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the http layer sends only GET to Drive, only POST to the token endpoint, never to another host, and https except for loopback`() {
        val client = DriveHttp.client(setOf("www.googleapis.com", "oauth2.googleapis.com"))
        fun refused(method: String, url: String) = assertThrows<java.io.IOException>("$method $url") {
            client.newCall(okhttp3.Request.Builder().url(url).method(method, if (method == "GET") null else okhttp3.RequestBody.create(null, ByteArray(0))).build()).execute()
        }.message!!.contains("read-only")
        for (m in listOf("POST", "PUT", "PATCH", "DELETE")) assertTrue(refused(m, "https://www.googleapis.com/drive/v3/files/abc"), m)
        assertTrue(refused("GET", "https://example.com/drive/v3/files/abc"))
        assertTrue(refused("GET", "http://www.googleapis.com/drive/v3/files/abc"))
        assertTrue(refused("POST", "https://oauth2.googleapis.com/revoke"))
        assertTrue(refused("GET", "https://oauth2.googleapis.com/token"))
    }
}
