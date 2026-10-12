package app.alfrd.engram.notion

import java.util.concurrent.ConcurrentHashMap

/**
 * Confines a [NotionApi] to the tables and readable pages of one [NotionTableSet], so a bug above
 * this layer cannot reach anything else the integration token can see, and cannot write where it
 * was only meant to read.
 *
 * - Queries and creates name a data source, which must be one of the set's tables. A create also
 *   needs that table to be read-write.
 * - A page can be updated, or appended to, only if it was returned by a call this guard let through
 *   (a query of, or a create in, a table of the set), and only if its table is read-write. A page id
 *   arriving from anywhere else (a model's output, a stale cache, a typo) is refused before a
 *   request is made.
 * - A relation may only point at such a page, and only at a table that accepts incoming relations:
 *   Notion mirrors a dual relation onto its target, so a relation into a read-only table would edit it.
 * - Blocks are appended only to pages of tables configured for page-body appends (the memory ledger).
 * - A write may not carry a value Notion computes (an auto-increment id, a created time, a formula
 *   or a rollup, or a property type this package does not model).
 * - A table that opted in to an idempotency key must receive it on every create, and it can never be
 *   changed afterwards.
 * - Readable pages (the persona page) can be retrieved and listed and are never writable: they are
 *   never known pages.
 * - A page fetched by id is checked against the set's tables *after* the fetch, and nothing from it is
 *   returned if it belongs elsewhere.
 *
 * The set is fixed when the guard is built; nothing about it changes while the process runs. Which
 * rows a particular goal may touch is not decided here. Refusals are [NotionGuardViolation]s and
 * never contain property values or memory text.
 */
