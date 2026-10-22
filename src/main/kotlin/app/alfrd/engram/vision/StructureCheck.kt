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
     * beside it. Null when that cannot be judged (the heading is not in the text, or it is the heading itself).
     */
    val inSection: Boolean?,
)

/**
 * Checks a vision model's [PageSection]s against the page's own text: the PDF text layer in reading order, and, for text only an image holds,
 * an OCR transcript. The two readers are independent of each other, so a heading or a number the model reports but neither text holds is not
 * on the page as the model says. Nothing here decides truth or establishes anything; it reports what is and is not borne out.
 *
 * Text is compared without case, with spacing collapsed and typographic dashes and quotes made plain. A number must stand on its own: "38" is
 * not found inside "138" or "3.8".
 */
object StructureCheck {

    fun normalize(s: String): String = s.lowercase()
        .replace('–', '-').replace('—', '-').replace('−', '-').replace('‘', '\'').replace('’', '\'')
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

    /**
     * [readingText] is the page's text in reading order (the PDF text layer as the reader orders it); [ocrText], when given, is a transcript
     * of the page image, consulted for presence only (its order is the OCR engine's).
     */
    fun check(sections: List<PageSection>, readingText: String, ocrText: String? = null): List<CheckedValue> {
        val text = normalize(readingText)
        val ocr = ocrText?.let { normalize(it) }
        val out = ArrayList<CheckedValue>()

        fun found(v: String) = occurrences(text, v).isNotEmpty() || (ocr != null && occurrences(ocr, v).isNotEmpty())

        // [lo, hi) is the span of the parent heading in the reading-order text; a heading is looked for inside it, in order after its previous
        // sibling, and runs to its next sibling (or the end of the parent's span).
        fun walk(level: List<PageSection>, parents: List<String>, lo: Int, hi: Int) {
            var cursor = lo
            val starts = level.map { s ->
                val at = occurrences(text, s.heading).firstOrNull { it in cursor until hi }
                if (at != null) cursor = at + 1
                at
            }
            level.forEachIndexed { i, s ->
                val path = parents + s.heading
                out += CheckedValue(parents, "heading", s.heading, found(s.heading), null)
                val from = starts[i]
                val to = starts.drop(i + 1).filterNotNull().firstOrNull { from == null || it > from } ?: hi
                fun add(kind: String, v: String) {
                    out += CheckedValue(path, kind, v, found(v), from?.let { f -> occurrences(text, v).any { it in f until to } })
                }
                s.numbers.forEach { add("number", it) }
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
