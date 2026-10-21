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
        /** One content-stream run holding several strings with gaps between them, as designed layouts write a row of column titles. */
        fun run(y: Float, parts: List<String>, at: List<Float>, size: Float = 10f, isBold: Boolean = false) {
            val font = if (isBold) bold else regular
            cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(at[0], PAGE_H - y)
            val items = ArrayList<Any>(); items += parts[0]
            for (i in 1 until parts.size) {
                val prevEnd = at[i - 1] + font.getStringWidth(parts[i - 1]) / 1000f * size
                items += -((at[i] - prevEnd) / size * 1000f); items += parts[i]
            }
            cs.showTextWithPositioning(items.toTypedArray()); cs.endText()
        }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) { cs.moveTo(x0, PAGE_H - y0); cs.lineTo(x1, PAGE_H - y1); cs.stroke() }
        fun box(x: Float, y: Float, w: Float, h: Float) { cs.addRect(x, PAGE_H - y - h, w, h); cs.stroke() }
        /** A ruled grid: [xs] column edges, [ys] row edges (top-down); [cells] by row. */
        fun grid(xs: List<Float>, ys: List<Float>, cells: List<List<String>>) {
            ys.forEach { line(xs.first(), it, xs.last(), it) }
            xs.forEach { line(it, ys.first(), it, ys.last()) }
            cells.forEachIndexed { r, row -> row.forEachIndexed { c, t -> text(xs[c] + 4, ys[r] + 14, t, 9f) } }
        }
    }

    fun build(pages: List<(Pg) -> Unit>, title: String? = null, size: PDRectangle = PDRectangle.LETTER): ByteArray {
        PDDocument().use { doc ->
            title?.let { doc.documentInformation.title = it }
            for (draw in pages) {
                val page = PDPage(size); doc.addPage(page)
                PDPageContentStream(doc, page).use { cs -> draw(Pg(doc, cs)) }
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    /** Page 1 is a cover with no number; the printed numbers of pages 2 and 3 are 1 and 2. */
    fun board(revision: Int = 1): ByteArray = memo["board$revision"] ?: boardBytes(revision).also { memo["board$revision"] = it }

    /** PDFBox stamps ids and dates, so the same fixture built twice differs; tests that need "the same file" get the same bytes. */
    private val memo = HashMap<String, ByteArray>()
    fun memoized(key: String, make: () -> ByteArray): ByteArray = memo[key] ?: make().also { memo[key] = it }

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

/** A designed one-pager like the PPS districtwide scenarios: sidebar, three narrative columns written as shared runs, and a table with only horizontal rules. */
fun PdfFixtures.scenarios(): ByteArray = memoized("scenarios") {
    build(listOf { p ->
        p.text(60f, 60f, "Comparing three scenarios", 22f, true)
        p.lines(40f, 300f, listOf("WELL RESOURCED SCHOOLS", "Consistent access to strong programming.", "SAFE LEARNING ENVIRONMENTS", "Facilities that support safety."), 9f)
        p.text(450f, 60f, "The three scenarios", 20f, true)
        // A small two-line note whose baselines straddle the title's baseline.
        p.text(1020f, 52f, "All figures are projections for the", 8f); p.text(1020f, 64f, "2031 school year unless noted.", 8f)
        p.run(100f, listOf("SCENARIO A", "SCENARIO B", "STATUS QUO"), listOf(450f, 700f, 950f), size = 12f, isBold = true)
        // The first line of every column is one run, the way the original file writes it.
        p.run(120f, listOf("Closures bring about two thirds", "A smaller set brings half", "No schools close and nothing"), listOf(450f, 700f, 950f), size = 9f)
        p.lines(450f, 133f, listOf("of schools up. About 80% of students", "attend a sustainable school."), 9f)
        p.lines(700f, 133f, listOf("up. About 73% of students will", "attend a sustainable school."), 9f)
        p.lines(950f, 133f, listOf("changes. About 48% of students", "attend a sustainable school."), 9f)
        p.text(450f, 230f, "District-wide, side by side", 12f, true)
        val xs = listOf(870f, 1000f, 1125f)
        fun row(y: Float, label: List<String>, vals: List<String>) {
            label.forEachIndexed { i, l -> p.text(456f, y + i * 13, l, 9f, true) }
            vals.forEachIndexed { i, v -> p.text(xs[i] - 8, y + (label.size - 1) * 6.5f, v, 9f, true) }
        }
        p.text(456f, 262f, "Measure", 9f, true)
        listOf("SCENARIO A", "SCENARIO B", "STATUS QUO").forEachIndexed { i, t -> p.text(xs[i] - 30, 262f, t, 9f, true) }
        val rules = listOf(250f, 275f, 315f, 350f, 390f, 440f, 485f)
        rules.forEach { p.line(452f, it, 1170f, it) }
        row(295f, listOf("Schools or sites that would close"), listOf("14", "11", "0"))
        row(335f, listOf("Students who would change schools"), listOf("27%", "24%", "0%"))
        row(375f, listOf("Students attending a school above the threshold"), listOf("80%", "73%", "48%"))
        row(412f, listOf("Historically Underserved Students in", "Sustainable Schools"), listOf("83%", "78%", "46%"))
        row(462f, listOf("Multilingual Learners in Sustainable Schools"), listOf("87%", "77%", "44%"))
    }, size = org.apache.pdfbox.pdmodel.common.PDRectangle(1224f, 792f))
}

/** A table of short text cells with no lines and no figures, then three columns of ordinary prose that is not a table. */
fun PdfFixtures.textTable(): ByteArray = memoized("texttable") {
    build(listOf { p ->
        p.text(72f, 80f, "Table 3.1. Focus group participants by school represented", 10f, true)
        val xs = listOf(72f, 160f, 360f, 470f)
        val rows = listOf(
            listOf("ID", "School Represented", "School Level", "School Type"),
            listOf("P-001-A", "Atkinson Elementary", "Elementary", "Neighborhood"),
            listOf("P-002-B", "Harrison Park Middle School", "Middle School", "Neighborhood"),
            listOf("P-005-C", "Creative Science School at Bridger", "K-8", "Focus Option"),
            listOf("P-006-D", "Arleta Elementary", "Elementary", "Neighborhood"),
        )
        // The first column is centred on x = 110, so the header "ID" and the longer ids start at different places.
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, t -> p.text(if (c == 0) (if (r == 0) 105f else 90f) else xs[c], 110f + r * 24, t, 10f, r == 0) } }
        val prose = listOf(
            listOf("The committee met several times over", "the course of the spring term to", "review the available enrollment data", "and to hear from many families."),
            listOf("Members asked staff for a clearer", "account of how boundaries would", "change under each of the proposals", "before any decision was reached."),
            listOf("Staff agreed to return with a fuller", "set of projections and a plain", "summary that families could read", "without needing a technical guide."),
        )
        prose.forEachIndexed { c, col -> p.lines(72f + c * 190f, 300f, col, 9f) }
    })
}

/** Two parallel columns (A and B) of the same cards, each card a heading with a count at its right edge above a list; the columns' gutter is no wider than the gaps between cards. */
fun PdfFixtures.poster(): ByteArray = memoized("poster") {
    build(listOf { p ->
        p.text(48f, 60f, "What changes under each scenario", 20f, true)
        p.text(48f, 100f, "STATUS QUO", 10f, true); p.text(420f, 100f, "26% of schools above the sustainability threshold on the whole", 10f)
        listOf("A" to 60f, "B" to 640f).forEach { (name, x) ->
            p.text(x, 160f, "SCENARIO $name", 14f, true)
            p.text(x, 190f, if (name == "A") "14 schools close" else "11 schools close", 10f)
            val cards = listOf("SCHOOLS CLOSING" to (if (name == "A") "14" else "11"), "BOUNDARY CHANGES" to (if (name == "A") "38" else "34"))
            cards.forEachIndexed { i, (title, count) ->
                val y = 240f + i * 90
                p.text(x, y, title, 9f, true); p.text(x + 455, y, count, 11f, true)
                p.lines(x, y + 18, listOf("Beach Buckman Creston Irvington James John Lewis Maplewood Marysville Peninsula Roosevelt Sabin Sellwood", "Stephenson Woodmere Abernethy Arleta Astor Atkinson Beaumont Cleary Brentwood Capitol Hill Chief Joseph ${name}${i}"), 9f)
            }
        }
    }, size = org.apache.pdfbox.pdmodel.common.PDRectangle(1224f, 792f))
}
