package app.alfrd.engram.vision

import app.alfrd.engram.db.ChunkDraft
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PdfPreparer
import app.alfrd.engram.pdf.PreparedPdf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * One checked entry of a vision reading: what it is, the headings it sits under, its value, and the line of the chunk text that states it;
 * with the context the page bears out: the passage it is printed in (also a line of the chunk), the things it is about and its period.
 */
@Serializable
data class ReadingEntry(
    val kind: String, val path: List<String>, val value: String, val label: String = "", val line: String,
    val passage: String? = null, val about: List<PageThing> = emptyList(), val asOf: String? = null,
)

/** Why a page goes to the vision reader. Empty reasons: it stays with the text reader. */
@Serializable
data class PageRoute(val page: Int, val reasons: List<String>)

@Serializable
data class PageReadingResult(
    val page: Int,
    /** `added`, `already there`, `nothing verified` or `failed`. */
    val outcome: String,
    val chunkUid: String? = null,
    val chunkOrdinal: Int? = null,
    val model: String? = null,
    val ocrModel: String? = null,
    val ms: Long = 0,
    val accepted: Int = 0,
    val rejected: Int = 0,
    /** The rejected values (heading path :: value) with why: not on the page, or not under that heading. */
    val rejectedValues: List<String> = emptyList(),
    val failure: String? = null,
    /** Names the model misread, kept with the page's spelling (`path :: model's → page's`). */
    val corrected: List<String> = emptyList(),
)

/**
 * Reads a page of a stored PDF Source with the local vision model and adds what can be checked as one more chunk of that Source.
 *
 * **Routing.** Prose pages stay with the text reader. A page goes to vision when it has a table the text reader found, an embedded image of
 * chart size, a figure or table caption, a dense designed layout (many short text blocks: cards, posters), or no text layer ([route]).
 *
 * **Two independent readers.** The vision model (8083) proposes the page's structure ([PageStructure]); every heading and value is then looked
 * for in the page's own text: the text layer in reading order, and an OCR transcript of the image (8084) when given ([StructureCheck]). Only
 * what is found, under the heading it was given (where that can be judged), and with its row label and column header found too for a table
 * cell, goes into the chunk. The rest is reported, never stored as text of the page.
 *
 * **The chunk.** One per page and reader version, added after the Source's existing chunks ([SourceStore.appendChunk]); the Source and its
 * text-layer chunks are untouched. Its text is one line per entry (`HEADING > SUB: value label`, `HEADING > row > column: cell`) under bare
 * heading lines, and its `reading` holds the entries as JSON, from which claims are made directly ([StructuredClaimModel]). Chart text is kept
 * in the chunk text so it can be read and searched, but makes no claims. Idempotent: a page read again under the same key adds nothing and calls
 * no model. The file must be the Source's own (same SHA-256).
 */
