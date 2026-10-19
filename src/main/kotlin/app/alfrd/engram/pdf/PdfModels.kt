package app.alfrd.engram.pdf

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** A rectangle on a page in points, origin at the top left, y growing downward (the way a reader looks at the page). */
@Serializable
data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    val cx get() = (x0 + x1) / 2
    val cy get() = (y0 + y1) / 2
    fun union(o: Box) = Box(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))
    fun containsCenterOf(o: Box, slack: Double = 0.0) = o.cx >= x0 - slack && o.cx <= x1 + slack && o.cy >= y0 - slack && o.cy <= y1 + slack
}

/** A run of text with a box, as it sits on the page: one paragraph or box, lines joined by newlines. */
@Serializable
data class TextBlock(val box: Box, val text: String)

/** A table as rows of cells, row 0 first. [method] is `ruled` (cells bounded by drawn lines) or `aligned` (columns of text lining up). */
@Serializable
data class PdfTable(val box: Box, val rows: List<List<String>>, val method: String)

/**
 * One chunk of a prepared PDF. [kind] `text` is narrative in page order, [kind] `table` is one table. [page] is the physical page
 * (1-based), [printedPage] the number printed on it (null when none could be corroborated). [extractor] is how the page was read:
 * `textlayer`, or `ocr` with the engine's own mean word [ocrConfidence] (0 to 100), passed through and not interpreted.
 */
@Serializable
data class PreparedChunk(
    val kind: String,
    val page: Int,
    val printedPage: String?,
    val section: String?,
    val text: String,
    val box: Box? = null,
    val table: PdfTable? = null,
    val blocks: List<TextBlock> = emptyList(),
    val extractor: String = "textlayer",
    val ocrConfidence: Double? = null,
    val location: String = "",
)

/** What happened to one page: `path` is `textlayer`, `ocr` or `unread` (nothing could be read; [note] says why). */
@Serializable
data class PagePrep(
    val page: Int, val printedPage: String?, val path: String, val ocrConfidence: Double?, val tables: Int, val chars: Int, val note: String? = null,
)

@Serializable
data class PreparedPdf(
    val sha256: String,
    val title: String,
    val pageCount: Int,
    val pages: List<PagePrep>,
    val chunks: List<PreparedChunk>,
    /** `full` every page read from its text layer; `ocr` all pages read, at least one by OCR; `partial` at least one page not read. */
    val readMode: String,
    val ocrEngine: String,
    val extractorVersion: Int,
) {
    val ocrPages get() = pages.count { it.path == "ocr" }
    val unreadPages get() = pages.count { it.path == "unread" }
}

/** Anything that turns the bytes of a PDF into a [PreparedPdf]; [ocrId] names the OCR engine in use so a cache can tell runs apart. */
interface PdfPreparer {
    val ocrId: String
    fun prepare(bytes: ByteArray): PreparedPdf
}
