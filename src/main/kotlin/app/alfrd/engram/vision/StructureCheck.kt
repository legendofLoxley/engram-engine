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
    /**
     * The value as the page prints it, when that differs from what the model wrote: its case ("SEGc" for the printed "SEGC"), or a name the
     * model misread by a letter or two ("Sitten" for "Sitton"; see [StructureCheck.check]). What is stored is the page's text, never the model's.
     */
    val printedAs: String? = null,
    /** The value's context as the page bears it out (passage as printed, things and period printed on the page); null when none. */
    val context: PageContext? = null,
    /** Context the model gave that the page does not print, dropped (the value itself is judged on its own). */
    val contextDropped: List<String> = emptyList(),
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
 * one-letter word, unless it is four words or longer ("SCENARIO A" must be printed as such; the "a" in a sentence is passed over). A number's
 * label is found when each of its words is printed anywhere on the page.
 *
 * **Misread names.** The model sometimes misreads a name by a letter or two ("Sitten", "Siton" for "Sitton"; "Dunaway" for "Duniway"), which
 * would lose the whole list item that holds it. A capitalized word of five or more letters that the page does not print is replaced by the
 * nearest word the page prints within two edits of it with the same first letter (the text layer's words first, then the OCR transcript's;
 * no guess when two are equally near, and none when the page prints that word in lower case: an ordinary word is not a name), and the value is found only if the corrected value is then found as a whole. The page's spelling is what is kept ([CheckedValue.printedAs]); the correction is reported.
 *
 * **Context.** A value's passage is kept only when the page prints it as one run (as printed, case and spacing aside); each thing it is
 * about, that thing's parent, and its period are kept only when the page prints them. What is dropped is reported; a value is never rejected
 * for its context.
 */
object StructureCheck {

    fun normalize(s: String): String = s.lowercase()
        .replace('–', '-').replace('—', '-').replace('−', '-').replace('‐', '-').replace('‑', '-').replace('‒', '-').replace('‘', '\'').replace('’', '\'')
        .replace('“', '"').replace('”', '"').replace(' ', ' ')
        .replace(Regex("\\s+"), " ").trim()

    private val NUMERIC = Regex("^[-+$]?[0-9][0-9,.]*\\s*%?$")

    private val PAGE_PERIOD = Regex(
        "\\b(?:all|the)\\s+(?:figures?|numbers?|data|values?|amounts?)\\b[^.]{0,80}?\\b((?:19|20)\\d{2}\\s*[–—-]\\s*\\d{2,4}(?:\\s+school\\s+year)?)",
        RegexOption.IGNORE_CASE,
    )
    private val A_YEAR = Regex("(?<![0-9])(?:19|20)\\d{2}(?![0-9])")

    /**
     * The one period a page states for its figures ("All figures are projections for the 2031–32 school year unless noted"), as the page prints it,
     * or null. Read from the page's own text, not from the model, so it does not depend on which section the model chose to hang it on.
     */
    /** Where a section's period came from: the page's own statement, the section's own heading, or neither (a caption elsewhere, the model's say-so). */
    enum class PeriodSource { PAGE, HEADING, OTHER, DOCUMENT_DATE }
    data class SectionPeriod(val period: String, val source: PeriodSource)

    private val PERIOD_DASHES = Regex("[–—‑-]")
    private fun periodKey(s: String) = normalize(s).replace(PERIOD_DASHES, "-").replace(Regex("\\s+"), " ").trim()

    /** Whether [text] prints [period] (dash and case aside). */
    fun printsPeriod(text: String?, period: String): Boolean = text != null && periodKey(text).contains(periodKey(period))

    /** "Updated October 4, 2026", "Draft v2": when a document was made, not the period its figures are about. */
    private val DOCUMENT_DATE = Regex("^\\s*(?:last\\s+)?(?:updated|revised|published|posted|printed|draft|version|rev\\.?)\\b", RegexOption.IGNORE_CASE)
    fun isDocumentDate(period: String) = DOCUMENT_DATE.containsMatchIn(period)

    private fun periodSource(period: String, heading: String, pageStated: String?): PeriodSource = when {
        isDocumentDate(period) -> PeriodSource.DOCUMENT_DATE
        pageStated != null && periodKey(period) == periodKey(pageStated) -> PeriodSource.PAGE
        printsPeriod(heading, period) -> PeriodSource.HEADING
        else -> PeriodSource.OTHER
    }