class VisionReadingService(
    private val store: SourceStore,
    private val structure: VisionModel,
    private val ocr: VisionModel?,
    private val preparer: PdfPreparer,
    private val cache: PdfPreparationCache,
    private val dpi: Float = 150f,
) {
    private val logger = LoggerFactory.getLogger(VisionReadingService::class.java)
    private val json = Json { encodeDefaults = true }

    private fun prepared(pdf: ByteArray): PreparedPdf = cache.getOrPrepare(pdf, preparer).prepared

    private fun checkFile(userEmail: String, sourceUid: String, pdf: ByteArray) {
        val source = store.sourceByUid(userEmail, sourceUid) ?: throw IllegalArgumentException("unknown source")
        val sha = MessageDigest.getInstance("SHA-256").digest(pdf).joinToString("") { "%02x".format(it) }
        require(source.fileSha256 == sha) { "this file is not the one the source was read from (SHA-256 differs)" }
    }

    /** Which pages go to vision, and why. */
    fun plan(userEmail: String, sourceUid: String, pdf: ByteArray): List<PageRoute> {
        checkFile(userEmail, sourceUid, pdf)
        return VisionReading.route(pdf, prepared(pdf))
    }

    fun readPage(userEmail: String, sourceUid: String, pdf: ByteArray, page: Int, useOcr: Boolean = true): PageReadingResult {
        checkFile(userEmail, sourceUid, pdf)
        val prep = prepared(pdf)
        require(page in 1..prep.pageCount) { "page $page is outside 1..${prep.pageCount}" }
        val key = "$sourceUid|$page|${PageStructure.VERSION}"
        store.chunks(userEmail, sourceUid).firstOrNull { it.readingKey == key }?.let {
            return PageReadingResult(page, "already there", it.uid, it.ordinal, model = it.extractor?.removePrefix("vision:"))
        }
        val started = System.currentTimeMillis()
        val png = PageImages.render(pdf, page, dpi)
        val readingText = prep.chunks.filter { it.page == page }.joinToString("\n") { it.text }
        var failure: Exception? = null
        fun ask(): VisionAnswer? = try { structure.read(png, PageStructure.PROMPT) } catch (e: Exception) { failure = e; null }
        fun serverFailed() = PageReadingResult(page, "failed", failure = "vision server: ${failure?.let { it::class.java.simpleName }}", ms = System.currentTimeMillis() - started)
        // An answer that cannot be read is asked for once more: the model samples, so a second answer is a fresh one.
        var answer = ask() ?: return serverFailed()
        var parsed = runCatching { PageStructure.parse(answer.text) }
        if (parsed.isFailure) {
            answer = ask() ?: return serverFailed()
            parsed = runCatching { PageStructure.parse(answer.text) }
        }
        val sections = parsed.getOrElse { e ->
            val why = if (answer.finishReason == "length") "cut off at the token limit, ${answer.completionTokens} tokens" else "finish ${answer.finishReason}, ${answer.completionTokens} tokens"
            return PageReadingResult(page, "failed", model = answer.model, failure = "vision answer, twice: ${e.message} ($why)", ms = System.currentTimeMillis() - started)
        }
        val ocrAnswer = if (useOcr && ocr != null) {
            try { ocr.read(png, "OCR:") } catch (e: Exception) {
                return PageReadingResult(page, "failed", model = answer.model, failure = "ocr server: ${e::class.java.simpleName}", ms = System.currentTimeMillis() - started)
            }
        } else null
        val checks = StructureCheck.check(sections, readingText, ocrAnswer?.text)
        val built = VisionReading.build(sections, checks)
        val ms = System.currentTimeMillis() - started
        // Counts only: never page text in the log.
        logger.info("vision: page {} read in {} ms: {} entr(ies) kept, {} rejected", page, ms, built.entries.size, built.rejected.size)
        if (built.entries.isEmpty()) {
            return PageReadingResult(page, "nothing verified", model = answer.model, ocrModel = ocrAnswer?.model, ms = ms, rejected = built.rejected.size, rejectedValues = built.rejected)
        }
        val printed = prep.pages.firstOrNull { it.page == page }?.printedPage
        val draft = ChunkDraft(
            location = "pdf p. $page" + (printed?.let { " (printed $it)" } ?: "") + ", vision reading",
            text = built.text, page = page, printedPage = printed, section = sections.firstOrNull()?.heading,
            kind = VisionReading.CHUNK_KIND, extractor = "vision:" + (answer.model ?: "unreported"),
        )
        val (chunk, created) = store.appendChunk(userEmail, sourceUid, draft, key, json.encodeToString(built.entries))
            ?: throw IllegalArgumentException("unknown source")
        return PageReadingResult(
            page, if (created) "added" else "already there", chunk.uid, chunk.ordinal, answer.model, ocrAnswer?.model, ms,
            built.entries.size, built.rejected.size, built.rejected, corrected = built.corrected,
        )
    }
}

object VisionReading {
    const val CHUNK_KIND = "vision"
    private const val SEP = " > "

    /** Blocks of text on one page above which a page counts as a designed layout (cards, posters) rather than prose. */
    const val DENSE_BLOCKS = 12

    /**
     * ...and the most characters a block may average for it to count: cards and posters are many short blocks; a prose page with many
     * paragraphs and footnotes is many long ones (the SEGC prose pages average 200 to 390, the PPS poster 82, the planning-floor table 79).
     */
    const val DESIGNED_MAX_CHARS_PER_BLOCK = 140

    /**
     * A line that starts a figure or table caption ("Figure 2.5 Students Left…", "Figure 2.5 (DLI) Students…"): a chart drawn as vector shapes has no image and few blocks,
     * but it has its caption. A reference that wraps to the start of a line ("Figure 1). The share…") is not one.
     */
    private val CAPTION = Regex("^\\s*(Figure|Table|Exhibit|Chart)\\s+[0-9]+(\\.[0-9]+)*[.:]?\\s+[\\p{L}(]")

    /** Smallest embedded image, in pixels, that counts as a chart or figure rather than a logo or icon. */
    const val MIN_IMAGE_PIXELS = 300 * 200

