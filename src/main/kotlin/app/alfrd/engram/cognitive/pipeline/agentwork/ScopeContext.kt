package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.MentionKind
import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionApiException
import app.alfrd.engram.notion.NotionGuardViolation
import app.alfrd.engram.notion.NotionMention
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionSort
import app.alfrd.engram.notion.NotionTable
import app.alfrd.engram.notion.NotionTableSet
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.normalizeNotionId
import java.net.URI

/** The canonical way this package writes the URL of a Notion page. */
fun notionPageUrl(pageId: String): String = "https://www.notion.so/${pageId.replace("-", "")}"

/** Whether [url] is an http(s) URL on a Notion host. Anything else, an external site included, can never be in a goal's scope. */
fun isNotionUrl(url: String): Boolean {
    val uri = try { URI(url.trim()) } catch (_: Exception) { return false }
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "notion.so" || host.endsWith(".notion.so") || host == "notion.com" || host.endsWith(".notion.com") || host.endsWith(".notion.site")
}

/**
 * The page id a Notion URL addresses: the one in its **path**. An id in the user-info
 * (`https://<id>@www.notion.so/other`), the fragment or the query does not address that page, so it
 * must not make a link look in scope. A URL with user-info is refused outright. Every place that turns a
 * task's link into an id uses this one function, so the page that was checked is the page that is read.
 */
fun notionPageIdInPath(url: String): String? {
    val uri = try { URI(url.trim()) } catch (_: Exception) { return null }
    if (uri.rawUserInfo != null) return null
    return normalizeNotionId(uri.rawPath ?: return null)
}

/** An http(s) URL, or null. Used to tell "not a URL at all" from "a URL outside the scope". */
fun isHttpUrl(url: String): Boolean {
    val uri = try { URI(url.trim()) } catch (_: Exception) { return false }
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}

/**
 * **What a goal may touch**, decided in code from the goal's Scope mentions and nothing else:
 * the mentioned pages, the mentioned databases, and the rows of a mentioned database. A link a plan
 * proposes, and a page a task reads, must be one of these; everything else is rejected, including any
 * external URL (Scope holds no external URLs, so none is ever in scope).
 *
 * Rows are the ones the Director listed from each mentioned database (up to the reader's per-table
 * limit, oldest first, so a later row never displaces an earlier one). A row beyond that limit is not
 * in the set, which is the cautious side: a link to it is rejected, never guessed at.
 */
class AllowedScope(pageIds: Set<String>, databaseIds: Set<String>, rowIds: Set<String>) {
    private val pages: Set<String> = pageIds.mapNotNull(::normalizeNotionId).toSet()
    private val databases: Set<String> = databaseIds.mapNotNull(::normalizeNotionId).toSet()
    private val rows: Set<String> = rowIds.mapNotNull(::normalizeNotionId).toSet()

    val pageCount: Int get() = pages.size
    val databaseCount: Int get() = databases.size
    val rowCount: Int get() = rows.size

    fun allows(url: String): Boolean {
        val trimmed = url.trim()
        if (!isNotionUrl(trimmed)) return false
        val id = notionPageIdInPath(trimmed) ?: return false
        return id in pages || id in databases || id in rows
    }

    /** True when [url] is a link to one of the mentioned databases themselves (not to a row of one). */
    fun isDatabase(url: String): Boolean = isNotionUrl(url.trim()) && notionPageIdInPath(url)?.let { it in databases } == true

    companion object {
        val NONE = AllowedScope(emptySet(), emptySet(), emptySet())
    }
}

/** One page the Director read for context: a mentioned page, or a row of a mentioned database. [facts] is an informational digest of its properties, not a mapping. */
data class ContextRow(
    val table: String,
    val pageId: String,
    val pageUrl: String,
    val title: String,
    /** A short digest for the planner: each property on one line, long text cut with a marker that says so. Not what a worker is given. */
    val facts: List<String>,
    /** Every readable property in full, untouched, in the page's own order. This is what a task or QC is shown. */
    val fields: List<RowField> = emptyList(),
    /** Names of properties this client does not read (people, files, relations, ...): shown to the worker as not given, never silently dropped. */
    val unreadable: List<String> = emptyList(),
    /** How many facts the digest left out because of its count limit. */
    val factsOmitted: Int = 0,
)

/** One property of a row, as text. [text] is never shortened here. */
data class RowField(val name: String, val text: String)

enum class SkipReason {
    /** A database mention whose id is not one of the configured tables. The client was never configured to read it. */
    DATABASE_NOT_CONFIGURED,

    /** A page that is neither a row of a configured table nor an allowlisted page (the guard refused it). */
    NOT_READABLE,

    /** Notion would not return it (not found, or not shared with the integration). */
    UNREADABLE,

