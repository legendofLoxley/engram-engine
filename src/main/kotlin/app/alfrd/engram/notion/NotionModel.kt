package app.alfrd.engram.notion

import java.time.Instant

/**
 * Notion ids are 32 hex digits, written with or without dashes, sometimes inside a URL or a
 * `collection://` reference. Everything in this package compares the dashed lowercase form, so an
 * allowlist entry copied from a URL still matches an id the API returns.
 */
private val NOTION_ID_PATTERN = Regex("([0-9a-fA-F]{8})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{12})")

/** The dashed lowercase id found in [raw] (a bare id, a `collection://` ref or a Notion URL), or null. For a URL the last id before any query string wins. */
fun normalizeNotionId(raw: String?): String? {
    val text = raw?.trim()?.substringBefore('?')?.takeIf { it.isNotEmpty() } ?: return null
    val match = NOTION_ID_PATTERN.findAll(text).lastOrNull() ?: return null
    return match.groupValues.drop(1).joinToString("-").lowercase()
}

/** One property value as this package reads and writes it. Types it never touches are kept only as [Unsupported] so a read never fails on them. */
sealed interface NotionValue {
    data class Title(val text: String) : NotionValue
    data class RichText(val text: String) : NotionValue
    /** [name] null is an empty select; written, it clears the property. */
    data class Select(val name: String?) : NotionValue
    data class Checkbox(val checked: Boolean) : NotionValue

    /** ISO-8601 date (`2026-10-10`) or datetime (`2026-10-10T14:00:00.000-04:00`). [start] null is an empty date; written, it clears the property. */
    data class DateValue(val start: String?, val end: String? = null) : NotionValue {
        val hasTime: Boolean get() = start?.contains('T') == true
    }

    /** [hasMore] is true when Notion returned only the first 25 related pages; read the relation from the other side before trusting this list as complete. */
    data class Relation(val ids: List<String>, val hasMore: Boolean = false) : NotionValue

    /** Read-only. */
    data class CreatedTime(val at: Instant) : NotionValue

    data class Unsupported(val type: String) : NotionValue
}

/** A row of a data source (or a plain page, whose [dataSourceId] is null). */
data class NotionPage(
    val id: String,
    val dataSourceId: String?,
    val createdTime: Instant,
    val lastEditedTime: Instant,
    val properties: Map<String, NotionValue>,
    val archived: Boolean = false,
) {
    fun title(name: String): String = (properties[name] as? NotionValue.Title)?.text.orEmpty()
    fun text(name: String): String = (properties[name] as? NotionValue.RichText)?.text.orEmpty()
    fun select(name: String): String? = (properties[name] as? NotionValue.Select)?.name
    fun checkbox(name: String): Boolean = (properties[name] as? NotionValue.Checkbox)?.checked ?: false
    fun date(name: String): NotionValue.DateValue? = (properties[name] as? NotionValue.DateValue)?.takeIf { it.start != null }
    fun relation(name: String): List<String> = (properties[name] as? NotionValue.Relation)?.ids.orEmpty()

    /** True when Notion returned only part of this relation (it caps a page's inline relation at 25). */
    fun relationHasMore(name: String): Boolean = (properties[name] as? NotionValue.Relation)?.hasMore == true
}

/** The subset of Notion's query filters this package uses. Compiled to API JSON by [HttpNotionApi] and interpreted by the test fake. */
sealed interface NotionFilter {
    enum class TextKind { TITLE, RICH_TEXT }

    data class SelectEquals(val property: String, val name: String) : NotionFilter
    data class SelectDoesNotEqual(val property: String, val name: String) : NotionFilter
    data class SelectIsEmpty(val property: String) : NotionFilter
    data class SelectIsNotEmpty(val property: String) : NotionFilter
    data class CheckboxEquals(val property: String, val value: Boolean) : NotionFilter
    data class DateOnOrBefore(val property: String, val iso: String) : NotionFilter
    data class DateOnOrAfter(val property: String, val iso: String) : NotionFilter
    data class DateIsEmpty(val property: String) : NotionFilter
    data class DateIsNotEmpty(val property: String) : NotionFilter
    data class RelationContains(val property: String, val pageId: String) : NotionFilter
    data class RelationIsEmpty(val property: String) : NotionFilter
    data class RelationIsNotEmpty(val property: String) : NotionFilter
    data class TextEquals(val property: String, val value: String, val kind: TextKind = TextKind.RICH_TEXT) : NotionFilter
    data class TextContains(val property: String, val value: String, val kind: TextKind = TextKind.RICH_TEXT) : NotionFilter

    /**
     * Inclusive. Notion reports `last_edited_time` at minute precision, so an incremental refresh
     * must use on-or-after with a watermark backed off by a minute, never a strict "after".
     */
    data class LastEditedOnOrAfter(val iso: String) : NotionFilter

    data class And(val all: List<NotionFilter>) : NotionFilter
    data class Or(val any: List<NotionFilter>) : NotionFilter
}

sealed interface NotionSort {
    val ascending: Boolean

    enum class Timestamp { CREATED_TIME, LAST_EDITED_TIME }

    data class ByProperty(val property: String, override val ascending: Boolean = true) : NotionSort
    data class ByTimestamp(val field: Timestamp, override val ascending: Boolean = true) : NotionSort
}

