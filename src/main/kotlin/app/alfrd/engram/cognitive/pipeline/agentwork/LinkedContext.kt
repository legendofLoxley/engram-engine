package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionApiException
import app.alfrd.engram.notion.NotionGuardViolation
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionSort
import app.alfrd.engram.notion.NotionTable
import app.alfrd.engram.notion.NotionTableSet
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.normalizeNotionId
import java.net.URI

/** The canonical way this package writes the URL of a Notion page. */
fun notionPageUrl(pageId: String): String = "https://www.notion.so/${pageId.replace("-", "")}"

/**
 * What a plan's Target Links are allowed to point at, decided by code from what the Director
 * actually put in front of the model, and not from the goal's prose `Scope`:
 * a Notion page by id (in any URL spelling), or an external URL by exact match.
 */
class AllowedLinks(pageIds: Set<String>, externalUrls: Set<String>) {
    private val pages: Set<String> = pageIds.mapNotNull(::normalizeNotionId).toSet()
    private val external: Set<String> = externalUrls.mapNotNull(::canonicalExternal).toSet()

    val pageIdCount: Int get() = pages.size
    val externalCount: Int get() = external.size

    fun allows(url: String): Boolean {
        val trimmed = url.trim()
        if (isNotionUrl(trimmed)) return normalizeNotionId(trimmed)?.let { it in pages } == true
        return canonicalExternal(trimmed)?.let { it in external } == true
    }

    companion object {
        val NONE = AllowedLinks(emptySet(), emptySet())

        fun isNotionUrl(url: String): Boolean {
            val host = hostOf(url) ?: return false
            return host == "notion.so" || host.endsWith(".notion.so") || host == "notion.com" || host.endsWith(".notion.com") || host.endsWith(".notion.site")
        }

        /** `https` or `http` only; scheme and host lowercased; no fragment; no trailing slash. Null for anything else. */
        fun canonicalExternal(url: String): String? {
            val uri = parse(url) ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase() ?: return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return "$scheme://$host$port$path$query"
        }

        private fun parse(url: String): URI? = try { URI(url.trim()) } catch (_: Exception) { null }

        private fun hostOf(url: String): String? = parse(url)?.takeIf { it.scheme?.lowercase() in setOf("http", "https") }?.host?.lowercase()
    }
}

/** One row of a configured table that the Director read for context. [facts] is an informational digest, not a mapping. */
data class ContextRow(
    val table: String,
    val pageId: String,
    val pageUrl: String,
    val title: String,
    val facts: List<String>,
    /** A URL-typed property of the row, such as a Source's own URL. */
    val externalUrl: String?,
)

enum class SkipReason {
    /** A Notion page that is not a row of any configured table, such as "How this ledger works". Reported and skipped, never read. */
    NOT_A_CONFIGURED_ROW,

    /** Notion would not return the page (not found, not shared with the integration, or not a page). */
    UNREADABLE,

    /** A Notion URL with no page or database id in it. */
    NOT_A_PAGE_LINK,

    /** Past the limit on how many links of one goal are followed. */
    TOO_MANY_LINKS,
}

/** What became of each link on the goal. This is what the Director reports for "read the target pages it links to". */
sealed interface LinkOutcome {
    val link: String

    /** The link points at a configured table (as a database or data source page); its rows were listed. */
    data class TableListed(override val link: String, val table: String, val rows: Int, val truncated: Boolean) : LinkOutcome

    /** The link points at one row of a configured table; the page was read. */
    data class RowRead(override val link: String, val table: String, val pageId: String) : LinkOutcome

    /** A non-Notion URL: kept as an allowed link, never fetched. */
    data class External(override val link: String) : LinkOutcome

    data class Skipped(override val link: String, val reason: SkipReason, val detail: String? = null) : LinkOutcome
}

data class LinkedContext(
    val rows: List<ContextRow>,
    val outcomes: List<LinkOutcome>,
    val allowed: AllowedLinks,
    /** True when a limit cut the rows short. */
    val truncated: Boolean,
) {
    val skipped: List<LinkOutcome.Skipped> get() = outcomes.filterIsInstance<LinkOutcome.Skipped>()

    companion object {
        val EMPTY = LinkedContext(emptyList(), emptyList(), AllowedLinks.NONE, truncated = false)
    }
}

/**
 * Reads what a goal's links point at, through the guarded client and nothing else. Read-only by
 * construction: it calls `query` and `retrievePage` and no write.
 *
 * A goal's `Target Links` hold page mentions, and people paste *database* pages; the data source
 * ids the client needs are different. [NotionTable.databaseId] is what lets a database link be
 * recognized as one of the configured tables.
 *
 * It never widens the guard. A link to a page that is not a row of a configured table is fetched by
 * the guard's own rule (check after fetch), refused, reported, and nothing from it is kept.
 */
