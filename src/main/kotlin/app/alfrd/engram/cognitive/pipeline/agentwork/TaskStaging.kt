package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionApiException
import app.alfrd.engram.notion.NotionGuardViolation
import app.alfrd.engram.notion.normalizeNotionId

/** One page, or one database's rows, put in front of a task. [text] is already within the limits. */
data class StagedPage(
    val url: String,
    val title: String,
    /** The table a row belongs to, `database` for a mentioned database's rows, or the allowlist name of a plain page. */
    val source: String,
    val text: String,
    val truncated: Boolean,
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
) {
    val truncated: Boolean get() = pages.any { it.truncated } || problems.any { it is StagingProblem.OverBudget }
    val outOfScope: List<StagingProblem.OutOfScope> get() = problems.filterIsInstance<StagingProblem.OutOfScope>()
}

/**
 * Puts the in-scope content a task needs in front of it, within bounds. One stager serves one cycle: it
 * remembers the pages it has read (a page read for one task is reused by the next at no cost) and counts
 * the text it has read fresh against the cycle's limit.
 *
 * **Every link is checked against [ScopeContext.allowed] here**, whatever validated it at planning
 * time: the task's Target Links are read back from the Notion row, and a person can edit that row. A link
 * outside the scope is reported as [StagingProblem.OutOfScope] and nothing is read for it.
 */
class TaskStager(
    private val scope: ScopeContext,
    private val reader: PageContentReader,
    private val limits: ContentLimits = ContentLimits(),
) {
    private val cache = HashMap<String, PageContent>()
    private var cycleCharsRead = 0

    suspend fun stage(links: List<String>): StagedContext {
        val pages = ArrayList<StagedPage>()
        val problems = ArrayList<StagingProblem>()
        val traces = ArrayList<ContentTrace>()
        var taskChars = 0

        for (link in links.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            if (!scope.allowed.allows(link)) { problems += StagingProblem.OutOfScope(link); continue }
            val id = notionPageIdInPath(link)
            if (id == null) { problems += StagingProblem.OutOfScope(link); continue }
            val room = limits.perTaskChars - taskChars
            if (room <= 0) {
                problems += StagingProblem.OverBudget(link, "this task already has its ${limits.perTaskChars} characters of page content")
                traces += ContentTrace(link, "", 0, truncated = true, note = "not staged: task content limit")
                continue
            }

            val table = scope.tableOf(link)
            val staged = if (table != null) stageDatabase(link, table, room) else stagePage(link, id, room, problems, traces)
            if (staged == null) continue
            pages += staged
            taskChars += staged.text.length
            traces += ContentTrace(staged.url, staged.title, staged.text.length, staged.truncated, note = if (staged.truncated) "cut by a size limit" else null)
        }
        return StagedContext(pages, problems, traces)
    }

    private fun stageDatabase(link: String, table: String, room: Int): StagedPage {
        val rows = scope.rowsOf(table)
        val lines = rows.map { row -> "- ${row.title.ifBlank { "(untitled)" }} <${row.pageUrl}>${if (row.facts.isEmpty()) "" else " | " + row.facts.joinToString("; ")}" }
        var text = lines.joinToString("\n")
        var cut = scope.truncated
        if (text.length > room.coerceAtMost(limits.perPageChars)) {
            val max = room.coerceAtMost(limits.perPageChars)
            text = text.take(max).substringBeforeLast('\n', text.take(max))
            cut = true
        }
        if (cut) text += "\n[… more rows exist than are shown]"
        return StagedPage(link, "Rows of $table (${rows.size} listed)", "database", text, cut)
    }

    private suspend fun stagePage(link: String, id: String, room: Int, problems: MutableList<StagingProblem>, traces: MutableList<ContentTrace>): StagedPage? {
        val row = scope.row(id)
        val content = cache[id] ?: run {
            val left = limits.perCycleChars - cycleCharsRead
            if (left <= 0) {
                problems += StagingProblem.OverBudget(link, "the cycle has already read ${limits.perCycleChars} characters of page content")
                traces += ContentTrace(link, row?.title.orEmpty(), 0, truncated = true, note = "not read: cycle content limit")
                return null
            }
            val read = try {
                reader.read(id, maxChars = minOf(limits.perPageChars, left))
            } catch (e: NotionApiException) {
                problems += StagingProblem.Unreadable(link, "Notion answered ${e.status}${e.code?.let { " ($it)" }.orEmpty()}")
                return null
            } catch (e: NotionGuardViolation) {
                problems += StagingProblem.Unreadable(link, "the guard would not list this page's blocks")
                return null
            }
            cycleCharsRead += read.text.length
            cache[id] = read
            read
        }
        var text = buildString {
            if (row != null && row.facts.isNotEmpty()) appendLine("Properties: ${row.facts.joinToString("; ")}")
            if (content.text.isNotBlank()) append(content.text) else append("(this page has no readable text)")
        }
        var cut = content.truncated
        if (text.length > room) {
            text = text.take(room).substringBeforeLast('\n', text.take(room)) + "\n[… cut: this task's content limit]"
            cut = true
        }
        return StagedPage(link, row?.title.orEmpty().ifBlank { "(untitled)" }, row?.table ?: "page", text, cut)
    }
}