data class QueryPage(val pages: List<NotionPage>, val nextCursor: String?)

/** One run of text inside a block. Plain text is the verbatim ledger; [bold] marks the speaker label that separates ledger entries. */
data class RichSegment(val text: String, val bold: Boolean = false)

/** A block this package can write. The ledger is paragraphs only. */
sealed interface NotionBlock {
    data class Paragraph(val segments: List<RichSegment>) : NotionBlock
}

/** A block as read back. [segments] is empty for any block type that carries no text. */
data class NotionBlockRead(val id: String, val type: String, val segments: List<RichSegment>, val createdTime: Instant) {
    val plainText: String get() = segments.joinToString("") { it.text }
}

data class BlockPage(val blocks: List<NotionBlockRead>, val nextCursor: String?)

/**
 * The slice of Notion's REST API the adapter needs, and nothing more. [HttpNotionApi] talks to the
 * real service; the test fake implements the same contract in memory. Guards ([AllowlistedNotionApi])
 * and pacing ([RateLimitedNotionApi]) are decorators over this interface, so neither the real client
 * nor the fake carries policy.
 */
interface NotionApi {
    suspend fun query(
        dataSourceId: String,
        filter: NotionFilter? = null,
        sorts: List<NotionSort> = emptyList(),
        startCursor: String? = null,
        pageSize: Int = MAX_PAGE_SIZE,
    ): QueryPage

    /** Not idempotent: a repeated call makes another row. */
    suspend fun createPage(dataSourceId: String, properties: Map<String, NotionValue>): NotionPage

    /** Idempotent: setting a property to a value it already has is a no-op. Properties left out of [properties] are untouched. */
    suspend fun updatePage(pageId: String, properties: Map<String, NotionValue>): NotionPage

    suspend fun retrievePage(pageId: String): NotionPage

    /** Not idempotent: appends after the last block. At most [MAX_BLOCKS_PER_APPEND] blocks, each segment at most [MAX_TEXT_CHARS] characters. */
    suspend fun appendBlocks(parentBlockId: String, blocks: List<NotionBlock>)

    /** Notion lists children from the first block on; there is no way to read from the end. */
    suspend fun listBlocks(parentBlockId: String, startCursor: String? = null, pageSize: Int = MAX_PAGE_SIZE): BlockPage

    companion object {
        const val MAX_PAGE_SIZE = 100
        const val MAX_BLOCKS_PER_APPEND = 100
        const val MAX_TEXT_CHARS = 2000
    }
}

/** Reads every page a query matches, following cursors, but never more than [maxPages] requests' worth. [truncated] is true when the cap cut the read short. */
data class QueryAllResult(val pages: List<NotionPage>, val truncated: Boolean)

suspend fun NotionApi.queryAll(
    dataSourceId: String,
    filter: NotionFilter? = null,
    sorts: List<NotionSort> = emptyList(),
    maxPages: Int = 10,
): QueryAllResult {
    require(maxPages > 0) { "maxPages must be positive" }
    val out = ArrayList<NotionPage>()
    var cursor: String? = null
    var requests = 0
    while (true) {
        val page = query(dataSourceId, filter, sorts, cursor, NotionApi.MAX_PAGE_SIZE)
        out += page.pages
        requests++
        cursor = page.nextCursor
        if (cursor == null) return QueryAllResult(out, truncated = false)
        if (requests >= maxPages) return QueryAllResult(out, truncated = true)
    }
}

/** Base type for everything this package throws. Messages never contain a token or the text of a memory. */
open class NotionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Notion answered with an error. [message] is Notion's own and is truncated; it describes the request's shape, not its content. */
class NotionApiException(val status: Int, val code: String?, detail: String) :
    NotionException("Notion API error $status${code?.let { " ($it)" } ?: ""}: ${detail.take(MAX_DETAIL)}") {
    /** The description, truncated the same way the message is. */
    val detail: String = detail.take(MAX_DETAIL)

    private companion object { const val MAX_DETAIL = 300 }
}

/** HTTP 429. [retryAfterMs] is Notion's `Retry-After`, when it sent one. The request was not processed. */
class NotionRateLimitedException(val retryAfterMs: Long?) : NotionException("Notion rate limit reached${retryAfterMs?.let { " (retry after ${it}ms)" } ?: ""}")

/** No usable HTTP answer. [requestMayHaveBeenProcessed] is false only when the failure came before any request left (connect or DNS), so nothing could have been written. */
class NotionTransportException(val requestMayHaveBeenProcessed: Boolean, cause: Throwable? = null) :
    NotionException("Notion request failed in transit (${cause?.javaClass?.simpleName ?: "unknown"})", cause)

/** A write whose outcome is unknown. Never retried blindly: the caller looks the row up by its `Op key` / `Key` before deciding. */
class NotionAmbiguousWriteException(val operation: String, cause: Throwable?) :
    NotionException("Notion $operation may or may not have been applied", cause)

/** Refused before any request left the process: the call would have touched something outside the identity's configuration. */
class NotionGuardViolation(message: String) : NotionException(message)

/** The identity's Notion configuration is missing or malformed. Names the field, never a secret. */
class NotionConfigException(message: String) : NotionException(message)
