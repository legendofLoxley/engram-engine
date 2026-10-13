package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionApiException
import app.alfrd.engram.notion.NotionGuardViolation

/** One thing put in front of a model: a page, or a single row. [id] and [url] are the page's own, so a citation can name exactly it. */
data class StagedItem(val id: String, val url: String, val title: String)

/**
 * One page, or one database's rows, put in front of a task. [text] holds the items in full. When a bound was
 * hit, nothing is silently shortened: an item whose text was cut is in [cut] and its text carries a
 * `[CUT ...]` marker, and an item that did not fit at all is in [leftOut]. Both are shown to the model by name
 * (see [StagedContext.notGiven]) and recorded in the trace by id.
 */
data class StagedPage(
    val url: String,
    val title: String,
    /** The table a row belongs to, `database` for a mentioned database's rows, or the allowlist name of a plain page. */
    val source: String,
    val text: String,
    val truncated: Boolean,
    /** The pages or rows in [text]. A bare page (as tests and older callers build one) is its own single item. */
    val items: List<StagedItem> = emptyList(),
    /** Items whose text was cut by a bound. */
    val cut: List<StagedItem> = emptyList(),
    /** Items of this block that did not fit and are not in [text] at all. */
    val leftOut: List<StagedItem> = emptyList(),
)

/** Why a link could not be staged. A task with any [OutOfScope] link is Blocked and is not run. */
sealed interface StagingProblem {
    val link: String

    /** The link is not in the goal's scope. Checked again at execution, because a person can edit a task row in Notion. */
    data class OutOfScope(override val link: String) : StagingProblem

    /** In scope, but Notion would not give the page (or the guard refused it). */
    data class Unreadable(override val link: String, val detail: String) : StagingProblem

    /** In scope, but a content limit left no room for it. */
    data class OverBudget(override val link: String, val detail: String) : StagingProblem
}

data class StagedContext(
    val pages: List<StagedPage>,
    val problems: List<StagingProblem>,
    val traces: List<ContentTrace>,
    /** Facts about what was never read at all (for example more rows in Notion than the Director lists). */
    val notes: List<String> = emptyList(),
) {
    val truncated: Boolean get() = pages.any { it.truncated } || problems.any { it is StagingProblem.OverBudget }
    val outOfScope: List<StagingProblem.OutOfScope> get() = problems.filterIsInstance<StagingProblem.OutOfScope>()

    /** Every page and row given, by id and URL. Citations are checked against exactly this. */
    val items: List<StagedItem> get() = pages.flatMap { page ->
        // A rows block lists its own items (possibly none); only a bare page is its own single item.
        if (page.items.isNotEmpty() || page.source == "database") page.items else listOf(StagedItem(idOf(page.url), page.url, page.title))
    }
    val cutItems: List<StagedItem> get() = pages.flatMap { it.cut }
    val leftOutItems: List<StagedItem> get() = pages.flatMap { it.leftOut }

    /**
     * What the model must be told it was NOT given, one line each, naming the item: items cut, items left out,
     * links that could not be read or did not fit, and what was never read. Empty when everything was given in full.
     */
    fun notGiven(maxListed: Int = MAX_LISTED): List<String> = buildList {
        cutItems.forEach { add("CUT: \"${it.title.ifBlank { "(untitled)" }}\" <${it.url}> was given only in part (see its [CUT ...] marker).") }
        val left = leftOutItems
        left.take(maxListed).forEach { add("LEFT OUT: \"${it.title.ifBlank { "(untitled)" }}\" <${it.url}> did not fit within this call's size limit.") }
        if (left.size > maxListed) add("LEFT OUT: ${left.size - maxListed} more item(s) did not fit; they are not named here.")
        problems.forEach {
            when (it) {
                is StagingProblem.Unreadable -> add("UNREADABLE: <${it.link}> could not be read (${it.detail}).")
                is StagingProblem.OverBudget -> add("LEFT OUT: <${it.link}> was not given (${it.detail}).")
                is StagingProblem.OutOfScope -> Unit // a task naming one is Blocked before any model call
            }
        }
        addAll(notes)
    }

    companion object {
        const val MAX_LISTED = 25
        val EMPTY = StagedContext(emptyList(), emptyList(), emptyList())
        private fun idOf(url: String): String = notionPageIdInPath(url) ?: url
    }
}

/**
 * Renders a row as text, **every readable property in full**. A row larger than the bound (a single very long
 * property) has its longest property cut and marked, and says so; it is never cut without a marker.
 */
object RowText {
    class Rendered(val text: String, val cutProperties: List<String>)

