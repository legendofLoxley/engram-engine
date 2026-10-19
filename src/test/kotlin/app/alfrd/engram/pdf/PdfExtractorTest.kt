package app.alfrd.engram.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class PdfExtractorTest {

    /** Stands in for Tesseract: reads any image as the same two lines, at a stated confidence. */
    private class FakeOcr(val confidence: Double = 91.5) : OcrEngine {
        override val id = "fake-ocr"
        var calls = 0
        override fun recognize(image: BufferedImage, dpi: Int): OcrResult {
            calls++
            val w = image.width.toDouble(); val h = image.height.toDouble()
            return OcrResult(
                listOf(
                    OcrWord("Scanned", Box(0.1 * w, 0.2 * h, 0.2 * w, 0.22 * h)), OcrWord("appendix", Box(0.22 * w, 0.2 * h, 0.35 * w, 0.22 * h)),
                    OcrWord("budget", Box(0.1 * w, 0.25 * h, 0.2 * w, 0.27 * h)),
                ),
                confidence,
            )
        }
    }

    @Test
    fun `plain text extraction returns the narrative boxes swapped, which is the problem being solved`() {
        val plain = Loader.loadPDF(PdfFixtures.board()).use { PDFTextStripper().getText(it) }
        assertTrue(plain.indexOf("Scenario B closes") < plain.indexOf("Scenario A closes"), "the fixture must reproduce the swap in stream order")
    }

    @Test
    fun `chunks carry the pdf page, the printed page, and no printed number is invented for the cover`() {
        val prepared = PdfExtractor().prepare(PdfFixtures.board())
        assertEquals(3, prepared.pageCount)
        assertEquals(listOf(null, "1", "2"), prepared.pages.map { it.printedPage })
        assertEquals(listOf(1, 2, 3), prepared.pages.map { it.page })
        assertTrue(prepared.chunks.all { it.page in 1..3 })
        val onPrinted2 = prepared.chunks.filter { it.printedPage == "2" }
        assertTrue(onPrinted2.isNotEmpty() && onPrinted2.all { it.page == 3 })
        assertEquals("full", prepared.readMode)
        assertTrue(prepared.pages.all { it.path == "textlayer" })
        assertEquals(64, prepared.sha256.length)
    }

    @Test
    fun `the page number in the footer is not part of the text`() {
        val p = PdfExtractor().prepare(PdfFixtures.board())
        val page2 = p.chunks.filter { it.page == 2 }.joinToString("\n") { it.text }
        assertFalse(page2.trim().endsWith("1"), page2)
        assertTrue(page2.contains("Revision 1"))
    }

    @Test
    fun `the section is the last heading seen, and carries onto the following chunks`() {
        val p = PdfExtractor().prepare(PdfFixtures.board())
        assertEquals("Student enrollment outcomes", p.chunks.first { it.page == 3 }.section)
        assertEquals("Background", p.chunks.first { it.page == 2 }.section)
    }

    @Test
    fun `the board table comes out as rows and cells and is its own chunk with its page`() {
        val p = PdfExtractor().prepare(PdfFixtures.board())
        assertEquals(listOf(0, 0, 1), p.pages.map { it.tables })
        val table = p.chunks.single { it.kind == "table" }
        assertEquals(3, table.page); assertEquals("2", table.printedPage)
        val rows = requireNotNull(table.table).rows
        assertEquals(4, rows.size)
        assertEquals(listOf("Measure", "Status quo", "Scenario A", "Scenario B"), rows[0])
        assertEquals(listOf("Students in sustainable schools", "48%", "80%", "73%"), rows[1])
        assertEquals(listOf("Boundary changes", "0", "38", "34"), rows[3])
        assertTrue(table.text.contains("80%"))
        assertNotNull(table.box)
    }

    @Test
    fun `table text is not repeated in the page narrative`() {
        val p = PdfExtractor().prepare(PdfFixtures.board())
        val narrative = p.chunks.filter { it.page == 3 && it.kind == "text" }.joinToString("\n") { it.text }
        assertFalse(narrative.contains("Boundary changes"), narrative)
        assertFalse(narrative.contains("48%"), narrative)
    }

    @Test
    fun `the narrative boxes keep their positions, A left of B, and the page order wins over the stream order`() {
        val p = PdfExtractor().prepare(PdfFixtures.board())
        val blocks = p.chunks.filter { it.page == 3 && it.kind == "text" }.flatMap { it.blocks }
        val a = blocks.single { it.text.startsWith("Scenario A closes") }
        val b = blocks.single { it.text.startsWith("Scenario B closes") }
        assertTrue(a.box.x1 < b.box.x0, "A is left of B: ${a.box} ${b.box}")
        assertTrue(a.text.contains("80 percent") && !a.text.contains("73 percent"))
        assertTrue(b.text.contains("73 percent") && !b.text.contains("80 percent"))
        val joined = p.chunks.filter { it.page == 3 && it.kind == "text" }.joinToString("\n") { it.text }
        assertTrue(joined.indexOf("Scenario A closes") < joined.indexOf("Scenario B closes"))
        // The table says A is 80, and the box beside it agrees.
        assertTrue(requireNotNull(p.chunks.single { it.kind == "table" }.table).rows[1][2] == "80%")
    }

    @Test
    fun `a page with no text layer goes through OCR, and the chunk records ocr and the engine's confidence`() {
        val ocr = FakeOcr(88.25)
        val p = PdfExtractor(ocr).prepare(PdfFixtures.withScannedPage())
        assertEquals(1, ocr.calls)
        assertEquals(listOf("textlayer", "ocr"), p.pages.map { it.path })
        assertEquals("ocr", p.readMode)
        val c = p.chunks.single { it.page == 2 }
        assertEquals("ocr", c.extractor); assertEquals(88.25, c.ocrConfidence)
        assertEquals(88.25, p.pages[1].ocrConfidence)
        assertNull(p.chunks.first { it.page == 1 }.ocrConfidence); assertEquals("textlayer", p.chunks.first { it.page == 1 }.extractor)
        assertTrue(c.text.contains("appendix"))
    }

    @Test
    fun `without an OCR engine the page is recorded as unread and the read mode is partial, not ocr and not full`() {
        val p = PdfExtractor(null).prepare(PdfFixtures.withScannedPage())
        assertEquals(listOf("textlayer", "unread"), p.pages.map { it.path })
        assertEquals("partial", p.readMode)
        assertTrue(p.chunks.none { it.page == 2 })
        assertNotNull(p.pages[1].note)
    }

    @Test
    fun `a blank page is unread even with OCR that finds nothing`() {
        val p = PdfExtractor(object : OcrEngine { override val id = "none-found"; override fun recognize(image: BufferedImage, dpi: Int) = OcrResult(emptyList(), 0.0) })
            .prepare(PdfFixtures.blankPage())
        assertEquals(listOf("textlayer", "unread"), p.pages.map { it.path })
        assertEquals("partial", p.readMode)
    }

    @Test
    fun `real Tesseract reads an image-only page when it is installed`() {
        val tess = TesseractOcr.detect()
        assumeTrue(tess != null, "tesseract is not installed here")
        val p = PdfExtractor(tess).prepare(PdfFixtures.withScannedPage())
        assertEquals("ocr", p.readMode)
        val c = p.chunks.single { it.page == 2 }
        assertEquals("ocr", c.extractor)
        assertTrue(requireNotNull(c.ocrConfidence) > 50.0, "confidence ${c.ocrConfidence}")
        assertTrue(c.text.lowercase().contains("appendix") && c.text.lowercase().contains("budget"), c.text)
    }

    @Test
    fun `the same file prepares the same way twice`() {
        val a = PdfExtractor().prepare(PdfFixtures.board()); val b = PdfExtractor().prepare(PdfFixtures.board())
        assertEquals(a.chunks.map { it.text }, b.chunks.map { it.text })
        assertEquals(a.sha256, b.sha256)
    }

    @Test
    fun `a file that is not a pdf is refused`() {
        val e = org.junit.jupiter.api.assertThrows<IllegalArgumentException> { PdfExtractor().prepare("not a pdf".toByteArray()) }
        assertTrue(e.message!!.contains("PDF"))
    }
}