class LinkedContextReader(
    private val api: NotionApi,
    private val tableSet: NotionTableSet,
    private val maxRowsPerTable: Int = 50,
    private val maxLinks: Int = 12,
    private val maxFactsPerRow: Int = 12,
    private val maxFactChars: Int = 240,
) {
    init {
        require(maxRowsPerTable in 1..NotionApi.MAX_PAGE_SIZE) { "maxRowsPerTable must be 1..${NotionApi.MAX_PAGE_SIZE}" }
        require(maxLinks >= 1) { "maxLinks must be at least 1" }
    }

    suspend fun read(goal: Goal): LinkedContext {
        val rows = LinkedHashMap<String, ContextRow>()
        val outcomes = ArrayList<LinkOutcome>()
        val externalUrls = LinkedHashSet<String>()
        val allowedPages = LinkedHashSet<String>()
        var truncated = false

        val links = goal.targetLinks.distinct()
        for ((index, link) in links.withIndex()) {
            if (index >= maxLinks) {
                outcomes += LinkOutcome.Skipped(link, SkipReason.TOO_MANY_LINKS, "only the first $maxLinks links are followed")
                continue
            }
            if (!AllowedLinks.isNotionUrl(link)) {
                if (AllowedLinks.canonicalExternal(link) != null) {
                    externalUrls += link
                    outcomes += LinkOutcome.External(link)
                } else {
                    outcomes += LinkOutcome.Skipped(link, SkipReason.NOT_A_PAGE_LINK, "not an http(s) URL")
                }
                continue
            }
            val id = normalizeNotionId(link)
            if (id == null) {
                outcomes += LinkOutcome.Skipped(link, SkipReason.NOT_A_PAGE_LINK, "no page or database id in the URL")
                continue
            }
            val table = tableSet.tableForDatabase(id) ?: tableSet.tableForDataSource(id)
            if (table != null) {
                val listed = listRows(table)
                listed.rows.forEach { rows.putIfAbsent(it.pageId, it) }
                allowedPages += id
                truncated = truncated || listed.truncated
                outcomes += LinkOutcome.TableListed(link, table.name, listed.rows.size, listed.truncated)
                continue
            }
            try {
                val page = api.retrievePage(id)
                val tableName = page.dataSourceId?.let(tableSet::tableForDataSource)?.name
                if (tableName == null) {
                    outcomes += LinkOutcome.Skipped(link, SkipReason.NOT_A_CONFIGURED_ROW, "the page is not a row of a configured table")
                    continue
                }
                rows.putIfAbsent(page.id, rowOf(tableName, page))
                allowedPages += page.id
                outcomes += LinkOutcome.RowRead(link, tableName, page.id)
            } catch (e: NotionGuardViolation) {
                outcomes += LinkOutcome.Skipped(link, SkipReason.NOT_A_CONFIGURED_ROW, "the page is not a row of a configured table")
            } catch (e: NotionApiException) {
                outcomes += LinkOutcome.Skipped(link, SkipReason.UNREADABLE, "Notion answered ${e.status}${e.code?.let { " ($it)" }.orEmpty()}")
            }
        }

        rows.values.forEach { row ->
            allowedPages += row.pageId
            row.externalUrl?.let { externalUrls += it }
        }
        return LinkedContext(rows.values.toList(), outcomes, AllowedLinks(allowedPages, externalUrls), truncated)
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
        val facts = ArrayList<String>()
        var external: String? = null
        for ((name, value) in page.properties) {
            if (facts.size >= maxFactsPerRow) break
            val fact = when (value) {
                is NotionValue.Title -> null
                is NotionValue.RichText -> value.text.takeIf { it.isNotBlank() }?.let { "$name: ${it.shorten()}" }
                is NotionValue.Select -> value.name?.let { "$name: $it" }
                is NotionValue.NumberValue -> value.value?.let { "$name: ${trim(it)}" }
                is NotionValue.UrlValue -> value.value?.also { if (external == null) external = it }?.let { "$name: $it" }
                is NotionValue.DateValue -> value.start?.let { "$name: $it" }
                is NotionValue.Checkbox -> if (value.checked) "$name: yes" else null
                is NotionValue.Formula -> value.display.takeIf { it.isNotBlank() }?.let { "$name: $it (formula, informational)" }
                is NotionValue.Rollup -> rollupText(value)?.let { "$name: $it (rollup, informational)" }
                else -> null
            }
            if (fact != null) facts += fact
        }
        return ContextRow(table, page.id, notionPageUrl(page.id), title, facts, external)
    }

    private fun rollupText(rollup: NotionValue.Rollup): String? = when {
        rollup.number != null -> trim(rollup.number!!)
        rollup.date?.start != null -> rollup.date!!.start
        else -> null
    }

    private fun trim(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    private fun String.shorten(): String {
        val oneLine = replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= maxFactChars) oneLine else oneLine.take(maxFactChars - 1) + "…"
    }
}