    fun render(row: ContextRow, maxChars: Int, header: Boolean = true): Rendered {
        var fields = row.fields
        var cut = emptyList<String>()
        fun body(f: List<RowField>) = buildString {
            if (header) appendLine("### ${row.title.ifBlank { "(untitled)" }}  <${row.pageUrl}>")
            f.forEach { appendLine("${it.name}: ${it.text.replace("\n", "\n  ")}") }
            if (row.unreadable.isNotEmpty()) appendLine("(properties this client cannot read, so not shown: ${row.unreadable.joinToString(", ")})")
        }.trimEnd()

        var text = body(fields)
        if (text.length > maxChars && fields.isNotEmpty()) {
            val longest = fields.maxBy { it.text.length }
            val over = text.length - maxChars
            fun marker(kept: Int) = "[CUT: showing $kept of ${longest.text.length} characters of this property; the rest was left out by a size limit]"
            val keep = (longest.text.length - over - marker(0).length - 8).coerceAtLeast(200).coerceAtMost(longest.text.length)
            if (keep < longest.text.length) {
                fields = fields.map { if (it === longest) RowField(it.name, it.text.take(keep) + " " + marker(keep)) else it }
                cut = listOf(longest.name)
                text = body(fields)
            }
        }
        return Rendered(text, cut)
    }
}

/**
 * Puts the in-scope content a task needs in front of it, within bounds. One stager serves one cycle: it
 * remembers the pages it has read (a page read for one task is reused by the next at no cost) and counts
 * the text it has read fresh against the cycle's limit.
 *
 * **Every link is checked against [ScopeContext.allowed] here**, whatever validated it at planning
 * time: the task's Target Links are read back from the Notion row, and a person can edit that row. A link
 * outside the scope is reported as [StagingProblem.OutOfScope] and nothing is read for it.
 *
 * **Nothing is cut silently.** A row is given with every readable property in full. When a bound is hit the
 * affected item is named: [StagedPage.cut] for text cut (with a marker in the text), [StagedPage.leftOut] for
 * a row that did not fit, [StagingProblem] for a link that could not be given. The caller shows these to the
 * model, so it never has to guess why something is missing.
 */
