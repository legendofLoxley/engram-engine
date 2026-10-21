package app.alfrd.engram.pdf

/** A page-number-looking line in the top or bottom margin of a page: what it says, its value and kind, and the words that make it up. */
internal data class PageNumberCandidate(val printed: String, val value: Int, val roman: Boolean, val words: List<Word>)

/**
 * Finds the number printed on a page and checks it against the other pages, so a stray figure in a margin is not taken for one.
 *
 * A candidate is a line alone in the top or bottom 9 percent of the page that reads as `12`, `Page 12`, `12 of 40`, `12 / 40`,
 * `- 12 -` or a roman numeral. A page's number is only accepted when at least two pages agree on the same offset between printed
 * and physical numbers (page 2 prints 1, page 3 prints 2: offset -1). A lone number, or a year in a footer, does not agree with
 * anything and is dropped, so a page without a corroborated number has `null` rather than a guess.
 */
internal object PrintedPage {
    private val PLAIN = Regex("""^[-–—\s]*(\d{1,4})[-–—\s]*$""")
    private val LABELLED = Regex("""^(?:page|p\.?|pg\.?)\s*(\d{1,4})(?:\s*(?:of|/)\s*\d{1,4})?$""", RegexOption.IGNORE_CASE)
    private val OF = Regex("""^(\d{1,4})\s*(?:of|/)\s*\d{1,4}$""", RegexOption.IGNORE_CASE)
    private val ROMAN = Regex("""^[ivxlc]{1,6}$""")

    fun candidate(words: List<Word>, pageHeight: Double): PageNumberCandidate? {
        val margin = 0.09 * pageHeight
        val inMargin = words.filter { it.box.y1 <= margin || it.box.y0 >= pageHeight - margin }
        // Bottom lines first: footers are the common place.
        val lines = PageLayout.lines(inMargin).sortedByDescending { it.first().box.y1 }
        for (line in lines) for (seg in PageLayout.segments(line)) {
            val text = seg.joinToString(" ") { it.text }.trim()
            PLAIN.matchEntire(text)?.let { return PageNumberCandidate(it.groupValues[1], it.groupValues[1].toInt(), false, seg) }
            LABELLED.matchEntire(text)?.let { return PageNumberCandidate(it.groupValues[1], it.groupValues[1].toInt(), false, seg) }
            OF.matchEntire(text)?.let { return PageNumberCandidate(it.groupValues[1], it.groupValues[1].toInt(), false, seg) }
            if (ROMAN.matches(text)) return PageNumberCandidate(text, romanValue(text), true, seg)
        }
        return null
    }

    /** The corroborated candidates by physical page (1-based); pages whose candidate no other page agrees with are left out. */
    fun corroborate(byPage: Map<Int, PageNumberCandidate>): Map<Int, PageNumberCandidate> {
        val keep = HashMap<Int, PageNumberCandidate>()
        for (roman in listOf(false, true)) {
            val group = byPage.filterValues { it.roman == roman }
            val offsets = group.entries.groupBy { it.value.value - it.key }
            offsets.values.filter { it.size >= 2 }.forEach { agreeing -> agreeing.forEach { keep[it.key] = it.value } }
        }
        return keep
    }

    private fun romanValue(s: String): Int {
        val v = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        var total = 0
        for (i in s.indices) { val c = v.getValue(s[i]); val n = s.getOrNull(i + 1)?.let { v[it] } ?: 0; total += if (c < n) -c else c }
        return total
    }
}
