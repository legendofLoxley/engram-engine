package app.alfrd.engram.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/**
 * PDFs built in the test, so nothing real is committed. The "board" fixture reproduces the shape of the PPS Board comparison:
 * a ruled table (Status quo 48 percent, Scenario A 80 percent, Scenario B 73 percent) above two narrative boxes whose text is
 * written to the content stream in the order B, then A, so plain text extraction returns them swapped relative to the page.
 */
object PdfFixtures {
    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    const val PAGE_H = 792f

    class Pg(val doc: PDDocument, val cs: PDPageContentStream) {
        /** [y] is measured from the top of the page, like the extractor reports. */
        fun text(x: Float, y: Float, s: String, size: Float = 10f, isBold: Boolean = false) {
            cs.beginText(); cs.setFont(if (isBold) bold else regular, size); cs.newLineAtOffset(x, PAGE_H - y); cs.showText(s); cs.endText()
        }
        fun lines(x: Float, y: Float, ls: List<String>, size: Float = 10f) = ls.forEachIndexed { i, l -> text(x, y + i * (size + 3), l, size) }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) { cs.moveTo(x0, PAGE_H - y0); cs.lineTo(x1, PAGE_H - y1); cs.stroke() }
        fun box(x: Float, y: Float, w: Float, h: Float) { cs.addRect(x, PAGE_H - y - h, w, h); cs.stroke() }
        /** A ruled grid: [xs] column edges, [ys] row edges (top-down); [cells] by row. */
        fun grid(xs: List<Float>, ys: List<Float>, cells: List<List<String>>) {
            ys.forEach { line(xs.first(), it, xs.last(), it) }
            xs.forEach { line(it, ys.first(), it, ys.last()) }
            cells.forEachIndexed { r, row -> row.forEachIndexed { c, t -> text(xs[c] + 4, ys[r] + 14, t, 9f) } }
        }
    }

    fun build(pages: List<(Pg) -> Unit>, title: String? = null): ByteArray {
        PDDocument().use { doc ->
            title?.let { doc.documentInformation.title = it }
            for (draw in pages) {
                val page = PDPage(PDRectangle.LETTER); doc.addPage(page)
                PDPageContentStream(doc, page).use { cs -> draw(Pg(doc, cs)) }
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    /** Page 1 is a cover with no number; the printed numbers of pages 2 and 3 are 1 and 2. */
    fun board(revision: Int = 1): ByteArray = memo["board$revision"] ?: boardBytes(revision).also { memo["board$revision"] = it }

    /** PDFBox stamps ids and dates, so the same fixture built twice differs; tests that need "the same file" get the same bytes. */
    private val memo = HashMap<String, ByteArray>()

    private fun boardBytes(revision: Int): ByteArray = build(listOf(
        { p -> p.text(72f, 200f, "Portland Public Schools", 24f, true); p.text(72f, 240f, "Comparison of Proposals A and B", 14f) },
        { p ->
            p.text(72f, 90f, "Background", 16f, true)
            p.lines(72f, 120f, listOf("The Board asked staff to compare two proposals for rightsizing.", "Both proposals are projected to 2031-32. Revision $revision."))
            p.text(300f, 740f, "1")
        },
        { p ->
            p.text(72f, 90f, "Student enrollment outcomes", 16f, true)
            p.grid(
                listOf(72f, 232f, 332f, 432f, 540f), listOf(120f, 142f, 164f, 186f, 208f),
                listOf(
                    listOf("Measure", "Status quo", "Scenario A", "Scenario B"),
                    listOf("Students in sustainable schools", "48%", "80%", "73%"),
                    listOf("School closures", "0", "14", "11"),
                    listOf("Boundary changes", "0", "38", "34"),
                ),
            )
            // Written B first, then A: stream order is the reverse of the page.
            p.box(310f, 260f, 230f, 90f); p.lines(318f, 278f, listOf("Scenario B closes 11 schools.", "73 percent of students would attend", "a sustainable school."))
            p.box(72f, 260f, 230f, 90f); p.lines(80f, 278f, listOf("Scenario A closes 14 schools.", "80 percent of students would attend", "a sustainable school."))
            p.text(300f, 740f, "2")
        },
    ), title = "Board comparison fixture")

    /** One page with text and one page that is only a picture of text. */
    fun withScannedPage(): ByteArray = memo["scan"] ?: scanBytes().also { memo["scan"] = it }

    private fun scanBytes(): ByteArray = build(listOf(
        { p -> p.text(72f, 100f, "Cover page with a real text layer and enough words to count as readable text.", 11f); p.text(300f, 740f, "1") },
        { p ->
            val img = BufferedImage(1700, 2200, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.WHITE; g.fillRect(0, 0, 1700, 2200); g.color = Color.BLACK; g.font = Font("SansSerif", Font.PLAIN, 54)
            g.drawString("Scanned appendix page", 200, 400); g.drawString("The committee approved the transportation budget", 200, 520)
            g.drawString("after reviewing the enrollment forecast.", 200, 600); g.dispose()
            p.cs.drawImage(LosslessFactory.createFromImage(p.doc, img), 0f, 0f, 612f, 792f)
        },
    ))

    fun blankPage(): ByteArray = build(listOf({ p -> p.text(72f, 100f, "A page with text that can be read from the layer, so it is not blank.", 11f) }, { _ -> }))
}
