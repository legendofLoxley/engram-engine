package app.alfrd.engram.drive

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.PdfFixtures
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PdfSourceService
import app.alfrd.engram.pdf.scenarios
import app.alfrd.engram.pdf.textTable
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

class DriveShelfServiceTest {
    private val folderId = "EVIDENCE-FOLDER"
    private val user = DebugConverseService.resolveUserId("drive-shelf-1")

    private class Env(val g: FakeGoogle, val shelf: DriveShelfService, val store: SourceStore, val manager: DatabaseManager)

    private fun withShelf(block: (Env) -> Unit) {
        val dir = Files.createTempDirectory("engram-drive-").toFile()
        val manager = DatabaseManager(File(dir, "db").path)
        val g = FakeGoogle(); val server = MockWebServer(); server.dispatcher = g; server.start()
        try {
            val db = manager.getDatabase(); SchemaBootstrap.bootstrap(db); DebugConverseService.ensureSyntheticUser(db, user)
            val store = SourceStore(db)
            val http = DriveHttp.client(setOf(server.hostName))
            val creds = GoogleCredentials.parse(TestKeys.keyJson(server.url("/token").toString()))
            val drive = DriveClient(GoogleAccessTokens(creds, http), http, server.url("/").toString().trimEnd('/'), sleep = {})
            g.folder(folderId)
            val pdfs = PdfSourceService(store, PdfExtractor(null), PdfPreparationCache(File(dir, "cache")))
            block(Env(g, DriveShelfService(drive, pdfs, store, folderId), store, manager))
        } finally { server.shutdown(); manager.close(); dir.deleteRecursively() }
    }

    private fun count(m: DatabaseManager, type: String) = m.getDatabase().query("sql", "SELECT count(*) AS n FROM `$type`").use { it.next().getProperty<Number>("n").toLong() }
    private fun graph(m: DatabaseManager) = listOf("Source", "Chunk", "CONTAINS", "TRUSTS").map { count(m, it) }

