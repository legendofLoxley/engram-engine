package app.alfrd.engram.drive

import app.alfrd.engram.pdf.PdfFixtures
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DriveClientTest {
    private val creds = GoogleCredentials.parse("""{"client_id":"$FAKE_CLIENT_ID","client_secret":"$FAKE_CLIENT_SECRET","refresh_token":"$FAKE_REFRESH"}""")
    val folderId = "FOLDER-1"

    private fun withDrive(block: (FakeGoogle, DriveClient) -> Unit) {
        val g = FakeGoogle(); val server = MockWebServer(); server.dispatcher = g; server.start()
        try {
            val http = DriveHttp.client(setOf(server.hostName))
            val client = DriveClient(GoogleAccessTokens(creds, http, server.url("/token").toString()), http, server.url("/").toString().trimEnd('/'), sleep = {})
            g.folder(folderId)
            block(g, client)
        } finally { server.shutdown() }
    }

    @Test
    fun `every files call on a Shared Drive carries supportsAllDrives and includeItemsFromAllDrives, with the bearer token and only GET`() = withDrive { g, drive ->
        g.pdf("f1", "A.pdf", PdfFixtures.board(), folderId)
        drive.folder(folderId); drive.children(folderId); drive.download(drive.children(folderId).single())
        val files = g.requests.filter { it.requestUrl!!.encodedPath.startsWith("/drive/v3/files") }
        assertTrue(files.size >= 4)
        for (r in files) {
            assertEquals("GET", r.method)
            assertEquals("true", r.requestUrl!!.queryParameter("supportsAllDrives"), r.path)
            assertTrue(r.getHeader("Authorization")!!.startsWith("Bearer ACCESS-"))
        }
        val list = files.first { it.requestUrl!!.encodedPath == "/drive/v3/files" }
        assertEquals("true", list.requestUrl!!.queryParameter("includeItemsFromAllDrives"))
        assertEquals("allDrives", list.requestUrl!!.queryParameter("corpora"))
        assertTrue(list.requestUrl!!.queryParameter("q")!!.contains("'$folderId' in parents") && list.requestUrl!!.queryParameter("q")!!.contains("trashed = false"))
        val fields = list.requestUrl!!.queryParameter("fields")!!
        for (f in listOf("id", "name", "mimeType", "md5Checksum", "version", "headRevisionId", "modifiedTime", "webViewLink", "driveId", "size", "nextPageToken")) assertTrue(fields.contains(f), "fields lacks $f: $fields")
        assertTrue(g.requests.all { it.method == "GET" || it.requestUrl!!.encodedPath == "/token" }, "nothing but GET to Drive")
    }

    @Test
    fun `a listing is read to the last page`() = withDrive { g, drive ->
        (1..7).forEach { g.pdf("f$it", "F$it.pdf", PdfFixtures.board(it % 2 + 1), folderId) }
        val all = drive.children(folderId, pageSize = 3)
        assertEquals((1..7).map { "f$it" }, all.map { it.id })
        assertEquals(3, g.requests.count { it.requestUrl!!.encodedPath == "/drive/v3/files" })
    }

    @Test
    fun `file facts are carried through as Drive reports them`() = withDrive { g, drive ->
        val bytes = PdfFixtures.board()
        g.pdf("f1", "SEGC Equity Audit Evaluation Report.pdf", bytes, folderId, version = 42)
        val f = drive.children(folderId).single()
        assertEquals("f1", f.id); assertEquals("SEGC Equity Audit Evaluation Report.pdf", f.name); assertEquals("application/pdf", f.mimeType)
        assertEquals(bytes.size.toLong(), f.size); assertEquals(md5(bytes), f.md5); assertEquals("42", f.version); assertEquals("rev-42", f.headRevisionId)
        assertEquals("https://drive.google.com/file/d/f1/view?usp=drivesdk", f.webViewLink); assertEquals("0AShared", f.driveId)
    }

    @Test
    fun `trashed items are not listed even if the server returned them`() = withDrive { g, drive ->
        g.pdf("f1", "A.pdf", PdfFixtures.board(), folderId); g.pdf("f2", "B.pdf", PdfFixtures.board(2), folderId).trashed = true
        assertEquals(listOf("f1"), drive.children(folderId).map { it.id })
    }

    @Test
    fun `a download is the exact bytes, checked against the size and md5 Drive reported`() = withDrive { g, drive ->
        val bytes = PdfFixtures.board()
        g.pdf("f1", "A.pdf", bytes, folderId)
        assertTrue(bytes.contentEquals(drive.download(drive.children(folderId).single())))
        g.corrupt += "f1"
        val e = assertThrows<DriveApiException> { drive.download(drive.children(folderId).single()) }
        assertTrue(e.message!!.contains("md5"), e.message)
    }

    @Test
    fun `the configured folder must be a folder`() = withDrive { g, drive ->
        g.pdf("f1", "A.pdf", PdfFixtures.board(), folderId)
        assertEquals(folderId, drive.folder(folderId).id)
        assertThrows<DriveConfigException> { drive.folder("f1") }
        val e = assertThrows<DriveApiException> { drive.folder("nope") }
        assertEquals(404, e.status)
    }

    @Test
    fun `a rate limit or a server error is retried and then succeeds, and gives up after four attempts`() = withDrive { g, drive ->
        g.pdf("f1", "A.pdf", PdfFixtures.board(), folderId)
        g.failures += listOf(429, 503)
        assertEquals(1, drive.children(folderId).size)
        g.failures += listOf(500, 500, 500, 500, 500)
        val e = assertThrows<DriveApiException> { drive.children(folderId) }
        assertEquals(500, e.status)
        assertFalse(e.message!!.contains("ACCESS-"))
    }

    @Test
    fun `a revoked token is refreshed once and the call retried, and a second 401 is an error that carries the status only`() = withDrive { g, drive ->
        g.pdf("f1", "A.pdf", PdfFixtures.board(), folderId)
        drive.children(folderId)
        g.currentToken = "something-else"
        assertEquals(1, drive.children(folderId).size)
        assertEquals(2, g.tokenCalls)
        g.failures += listOf(401, 401)
        val e = assertThrows<DriveApiException> { drive.children(folderId) }
        assertEquals(401, e.status)
        assertEquals(3, g.tokenCalls)
        assertFalse(e.message!!.contains("ACCESS-"))
    }
}