    /** Past the limit on how many mentions of one goal are followed. */
    TOO_MANY_MENTIONS,
}

/** What became of each mention of a goal's Scope. */
sealed interface ScopeOutcome {
    val mention: NotionMention

    /** The mention is a configured table (a database mention, or a page mention of one); its rows were listed. */
    data class TableListed(override val mention: NotionMention, val table: String, val rows: Int, val truncated: Boolean) : ScopeOutcome

    /** The mention is one page: a row of a configured table, or an allowlisted page. [label] is the table or the allowlist name. */
    data class PageRead(override val mention: NotionMention, val label: String, val pageId: String) : ScopeOutcome

    data class Skipped(override val mention: NotionMention, val reason: SkipReason, val detail: String? = null) : ScopeOutcome
}

/** Everything the Director learned from a goal's Scope: what it may touch, what it read, and what it could not. */
data class ScopeContext(
    val rows: List<ContextRow>,
    val outcomes: List<ScopeOutcome>,
    val allowed: AllowedScope,
    /** True when a limit cut the rows short. */
    val truncated: Boolean,
    /** database id and data source id (normalized) -> table name, for each mentioned configured table. */
    val tablesByDatabaseId: Map<String, String>,
) {
    val skipped: List<ScopeOutcome.Skipped> get() = outcomes.filterIsInstance<ScopeOutcome.Skipped>()

    /** At least one mention could be read. A scope with none is a Failed cycle, not a plan from nothing. */
    val usable: Boolean get() = outcomes.any { it !is ScopeOutcome.Skipped }

    private val byId: Map<String, ContextRow> = rows.associateBy { it.pageId }

    /** Short names (P1, D1, R1) for what this scope puts in reach, shown to every model call of the cycle. */
    val handles: Handles by lazy { Handles.of(this) }

    fun row(pageId: String): ContextRow? = normalizeNotionId(pageId)?.let(byId::get)
    fun rowsOf(table: String): List<ContextRow> = rows.filter { it.table == table }
    fun tableOf(url: String): String? = notionPageIdInPath(url)?.let(tablesByDatabaseId::get)

    companion object {
        val EMPTY = ScopeContext(emptyList(), emptyList(), AllowedScope.NONE, truncated = false, tablesByDatabaseId = emptyMap())
    }
}

/**
 * Reads what a goal's Scope mentions, through the guarded client and nothing else. Read-only by
 * construction: it calls `query` and `retrievePage` and no write.
 *
 * - A database mention (or a page mention whose id is a configured table) puts that table's rows in scope.
 *   Its `databaseId` in the configuration is what lets a mention of the *database* be recognized as one of
 *   the configured *data sources*.
 * - A page mention puts that page in scope, provided the guard will let the Director read it: a row of a
 *   configured table, or the allowlisted Persona page. A mentioned page outside both is reported and
 *   skipped, never read.
 *
 * It never widens the guard: the allowlist is fixed when the client is built.
 */
