package app.alfrd.engram.notion

import java.util.concurrent.ConcurrentHashMap

/**
 * Confines a [NotionApi] to the tables and the persona page named in one identity's
 * configuration, so a bug above this layer cannot reach anything else the integration token can see.
 *
 * - Queries and creates name a data source, which must be one of the five configured tables.
 * - A page can be updated, or appended to, only if it was returned by a call this guard let through
 *   (a query of, or a create in, a configured table). A page id arriving from anywhere else (a model's
 *   output, a stale cache, a typo) is refused before a request is made.
 * - A relation may only point at such a page, so a write cannot link a row to something outside.
 * - Blocks are appended only to Sessions pages (the ledger). The persona page is read-only: it is
 *   never a known page, so no write can reach it.
 * - A page fetched by id is checked against the configured tables *after* the fetch, and nothing from
 *   it is returned if it belongs elsewhere.
 *
 * Refusals are [NotionGuardViolation]s and never contain memory text.
 */
class AllowlistedNotionApi(
    private val delegate: NotionApi,
    private val sources: NotionDataSources,
) : NotionApi {

    /** page id -> the configured data source it was seen in. */
    private val knownPages = ConcurrentHashMap<String, String>()
    private val writable = sources.writableDataSourceIds

    fun isKnownPage(pageId: String): Boolean = normalizeNotionId(pageId)?.let(knownPages::containsKey) == true

    override suspend fun query(dataSourceId: String, filter: NotionFilter?, sorts: List<NotionSort>, startCursor: String?, pageSize: Int): QueryPage {
        val ds = requireConfiguredSource(dataSourceId, "query")
        val result = delegate.query(ds, filter, sorts, startCursor, pageSize)
        result.pages.forEach { remember(it, "query") }
        return result
    }

    override suspend fun createPage(dataSourceId: String, properties: Map<String, NotionValue>): NotionPage {
        val ds = requireConfiguredSource(dataSourceId, "create")
        requireKnownRelationTargets(properties, "create")
        return remember(delegate.createPage(ds, properties), "create")
    }

    override suspend fun updatePage(pageId: String, properties: Map<String, NotionValue>): NotionPage {
        val id = requireKnownPage(pageId, "update")
        requireKnownRelationTargets(properties, "update")
        return remember(delegate.updatePage(id, properties), "update")
    }

    override suspend fun retrievePage(pageId: String): NotionPage {
        val id = normalizeNotionId(pageId) ?: throw NotionGuardViolation("retrieve refused: not a Notion id")
        if (id == sources.personaPageId) return delegate.retrievePage(id)
        val page = delegate.retrievePage(id)
        return remember(page, "retrieve")
    }

    override suspend fun appendBlocks(parentBlockId: String, blocks: List<NotionBlock>) {
        val id = requireKnownPage(parentBlockId, "append")
        if (knownPages[id] != sources.sessions) {
            throw NotionGuardViolation("append refused: blocks may only be added to a Sessions page")
        }
        delegate.appendBlocks(id, blocks)
    }

    override suspend fun listBlocks(parentBlockId: String, startCursor: String?, pageSize: Int): BlockPage {
        val id = normalizeNotionId(parentBlockId) ?: throw NotionGuardViolation("list refused: not a Notion id")
        if (id != sources.personaPageId && !knownPages.containsKey(id)) {
            throw NotionGuardViolation("list refused: page $id is not the persona page or a page from a configured table")
        }
        return delegate.listBlocks(id, startCursor, pageSize)
    }

    private fun requireConfiguredSource(raw: String, operation: String): String {
        val id = normalizeNotionId(raw) ?: throw NotionGuardViolation("$operation refused: not a Notion id")
        if (id !in writable) throw NotionGuardViolation("$operation refused: data source $id is not one of this identity's configured tables")
        return id
    }

    private fun requireKnownPage(raw: String, operation: String): String {
        val id = normalizeNotionId(raw) ?: throw NotionGuardViolation("$operation refused: not a Notion id")
        if (!knownPages.containsKey(id)) throw NotionGuardViolation("$operation refused: page $id was not returned from a configured table")
        return id
    }

    private fun requireKnownRelationTargets(properties: Map<String, NotionValue>, operation: String) {
        for ((name, value) in properties) {
            if (value !is NotionValue.Relation) continue
            for (target in value.ids) {
                val id = normalizeNotionId(target) ?: throw NotionGuardViolation("$operation refused: relation '$name' holds something that is not a Notion id")
                if (!knownPages.containsKey(id)) throw NotionGuardViolation("$operation refused: relation '$name' points at page $id, which was not returned from a configured table")
            }
        }
    }

    /** Records a page the delegate returned, after checking it really lives in a configured table. Nothing from a foreign page is passed on. */
    private fun remember(page: NotionPage, operation: String): NotionPage {
        val ds = page.dataSourceId?.let(::normalizeNotionId)
        if (ds == null || ds !in writable) {
            throw NotionGuardViolation("$operation refused: page ${page.id} belongs to a table that is not configured for this identity")
        }
        knownPages[page.id] = ds
        return page
    }
}
