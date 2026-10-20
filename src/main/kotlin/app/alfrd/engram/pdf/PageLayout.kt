package app.alfrd.engram.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A word on a page, in points from the top left. [size] is the font size, [bold] whether the font is bold. */
internal data class Word(val text: String, val box: Box, val size: Double, val bold: Boolean)

/** A block of the page with its lines; [heading] says it is set larger or bolder than the body text. */
internal data class LayoutBlock(val box: Box, val lines: List<String>, val size: Double, val bold: Boolean, val heading: Boolean = false, val table: PdfTable? = null) {
    val text get() = lines.joinToString(if (heading) " " else "\n")
}

/**
 * Turns the words of one page into blocks in reading order, from their positions alone. The order in which the PDF happens to write
 * its text is never used: two boxes drawn right to left in the content stream still come out left to right, which is what a reader
 * of the page sees (and what a table beside them says).
 *
 * Words on one baseline form a line; a line is cut into segments where the gap is wider than a column gutter; segments that sit
 * directly below one another and overlap horizontally form a block; blocks are ordered by recursive X-Y cut (split at the widest
 * empty band, top to bottom or left to right, and recurse).
 */
private class Seg(val members: List<Word>) {
    val box = members.map { it.box }.reduce(Box::union)
    val size = members.maxOf { it.size }
    val bold = members.all { it.bold }
    val text = members.joinToString(" ") { it.text }
}

private class Acc(var box: Box, val lines: MutableList<String>, var size: Double, var bold: Boolean, var lastTop: Double, var lastBottom: Double)

internal object PageLayout {

    fun lines(words: List<Word>): List<List<Word>> {
        val sorted = words.sortedWith(compareBy({ it.box.y1 }, { it.box.x0 }))
        val lines = ArrayList<MutableList<Word>>()
        for (w in sorted) {
            val line = lines.lastOrNull()
            // Against the line's first word and the smaller font: a large title must not bridge the baselines of two small lines.
            if (line != null && abs(line.first().box.y1 - w.box.y1) <= 0.45 * min(w.size, line.first().size)) line += w else lines += mutableListOf(w)
        }
        return lines.map { l -> l.sortedBy { it.box.x0 } }
    }

    /** A line cut where the gap between neighbouring words is wider than [gutter] times the font size. */
    fun segments(line: List<Word>, gutter: Double = 2.2): List<List<Word>> {
        val out = ArrayList<MutableList<Word>>()
        for (w in line) {
            val cur = out.lastOrNull()
            if (cur != null && w.box.x0 - cur.last().box.x1 <= gutter * max(w.size, cur.last().size)) cur += w else out += mutableListOf(w)
        }
        return out
    }

    fun blocks(words: List<Word>, bodySize: Double): List<LayoutBlock> {
        val segs = lines(words).flatMap { ln -> segments(ln).map { Seg(it) } }
        val open = ArrayList<Acc>(); val done = ArrayList<Acc>()
        // Line by line, top to bottom: a segment extends the block directly above it, or starts a new one.
        for (s in segs.sortedWith(compareBy({ it.box.y0 }, { it.box.x0 }))) {
            val target = open.filter { a ->
                val gap = s.box.y0 - a.lastBottom
                gap <= 0.9 * max(s.size, a.size) && s.box.y0 > a.lastTop + 0.3 * s.size &&
                    min(s.box.x1, a.box.x1) - max(s.box.x0, a.box.x0) > 0 && sameStyle(s.size, s.bold, a.size, a.bold) &&
                    // A short label or count above a long line is not the first line of that line's paragraph.
                    !(a.lines.size == 1 && a.box.width < 0.3 * s.box.width)
            }.minByOrNull { s.box.y0 - it.lastBottom }
            if (target != null) {
                target.lines += s.text; target.box = target.box.union(s.box); target.lastTop = s.box.y0; target.lastBottom = max(target.lastBottom, s.box.y1)
            } else {
                open += Acc(s.box, mutableListOf(s.text), s.size, s.bold, s.box.y0, s.box.y1)
            }
            // A block that nothing can extend any more is closed, so a long page stays cheap.
            open.filter { s.box.y0 - it.lastBottom > 3 * max(it.size, 1.0) }.also { done += it; open.removeAll(it) }
        }
        done += open
        return done.map { a ->
            val heading = isHeading(a.size, a.bold, a.lines, bodySize)
            LayoutBlock(a.box, a.lines, a.size, a.bold, heading)
        }
    }

    private fun sameStyle(s1: Double, b1: Boolean, s2: Double, b2: Boolean): Boolean =
        abs(s1 - s2) <= 0.12 * max(s1, s2) && b1 == b2

    fun isHeading(size: Double, bold: Boolean, lines: List<String>, body: Double): Boolean {
        val chars = lines.sumOf { it.length }
        if (chars == 0 || chars > 160 || lines.size > 3) return false
        if (size >= body * 1.25) return true
        return bold && size >= body * 0.95 && lines.size == 1 && chars <= 100 && !lines.last().trimEnd().endsWith(".")
    }

    /** Median font size over words (each word counted once): the page's or document's body size. */
    fun bodySize(words: List<Word>): Double {
        if (words.isEmpty()) return 10.0
        val s = words.map { it.size }.sorted()
        return s[s.size / 2]
    }