class TaskStager(
    private val scope: ScopeContext,
    private val reader: PageContentReader,
    private val limits: ContentLimits = ContentLimits(),
) {
    private val cache = HashMap<String, PageContent>()
    private var cycleCharsRead = 0

    /** What one staging call has given so far. */
    private inner class Run(val room: Int) {
        val pages = ArrayList<StagedPage>()
        val problems = ArrayList<StagingProblem>()
        val traces = ArrayList<ContentTrace>()
        val notes = ArrayList<String>()
        val givenIds = HashSet<String>()
        val stagedTables = HashSet<String>()
        var chars = 0
        val left: Int get() = room - chars
        fun result() = StagedContext(pages, problems, traces, notes)
    }

    /**
     * Stages a task's own links, in order. With [withScope], the goal's other in-scope rows and pages follow,
     * in the order Scope mentions them, so a task that must verify or consolidate has the source in front of it
     * and not only what a plan chose to link. Both share the one per-task bound.
     */
    suspend fun stage(links: List<String>, withScope: Boolean = false): StagedContext {
        val run = Run(limits.perTaskChars)
        for (link in links.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) stageLink(run, link)
        if (withScope) stageScopeInto(run)
        return run.result()
    }

    /** The goal's in-scope rows and pages as source material, in Scope order, within [room] characters. Used by QC. */
    suspend fun stageSources(room: Int = limits.perTaskChars): StagedContext {
        val run = Run(room)
        stageScopeInto(run)
        return run.result()
    }

    // ── One link a task named ────────────────────────────────────────────────

    private suspend fun stageLink(run: Run, link: String) {
        if (!scope.allowed.allows(link)) { run.problems += StagingProblem.OutOfScope(link); return }
        val id = notionPageIdInPath(link)
        if (id == null) { run.problems += StagingProblem.OutOfScope(link); return }
        if (run.left < MIN_ROOM) {
            run.problems += StagingProblem.OverBudget(link, "this call already has its ${run.room} characters of page content")
            run.traces += ContentTrace(link, "", 0, truncated = true, note = "not staged: task content limit")
            return
        }
        val table = scope.tableOf(link)
        if (table != null) {
            if (run.stagedTables.add(table)) stageRows(run, link, table, scope.rowsOf(table).filter { it.pageId !in run.givenIds }, scope.truncated)
        } else {
            stagePage(run, link, id)
        }
    }

    // ── The rest of the scope ────────────────────────────────────────────────

    private suspend fun stageScopeInto(run: Run) {
        for (outcome in scope.outcomes) {
            when (outcome) {
                is ScopeOutcome.PageRead -> {
                    if (outcome.pageId in run.givenIds) continue
                    val link = notionPageUrl(outcome.pageId)
                    if (run.left < MIN_ROOM) {
                        run.problems += StagingProblem.OverBudget(link, "no room left in this call's ${run.room} characters")
                        continue
                    }
                    stagePage(run, link, outcome.pageId)
                }
                is ScopeOutcome.TableListed -> {
                    if (!run.stagedTables.add(outcome.table)) continue
                    val rows = scope.rowsOf(outcome.table).filter { it.pageId !in run.givenIds }
                    stageRows(run, notionPageUrl(outcome.mention.id), outcome.table, rows, outcome.truncated)
                }
                is ScopeOutcome.Skipped -> Unit
            }
        }
    }

    // ── A table's rows ───────────────────────────────────────────────────────

    private fun stageRows(run: Run, link: String, table: String, rows: List<ContextRow>, moreInNotion: Boolean) {
        val given = ArrayList<StagedItem>()
        val cut = ArrayList<StagedItem>()
        val left = ArrayList<StagedItem>()
        val text = StringBuilder()
        var full = false
        for (row in rows) {
            val item = StagedItem(row.pageId, row.pageUrl, row.title)
            if (full) { left += item; continue }
            val rendered = RowText.render(row, limits.perPageChars)
            val add = rendered.text.length + (if (text.isEmpty()) 0 else 2)
            if (add > run.left - text.length - FOOTER_ROOM) { full = true; left += item; continue }
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(rendered.text)
            given += item
            run.givenIds += row.pageId
            if (rendered.cutProperties.isNotEmpty()) cut += item
        }
        if (moreInNotion) run.notes += "NEVER READ: $table has more rows in Notion than the Director lists; the rows past the listed ones were not read."
        if (left.isNotEmpty()) text.append("\n\n[… ${left.size} more row(s) of this table did not fit; they are listed under NOT GIVEN TO YOU]")
        run.chars += text.length
        val truncated = cut.isNotEmpty() || left.isNotEmpty()
        run.pages += StagedPage(link, "Rows of $table (${given.size} of ${rows.size} given)", "database", text.toString(), truncated, given, cut, left)
        run.traces += ContentTrace(link, "Rows of $table", text.length, truncated, note = noteOf(given.size, cut.size, left.size))
    }

    // ── A page ───────────────────────────────────────────────────────────────

    private suspend fun stagePage(run: Run, link: String, id: String) {
        val row = scope.row(id)
        val content = cache[id] ?: run {
            val left = limits.perCycleChars - cycleCharsRead
            if (left <= 0) {
                run.problems += StagingProblem.OverBudget(link, "the cycle has already read ${limits.perCycleChars} characters of page content")
                run.traces += ContentTrace(link, row?.title.orEmpty(), 0, truncated = true, note = "not read: cycle content limit")
                return
            }
            val read = try {
                reader.read(id, maxChars = minOf(limits.perPageChars, left))
            } catch (e: NotionApiException) {
                run.problems += StagingProblem.Unreadable(link, "Notion answered ${e.status}${e.code?.let { " ($it)" }.orEmpty()}")
                return
            } catch (e: NotionGuardViolation) {
                run.problems += StagingProblem.Unreadable(link, "the guard would not list this page's blocks")
                return
            }
            cycleCharsRead += read.text.length
            cache[id] = read
            read
        }
        val title = row?.title.orEmpty().ifBlank { "(untitled)" }
        var cutText = false
        var text = buildString {
            if (row != null && row.fields.isNotEmpty()) {
                val props = RowText.render(row, limits.perPageChars, header = false)
                if (props.cutProperties.isNotEmpty()) cutText = true
                appendLine("Properties:").appendLine(props.text.prependIndent("  "))
            }
            if (content.text.isNotBlank()) append(content.text) else append("(this page has no readable text)")
        }
        if (content.truncated) { text += "\n[CUT: this page has more text than the size limit allowed; the rest was not read]"; cutText = true }
        if (text.length > run.left) {
            val keep = (run.left - 90).coerceAtLeast(0)
            val shown = text.take(keep).substringBeforeLast('\n', text.take(keep))
            text = shown + "\n[CUT: ${text.length - shown.length} characters of this page were left out by this call's size limit]"
            cutText = true
        }
        run.chars += text.length
        val item = StagedItem(id, link, title)
        run.givenIds += id
        run.pages += StagedPage(link, title, row?.table ?: "page", text, cutText, listOf(item), if (cutText) listOf(item) else emptyList())
        run.traces += ContentTrace(link, title, text.length, cutText, note = if (cutText) "cut by a size limit" else null)
    }

    private fun noteOf(given: Int, cut: Int, left: Int): String? =
        if (cut == 0 && left == 0) null else "$given row(s) given, $cut with a property cut, $left left out by a size limit"

    private companion object {
        /** Characters kept free in a rows block for the line that says how many rows did not fit. */
        const val FOOTER_ROOM = 120

        /** Less than this left and a page would be only a marker, so it is reported as not given instead. */
        const val MIN_ROOM = 300
    }
}