class AllowlistedNotionApi(
    private val delegate: NotionApi,
    tableSet: NotionTableSet,
) : NotionApi {

    /** The alfrd memory template: five read-write tables, a Sessions ledger, a read-only persona page. */
    constructor(delegate: NotionApi, sources: NotionDataSources) : this(delegate, sources.toTableSet())

    /** Copied at construction, so changing the list the caller passed in cannot widen the allowlist later. */
    private val tables: Map<String, NotionTable> = tableSet.tables.associateBy { it.dataSourceId }
    private val readablePages: Set<String> = tableSet.readablePageIds.toSet()

    /** page id -> the data source it was seen in. */
    private val knownPages = ConcurrentHashMap<String, String>()

    fun isKnownPage(pageId: String): Boolean = normalizeNotionId(pageId)?.let(knownPages::containsKey) == true

    override suspend fun query(dataSourceId: String, filter: NotionFilter?, sorts: List<NotionSort>, startCursor: String?, pageSize: Int): QueryPage {
        val table = requireConfiguredTable(dataSourceId, "query")
        val result = withNotFoundHint(table) { delegate.query(table.dataSourceId, filter, sorts, startCursor, pageSize) }
        result.pages.forEach { remember(it, "query") }
        return result
    }

    override suspend fun createPage(dataSourceId: String, properties: Map<String, NotionValue>): NotionPage {
        val table = requireConfiguredTable(dataSourceId, "create")
        requireWritable(table, "create")
        requireNoComputedValues(properties, "create")
        requireIdempotencyKey(table, properties)
        requireRelationTargetsAccept(properties, "create")
        return remember(withNotFoundHint(table) { delegate.createPage(table.dataSourceId, properties) }, "create")
    }

    override suspend fun updatePage(pageId: String, properties: Map<String, NotionValue>): NotionPage {
        val (id, table) = requireKnownPage(pageId, "update")
        requireWritable(table, "update")
        requireNoComputedValues(properties, "update")
        requireKeyUnchanged(table, properties)
        requireRelationTargetsAccept(properties, "update")
        return remember(delegate.updatePage(id, properties), "update")
    }

    override suspend fun retrievePage(pageId: String): NotionPage {
        val id = normalizeNotionId(pageId) ?: throw NotionGuardViolation("retrieve refused: not a Notion id")
        if (id in readablePages) return delegate.retrievePage(id)
        return remember(delegate.retrievePage(id), "retrieve")
    }

    override suspend fun appendBlocks(parentBlockId: String, blocks: List<NotionBlock>) {
        val (id, table) = requireKnownPage(parentBlockId, "append")
        requireWritable(table, "append")
        if (!table.pageBodyAppend) {
            throw NotionGuardViolation("append refused: table '${table.name}' does not accept page-body appends")
        }
        delegate.appendBlocks(id, blocks)
    }

    override suspend fun listBlocks(parentBlockId: String, startCursor: String?, pageSize: Int): BlockPage {
        val id = normalizeNotionId(parentBlockId) ?: throw NotionGuardViolation("list refused: not a Notion id")
        if (id !in readablePages && !knownPages.containsKey(id)) {
            throw NotionGuardViolation("list refused: page $id is not a readable page or a page from a configured table")
        }
        return delegate.listBlocks(id, startCursor, pageSize)
    }

    private fun requireConfiguredTable(raw: String, operation: String): NotionTable {
        val id = normalizeNotionId(raw) ?: throw NotionGuardViolation("$operation refused: not a Notion id")
        return tables[id] ?: throw NotionGuardViolation("$operation refused: data source $id is not one of this set's configured tables")
    }

    private fun requireWritable(table: NotionTable, operation: String) {
        if (!table.writable) throw NotionGuardViolation("$operation refused: table '${table.name}' is read-only")
    }

    private fun requireKnownPage(raw: String, operation: String): Pair<String, NotionTable> {
        val id = normalizeNotionId(raw) ?: throw NotionGuardViolation("$operation refused: not a Notion id")
        val table = knownPages[id]?.let(tables::get)
            ?: throw NotionGuardViolation("$operation refused: page $id was not returned from a configured table")
        return id to table
    }

    /** Notion assigns or computes these (auto-increment ids, created time, formulas, rollups), and rejects a write to them. Refuse it here, naming the property and the kind, never the value. */
    private fun requireNoComputedValues(properties: Map<String, NotionValue>, operation: String) {
        for ((name, value) in properties) {
            if (value.isReadOnly) {
                throw NotionGuardViolation("$operation refused: property '$name' is a ${value.kindName} value, which Notion computes, so it cannot be written")
            }
        }
    }

    private fun requireIdempotencyKey(table: NotionTable, properties: Map<String, NotionValue>) {
        val key = table.idempotencyKeyProperty ?: return
        val value = properties[key]
        if (value !is NotionValue.RichText || value.text.isBlank()) {
            throw NotionGuardViolation("create refused: table '${table.name}' needs a non-blank '$key' on every create, so a write of unknown outcome can be found again")
        }
    }

    private fun requireKeyUnchanged(table: NotionTable, properties: Map<String, NotionValue>) {
        val key = table.idempotencyKeyProperty ?: return
        if (properties.containsKey(key)) throw NotionGuardViolation("update refused: '$key' on table '${table.name}' is set once, when the row is created")
    }

    private fun requireRelationTargetsAccept(properties: Map<String, NotionValue>, operation: String) {
        for ((name, value) in properties) {
            if (value !is NotionValue.Relation) continue
            for (target in value.ids) {
                val id = normalizeNotionId(target) ?: throw NotionGuardViolation("$operation refused: relation '$name' holds something that is not a Notion id")
                val table = knownPages[id]?.let(tables::get)
                    ?: throw NotionGuardViolation("$operation refused: relation '$name' points at page $id, which was not returned from a configured table")
                if (!table.allowIncomingRelations) {
                    throw NotionGuardViolation("$operation refused: relation '$name' points at page $id in table '${table.name}', which does not accept incoming relations")
                }
            }
        }
    }

    /** A 404 on a configured table is almost always the wrong kind of id, or an integration that was never connected to that database. Say so. */
    private suspend fun <T> withNotFoundHint(table: NotionTable, block: suspend () -> T): T =
        try {
            block()
        } catch (e: NotionApiException) {
            if (e.status != 404) throw e
            throw NotionApiException(
                404,
                e.code,
                "table '${table.name}' was not found: its configured id must be a data source id (the id inside a collection:// reference), " +
                    "not a database id, and the integration must be connected to that database. Notion said: ${e.detail}",
            )
        }

    /** Records a page the delegate returned, after checking it really lives in a table of the set. Nothing from a foreign page is passed on. */
    private fun remember(page: NotionPage, operation: String): NotionPage {
        val ds = page.dataSourceId?.let(::normalizeNotionId)
        if (ds == null || ds !in tables) {
            throw NotionGuardViolation("$operation refused: page ${page.id} belongs to a table that is not configured for this set")
        }
        knownPages[page.id] = ds
        return page
    }
}