    fun route(pdf: ByteArray, prepared: PreparedPdf): List<PageRoute> {
        val images = HashMap<Int, MutableList<String>>()
        Loader.loadPDF(pdf).use { doc ->
            doc.pages.forEachIndexed { i, p ->
                val res = p.resources ?: return@forEachIndexed
                res.xObjectNames.forEach { name ->
                    val x = try { res.getXObject(name) } catch (e: Exception) { null }
                    if (x is PDImageXObject && x.width * x.height >= MIN_IMAGE_PIXELS) images.getOrPut(i + 1) { ArrayList() } += "image ${x.width}x${x.height}"
                }
            }
        }
        return (1..prepared.pageCount).map { page ->
            val reasons = ArrayList<String>()
            if (prepared.chunks.any { it.page == page && it.kind == "table" }) reasons += "table"
            images[page]?.let { reasons += it }
            val texts = prepared.chunks.filter { it.page == page && it.kind == "text" }
            val blocks = texts.sumOf { it.blocks.size }
            val perBlock = if (blocks == 0) 0 else texts.sumOf { it.text.length } / blocks
            if (blocks >= DENSE_BLOCKS && perBlock <= DESIGNED_MAX_CHARS_PER_BLOCK) reasons += "designed layout ($blocks blocks, $perBlock chars each)"
            prepared.chunks.filter { it.page == page }.flatMap { it.text.lines() }.firstOrNull { CAPTION.containsMatchIn(it) }
                ?.let { reasons += "caption (${it.trim().take(40)})" }
            if (prepared.pages.firstOrNull { it.page == page }?.path == "unread") reasons += "no text layer"
            PageRoute(page, reasons)
        }
    }

    /** [corrected]: values kept with the page's spelling of a name the model misread (`path :: model's → page's`). */
    data class Built(val text: String, val entries: List<ReadingEntry>, val rejected: List<String>, val corrected: List<String> = emptyList())