    /**
     * Reading order. Where a column gutter runs down the region and only a few blocks cross it (a heading or a status line across the top),
     * the crossing blocks are spanners and the bands between them are read column by column: the whole left column, then the whole right one.
     * Otherwise recursive X-Y cut: split at the widest empty band, top to bottom or left to right (a horizontal cut has to beat a column
     * gutter clearly, not tie with it), and recurse. Ties and unsplittable groups fall back to top-to-bottom, left-to-right.
     */
    fun order(blocks: List<LayoutBlock>): List<LayoutBlock> {
        if (blocks.size <= 1) return blocks
        spannedColumns(blocks)?.let { return it }
        val h = bestGap(blocks, horizontal = true); val v = bestGap(blocks, horizontal = false)
        val useH = h != null && (v == null || h.first > 1.35 * v.first)
        val cut = (if (useH) h else v) ?: return blocks.sortedWith(compareBy({ it.box.y0 }, { it.box.x0 }))
        val (first, second) = blocks.partition { if (useH) it.box.cy < cut.second else it.box.cx < cut.second }
        if (first.isEmpty() || second.isEmpty()) return blocks.sortedWith(compareBy({ it.box.y0 }, { it.box.x0 }))
        return order(first) + order(second)
    }

    /**
     * A gutter that few blocks cross, with substantial text on both sides: the x range, at least 4 percent of the region wide (and 20 points),
     * where the blocks crossing it cover at most 12 percent of the region's text height but at least one block does (a clean gutter needs no
     * spanners and is left to the plain cut). The blocks are then ordered band by band between the spanners, each band left column then right.
     */
    private fun spannedColumns(blocks: List<LayoutBlock>): List<LayoutBlock>? {
        if (blocks.size < 6) return null
        val minX = blocks.minOf { it.box.x0 }; val maxX = blocks.maxOf { it.box.x1 }
        val total = blocks.sumOf { it.box.height }
        if (total <= 0 || maxX - minX < 100) return null
        val edges = (blocks.flatMap { listOf(it.box.x0, it.box.x1) } + minX + maxX).distinct().sorted()
        var best: Pair<Double, Double>? = null
        var runStart: Double? = null
        for (i in 0 until edges.size - 1) {
            val a = edges[i]; val b = edges[i + 1]
            val low = blocks.filter { it.box.x0 < b && it.box.x1 > a }.sumOf { it.box.height } <= 0.12 * total
            if (low) {
                val start = runStart ?: a.also { runStart = it }
                val current = best
                if (current == null || b - start > current.second - current.first) best = start to b
            } else runStart = null
        }
        val candidate = best ?: return null
        // Within the low-occupancy range, the gutter proper is the widest stretch crossed by the least: a column's own figures standing at its
        // edge raise the occupancy in places but are not what runs across the whole gutter.
        val inner = edges.filter { it >= candidate.first && it <= candidate.second }
        fun crossing(a: Double, b: Double) = blocks.filter { it.box.x0 < b && it.box.x1 > a }.sumOf { it.box.height }
        val minOcc = (0 until inner.size - 1).minOf { crossing(inner[it], inner[it + 1]) }
        // Of the stretches crossed by the least, the one that divides the text most evenly is the gutter between the columns.
        val runs = ArrayList<Pair<Double, Double>>()
        var start: Double? = null
        for (i in 0 until inner.size - 1) {
            if (crossing(inner[i], inner[i + 1]) <= minOcc + 1e-6) { if (start == null) start = inner[i]; if (i == inner.size - 2 || crossing(inner[i + 1], inner[i + 2]) > minOcc + 1e-6) { runs += start!! to inner[i + 1]; start = null } }
            else start = null
        }
        fun balance(r: Pair<Double, Double>): Double {
            val mid = (r.first + r.second) / 2
            return minOf(blocks.filter { it.box.cx < mid }.sumOf { it.box.height }, blocks.filter { it.box.cx >= mid }.sumOf { it.box.height })
        }
        val gutter = runs.maxByOrNull { balance(it) } ?: return null
        if (gutter.second - gutter.first < max(20.0, 0.04 * (maxX - minX)) || gutter.first <= minX || gutter.second >= maxX) return null
        val spanners = blocks.filter { it.box.x0 < gutter.second && it.box.x1 > gutter.first }
        if (spanners.isEmpty()) return null
        val mid = (gutter.first + gutter.second) / 2
        val rest = blocks - spanners.toSet()
        val left = rest.filter { it.box.cx < mid }; val right = rest.filter { it.box.cx >= mid }
        if (left.size < 2 || right.size < 2 || left.sumOf { it.box.height } < 0.25 * total || right.sumOf { it.box.height } < 0.25 * total) return null
        fun columns(bs: List<LayoutBlock>) = order(bs.filter { it.box.cx < mid }) + order(bs.filter { it.box.cx >= mid })
        val out = ArrayList<LayoutBlock>(); val used = HashSet<LayoutBlock>()
        for (sp in spanners.sortedBy { it.box.y0 }) {
            val above = rest.filter { it !in used && it.box.cy < sp.box.y0 }
            used += above; out += columns(above)
            val row = (rest.filter { it !in used && it.box.cy in sp.box.y0..sp.box.y1 } + sp).sortedBy { it.box.x0 }
            used += row; out += row
        }
        out += columns(rest.filter { it !in used })
        return out
    }

    /** The widest empty band across [blocks] (width, position of its middle), or null when no band is wider than 1.5 points. */
    private fun bestGap(blocks: List<LayoutBlock>, horizontal: Boolean): Pair<Double, Double>? {
        val iv = blocks.map { if (horizontal) it.box.y0 to it.box.y1 else it.box.x0 to it.box.x1 }.sortedBy { it.first }
        var reach = iv.first().second; var best: Pair<Double, Double>? = null
        for (i in 1 until iv.size) {
            val gap = iv[i].first - reach
            if (gap > 1.5 && (best == null || gap > best.first)) best = gap to (reach + iv[i].first) / 2
            reach = max(reach, iv[i].second)
        }
        return best
    }
}
