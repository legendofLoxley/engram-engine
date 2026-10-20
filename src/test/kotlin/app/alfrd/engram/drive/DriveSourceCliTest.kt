package app.alfrd.engram.drive

import app.alfrd.engram.pdf.PdfFixtures
import app.alfrd.engram.scripts.DriveSourceCli
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DriveSourceCliTest {
    private class Result(val code: Int, val out: String, val err: String)

    private fun run(args: List<String>, env: Map<String, String> = emptyMap(), endpoints: () -> DriveSourceCli.Endpoints = DriveSourceCli.Endpoints::production): Result {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        return Result(DriveSourceCli.run(args, PrintStream(o), PrintStream(e), env, endpoints), o.toString(), e.toString())
    }

    private fun credsFile(mode: String = "rw-------") = Files.createTempFile("gcreds-", ".json").also {
        Files.writeString(it, """{"client_id":"$FAKE_CLIENT_ID","client_secret":"$FAKE_CLIENT_SECRET","refresh_token":"$FAKE_REFRESH"}""")
        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString(mode)); it.toFile().deleteOnExit()
    }.toString()

    @Test
    fun `check lists the PDFs in the folder with ids, versions, md5 and links, reads no file and prints no secret`() {
        val g = FakeGoogle(); val server = MockWebServer(); server.dispatcher = g; server.start()
        try {
            g.folder("FOLDER-1", "Evidence")
            g.pdf("id-a", "SEGC Equity Audit Evaluation Report.pdf", PdfFixtures.board(), "FOLDER-1", version = 9)
            g.files["doc"] = FakeGoogle.Entry("doc", "notes.docx", "application/msword", ByteArray(3), parents = mutableListOf("FOLDER-1"))
            val r = run(listOf("--check"), mapOf("GOOGLE_DRIVE_CREDENTIALS_FILE" to credsFile(), "GOOGLE_DRIVE_FOLDER_ID" to "FOLDER-1")) {
                DriveSourceCli.Endpoints(server.url("/").toString().trimEnd('/'), server.url("/token").toString(), DriveHttp.client(setOf(server.hostName)))
            }
            assertEquals(0, r.code, r.err)
            assertTrue(r.out.contains("folder FOLDER-1 \"Evidence\" shared drive 0AShared"), r.out)
            assertTrue(r.out.contains("PDF     \"SEGC Equity Audit Evaluation Report.pdf\" id=id-a") && r.out.contains("version=9"), r.out)
            assertTrue(r.out.contains("skipped \"notes.docx\"") && r.out.contains("1 PDF(s) in the folder"), r.out)
            assertTrue(g.mediaRequests().isEmpty(), "check downloads nothing")
            for (secret in listOf(FAKE_REFRESH, FAKE_CLIENT_SECRET, "ACCESS-")) assertFalse((r.out + r.err).contains(secret))
        } finally { server.shutdown() }
    }

    @Test
    fun `check needs configuration, refuses a credentials file others can read, and reports Drive errors by status`() {
        assertEquals(64, run(listOf("--check")).code)
        val open = run(listOf("--check"), mapOf("GOOGLE_DRIVE_CREDENTIALS_FILE" to credsFile("rw-r--r--"), "GOOGLE_DRIVE_FOLDER_ID" to "F"))
        assertEquals(64, open.code); assertTrue(open.err.contains("600"), open.err); assertFalse(open.err.contains(FAKE_REFRESH))
        val g = FakeGoogle(); val server = MockWebServer(); server.dispatcher = g; server.start()
        try {
            val r = run(listOf("--check"), mapOf("GOOGLE_DRIVE_CREDENTIALS_FILE" to credsFile(), "GOOGLE_DRIVE_FOLDER_ID" to "MISSING")) {
                DriveSourceCli.Endpoints(server.url("/").toString().trimEnd('/'), server.url("/token").toString(), DriveHttp.client(setOf(server.hostName)))
            }
            assertEquals(2, r.code); assertTrue(r.err.contains("404"), r.err)
        } finally { server.shutdown() }
    }

    @Test
    fun `usage errors, owner-jacob and a non-loopback url are refused before anything is sent`() {
        assertEquals(64, run(emptyList()).code)
        assertEquals(64, run(listOf("--user", "owner-jacob"), mapOf("DEBUG_CONVERSE_TOKEN" to "t")).code)
        assertEquals(64, run(listOf("--user", "x")).code)
        assertEquals(64, run(listOf("--user", "x", "--url", "http://example.com:8080"), mapOf("DEBUG_CONVERSE_TOKEN" to "t")).code)
    }
}