    /**
     * Turns checked structure into chunk text and entries. A value is kept when it is found on the page, is not found only outside its heading,
     * and (for a number) its label is found. A table cell is kept as `row label > column header: cell` when the cell, its row label and its
     * column header are all kept. Headings are written as bare lines (the top level alone, deeper levels as a path) so a claim's place can be
     * checked against them.
     *
     * A heading the page does not have is left out of the path, and what the model put under it is checked as under the heading above it: the
     * values are the page's even when the model made up a title for their box. A number left with neither a heading nor a label says nothing
     * and is left out. A number that is part of a cell of a table in the same section is left out too: the cell says it, with its row and column.
     */
    fun build(sections: List<PageSection>, checks: List<CheckedValue>): Built {
        val byKey = checks.groupBy { Triple(it.path.joinToString(SEP), it.kind, it.value) }
        fun check(path: List<String>, kind: String, value: String) = byKey[Triple(path.joinToString(SEP), kind, value)]?.firstOrNull()
        fun ok(c: CheckedValue?) = c != null && c.found && c.inSection != false && c.labelFound
        val lines = ArrayList<String>()
        val entries = ArrayList<ReadingEntry>()
        val rejected = ArrayList<String>()
        val corrected = ArrayList<String>()
        // What is written is the page's text: its case, and its spelling of a name the model misread.
        fun printed(c: CheckedValue?, v: String, prefix: String): String {
            val p = c?.printedAs ?: return v
            if (StructureCheck.normalize(p) != StructureCheck.normalize(v)) corrected += "$prefix :: $v → $p"
            return p
        }
        fun why(c: CheckedValue?) = when {
            c == null || !c.found -> "not on the page"
            c.inSection == false -> "not under that heading"
            !c.labelFound -> "label not on the page"
            else -> "kept"
        }
        fun words(v: String) = StructureCheck.words(StructureCheck.normalize(v)).map { it.text }
        // The context the page bears out goes on the entry; what it does not print is reported, and the value is kept.
        fun entry(kind: String, path: List<String>, value: String, label: String, line: String, c: CheckedValue?, prefix: String): ReadingEntry {
            c?.contextDropped?.forEach { rejected += "$prefix :: $value — $it (value kept)" }
            val ctx = c?.context
            return ReadingEntry(kind, path, value, label, line, ctx?.passage, ctx?.about.orEmpty(), ctx?.asOf)
        }

        // The words of each table's cells, by the path of the section that holds the table (the model's path, invented headings included).
        val tableWords = ArrayList<Pair<List<String>, List<Set<String>>>>()
        fun collect(level: List<PageSection>, parents: List<String>) {
            level.forEach { s ->
                val path = parents + s.heading
                s.tables.forEach { t -> tableWords += path to t.flatten().map { words(it).toSet() } }
                collect(s.subsections, path)
            }
        }
        collect(sections, emptyList())
        fun inATable(path: List<String>, value: String): Boolean {
            val w = words(value).takeIf { it.isNotEmpty() } ?: return false
            return tableWords.any { (p, cells) ->
                (path.take(p.size) == p || p.take(path.size) == path) && cells.any { it.containsAll(w) }
            }
        }

        // [path] is the model's (how the checks are keyed); [shown] is the headings of it that are on the page (what the chunk says).
        fun walk(level: List<PageSection>, parents: List<String>, shownParents: List<String>) {
            level.forEach { s ->
                val path = parents + s.heading
                val head = check(parents, "heading", s.heading)
                val shown = if (head != null && head.found) shownParents + printed(head, s.heading, parents.joinToString(SEP)) else {
                    rejected += "${path.joinToString(SEP)} :: (heading) not on the page"
                    shownParents
                }
                if (shown.size > shownParents.size) lines += shown.joinToString(SEP)
                val prefix = shown.joinToString(SEP)
                fun line(rest: String) = if (prefix.isEmpty()) rest else "$prefix: $rest"
                s.numbers.forEach { n ->
                    val c = check(path, "number", n.value)
                    when {
                        !ok(c) -> rejected += "$prefix :: ${n.value} — ${why(c)}"
                        shown.isEmpty() && n.label.isBlank() -> rejected += "$prefix :: ${n.value} — no heading or label on the page"
                        inATable(path, n.value) -> rejected += "$prefix :: ${n.value} — repeats a table cell"
                        else -> {
                            val v = printed(c, n.value, prefix)
                            val l = line(v + (if (n.label.isNotBlank()) " ${n.label}" else ""))
                            lines += l; entries += entry("number", shown, v, n.label, l, c, prefix)
                        }
                    }
                }
                s.items.forEach { item ->
                    val c = check(path, "item", item)
                    if (ok(c)) { val v = printed(c, item, prefix); val l = line(v); lines += l; entries += entry("item", shown, v, "", l, c, prefix) }
                    else rejected += "$prefix :: $item — ${why(c)}"
                }
                s.tables.forEach { t ->
                    val header = t.firstOrNull().orEmpty()
                    t.drop(1).forEach { row ->
                        val rowLabel = row.firstOrNull() ?: return@forEach
                        row.drop(1).forEachIndexed { i, cell ->
                            val column = header.getOrNull(i + 1)
                            val parts = listOf("cell" to check(path, "cell", cell), "row label" to check(path, "cell", rowLabel), "column header" to column?.let { check(path, "cell", it) })
                            val where = (shown + rowLabel + (column ?: "?")).joinToString(SEP)
                            val bad = parts.firstOrNull { !ok(it.second) }
                            if (column != null && bad == null) {
                                val r = printed(parts[1].second, rowLabel, prefix); val col = printed(parts[2].second, column, prefix)
                                val v = printed(parts[0].second, cell, prefix)
                                val l = "${(shown + r + col).joinToString(SEP)}: $v"
                                lines += l; entries += ReadingEntry("cell", shown + r + col, v, line = l)
                            } else rejected += "$where :: $cell — " + if (column == null) "no column header" else "${bad!!.first}: ${why(bad.second)}"
                        }
                    }
                }
                s.charts.forEach { c ->
                    val kept = (listOf(c.title).filter { it.isNotBlank() } + c.values).mapNotNull { v ->
                        check(path, "chart", v).takeIf { ok(it) }?.let { printed(it, v, prefix) }
                    }
                    if (kept.isNotEmpty()) lines += (if (prefix.isEmpty()) "(chart): " else "$prefix (chart): ") + kept.joinToString("; ")
                }
                walk(s.subsections, path, shown)
            }
        }
        walk(sections, emptyList(), emptyList())
        // Each kept passage is also a line of the chunk, so a claim's passage is a passage of its chunk like any quote.
        val passages = entries.mapNotNull { it.passage }.distinct().filter { it !in lines }
        return Built((lines + passages).joinToString("\n"), entries, rejected, corrected)
    }

    /** The checked entries of a vision chunk. Empty for any other chunk. */
    fun entries(chunk: ChunkRecord): List<ReadingEntry> =
        if (chunk.kind != CHUNK_KIND || chunk.reading == null) emptyList() else READ_JSON.decodeFromString(chunk.reading)

    private val READ_JSON = Json { ignoreUnknownKeys = true }
}
