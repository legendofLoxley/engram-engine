package app.alfrd.engram.pdf

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A drawn line: horizontal when [horizontal], at [pos] (y or x, from the top left) from [from] to [to]. */
internal data class Ruling(val horizontal: Boolean, val pos: Double, val from: Double, val to: Double)

/** Reads the straight lines and thin rectangles a page draws (the rules of a table), in the same top-left coordinates as the words. */
internal class RulingCollector(page: PDPage) : PDFGraphicsStreamEngine(page) {
    private val box = page.cropBox
    private val h = box.height.toDouble()
    private val left = box.lowerLeftX.toDouble()
    private val segments = ArrayList<DoubleArray>()
    private val rects = ArrayList<DoubleArray>()
    private var current = ArrayList<DoubleArray>()
    private var currentRects = ArrayList<DoubleArray>()
    private var at: Point2D? = null
    val pageWidth get() = box.width.toDouble()
    val pageHeight get() = h

    private fun x(p: Point2D) = p.x - left
    private fun y(p: Point2D) = h - (p.y - box.lowerLeftY)

    fun collect(): List<Ruling> {
        processPage(page)
        val out = ArrayList<Ruling>()
        fun line(x0: Double, y0: Double, x1: Double, y1: Double) {
            if (abs(y0 - y1) <= 1.5 && abs(x1 - x0) >= 8) out += Ruling(true, (y0 + y1) / 2, min(x0, x1), max(x0, x1))
            else if (abs(x0 - x1) <= 1.5 && abs(y1 - y0) >= 8) out += Ruling(false, (x0 + x1) / 2, min(y0, y1), max(y0, y1))
        }
        segments.forEach { line(it[0], it[1], it[2], it[3]) }
        for (r in rects) {
            val (x0, y0, x1, y1) = r
            // A page-sized frame is not a table.
            if ((x1 - x0) > 0.92 * pageWidth && (y1 - y0) > 0.92 * h) continue
            when {
                y1 - y0 <= 3 -> line(x0, (y0 + y1) / 2, x1, (y0 + y1) / 2)
                x1 - x0 <= 3 -> line((x0 + x1) / 2, y0, (x0 + x1) / 2, y1)
                else -> { line(x0, y0, x1, y0); line(x0, y1, x1, y1); line(x0, y0, x0, y1); line(x1, y0, x1, y1) }
            }
        }
        return out
    }

    override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
        val xs = listOf(x(p0), x(p1), x(p2), x(p3)); val ys = listOf(y(p0), y(p1), y(p2), y(p3))
        currentRects += doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }
    override fun moveTo(x: Float, y: Float) { at = Point2D.Float(x, y) }
    override fun lineTo(x: Float, y: Float) {
        val from = at; val to = Point2D.Float(x, y)
        if (from != null) current += doubleArrayOf(x(from), y(from), x(to), y(to))
        at = to
    }
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { at = Point2D.Float(x3, y3) }
    override fun getCurrentPoint(): Point2D = at ?: Point2D.Double(0.0, 0.0)
    override fun closePath() {}
    override fun endPath() { current = ArrayList(); currentRects = ArrayList() }
    override fun strokePath() { segments += current; rects += currentRects; endPath() }
    override fun fillPath(windingRule: Int) { rects += currentRects.filter { (it[2] - it[0]) <= 3 || (it[3] - it[1]) <= 3 }; endPath() }
    override fun fillAndStrokePath(windingRule: Int) { segments += current; rects += currentRects; endPath() }
    override fun drawImage(pdImage: PDImage) {}
    override fun clip(windingRule: Int) {}
    override fun shadingFill(shadingName: COSName) {}
}

/**
 * Finds tables on a page and cuts their words into rows and cells.
 *
 * **Ruled**: lines the page draws are grouped into connected sets; a set with at least three distinct horizontal and three distinct
 * vertical lines makes a grid of at least two rows by two columns, and each word goes to the cell its centre is in. A single box
 * (two lines each way) is not a table.
 * **Aligned**: where no lines are drawn, at least three consecutive lines that split into the same number (three or more) of
 * segments whose left edges line up, and whose later cells are mostly numbers, are taken as a table. Narrative in columns is not,
 * because its cells are not numbers.
 */
internal object TableDetector {
    private const val TOL = 3.0

    fun ruled(rulings: List<Ruling>, words: List<Word>): List<Pair<PdfTable, List<Word>>> {
        val comps = components(rulings)
        val out = ArrayList<Pair<PdfTable, List<Word>>>()
        for (c in comps) {
            val ys = cluster(c.filter { it.horizontal }.map { it.pos }); val xs = cluster(c.filter { !it.horizontal }.map { it.pos })
            if (ys.size < 3 || xs.size < 3) continue
            val box = Box(xs.first(), ys.first(), xs.last(), ys.last())
            val inside = words.filter { box.containsCenterOf(it.box, 1.0) }
            val rows = (0 until ys.size - 1).map { r ->
                (0 until xs.size - 1).map { col ->
                    val cell = Box(xs[col], ys[r], xs[col + 1], ys[r + 1])
                    cellText(inside.filter { cell.containsCenterOf(it.box) })
                }
            }
            if (rows.sumOf { r -> r.count { it.isNotBlank() } } < 4) continue
            out += PdfTable(box, rows, "ruled") to inside
        }
        return out
    }

