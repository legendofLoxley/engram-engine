package app.alfrd.engram.drive

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class GoogleCredentialsTest {
    private val json = """{"client_id":"$FAKE_CLIENT_ID","client_secret":"$FAKE_CLIENT_SECRET","refresh_token":"$FAKE_REFRESH"}"""

    private fun file(content: String, mode: String): File {
        val f = Files.createTempFile("gcreds-", ".json")
        Files.writeString(f, content); Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(mode)); f.toFile().deleteOnExit()
        return f.toFile()
    }

    @Test
    fun `a credentials file readable by owner only loads, and prints as masked`() {
        val c = GoogleCredentials.load(file(json, "rw-------").path)
        assertEquals(FAKE_CLIENT_ID, c.clientId)
        assertEquals("GoogleCredentials(***)", c.toString())
        assertFalse(c.toString().contains(FAKE_REFRESH) || c.toString().contains(FAKE_CLIENT_SECRET))
    }

    @Test
    fun `a file others can read is refused before it is read, and the error names the rule and not the contents`() {
        for (mode in listOf("rw-r--r--", "rw-r-----", "rwx------", "rw-rw----")) {
            val e = assertThrows<DriveConfigException> { GoogleCredentials.load(file(json, mode).path) }
            assertTrue(e.message!!.contains("600"), e.message)
            assertFalse(e.message!!.contains(FAKE_REFRESH) || e.message!!.contains(FAKE_CLIENT_SECRET))
        }
    }

    @Test
    fun `a missing file, bad json or a missing field is a configuration error that never repeats what was in the file`() {
        assertThrows<DriveConfigException> { GoogleCredentials.load("/nonexistent/google.json") }
        for (bad in listOf("not json $FAKE_REFRESH", """{"client_id":"a","client_secret":"$FAKE_CLIENT_SECRET"}""", """{"client_id":"","client_secret":"x","refresh_token":"$FAKE_REFRESH"}""")) {
            val e = assertThrows<DriveConfigException> { GoogleCredentials.load(file(bad, "rw-------").path) }
            assertFalse(e.message!!.contains(FAKE_REFRESH) || e.message!!.contains(FAKE_CLIENT_SECRET), e.message)
        }
    }

    private fun withGoogle(block: (FakeGoogle, MockWebServer, GoogleAccessTokens, () -> Long) -> Unit) {
        val g = FakeGoogle(); val server = MockWebServer(); server.dispatcher = g; server.start()
        var now = 1_000_000L
        try {
            val tokens = GoogleAccessTokens(GoogleCredentials.parse(json), DriveHttp.client(setOf(server.hostName)), server.url("/token").toString()) { now }
            block(g, server, tokens) { now.also { } }
        } finally { server.shutdown() }
    }

    @Test
    fun `the token is fetched with the refresh grant, reused until a minute before it expires, then fetched again`() {
        val g = FakeGoogle(); val server = MockWebServer(); server.dispatcher = g; server.start()
        var now = 1_000_000L
        try {
            val tokens = GoogleAccessTokens(GoogleCredentials.parse(json), DriveHttp.client(setOf(server.hostName)), server.url("/token").toString()) { now }
            assertEquals("ACCESS-1", tokens.token()); assertEquals("ACCESS-1", tokens.token())
            assertEquals(1, g.tokenCalls)
            now += 3_539_000; assertEquals("ACCESS-1", tokens.token())
            now += 2_000; assertEquals("ACCESS-2", tokens.token())
            assertEquals(2, g.tokenCalls)
        } finally { server.shutdown() }
    }

    @Test
    fun `a token whose scope goes beyond read-only is refused, naming the scope and not the token`() = withGoogle { g, server, _, _ ->
        for (scope in listOf("https://www.googleapis.com/auth/drive", "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/gmail.send", "https://www.googleapis.com/auth/drive.file")) {
            g.tokenScope = scope
            val e = assertThrows<DriveConfigException> { GoogleAccessTokens(GoogleCredentials.parse(json), DriveHttp.client(setOf(server.hostName)), server.url("/token").toString()).token() }
            assertTrue(e.message!!.contains("read-only"), e.message)
            assertFalse(e.message!!.contains("ACCESS-"), e.message)
        }
    }

    @Test
    fun `read-only scopes are accepted`() = withGoogle { g, _, tokens, _ ->
        g.tokenScope = "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/gmail.readonly openid https://www.googleapis.com/auth/userinfo.email"
        assertEquals("ACCESS-1", tokens.token())
    }

    @Test
    fun `a rejected refresh is an error carrying the error code only, never the body or the secrets`() {
        val g = FakeGoogle(); val server = MockWebServer(); server.dispatcher = g; server.start()
        try {
            val wrong = GoogleCredentials.parse(json.replace(FAKE_REFRESH, "OTHER-TOKEN-0000"))
            val e = assertThrows<DriveApiException> { GoogleAccessTokens(wrong, DriveHttp.client(setOf(server.hostName)), server.url("/token").toString()).token() }
            assertEquals(400, e.status)
            assertTrue(e.message!!.contains("invalid_grant"))
            assertFalse(e.message!!.contains(FAKE_REFRESH) || e.message!!.contains("OTHER-TOKEN") || e.message!!.contains("echo"))
        } finally { server.shutdown() }
    }

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
        assertNotEquals(null, OkHttpClient())
    }
}
