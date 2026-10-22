package app.alfrd.engram.vision

/** One thing the vision model reported, where it put it, and whether the page's own text bears it out. */
data class CheckedValue(
    /** Headings from the outermost down to the one it sits under. */
    val path: List<String>,
    /** `heading`, `number`, `item`, `cell` or `chart`. */
    val kind: String,
    val value: String,
    /** The value occurs in the page's text. */
    val found: Boolean,
    /**
     * For a value under a heading: it occurs in the page's reading-order text after that heading and before the next heading the model put
     * beside it. Null when that cannot be judged (the heading or the value is not in the reading-order text, or it is the heading itself).
     */
    val inSection: Boolean?,
    /** For a number: the words the model printed with it, and whether they are found too. */
    val label: String = "",
    val labelFound: Boolean = true,
)

/**
 * Checks a vision model's [PageSection]s against the page's own text: the PDF text layer in reading order, and, for text only an image holds,
 * an OCR transcript. The two readers are independent of each other, so a heading or a number the model reports but neither text holds is not
 * on the page as the model says. Nothing here decides truth or establishes anything; it reports what is and is not borne out.
 *
 * Text is compared without case, with spacing collapsed and typographic dashes and quotes made plain. A number must stand on its own: "38" is
 * not found inside "138" or "3.8".
 *
 * **Wrapped and interleaved text.** A table's cells wrap, and the text layer reads a wrapped row across its cells, so a printed column header
 * ("Five-year buffer") can come out with its neighbour's words inside it ("Five-year Planning floor buffer"), and a card's title and its text can
 * sit apart with other words between. A value of two or more words that is not found as written is found when all its words occur, as whole
 * words, within a few words of each other ([SLACK]), in any order. Single words and numbers are never matched this way, nor any value with a
 * one-letter word ("SCENARIO A" must be printed as such).
 */
object StructureCheck {

    fun normalize(s: String): String = s.lowercase()
        .replace('–', '-').replace('—', '-').replace('−', '-').replace('‐', '-').replace('‑', '-').replace('‒', '-').replace('‘', '\'').replace('’', '\'')
        .replace('“', '"').replace('”', '"').replace(' ', ' ')
        .replace(Regex("\\s+"), " ").trim()

    private val NUMERIC = Regex("^[-+$]?[0-9][0-9,.]*\\s*%?$")

    /** Every position at which [value] occurs in [text] (both normalized). A number only counts where it is not part of a longer number. */
    fun occurrences(text: String, value: String): List<Int> {
        val v = normalize(value)
        if (v.isEmpty()) return emptyList()
        val numeric = NUMERIC.matches(v)
        val out = ArrayList<Int>()
        var at = text.indexOf(v)
        while (at >= 0) {
            val before = text.getOrNull(at - 1); val after = text.getOrNull(at + v.length)
            val ok = !numeric || ((before == null || !(before.isDigit() || before == '.' || before == ',')) &&
                (after == null || !(after.isDigit() || (after == '.' && text.getOrNull(at + v.length + 1)?.isDigit() == true) || (after == ',' && text.getOrNull(at + v.length + 1)?.isDigit() == true))))
            if (ok) out += at
            at = text.indexOf(v, at + 1)
        }
        return out
    }

    /** Words of text found apart from each other may have at most this many other words among them. */
    const val SLACK = 4

    /** A word of normalized text and where it starts. Punctuation around a word is not part of it; punctuation alone is not a word. */
    data class Word(val text: String, val at: Int)

    private const val EDGE = "\"'()[]{}:;,.|*•·…"

    fun words(normalized: String): List<Word> = Regex("\\S+").findAll(normalized).mapNotNull { m ->
        val raw = m.value
        val start = raw.indexOfFirst { it !in EDGE }.takeIf { it >= 0 } ?: return@mapNotNull null
        val end = raw.indexOfLast { it !in EDGE }
        val w = raw.substring(start, end + 1)
        if (w.none { it.isLetterOrDigit() }) null else Word(w, m.range.first + start)
    }.toList()

    /** Where the words of a value of two or more words occur near each other in [words] (the text's), when it is not found as written. */
    fun scattered(words: List<Word>, value: String): List<Int> {
        val want = words(normalize(value)).map { it.text }
        if (want.size < 2 || want.any { it.length < 2 }) return emptyList()
        val need = want.groupingBy { it }.eachCount()
        val out = ArrayList<Int>()
        for (i in words.indices) {
            if (words[i].text !in need) continue
            val left = HashMap(need); var missing = want.size
            for (j in i until minOf(words.size, i + want.size + SLACK)) {
                val c = left[words[j].text] ?: continue
                if (c > 0) { left[words[j].text] = c - 1; missing-- }
                if (missing == 0) break
            }
            if (missing == 0) out += words[i].at
        }
        return out
    }

    /** A normalized text and its words, so a page's text is split once. */
    private class Text(val text: String) {
        val words = words(text)
        fun locate(value: String): List<Int> = occurrences(text, value).ifEmpty { scattered(words, value) }
    }

    /**
     * [readingText] is the page's text in reading order (the PDF text layer as the reader orders it); [ocrText], when given, is a transcript
     * of the page image, consulted for presence only (its order is the OCR engine's).
     */
    fun check(sections: List<PageSection>, readingText: String, ocrText: String? = null): List<CheckedValue> {
        val reading = Text(normalize(readingText))
        val text = reading.text
        val ocr = ocrText?.let { Text(normalize(it)) }
        val out = ArrayList<CheckedValue>()

        fun found(v: String) = reading.locate(v).isNotEmpty() || (ocr != null && ocr.locate(v).isNotEmpty())

        // [lo, hi) is the span of the parent heading in the reading-order text; a heading is looked for inside it, in order after its previous
        // sibling, and runs to its next sibling (or the end of the parent's span).
        fun walk(level: List<PageSection>, parents: List<String>, lo: Int, hi: Int) {
            var cursor = lo
            val starts = level.map { s ->
                val at = reading.locate(s.heading).firstOrNull { it in cursor until hi }
                if (at != null) cursor = at + 1
                at
            }
            level.forEachIndexed { i, s ->
                val path = parents + s.heading
                out += CheckedValue(parents, "heading", s.heading, found(s.heading), null)
                val from = starts[i]
                val to = starts.drop(i + 1).filterNotNull().firstOrNull { from == null || it > from } ?: hi
                fun add(kind: String, v: String, label: String = "") {
                    val at = reading.locate(v)
                    out += CheckedValue(
                        path, kind, v, found(v), if (from == null || at.isEmpty()) null else at.any { it in from until to },
                        label, label.isBlank() || found(label),
                    )
                }
                s.numbers.forEach { add("number", it.value, it.label) }
                s.items.forEach { add("item", it) }
                s.tables.forEach { t -> t.flatten().forEach { add("cell", it) } }
                s.charts.forEach { c -> (listOf(c.title).filter { it.isNotBlank() } + c.values).forEach { add("chart", it) } }
                if (from != null) walk(s.subsections, path, from, to) else walk(s.subsections, path, lo, hi)
            }
        }
        walk(sections, emptyList(), 0, text.length)
        return out
    }
}