    private val NUMBER = Regex("""^[(\-–−]?[$€£]?\d[\d,]*(\.\d+)?[%)]?[KMB]?$""")

    /**
     * A table with no drawn grid: figures standing in the same columns, line after line (a designed page's side-by-side comparison, often
     * with only a rule between rows). Rows are the baselines that carry two or more figures; a run of at least three such rows whose figures
     * stand in the same columns is a table. Each row's label is the text left of the first column in that row's band (wrapped labels
     * included), starting at the left edge the labels share, so a sidebar further left is not taken; the header is the text above the
     * first row, set against the columns by position.
     */
    fun numericGrid(words: List<Word>): List<Pair<PdfTable, List<Word>>> {
        val nums = words.filter { NUMBER.matches(it.text) }
        val numRows = PageLayout.lines(nums).filter { r -> r.size >= 2 && r.zipWithNext().all { (a, b) -> b.box.x0 - a.box.x1 > 1.5 * a.size } }
            .sortedBy { it.first().box.y1 }
        val out = ArrayList<Pair<PdfTable, List<Word>>>()
        var i = 0
        while (i < numRows.size) {
            val cols = numRows[i].map { it.box.cx }
            var j = i + 1
            while (j < numRows.size) {
                val prev = numRows[j - 1]; val row = numRows[j]
                val size = max(row.first().size, 1.0)
                if (row.first().box.y1 - prev.first().box.y1 > 6 * size) break
                if (!row.all { w -> cols.any { abs(it - w.box.cx) <= 14 } }) break
                j++
            }
            val built = if (j - i >= 3) buildNumericTable(words, numRows.subList(i, j), cols) else null
            if (built != null) { out += built; i = j } else i++
        }
        return out
    }

    private fun buildNumericTable(words: List<Word>, rows: List<List<Word>>, cols: List<Double>): Pair<PdfTable, List<Word>>? {
        val numeric = rows.flatten().toSet()
        val baselines = rows.map { it.first().box.y1 }
        val gap = baselines.zipWithNext { a, b -> b - a }.sorted().let { it[it.size / 2] }
        val size = rows.first().first().size
        fun band(r: Int) = (if (r == 0) baselines[0] - gap / 2 else (baselines[r - 1] + baselines[r]) / 2) to
            (if (r == rows.lastIndex) baselines[r] + gap / 2 else (baselines[r] + baselines[r + 1]) / 2)
        val firstCol = cols.min()
        val others = words.filter { it !in numeric }
        // Where this table's labels start: the left edge most rows' label text shares.
        val lefts = rows.mapNotNull { row ->
            val onLine = others.filter { abs(it.box.y1 - row.first().box.y1) <= 0.5 * size && it.box.x1 < row.first().box.x0 }.sortedByDescending { it.box.x0 }
            var edge: Word? = null
            for (w in onLine) { if (edge == null || edge.box.x0 - w.box.x1 <= 1.5 * size) edge = w else break }
            edge?.box?.x0
        }
        if (lefts.size * 2 < rows.size) return null
        val left = lefts.sorted()[lefts.size / 2] - 6
        val labelWords = others.filter { it.box.x0 >= left && it.box.x1 <= firstCol + 4 }
        val tableRows = ArrayList<List<String>>(); val used = ArrayList<Word>(); used += numeric
        rows.indices.forEach { r ->
            val (top, bottom) = band(r)
            val mine = labelWords.filter { it.box.cy in top..bottom }
            used += mine
            val label = PageLayout.lines(mine).joinToString(" ") { l -> l.joinToString(" ") { it.text } }
            tableRows += listOf(label) + cols.map { c -> PageLayout.lines(rows[r].filter { abs(it.box.cx - c) <= 14 }).joinToString(" ") { l -> l.joinToString(" ") { it.text } } }
        }
        // The header: the lines directly above the first row, as many as stand at the header's own line pitch (a caption or heading
        // further up is left out), each phrase placed in the label column or the nearest figure column by its position.
        val pitchOfCols = cols.sorted().zipWithNext { a, b -> b - a }.sorted().let { it[it.size / 2] }
        val candidates = PageLayout.lines(others.filter { it.box.cy < baselines[0] - 0.5 * size && it.box.x0 >= left && it.box.x1 <= cols.max() + 0.6 * pitchOfCols && it.size <= 1.5 * size && it !in used })
            .sortedByDescending { it.first().box.y1 }
        val headerLines = ArrayList<List<Word>>()
        for (line in candidates) {
            val base = line.first().box.y1
            val ok = if (headerLines.isEmpty()) baselines[0] - base <= 1.3 * gap
            else headerLines.last().first().box.y1 - base <= 2.0 * max(line.first().size, 1.0)
            if (!ok || headerLines.size >= 5) break
            headerLines += line
        }
        val header = Array(cols.size + 1) { ArrayList<String>() }
        for (line in headerLines.reversed()) for (phrase in PageLayout.segments(line, 1.6)) {
            val cx = phrase.map { it.box }.reduce(Box::union).cx
            val k = cols.indices.minByOrNull { abs(cols[it] - cx) }!!
            val idx = if (cx < firstCol - 0.5 * pitchOfCols) 0 else k + 1
            header[idx] += phrase.joinToString(" ") { it.text }
            used += phrase
        }
        if (header.any { it.isNotEmpty() }) tableRows.add(0, header.map { it.joinToString(" ") })
        val box = used.map { it.box }.reduce(Box::union)
        return PdfTable(box, tableRows, "aligned") to used
    }

