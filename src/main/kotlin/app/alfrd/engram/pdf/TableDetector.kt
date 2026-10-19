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
                if (numeric * 2 >= body.size) {
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
        a.indices.all { k -> abs(a[k].first().box.x0 - b[k].first().box.x0) <= 6 || abs(a[k].last().box.x1 - b[k].last().box.x1) <= 6 }

    private fun verticalGapOk(prev: List<Word>, next: List<Word>): Boolean =
        next.minOf { it.box.y0 } - prev.maxOf { it.box.y1 } <= 1.6 * max(prev.maxOf { it.size }, 1.0)

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
