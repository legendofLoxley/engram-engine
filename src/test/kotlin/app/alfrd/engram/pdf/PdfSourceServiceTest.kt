package app.alfrd.engram.pdf

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SourceStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.security.MessageDigest

class PdfSourceServiceTest {
    private val user = DebugConverseService.resolveUserId("pdf-service-1")

    private fun withService(ocr: OcrEngine? = null, block: (PdfSourceService, SourceStore, DatabaseManager) -> Unit) {
        val dir = Files.createTempDirectory("engram-pdf-").toFile()
        val manager = DatabaseManager(java.io.File(dir, "db").path)
        try {
            val db = manager.getDatabase(); SchemaBootstrap.bootstrap(db); DebugConverseService.ensureSyntheticUser(db, user)
            val store = SourceStore(db)
            block(PdfSourceService(store, PdfExtractor(ocr), PdfPreparationCache(java.io.File(dir, "cache"))), store, manager)
        } finally { manager.close(); dir.deleteRecursively() }
    }

    private fun count(m: DatabaseManager, type: String) =
        m.getDatabase().query("sql", "SELECT count(*) AS n FROM `$type`").use { it.next().getProperty<Number>("n").toLong() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `a pdf commits as one Source whose hash is the SHA-256 of the file, with a chunk per page part and table`() = withService { svc, store, m ->
        val bytes = PdfFixtures.board()
        val r = svc.commit(user, bytes, "file:board.pdf")
        assertTrue(r.created)
        val s = store.sources(user).single()
        assertEquals(sha(bytes), s.fileSha256); assertEquals(sha(bytes), s.contentHash)
        assertEquals("full", s.readMode); assertEquals("document", s.type); assertEquals("file:board.pdf", s.locator); assertEquals(3, s.pageCount)
        assertEquals("Board comparison fixture", s.title)
        val chunks = store.chunks(user, s.uid)
        assertEquals(r.chunksCreated, chunks.size)
        assertEquals(chunks.indices.toList(), chunks.map { it.ordinal })
        assertTrue(chunks.all { it.page != null && it.hash.length == 64 && it.extractor == "textlayer" })
        assertEquals(setOf("text", "table"), chunks.map { it.kind }.toSet())
        assertEquals(1L, count(m, "Source"))
        val table = chunks.single { it.kind == "table" }
        assertEquals("2", table.printedPage); assertEquals(3, table.page)
        assertTrue(table.location.contains("table"), table.location)
        assertTrue(requireNotNull(table.table).contains("\"80%\""))
        assertTrue(chunks.filter { it.page == 3 }.all { it.location.contains("p. 2") }, chunks.map { it.location }.toString())
    }

    @Test
    fun `committing the same pdf twice adds nothing, and the second preparation comes from the cache`() = withService { svc, _, m ->
        val bytes = PdfFixtures.board()
        val first = svc.commit(user, bytes, "file:board.pdf")
        val before = listOf("Source", "Chunk", "CONTAINS", "TRUSTS").map { count(m, it) }
        val again = svc.commit(user, bytes, "file:board.pdf")
        assertFalse(again.created); assertEquals(0, again.chunksCreated); assertEquals(first.sourceUid, again.sourceUid)
        assertTrue(again.fromCache)
        assertEquals(before, listOf("Source", "Chunk", "CONTAINS", "TRUSTS").map { count(m, it) })
    }

    @Test
    fun `a changed file is a new Source and the old one stays`() = withService { svc, store, m ->
        val a = svc.commit(user, PdfFixtures.board(1), "file:board.pdf"); val b = svc.commit(user, PdfFixtures.board(2), "file:board.pdf")
        assertTrue(a.created && b.created); assertNotEquals(a.sourceUid, b.sourceUid)
        assertEquals(2, store.sources(user).size)
        assertEquals(2L, count(m, "Source")); assertTrue(store.chunks(user, a.sourceUid).isNotEmpty() && store.chunks(user, b.sourceUid).isNotEmpty())
    }

    @Test
    fun `the same bytes under another locator are a second Source`() = withService { svc, store, _ ->
        svc.commit(user, PdfFixtures.board(), "file:one.pdf"); svc.commit(user, PdfFixtures.board(), "file:two.pdf")
        assertEquals(2, store.sources(user).size)
    }

    @Test
    fun `an ocr page records the ocr read mode on the Source and the confidence on the chunk`() = withService(object : OcrEngine {
        override val id = "fake"
        override fun recognize(image: BufferedImage, dpi: Int) = OcrResult(listOf(OcrWord("Scanned", Box(100.0, 200.0, 300.0, 240.0)), OcrWord("appendix", Box(320.0, 200.0, 500.0, 240.0))), 77.5)
    }) { svc, store, _ ->
        svc.commit(user, PdfFixtures.withScannedPage(), "file:scan.pdf")
        val s = store.sources(user).single()
        assertEquals("ocr", s.readMode); assertEquals(1, s.ocrPages); assertEquals(0, s.unreadPages)
        val c = store.chunks(user, s.uid).single { it.page == 2 }
        assertEquals("ocr", c.extractor); assertEquals(77.5, c.ocrConfidence)
        assertTrue(c.location.contains("ocr"), c.location)
    }

    @Test
    fun `without OCR the Source is partial and says how many pages were not read`() = withService { svc, store, _ ->
        svc.commit(user, PdfFixtures.withScannedPage(), "file:scan.pdf")
        val s = store.sources(user).single()
        assertEquals("partial", s.readMode); assertEquals(1, s.unreadPages); assertEquals(0, s.ocrPages)
    }

    @Test
    fun `a pdf with nothing readable is refused and writes nothing`() = withService { svc, _, m ->
        val e = org.junit.jupiter.api.assertThrows<IllegalArgumentException> { svc.commit(user, PdfFixtures.build(listOf({ _ -> })), "file:empty.pdf") }
        assertTrue(e.message!!.contains("nothing"))
        assertEquals(0L, count(m, "Source"))
    }
}
