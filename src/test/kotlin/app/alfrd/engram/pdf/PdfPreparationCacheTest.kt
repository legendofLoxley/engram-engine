package app.alfrd.engram.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files

class PdfPreparationCacheTest {
    private class Counting(val ocr: OcrEngine? = null) {
        var runs = 0
        val extractor = object : PdfPreparer {
            override val ocrId = ocr?.id ?: "none"
            override fun prepare(bytes: ByteArray): PreparedPdf { runs++; return PdfExtractor(ocr).prepare(bytes) }
        }
    }

    private fun dir() = Files.createTempDirectory("pdf-cache-").toFile()

    @Test
    fun `the same file is never prepared twice, also by a new cache object on the same directory`() {
        val d = dir(); val c = Counting()
        val first = PdfPreparationCache(d).getOrPrepare(PdfFixtures.board(), c.extractor)
        val second = PdfPreparationCache(d).getOrPrepare(PdfFixtures.board(), c.extractor)
        assertEquals(1, c.runs)
        assertFalse(first.fromCache); assertTrue(second.fromCache)
        assertEquals(first.prepared, second.prepared)
        d.deleteRecursively()
    }

    @Test
    fun `a changed file is prepared under its own hash`() {
        val d = dir(); val c = Counting()
        val cache = PdfPreparationCache(d)
        val a = cache.getOrPrepare(PdfFixtures.board(1), c.extractor); val b = cache.getOrPrepare(PdfFixtures.board(2), c.extractor)
        assertEquals(2, c.runs); assertNotEquals(a.prepared.sha256, b.prepared.sha256)
        d.deleteRecursively()
    }

    @Test
    fun `an engine that appears later is not hidden behind an entry made without one`() {
        val d = dir()
        val without = Counting(); val with = Counting(object : OcrEngine { override val id = "e1"; override fun recognize(image: BufferedImage, dpi: Int) = OcrResult(emptyList(), 0.0) })
        val cache = PdfPreparationCache(d)
        cache.getOrPrepare(PdfFixtures.board(), without.extractor); cache.getOrPrepare(PdfFixtures.board(), with.extractor)
        assertEquals(1, without.runs); assertEquals(1, with.runs)
        d.deleteRecursively()
    }

    @Test
    fun `a damaged entry is ignored and rewritten, not trusted`() {
        val d = dir(); val c = Counting(); val cache = PdfPreparationCache(d)
        cache.getOrPrepare(PdfFixtures.board(), c.extractor)
        d.listFiles()!!.filter { it.isFile }.forEach { it.writeText("{ not json") }
        val again = cache.getOrPrepare(PdfFixtures.board(), c.extractor)
        assertEquals(2, c.runs); assertFalse(again.fromCache)
        assertTrue(cache.getOrPrepare(PdfFixtures.board(), c.extractor).fromCache)
        d.deleteRecursively()
    }

    @Test
    fun `no directory means no cache and no files`() {
        val c = Counting()
        PdfPreparationCache(null).getOrPrepare(PdfFixtures.board(), c.extractor); PdfPreparationCache(null).getOrPrepare(PdfFixtures.board(), c.extractor)
        assertEquals(2, c.runs)
    }
}