    fun pageStatedPeriod(pageText: String): String? =
        PAGE_PERIOD.find(pageText.replace(Regex("\\s+"), " "))?.groupValues?.get(1)?.trim()

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
        val all = words(normalize(value)).map { it.text }
        if (all.size < 2) return emptyList()
        val oneLetter = { w: String -> w.length < 2 && !w[0].isDigit() }
        if (all.size < 4 && all.any(oneLetter)) return emptyList()
        val want = all.filterNot(oneLetter)
        val need = want.groupingBy { it }.eachCount()
        val out = ArrayList<Int>()
        for (i in words.indices) {
            if (words[i].text !in need) continue
            val left = HashMap(need); var missing = want.size
            for (j in i until minOf(words.size, i + all.size + SLACK)) {
                val c = left[words[j].text] ?: continue
                if (c > 0) { left[words[j].text] = c - 1; missing-- }
                if (missing == 0) break
            }
            if (missing == 0) out += words[i].at
        }
        return out
    }

    /** Edits (insert, delete, substitute) between two short words. */
    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private const val DASHES = "-–—−‐‑‒"

    /**
     * Where [value] is printed in [original] (raw, not normalized), case and spacing aside, as printed with its spacing collapsed; null when it
     * is not printed as one run.
     */
    fun printed(original: String, value: String): String? {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val pattern = parts.joinToString("\\s+") { p ->
            p.map { c ->
                when {
                    c in DASHES -> "[$DASHES]" // the plain hyphen first: literal inside the class
                    c == '\'' || c == '‘' || c == '’' -> "['‘’]"
                    c == '"' || c == '“' || c == '”' -> "[\"“”]"
                    else -> Regex.escape(c.toString())
                }
            }.joinToString("")
        }
        return Regex(pattern, setOf(RegexOption.IGNORE_CASE)).find(original)?.value?.replace(Regex("\\s+"), " ")
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
        val pageAsOf = pageStatedPeriod(readingText)

        fun found(v: String) = reading.locate(v).isNotEmpty() || (ocr != null && ocr.locate(v).isNotEmpty())
        val originals = listOfNotNull(readingText, ocrText)
        fun printedForm(v: String): String? = originals.firstNotNullOfOrNull { printed(it, v) }

        // A label is what the number counts, often assembled from text printed apart from it (a chart's category, its legend, the year on its
        // axis): it is found when each of its words is printed on the page. The number itself is still checked where it sits.
        val readingWords = reading.words.map { it.text }.toSet()
        val pageWords = readingWords + (ocr?.words?.map { it.text } ?: emptyList())
        // A misread name: see the class comment. Null when there is nothing to correct, the correction is not unique, or the corrected value
        // is still not found.
        fun corrected(v: String): String? {
            var out = v; var changed = false
            for (m in Regex("\\p{L}[\\p{L}'’]*").findAll(v).toList().asReversed()) {
                val w = m.value
                if (w.length < 5 || !w[0].isUpperCase()) continue
                val key = normalize(w)
                if (key in pageWords) continue
                // The text layer's words first: an OCR transcript can hold its own misreading of the same name, which would make two candidates.
                fun near(words: Collection<String>) = words.filter { it.length >= 4 && it[0] == key[0] && it.all { c -> c.isLetter() || c == '\'' } }
                    .map { it to distance(it, key) }.filter { it.second <= 2 }
                val candidates = near(readingWords).ifEmpty { near(pageWords) }
                val best = candidates.minOfOrNull { it.second } ?: return null
                val nearest = candidates.filter { it.second == best }.map { it.first }.distinct()
                if (nearest.size != 1) return null
                val asPrinted = originals.firstNotNullOfOrNull { t ->
                    Regex("(?<!\\p{L})" + Regex.escape(nearest.single()) + "(?!\\p{L})", RegexOption.IGNORE_CASE).find(t)?.value
                } ?: return null
                // A name is printed with a capital. A word the page also prints in lower case ("while") is an ordinary word, not a name the model
                // misread: "White", a legend label an image holds, must not become "while" from the running text beside it.
                if (!asPrinted[0].isUpperCase() || originals.any { t -> Regex("(?<!\\p{L})" + Regex.escape(nearest.single()) + "(?!\\p{L})").containsMatchIn(t) }) return null
                out = out.replaceRange(m.range, asPrinted); changed = true
            }
            return out.takeIf { changed && found(it) }
        }

        fun checkContext(ctx: PageContext?): Pair<PageContext?, List<String>> {
            if (ctx == null || ctx.isEmpty()) return null to emptyList()
            val dropped = ArrayList<String>()
            fun asPrinted(s: String): String? = when {
                found(s) -> printedForm(s) ?: s.trim()
                else -> corrected(s)
            }
            val passage = ctx.passage?.let { p -> printedForm(p) ?: run { dropped += "passage not printed as given"; null } }
            val about = ctx.about.mapNotNull { t ->
                val name = asPrinted(t.name) ?: run { dropped += "about '${t.name}' not on the page"; return@mapNotNull null }
                val parent = t.parent?.let { p -> asPrinted(p) ?: run { dropped += "parent '$p' not on the page"; null } }
                PageThing(name, t.kind, parent)
            }
            val asOf = ctx.asOf?.let { a -> (if (found(a)) printedForm(a) ?: a.trim() else null) ?: run { dropped += "period '$a' not on the page"; null } }
            return PageContext(passage, about, asOf).takeIf { !it.isEmpty() } to dropped
        }

        fun labelFound(label: String): Boolean {
            if (label.isBlank() || found(label)) return true
            val w = words(normalize(label)).map { it.text }.filter { it.length > 1 || it[0].isDigit() }
            return w.isNotEmpty() && pageWords.containsAll(w)
        }

        // [lo, hi) is the span of the parent heading in the reading-order text; a heading is looked for inside it, in order after its previous
        // sibling, and runs to its next sibling (or the end of the parent's span).
        fun walk(level: List<PageSection>, parents: List<String>, lo: Int, hi: Int, inheritedAsOf: SectionPeriod? = null) {
            var cursor = lo
            val headingFix = level.map { s -> if (found(s.heading)) null else corrected(s.heading) }
            val starts = level.mapIndexed { i, s ->
                val at = reading.locate(headingFix[i] ?: s.heading).firstOrNull { it in cursor until hi }
                if (at != null) cursor = at + 1
                at
            }
            level.forEachIndexed { i, s ->
                val path = parents + s.heading
                // The one period a heading (or the page) states covers what is under it, unless a value gives its own.
                val sectionAsOf = s.asOf?.let { a -> SectionPeriod(a, periodSource(a, s.heading, pageAsOf)) } ?: inheritedAsOf
                val fix = headingFix[i]
                out += CheckedValue(parents, "heading", s.heading, fix != null || found(s.heading), null,
                    printedAs = fix ?: printedForm(s.heading)?.takeIf { it != s.heading })
                val from = starts[i]
                val to = starts.drop(i + 1).filterNotNull().firstOrNull { from == null || it > from } ?: hi
                fun add(kind: String, v: String, label: String = "", ctx: PageContext? = null) {
                    val ok = found(v)
                    val fix = if (ok) null else corrected(v)
                    val at = reading.locate(fix ?: v)
                    // An earlier figure ("from 618 students to 355 students in 2023–24") is not given the period of the sentence it is printed in.
                    val earlier = ctx?.passage?.let { p -> Regex("\\bfrom\\s+" + Regex.escape(v.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(p) } == true
                    // "Unless noted": a value whose own passage or label names a year keeps to that, never the page's period.
                    val noted = A_YEAR.containsMatchIn(ctx?.passage.orEmpty()) || A_YEAR.containsMatchIn(label)
                    val figure = kind == "number" || (kind == "cell" && NUMERIC.matches(normalize(v)))
                    // A period the model gives a value is kept when the value's own words (its passage, its label) or, for a value read from a chart
                    // or table, the heading it sits under print it: a range from a figure caption does not become the period of prose beside it.
                    val ownAsOf = ctx?.asOf
                    val ownSupported = ownAsOf == null || (!isDocumentDate(ownAsOf) && (printsPeriod(ctx.passage, ownAsOf) || printsPeriod(label, ownAsOf) ||
                        (ctx.passage.isNullOrBlank() && path.any { printsPeriod(it, ownAsOf) })))
                    val hasPassage = !ctx?.passage.isNullOrBlank()
                    val period = when {
                        // A period a heading prints covers what is under it.
                        sectionAsOf?.source == PeriodSource.HEADING && (figure || kind == "item") -> sectionAsOf.period
                        // Read from a figure or table: a range the model took from a caption covers what the figure or table says, not prose.
                        sectionAsOf?.source == PeriodSource.OTHER && (kind == "cell" && figure || kind == "chart" || kind == "number" && !hasPassage) -> sectionAsOf.period
                        // The page's own statement ("All figures are projections for the 2031–32 school year unless noted"): its figures, never its list items.
                        pageAsOf != null && figure && !noted -> pageAsOf
                        else -> null
                    }
                    val given = if (ownSupported) ctx else ctx?.copy(asOf = null)
                    val withPeriod = when {
                        earlier -> (ctx ?: PageContext()).copy(asOf = null)
                        given?.asOf == null && period != null -> (given ?: PageContext()).copy(asOf = period)
                        else -> given
                    }
                    val (context0, dropped0) = checkContext(withPeriod)
                    val context = context0
                    val dropped = dropped0 + (when {
                        ownSupported -> emptyList()
                        isDocumentDate(ownAsOf!!) -> listOf("period '$ownAsOf' is a document date, not a period of its figures")
                        found(ownAsOf) -> listOf("period '$ownAsOf' not in its passage, label or heading")
                        else -> listOf("period '$ownAsOf' not on the page")
                    })
                    out += CheckedValue(
                        path, kind, v, ok || fix != null, if (from == null || at.isEmpty()) null else at.any { it in from until to },
                        label, labelFound(label), printedAs = fix ?: printedForm(v)?.takeIf { it != v }, context = context, contextDropped = dropped,
                    )
                }
                s.numbers.forEach { add("number", it.value, it.label, it.context) }
                s.items.forEach { add("item", it, ctx = s.itemContext[it]) }
                s.tables.forEach { t -> t.flatten().forEach { add("cell", it) } }
                s.charts.forEach { c -> (listOf(c.title).filter { it.isNotBlank() } + c.values).forEach { add("chart", it) } }
                if (from != null) walk(s.subsections, path, from, to, sectionAsOf) else walk(s.subsections, path, lo, hi, sectionAsOf)
            }
        }
        walk(sections, emptyList(), 0, text.length)
        return out
    }
}
