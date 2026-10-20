package app.alfrd.engram.pdf

import app.alfrd.engram.db.SourceChunker
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.MessageDigest

/**
 * Reads a PDF into page-located chunks. See docs/pdf-source-extraction.md for the decisions; in short:
 *
 * - **Text layer first, per page.** Words come with positions; the page is laid out by position ([PageLayout]), not by the order the file
 *   writes its text.
 * - **OCR only where the text layer is unusable** (fewer than [MIN_LETTERS] letters and digits, or mostly unreadable characters), and only
 *   if an [OcrEngine] is available. A page that yields nothing either way is `unread`, which makes the Source `partial`.
 * - **Tables are their own chunks**, with rows and cells kept, in reading order among the text chunks of their page. Their words are not
 *   repeated in the narrative.
 * - **A chunk never crosses a page.** Each carries its physical page, the printed page number where two or more pages corroborate it, and
 *   the section (the last heading before it, carried across pages).
 */
class PdfExtractor(private val ocr: OcrEngine? = null) : PdfPreparer {
    override val ocrId: String get() = ocr?.id ?: "none"

    private class RawPage(
        val number: Int, val height: Double, var words: List<Word>, val rulings: List<Ruling>, val path: String, val ocrConfidence: Double?, val note: String?,
        var candidate: PageNumberCandidate? = null,
    )