    @Test
    fun `the three files in the folder each become one Source with the Drive file id, link, version and md5, and page-located chunks`() = withShelf { e ->
        val a = PdfFixtures.board(1); val b = PdfFixtures.scenarios(); val c = PdfFixtures.textTable()
        e.g.pdf("id-a", "SEGC Equity Audit Evaluation Report.pdf", a, folderId, version = 7)
        e.g.pdf("id-b", "SEGC School by School Analysis.pdf", b, folderId, version = 3)
        e.g.pdf("id-c", "District wide scenarios 10.04.26 (2).pdf", c, folderId, version = 12)
        val report = e.shelf.ingest(user)
        assertEquals(listOf("created", "created", "created"), report.files.map { it.outcome })
        assertEquals(setOf("SEGC Equity Audit Evaluation Report.pdf", "SEGC School by School Analysis.pdf", "District wide scenarios 10.04.26 (2).pdf"), report.files.map { it.name }.toSet())
        val sources = e.store.sources(user)
        assertEquals(3, sources.size)
        val sa = sources.single { it.locator == "gdrive:id-a" }
        assertEquals("SEGC Equity Audit Evaluation Report.pdf", sa.title)
        val d = requireNotNull(sa.drive)
        assertEquals("id-a", d.fileId); assertEquals("https://drive.google.com/file/d/id-a/view?usp=drivesdk", d.webViewLink); assertEquals("7", d.version)
        assertEquals(md5(a), d.md5); assertEquals("rev-7", d.headRevisionId); assertEquals("0AShared", d.driveId); assertEquals(folderId, d.folderId)
        assertEquals("SEGC Equity Audit Evaluation Report.pdf", d.fileName); assertTrue(d.modifiedTime!!.startsWith("2026-10-11"))
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(a).joinToString("") { "%02x".format(it) }, sa.fileSha256)
        val chunks = e.store.chunks(user, sa.uid)
        assertTrue(chunks.isNotEmpty() && chunks.all { it.page != null })
        report.files.forEach { r -> assertTrue(r.pageCount!! >= 1 && r.chunkCount!! >= 1 && r.readMode == "full" && r.elapsedMs!! >= 0, r.toString()) }
        assertEquals(3, report.created); assertEquals(0, report.unchanged)
    }

    @Test
    fun `an unchanged rerun creates nothing and downloads nothing`() = withShelf { e ->
        e.g.pdf("id-a", "A.pdf", PdfFixtures.board(), folderId); e.g.pdf("id-b", "B.pdf", PdfFixtures.scenarios(), folderId)
        e.shelf.ingest(user)
        val before = graph(e.manager); val downloads = e.g.mediaRequests().size
        val again = e.shelf.ingest(user)
        assertEquals(listOf("unchanged", "unchanged"), again.files.map { it.outcome })
        assertEquals(before, graph(e.manager))
        assertEquals(downloads, e.g.mediaRequests().size, "no file was fetched again")
        assertEquals(0, again.created)
    }

    @Test
    fun `changed content is a new Source and the old one stays, and the same bytes under a new version are not a new Source`() = withShelf { e ->
        e.g.pdf("id-a", "A.pdf", PdfFixtures.board(1), folderId, version = 1)
        e.shelf.ingest(user)
        val first = e.store.sources(user).single()
        // Only the version moved: the bytes are the same.
        e.g.files["id-a"]!!.version = 2
        val same = e.shelf.ingest(user)
        assertEquals("unchanged", same.files.single().outcome)
        assertEquals(1, e.store.sources(user).size)
        // The content changed.
        e.g.files["id-a"]!!.bytes = PdfFixtures.board(2); e.g.files["id-a"]!!.version = 3
        val changed = e.shelf.ingest(user)
        assertEquals("created", changed.files.single().outcome)
        val all = e.store.sources(user)
        assertEquals(2, all.size)
        assertNotEquals(first.uid, changed.files.single().sourceUid)
        assertTrue(all.any { it.uid == first.uid && it.drive!!.version == "1" }, "the old Source stays, with the version it was read at")
        assertEquals(setOf("1", "3"), all.map { it.drive!!.version }.toSet())
        // And the new content again changes nothing.
        val before = graph(e.manager)
        assertEquals("unchanged", e.shelf.ingest(user).files.single().outcome); assertEquals(before, graph(e.manager))
    }

    @Test
    fun `the folder is the boundary and only PDFs are read, with the rest reported as skipped`() = withShelf { e ->
        e.g.folder("OTHER-FOLDER"); e.g.pdf("outside", "Outside.pdf", PdfFixtures.board(), "OTHER-FOLDER")
        e.g.pdf("id-a", "A.pdf", PdfFixtures.board(), folderId)
        e.g.files["doc"] = FakeGoogle.Entry("doc", "Notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ByteArray(10), parents = mutableListOf(folderId))
        e.g.folder("SUB").parents += folderId
        val report = e.shelf.ingest(user)
        assertEquals(1, e.store.sources(user).size)
        assertEquals(setOf("created", "skipped"), report.files.map { it.outcome }.toSet())
        assertEquals(2, report.skipped)
        assertTrue(report.files.filter { it.outcome == "skipped" }.all { it.reason!!.isNotBlank() })
        assertTrue(e.g.mediaRequests().none { it.requestUrl!!.encodedPath.endsWith("/outside") || it.requestUrl!!.encodedPath.endsWith("/doc") })
    }

    @Test
    fun `one file that cannot be fetched or read does not stop the others, and is reported with a reason and nothing written for it`() = withShelf { e ->
        e.g.pdf("id-a", "A.pdf", PdfFixtures.board(), folderId); e.g.pdf("id-b", "Corrupt.pdf", PdfFixtures.scenarios(), folderId); e.g.pdf("id-c", "NotPdf.pdf", "not a pdf at all".toByteArray(), folderId)
        e.g.corrupt += "id-b"
        val report = e.shelf.ingest(user)
        assertEquals(mapOf("A.pdf" to "created", "Corrupt.pdf" to "failed", "NotPdf.pdf" to "failed"), report.files.associate { it.name to it.outcome })
        assertEquals(1, e.store.sources(user).size)
        assertTrue(report.files.filter { it.outcome == "failed" }.all { it.reason!!.isNotBlank() })
        assertEquals(2, report.failed)
        // Once the file is fixed, the next run picks it up.
        e.g.corrupt.clear()
        assertEquals("created", e.shelf.ingest(user).files.single { it.name == "Corrupt.pdf" }.outcome)
    }

    @Test
    fun `credentials, tokens and file contents never reach the logs`() = withShelf { e ->
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            e.g.pdf("id-a", "A.pdf", PdfFixtures.board(), folderId); e.g.corrupt += "id-a"
            e.shelf.ingest(user); e.g.corrupt.clear(); e.shelf.ingest(user); e.shelf.ingest(user)
        } finally { root.detachAppender(appender) }
        val text = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        for (secret in TestKeys.leakMarkers + listOf("ACCESS-", "Bearer", "Background", "Student enrollment")) assertFalse(text.contains(secret), "log contains $secret")
        assertTrue(appender.list.any { it.formattedMessage.startsWith("drive:") }, "the run is logged, with counts and ids")
    }

    @Test
    fun `nothing but GET reaches Drive in a whole run, and the token endpoint is the only other call`() = withShelf { e ->
        e.g.pdf("id-a", "A.pdf", PdfFixtures.board(), folderId)
        e.shelf.ingest(user); e.shelf.ingest(user)
        assertTrue(e.g.requests.all { (it.method == "GET" && it.requestUrl!!.encodedPath.startsWith("/drive/v3/files")) || (it.method == "POST" && it.requestUrl!!.encodedPath == "/token") })
        assertTrue(e.g.requests.filter { it.method == "POST" }.size <= 1)
    }
}