    fun aligned(words: List<Word>): List<Pair<PdfTable, List<Word>>> {
        val lines = PageLayout.lines(words)
        val rows = lines.map { l -> PageLayout.segments(l, gutter = 1.6) }
        val out = ArrayList<Pair<PdfTable, List<Word>>>()
        var i = 0
        while (i < rows.size) {
            val n = rows[i].size
            if (n < 3) { i++; continue }
            var j = i + 1
            while (j < rows.size && rows[j].size == n && alignedWith(rows[i], rows[j]) && verticalGapOk(lines[j - 1], lines[j])) j++
            if (j - i >= 3) {
                val body = rows.subList(i, j).flatMap { r -> r.drop(1) }
                val numeric = body.count { seg -> seg.joinToString(" ") { it.text }.any { it.isDigit() } }
                // Mostly figures, or four or more rows of short cells (at most six words each): prose in columns has longer lines.
                val short = j - i >= 4 && rows.subList(i, j).all { r -> r.all { seg -> seg.size <= 6 && seg.sumOf { it.text.length + 1 } <= 46 } }
                if (numeric * 2 >= body.size || short) {
                    val cells = rows.subList(i, j).map { r -> r.map { seg -> seg.joinToString(" ") { it.text } } }
                    val all = lines.subList(i, j).flatten()
                    out += PdfTable(all.map { it.box }.reduce(Box::union), cells, "aligned") to all
                    i = j; continue
                }
            }
            i++
        }
        return out
    }

    private fun alignedWith(a: List<List<Word>>, b: List<List<Word>>): Boolean =
        // A column lines up when the two cells overlap horizontally, or share a left edge, a right edge or a centre (a header is often padded
        // differently from its cells).
        a.indices.all { k ->
            val a0 = a[k].first().box.x0; val a1 = a[k].last().box.x1; val b0 = b[k].first().box.x0; val b1 = b[k].last().box.x1
            min(a1, b1) - max(a0, b0) > 0 || abs(a0 - b0) <= 6 || abs(a1 - b1) <= 6 || abs((a0 + a1) - (b0 + b1)) <= 12
        }

    private fun verticalGapOk(prev: List<Word>, next: List<Word>): Boolean =
        next.minOf { it.box.y0 } - prev.maxOf { it.box.y1 } <= 3.2 * max(prev.maxOf { it.size }, 1.0)

    private fun cellText(ws: List<Word>): String =
        PageLayout.lines(ws).joinToString(" ") { l -> l.joinToString(" ") { it.text } }.trim()

    /** Distinct positions, positions within [TOL] points counted as one. */
    private fun cluster(values: List<Double>): List<Double> {
        val out = ArrayList<Double>()
        for (v in values.sorted()) if (out.isEmpty() || v - out.last() > TOL) out += v
        return out
    }

    /** Rulings grouped into sets of lines that touch or cross. */
    private fun components(rulings: List<Ruling>): List<List<Ruling>> {
        val parent = IntArray(rulings.size) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (a in rulings.indices) for (b in a + 1 until rulings.size) if (touches(rulings[a], rulings[b])) parent[find(a)] = find(b)
        return rulings.indices.groupBy { find(it) }.values.map { g -> g.map { rulings[it] } }
    }

    private fun touches(a: Ruling, b: Ruling): Boolean = when {
        a.horizontal != b.horizontal -> {
            val (h, v) = if (a.horizontal) a to b else b to a
            v.pos >= h.from - TOL && v.pos <= h.to + TOL && h.pos >= v.from - TOL && h.pos <= v.to + TOL
        }
        else -> abs(a.pos - b.pos) <= TOL && a.from <= b.to + TOL && b.from <= a.to + TOL
    }
}