    override fun prepare(bytes: ByteArray): PreparedPdf {
        require(bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-") { "not a PDF file" }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val doc = try { Loader.loadPDF(bytes) } catch (e: IOException) { throw IllegalArgumentException("not a readable PDF file", e) }
        doc.use { d ->
            val renderer = PDFRenderer(d)
            val raws = (1..d.numberOfPages).map { readPage(d, renderer, it) }
            val docBody = PageLayout.bodySize(raws.filter { it.path == "textlayer" }.flatMap { it.words })
            raws.forEach { it.candidate = PrintedPage.candidate(it.words, it.height) }
            val printed = PrintedPage.corroborate(raws.filter { it.candidate != null }.associate { it.number to it.candidate!! })
            printed.forEach { (n, c) -> raws[n - 1].words = raws[n - 1].words - c.words.toSet() }

            val pages = ArrayList<PagePrep>(); val chunks = ArrayList<PreparedChunk>()
            var section: String? = null
            for (raw in raws) {
                val label = printed[raw.number]?.printed
                val body = if (raw.path == "ocr") PageLayout.bodySize(raw.words) else docBody
                val (made, tables, sectionAfter) = chunksOf(raw, label, body, section)
                section = sectionAfter
                chunks += made
                pages += PagePrep(raw.number, label, raw.path, raw.ocrConfidence, tables, raw.words.sumOf { it.text.length }, raw.note)
            }
            val unread = pages.count { it.path == "unread" }
            val readMode = when { unread > 0 -> "partial"; pages.any { it.path == "ocr" } -> "ocr"; else -> "full" }
            val title = d.documentInformation?.title?.trim().orEmpty()
            return PreparedPdf(sha, title, d.numberOfPages, pages, chunks, readMode, ocrId, VERSION)
        }
    }

    private fun readPage(doc: PDDocument, renderer: PDFRenderer, number: Int): RawPage {
        val page = doc.getPage(number - 1)
        val height = page.cropBox.height.toDouble()
        val words = textLayerWords(doc, number)
        val rulings = try { RulingCollector(page).collect() } catch (e: Exception) { logger.warn("pdf: could not read the lines of page {} ({})", number, e.javaClass.simpleName); emptyList() }
        val letters = words.sumOf { w -> w.text.count { it.isLetterOrDigit() } }
        if (letters >= MIN_LETTERS && !mostlyUnreadable(words)) return RawPage(number, height, words, rulings, "textlayer", null, null)

        val engine = ocr
        val shortLayer = if (letters >= 3) words else emptyList()
        if (engine == null) {
            return if (shortLayer.isNotEmpty()) RawPage(number, height, shortLayer, rulings, "textlayer", null, "short text layer; no OCR engine available")
            else RawPage(number, height, emptyList(), emptyList(), "unread", null, "no usable text layer and no OCR engine available")
        }
        return try {
            val image = renderer.renderImageWithDPI(number - 1, OCR_DPI.toFloat())
            val result = engine.recognize(image, OCR_DPI)
            val scale = 72.0 / OCR_DPI
            val ocrWords = result.words.filter { it.text.isNotBlank() }.map { w ->
                Word(w.text, Box(w.box.x0 * scale, w.box.y0 * scale, w.box.x1 * scale, w.box.y1 * scale), w.box.height * scale * 0.8, false)
            }
            val ocrLetters = ocrWords.sumOf { w -> w.text.count { it.isLetterOrDigit() } }
            when {
                ocrLetters > letters -> RawPage(number, height, ocrWords, emptyList(), "ocr", result.confidence, null)
                shortLayer.isNotEmpty() -> RawPage(number, height, shortLayer, rulings, "textlayer", null, "short text layer; OCR found no more")
                else -> RawPage(number, height, emptyList(), emptyList(), "unread", null, "OCR found no text")
            }
        } catch (e: Exception) {
            logger.warn("pdf: OCR failed on page {} ({})", number, e.javaClass.simpleName)
            if (shortLayer.isNotEmpty()) RawPage(number, height, shortLayer, rulings, "textlayer", null, "short text layer; OCR failed")
            else RawPage(number, height, emptyList(), emptyList(), "unread", null, "OCR failed")
        }
    }

    private fun mostlyUnreadable(words: List<Word>): Boolean {
        val chars = words.sumOf { it.text.length }
        if (chars == 0) return false
        return words.sumOf { w -> w.text.count { it == '�' || it in ''..'' || it.isISOControl() } } * 100 / chars > 20
    }

    /**
     * The words of a page with their boxes, built from the individual glyph positions rather than from PDFBox's own word grouping: a
     * designed page can write several columns' lines as one run with wide gaps inside it, and PDFBox's grouping splits and joins words
     * unpredictably on overlapping or scaled lines. Glyphs are grouped into lines by baseline and cut into words at spaces and at gaps
     * wider than half the glyph height.
     */
    internal fun textLayerWords(doc: PDDocument, number: Int): List<Word> {
        val glyphs = ArrayList<TextPosition>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) { glyphs += text }
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {}
        }
        stripper.startPage = number; stripper.endPage = number
        stripper.getText(doc)
        return wordsOf(glyphs)
    }

    private class Glyph(val ch: String, val x0: Double, val x1: Double, val base: Double, val h: Double, val bold: Boolean)

    private fun wordsOf(positions: List<TextPosition>): List<Word> {
        val glyphs = positions.mapNotNull { p ->
            val ch = p.unicode ?: return@mapNotNull null
            // fontSizeInPt ignores a scaling applied to the whole page content (a file can write 10 pt text as 46), so sizes come from
            // the measured glyph height, which includes it.
            val h = p.heightDir.toDouble().takeIf { it > 0 } ?: (p.fontSizeInPt * 0.6)
            val bold = p.font?.name?.contains("bold", ignoreCase = true) == true || (p.font?.fontDescriptor?.fontWeight ?: 0f) >= 700f
            Glyph(ch, p.xDirAdj.toDouble(), (p.xDirAdj + p.widthDirAdj).toDouble(), p.yDirAdj.toDouble(), h, bold)
        }.sortedWith(compareBy({ it.base }, { it.x0 }))
        val lines = ArrayList<MutableList<Glyph>>()
        for (g in glyphs) {
            val line = lines.lastOrNull()
            if (line != null && abs(line.first().base - g.base) <= 0.7 * min(g.h, line.first().h)) line += g else lines += mutableListOf(g)
        }
        val out = ArrayList<Word>()
        for (line in lines) {
            var cur = ArrayList<Glyph>()
            fun flush() {
                val text = cur.joinToString("") { it.ch }.trim()
                if (text.isNotEmpty()) {
                    val base = cur.maxOf { it.base }; val h = cur.maxOf { it.h }
                    out += Word(text, Box(cur.minOf { it.x0 }, base - h, cur.maxOf { it.x1 }, base), h / 0.6, cur.all { it.bold })
                }
                cur = ArrayList()
            }
            for (g in line.sortedBy { it.x0 }) {
                if (g.ch.isBlank()) { flush(); continue }
                val prev = cur.lastOrNull()
                if (prev != null && g.x0 - prev.x1 > 0.5 * max(g.h, prev.h)) flush()
                cur += g
            }
            flush()
        }
        return out
    }

    private data class PageChunks(val chunks: List<PreparedChunk>, val tables: Int, val section: String?)

    private fun chunksOf(raw: RawPage, printed: String?, bodySize: Double, sectionIn: String?): PageChunks {
        if (raw.words.isEmpty()) return PageChunks(emptyList(), 0, sectionIn)
        // Tables first: their words leave the narrative. Ruled tables where lines are drawn; aligned columns of numbers elsewhere.
        val tables = ArrayList<Pair<PdfTable, List<Word>>>()
        if (raw.path == "textlayer") {
            tables += TableDetector.ruled(raw.rulings, raw.words)
            val taken = tables.flatMap { it.second }.toSet()
            val afterRuled = raw.words.filter { it !in taken }
            tables += TableDetector.numericGrid(afterRuled)
            val taken2 = tables.flatMap { it.second }.toSet()
            tables += TableDetector.aligned(raw.words.filter { it !in taken2 })
        }
        val taken = tables.flatMap { it.second }.toSet()
        val blocks = PageLayout.blocks(raw.words.filter { it !in taken }, bodySize)
            .map { b -> b.copy(heading = PageLayout.isHeading(b.size, b.bold, b.lines, bodySize)) }
        val items = PageLayout.order(blocks + tables.map { (t, _) -> LayoutBlock(t.box, emptyList(), bodySize, false, false, t) })

        val out = ArrayList<PreparedChunk>()
        var section = sectionIn
        var tableNo = 0
        var run = ArrayList<LayoutBlock>()
        fun flushRun() {
            if (run.isEmpty()) return
            val (made, after) = textChunks(run, raw, printed, section)
            out += made; section = after; run = ArrayList()
        }
        for (item in items) {
            val table = item.table
            if (table == null) { run += item; continue }
            // Headings directly above a table are its caption, not a chunk of their own.
            val trailing = run.takeLastWhile { it.heading }
            run = ArrayList(run.dropLast(trailing.size))
            flushRun()
            trailing.lastOrNull()?.let { section = it.text }
            val caption = trailing.joinToString(" ") { it.text }.ifBlank { null }
            tableNo++
            val rendered = table.rows.joinToString("\n") { r -> r.joinToString(" | ") }
            out += PreparedChunk(
                "table", raw.number, printed, section, listOfNotNull(caption, rendered).joinToString("\n"), table.box, table, emptyList(),
                raw.path, raw.ocrConfidence, locationOf(raw, printed, section, "table $tableNo"),
            )
        }
        flushRun()
        return PageChunks(out, tableNo, section)
    }

    /** Packs [blocks] into chunks of at most [SourceChunker.MAX_CHARS]; returns them and the section in force after the last block. */
    private fun textChunks(blocks: List<LayoutBlock>, raw: RawPage, printed: String?, sectionIn: String?): Pair<List<PreparedChunk>, String?> {
        class Piece(val text: String, val blocks: List<TextBlock>, val section: String?)
        val pieces = ArrayList<Piece>()
        var section = sectionIn
        var text = StringBuilder(); var held = ArrayList<TextBlock>(); var pieceSection = sectionIn
        fun flush() { if (text.isNotBlank()) pieces += Piece(text.toString().trim(), held, pieceSection); text = StringBuilder(); held = ArrayList() }
        for (b in blocks) {
            if (b.heading) section = b.text
            val t = b.text
            if (t.length > SourceChunker.MAX_CHARS) {
                flush(); pieceSection = section
                SourceChunker.split(t).forEach { part -> pieces += Piece(part, listOf(TextBlock(b.box, part)), section) }
                continue
            }
            if (text.isNotEmpty() && text.length + 2 + t.length > SourceChunker.MAX_CHARS) { flush() }
            if (text.isEmpty()) pieceSection = section
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(t); held += TextBlock(b.box, t)
        }
        flush()
        val chunks = pieces.mapIndexed { i, p ->
            val part = if (pieces.size > 1) "part ${i + 1} of ${pieces.size}" else null
            PreparedChunk(
                "text", raw.number, printed, p.section, p.text, p.blocks.map { it.box }.takeIf { it.isNotEmpty() }?.reduce(Box::union), null, p.blocks,
                raw.path, raw.ocrConfidence, locationOf(raw, printed, p.section, part),
            )
        }
        return chunks to section
    }

    private fun locationOf(raw: RawPage, printed: String?, section: String?, part: String?): String = buildString {
        append(
            when {
                printed == null -> "pdf p. ${raw.number} (no printed number)"
                printed == raw.number.toString() -> "p. $printed"
                else -> "p. $printed (pdf p. ${raw.number})"
            },
        )
        part?.let { append(", $it") }
        section?.let { append(", section: ${it.take(60)}") }
        if (raw.path == "ocr") append(", ocr")
    }

    companion object {
        /** Bumped when extraction output changes, so a cache of earlier output is not reused. */
        const val VERSION = 1
        const val MIN_LETTERS = 25
        const val OCR_DPI = 300
        private val logger = LoggerFactory.getLogger(PdfExtractor::class.java)
    }
}