class ScopeReader(
    private val api: NotionApi,
    private val tableSet: NotionTableSet,
    private val maxRowsPerTable: Int = 100,
    private val maxMentions: Int = 12,
    private val maxFactsPerRow: Int = 12,
    private val maxFactChars: Int = 240,
) {
    init {
        require(maxRowsPerTable in 1..NotionApi.MAX_PAGE_SIZE) { "maxRowsPerTable must be 1..${NotionApi.MAX_PAGE_SIZE}" }
        require(maxMentions >= 1) { "maxMentions must be at least 1" }
    }

    suspend fun read(scope: GoalScope): ScopeContext {
        val rows = LinkedHashMap<String, ContextRow>()
        val outcomes = ArrayList<ScopeOutcome>()
        val pageIds = LinkedHashSet<String>()
        val databaseIds = LinkedHashSet<String>()
        val rowIds = LinkedHashSet<String>()
        val tables = LinkedHashMap<String, String>()
        val listedTables = HashSet<String>()
        var truncated = false

        for ((index, mention) in scope.mentions.withIndex()) {
            if (index >= maxMentions) {
                outcomes += ScopeOutcome.Skipped(mention, SkipReason.TOO_MANY_MENTIONS, "only the first $maxMentions mentions are followed")
                continue
            }
            val table = tableSet.tableForDatabase(mention.id) ?: tableSet.tableForDataSource(mention.id)
            if (table != null) {
                databaseIds += table.dataSourceId
                tables[table.dataSourceId] = table.name
                table.databaseId?.let { databaseIds += it; tables[it] = table.name }
                if (!listedTables.add(table.name)) continue // already listed through another mention of the same table
                try {
                    val listed = listRows(table)
                    listed.rows.forEach { rows.putIfAbsent(it.pageId, it); rowIds += it.pageId }
                    truncated = truncated || listed.truncated
                    outcomes += ScopeOutcome.TableListed(mention, table.name, listed.rows.size, listed.truncated)
                } catch (e: NotionApiException) {
                    outcomes += ScopeOutcome.Skipped(mention, SkipReason.UNREADABLE, "Notion answered ${e.status}${e.code?.let { " ($it)" }.orEmpty()}")
                }
                continue
            }
            if (mention.kind == MentionKind.DATABASE) {
                outcomes += ScopeOutcome.Skipped(mention, SkipReason.DATABASE_NOT_CONFIGURED, "the database is not one of this set's configured tables")
                continue
            }
            try {
                val page = api.retrievePage(mention.id)
                val label = page.dataSourceId?.let(tableSet::tableForDataSource)?.name
                    ?: tableSet.readablePages.firstOrNull { it.pageId == page.id }?.name
                if (label == null) {
                    outcomes += ScopeOutcome.Skipped(mention, SkipReason.NOT_READABLE, "the page is neither a row of a configured table nor an allowlisted page")
                    continue
                }
                rows.putIfAbsent(page.id, rowOf(label, page))
                pageIds += page.id
                outcomes += ScopeOutcome.PageRead(mention, label, page.id)
            } catch (e: NotionGuardViolation) {
                outcomes += ScopeOutcome.Skipped(mention, SkipReason.NOT_READABLE, "the page is neither a row of a configured table nor an allowlisted page")
            } catch (e: NotionApiException) {
                outcomes += ScopeOutcome.Skipped(mention, SkipReason.UNREADABLE, "Notion answered ${e.status}${e.code?.let { " ($it)" }.orEmpty()}")
            }
        }
        return ScopeContext(rows.values.toList(), outcomes, AllowedScope(pageIds, databaseIds, rowIds), truncated, tables.mapKeys { it.key })
    }

    private class Listed(val rows: List<ContextRow>, val truncated: Boolean)

    private suspend fun listRows(table: NotionTable): Listed {
        val page = api.query(
            table.dataSourceId,
            sorts = listOf(NotionSort.ByTimestamp(NotionSort.Timestamp.CREATED_TIME, ascending = true)),
            pageSize = maxRowsPerTable,
        )
        return Listed(page.pages.map { rowOf(table.name, it) }, truncated = page.nextCursor != null)
    }

    private fun rowOf(table: String, page: NotionPage): ContextRow {
        val title = page.properties.values.filterIsInstance<NotionValue.Title>().firstOrNull()?.text.orEmpty()
        val fields = ArrayList<RowField>()
        val unreadable = ArrayList<String>()
        for ((name, value) in page.properties) {
            val text = when (value) {
                is NotionValue.Title -> null
                is NotionValue.RichText -> value.text.trim().takeIf { it.isNotEmpty() }
                is NotionValue.Select -> value.name
                is NotionValue.NumberValue -> value.value?.let { trim(it) }
                is NotionValue.UrlValue -> value.value
                is NotionValue.DateValue -> value.start
                is NotionValue.Checkbox -> if (value.checked) "yes" else null
                is NotionValue.UniqueId -> value.display
                is NotionValue.Formula -> value.display.takeIf { it.isNotBlank() }?.let { "$it (formula, informational)" }
                is NotionValue.Rollup -> rollupText(value)?.let { "$it (rollup, informational)" }
                // A relation with something in it, or a kind this client cannot read, is named as not shown; an empty relation says nothing.
                is NotionValue.Relation -> { if (value.ids.isNotEmpty()) unreadable += name; null }
                is NotionValue.Unsupported -> { unreadable += name; null }
                is NotionValue.CreatedTime -> null
                else -> null
            }
            if (text != null) fields += RowField(name, text)
        }
        // The planner's digest: one line per property, long text cut with a marker, at most [maxFactsPerRow] lines.
        val allFacts = fields.map { f -> "${f.name}: ${f.text.shorten()}" }
        return ContextRow(table, page.id, notionPageUrl(page.id), title, allFacts.take(maxFactsPerRow), fields, unreadable, (allFacts.size - maxFactsPerRow).coerceAtLeast(0))
    }

    private fun rollupText(rollup: NotionValue.Rollup): String? = when {
        rollup.number != null -> trim(rollup.number!!)
        rollup.date?.start != null -> rollup.date!!.start
        else -> null
    }

    private fun trim(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    /** One line, and if it is too long a marker that says how much was cut, so no reader mistakes the cut for the source. */
    private fun String.shorten(): String {
        val oneLine = replace(Regex("\\s+"), " ").trim()
        if (oneLine.length <= maxFactChars) return oneLine
        val keep = maxFactChars - 45
        return oneLine.take(keep) + "[cut: showing $keep of ${oneLine.length} characters]"
    }
}
