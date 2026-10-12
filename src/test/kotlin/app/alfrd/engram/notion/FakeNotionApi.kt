package app.alfrd.engram.notion

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/** A property in a [FakeNotionApi] data source's schema. */
sealed interface FakeProperty {
    data object Title : FakeProperty
    data object RichText : FakeProperty
    data object Checkbox : FakeProperty
    data object Date : FakeProperty
    data object Number : FakeProperty
    data object Url : FakeProperty
    data class Select(val options: Set<String>) : FakeProperty

    /** An auto-increment id (`GOAL-1`, `GOAL-2`, ...). Assigned on create, never writable. */
    data class UniqueId(val prefix: String) : FakeProperty

    /** Computed by Notion: never writable, and, as in the real API, left out of a query's results and present in a page read. Tests set the value with [FakeNotionApi.setComputed]. */
    data object Formula : FakeProperty
    data object Rollup : FakeProperty

    /** [dual] names the property on the target that mirrors this one; Notion keeps the pair in step, and so does the fake. */
    data class Relation(val target: String, val dual: String?) : FakeProperty
}

/** One request that reached the fake, for assertions about what an adapter did, and did not, send. */
data class FakeRequest(val operation: String, val target: String)

/**
 * In-memory stand-in for the parts of Notion this adapter depends on, strict where the real service
 * is strict, so a bug that Notion would reject fails a test instead of passing against a lenient fake:
 *
 * - writes are validated against the data source's schema (unknown property, wrong value type, a
 *   select option that does not exist, a relation to a page of the wrong table: all 400);
 * - relations marked dual stay in step on both pages, including self-relations;
 * - `last_edited_time` is reported at [lastEditedGranularity] (Notion reports minutes), and a write
 *   moves it only when something actually changed; appending blocks moves the page's too;
 * - page size and blocks per append are capped at 100, and a block's text at 2,000 characters;
 * - a query with no sort returns newest-created first, so nothing can lean on insertion order.
 *
 * Time is [now]; tests move it. [failNext] injects an error into the next matching call.
 */
class FakeNotionApi(
    var now: Instant = Instant.parse("2026-10-06T12:00:00Z"),
    private val lastEditedGranularity: ChronoUnit = ChronoUnit.MINUTES,
) : NotionApi {

    private class Source(val id: String, val schema: Map<String, FakeProperty>)

    private class Stored(
        val id: String,
        val dataSourceId: String?,
        val created: Instant,
        var edited: Instant,
        val properties: MutableMap<String, NotionValue>,
        var archived: Boolean = false,
    )

    private class StoredBlock(val id: String, val segments: List<RichSegment>, val created: Instant)

    private val sources = LinkedHashMap<String, Source>()
    private val pages = LinkedHashMap<String, Stored>()
    private val blocks = HashMap<String, MutableList<StoredBlock>>()
    private val faults = ArrayList<Triple<String, Throwable, Int>>()
    private val lateFaults = ArrayList<Triple<String, Throwable, Int>>()
    private var counter = 0
    private val uniqueIds = HashMap<Pair<String, String>, Long>()

    val requests = ArrayList<FakeRequest>()

    fun requestCount(operation: String? = null): Int = requests.count { operation == null || it.operation == operation }

    /** Makes the next [times] calls of [operation] (`query`, `createPage`, `updatePage`, `retrievePage`, `appendBlocks`, `listBlocks`) throw [error] instead of running. The call is still recorded. */
    fun failNext(operation: String, error: Throwable, times: Int = 1) {
        faults += Triple(operation, error, times)
    }

    /**
     * Like [failNext], but the call is applied first and the error is thrown afterwards: the write
     * landed and the response was lost. This is the case that makes a create ambiguous. Supported for
     * `createPage`, `updatePage` and `appendBlocks`.
     */
    fun failAfterApplying(operation: String, error: Throwable, times: Int = 1) {
        require(operation in setOf("createPage", "updatePage", "appendBlocks")) { "$operation has nothing to apply" }
        lateFaults += Triple(operation, error, times)
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    fun newId(): String = UUID.nameUUIDFromBytes("fake-notion-${++counter}".toByteArray()).toString()

    fun defineDataSource(schema: Map<String, FakeProperty>): String = newId().also { defineDataSourceWithId(it, schema) }

    /** Registers a data source under an id reserved earlier with [newId], for schemas whose relations refer to each other. */
    fun defineDataSourceWithId(id: String, schema: Map<String, FakeProperty>) {
        require(schema.values.count { it is FakeProperty.Title } == 1) { "a data source has exactly one title property" }
        sources[id] = Source(id, schema)
    }

    /** Sets what a formula or rollup property of [pageId] evaluates to. Notion computes these; a test stands in for the computation. */
    fun setComputed(pageId: String, property: String, value: NotionValue) {
        val stored = pages[pageId] ?: error("no such page $pageId")
        val kind = stored.dataSourceId?.let { sources[it]?.schema?.get(property) } ?: error("no property $property on page $pageId")
        require((kind == FakeProperty.Formula && value is NotionValue.Formula) || (kind == FakeProperty.Rollup && value is NotionValue.Rollup)) {
            "$property is not a ${value.kindName.lowercase()} property"
        }
        stored.properties[property] = value
    }

    /** A plain page (not a row of any data source), such as the persona page. */
    fun createPlainPage(paragraphs: List<String> = emptyList()): String {
        val id = newId()
        pages[id] = Stored(id, null, now, truncate(now), HashMap())
        blocks[id] = paragraphs.mapTo(ArrayList()) { StoredBlock(newId(), listOf(RichSegment(it)), now) }
        return id
    }

    /** Every non-archived row of [dataSourceId], oldest first, as the adapter would see it. */
    fun rows(dataSourceId: String): List<NotionPage> = pages.values.filter { it.dataSourceId == dataSourceId && !it.archived }.map { it.toPage() }

    fun page(id: String): NotionPage = (pages[id] ?: error("no such page $id")).toPage()

    fun blockTexts(pageId: String): List<String> = blocks[pageId].orEmpty().map { b -> b.segments.joinToString("") { it.text } }

    // ── NotionApi ────────────────────────────────────────────────────────────

    override suspend fun query(dataSourceId: String, filter: NotionFilter?, sorts: List<NotionSort>, startCursor: String?, pageSize: Int): QueryPage {
        record("query", dataSourceId)
        val source = sources[dataSourceId] ?: throw notFound("data source")
        if (pageSize !in 1..NotionApi.MAX_PAGE_SIZE) throw invalid("page_size must be between 1 and ${NotionApi.MAX_PAGE_SIZE}")
        filter?.let { validate(it, source.schema) }
        val matching = pages.values.filter { it.dataSourceId == dataSourceId && !it.archived && (filter == null || matches(it, filter)) }
            .let { rows -> if (sorts.isEmpty()) rows.sortedByDescending { it.created } else rows.sortedWith(comparatorFor(sorts)) }
        val offset = startCursor?.let(::decodeCursor) ?: 0
        val slice = matching.drop(offset).take(pageSize)
        val next = if (offset + slice.size < matching.size) encodeCursor(offset + slice.size) else null
        return QueryPage(slice.map { it.toPage(forQuery = true) }, next)
    }

    override suspend fun createPage(dataSourceId: String, properties: Map<String, NotionValue>): NotionPage {
        record("createPage", dataSourceId)
        val source = sources[dataSourceId] ?: throw notFound("data source")
        validate(properties, source.schema)
        val id = newId()
        val stored = Stored(id, dataSourceId, now, truncate(now), HashMap())
        pages[id] = stored
        applyProperties(stored, source, properties)
        assignUniqueIds(stored, source)
        throwLateFault("createPage")
        return stored.toPage()
    }

    override suspend fun updatePage(pageId: String, properties: Map<String, NotionValue>): NotionPage {
        record("updatePage", pageId)
        val stored = pages[pageId] ?: throw notFound("page")
        val source = stored.dataSourceId?.let { sources[it] } ?: throw invalid("page ${stored.id} has no properties to update")
        validate(properties, source.schema)
        applyProperties(stored, source, properties)
        throwLateFault("updatePage")
        return stored.toPage()
    }

    override suspend fun retrievePage(pageId: String): NotionPage {
        record("retrievePage", pageId)
        return (pages[pageId] ?: throw notFound("page")).toPage()
    }

    override suspend fun appendBlocks(parentBlockId: String, blocks: List<NotionBlock>) {
        record("appendBlocks", parentBlockId)
        val page = pages[parentBlockId] ?: throw notFound("block")
        if (blocks.size > NotionApi.MAX_BLOCKS_PER_APPEND) throw invalid("children should be at most ${NotionApi.MAX_BLOCKS_PER_APPEND} blocks")
        val added = blocks.map { block ->
            val paragraph = block as NotionBlock.Paragraph
            paragraph.segments.forEach {
                if (it.text.length > NotionApi.MAX_TEXT_CHARS) throw invalid("rich_text content should be at most ${NotionApi.MAX_TEXT_CHARS} characters")
            }
            StoredBlock(newId(), paragraph.segments, now)
        }
        this.blocks.getOrPut(parentBlockId) { ArrayList() }.addAll(added)
        if (added.isNotEmpty()) page.edited = truncate(now)
        throwLateFault("appendBlocks")
    }

    override suspend fun listBlocks(parentBlockId: String, startCursor: String?, pageSize: Int): BlockPage {
        record("listBlocks", parentBlockId)
        if (!pages.containsKey(parentBlockId)) throw notFound("block")
        if (pageSize !in 1..NotionApi.MAX_PAGE_SIZE) throw invalid("page_size must be between 1 and ${NotionApi.MAX_PAGE_SIZE}")
        val all = blocks[parentBlockId].orEmpty()
        val offset = startCursor?.let(::decodeCursor) ?: 0
        val slice = all.drop(offset).take(pageSize)
        val next = if (offset + slice.size < all.size) encodeCursor(offset + slice.size) else null
        return BlockPage(slice.map { NotionBlockRead(it.id, "paragraph", it.segments, it.created) }, next)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun record(operation: String, target: String) {
        requests += FakeRequest(operation, target)
        val index = faults.indexOfFirst { it.first == operation }
        if (index >= 0) {
            val (op, error, times) = faults[index]
            if (times <= 1) faults.removeAt(index) else faults[index] = Triple(op, error, times - 1)
            throw error
        }
    }

    private fun throwLateFault(operation: String) {
        val index = lateFaults.indexOfFirst { it.first == operation }
        if (index < 0) return
        val (op, error, times) = lateFaults[index]
        if (times <= 1) lateFaults.removeAt(index) else lateFaults[index] = Triple(op, error, times - 1)
        throw error
    }

    private fun truncate(at: Instant): Instant = at.truncatedTo(lastEditedGranularity)

    private fun notFound(what: String) = NotionApiException(404, "object_not_found", "Could not find $what")
    private fun invalid(detail: String) = NotionApiException(400, "validation_error", detail)

    private fun defaultValue(kind: FakeProperty): NotionValue = when (kind) {
        FakeProperty.Title -> NotionValue.Title("")
        FakeProperty.RichText -> NotionValue.RichText("")
        FakeProperty.Checkbox -> NotionValue.Checkbox(false)
        FakeProperty.Date -> NotionValue.DateValue(null)
        FakeProperty.Number -> NotionValue.NumberValue(null)
        FakeProperty.Url -> NotionValue.UrlValue(null)
        is FakeProperty.Select -> NotionValue.Select(null)
        is FakeProperty.Relation -> NotionValue.Relation(emptyList())
        is FakeProperty.UniqueId -> NotionValue.UniqueId(kind.prefix, 0)
        FakeProperty.Formula -> NotionValue.Formula("string")
        FakeProperty.Rollup -> NotionValue.Rollup("array")
    }

    /** [forQuery] mimics the real API: a data source query does not return formula or rollup values, a page read does. */
    private fun Stored.toPage(forQuery: Boolean = false): NotionPage {
        val schema = dataSourceId?.let { sources[it]?.schema }.orEmpty()
        val props = LinkedHashMap<String, NotionValue>()
        for ((name, kind) in schema) {
            props[name] = when {
                forQuery && kind == FakeProperty.Formula -> NotionValue.Unsupported("formula")
                forQuery && kind == FakeProperty.Rollup -> NotionValue.Unsupported("rollup")
                else -> properties[name] ?: defaultValue(kind)
            }
        }
        return NotionPage(id, dataSourceId, created, edited, props, archived)
    }

    private fun assignUniqueIds(stored: Stored, source: Source) {
        for ((name, kind) in source.schema) {
            if (kind is FakeProperty.UniqueId) {
                val next = (uniqueIds[source.id to name] ?: 0L) + 1
                uniqueIds[source.id to name] = next
                stored.properties[name] = NotionValue.UniqueId(kind.prefix, next)
            }
        }
    }

    private fun validate(properties: Map<String, NotionValue>, schema: Map<String, FakeProperty>) {
        for ((name, value) in properties) {
            val kind = schema[name] ?: throw invalid("$name is not a property that exists.")
            if (kind is FakeProperty.UniqueId || kind == FakeProperty.Formula || kind == FakeProperty.Rollup) {
                throw invalid("$name is computed by Notion and cannot be edited.")
            }
            val ok = when (kind) {
                FakeProperty.Title -> value is NotionValue.Title
                FakeProperty.RichText -> value is NotionValue.RichText
                FakeProperty.Checkbox -> value is NotionValue.Checkbox
                FakeProperty.Date -> value is NotionValue.DateValue
                FakeProperty.Number -> value is NotionValue.NumberValue
                FakeProperty.Url -> value is NotionValue.UrlValue
                is FakeProperty.Select -> value is NotionValue.Select
                is FakeProperty.Relation -> value is NotionValue.Relation
                is FakeProperty.UniqueId, FakeProperty.Formula, FakeProperty.Rollup -> false
            }
            if (!ok) throw invalid("$name is expected to be ${kind.javaClass.simpleName.lowercase()}.")
            if (kind is FakeProperty.Select && value is NotionValue.Select && value.name != null && value.name !in kind.options) {
                throw invalid("select option '${value.name}' does not exist on $name")
            }
            if (kind is FakeProperty.Relation && value is NotionValue.Relation) {
                for (id in value.ids) {
                    val target = pages[id] ?: throw invalid("relation target $id does not exist")
                    if (target.dataSourceId != kind.target) throw invalid("relation target $id is not in the related data source")
                }
            }
        }
    }

    private fun validate(filter: NotionFilter, schema: Map<String, FakeProperty>) {
        fun need(property: String) { if (property !in schema) throw invalid("Could not find property with name or id: $property") }
        when (filter) {
            is NotionFilter.SelectEquals -> need(filter.property)
            is NotionFilter.SelectDoesNotEqual -> need(filter.property)
            is NotionFilter.SelectIsEmpty -> need(filter.property)
            is NotionFilter.SelectIsNotEmpty -> need(filter.property)
            is NotionFilter.CheckboxEquals -> need(filter.property)
            is NotionFilter.DateOnOrBefore -> need(filter.property)
            is NotionFilter.DateOnOrAfter -> need(filter.property)
            is NotionFilter.DateIsEmpty -> need(filter.property)
            is NotionFilter.DateIsNotEmpty -> need(filter.property)
            is NotionFilter.RelationContains -> need(filter.property)
            is NotionFilter.RelationIsEmpty -> need(filter.property)
            is NotionFilter.RelationIsNotEmpty -> need(filter.property)
            is NotionFilter.TextEquals -> need(filter.property)
            is NotionFilter.TextContains -> need(filter.property)
            is NotionFilter.LastEditedOnOrAfter -> Unit
            is NotionFilter.And -> filter.all.forEach { validate(it, schema) }
            is NotionFilter.Or -> filter.any.forEach { validate(it, schema) }
        }
    }

    /** Applies the writes, keeps dual relations in step, and moves `last_edited_time` only for pages that actually changed. */
    private fun applyProperties(stored: Stored, source: Source, properties: Map<String, NotionValue>) {
        var changed = stored.properties.isEmpty() // a new page counts as changed
        for ((name, value) in properties) {
            val kind = source.schema.getValue(name)
            val before = stored.properties[name] ?: defaultValue(kind)
            if (kind is FakeProperty.Relation && value is NotionValue.Relation) {
                val old = (before as? NotionValue.Relation)?.ids.orEmpty()
                val new = value.ids.distinct()
                if (old.toSet() != new.toSet()) {
                    changed = true
                    stored.properties[name] = NotionValue.Relation(new)
                    kind.dual?.let { dual ->
                        (old - new.toSet()).forEach { removed -> mirror(removed, dual, stored.id, add = false) }
                        (new - old.toSet()).forEach { added -> mirror(added, dual, stored.id, add = true) }
                    }
                }
            } else if (before != value) {
                changed = true
                stored.properties[name] = value
            }
        }
        if (changed) stored.edited = truncate(now)
    }

    private fun mirror(targetId: String, property: String, ownerId: String, add: Boolean) {
        val target = pages.getValue(targetId)
        val current = (target.properties[property] as? NotionValue.Relation)?.ids.orEmpty()
        val next = if (add) (current + ownerId).distinct() else current - ownerId
        if (next != current) {
            target.properties[property] = NotionValue.Relation(next)
            target.edited = truncate(now)
        }
    }

    // ── Filtering and sorting ────────────────────────────────────────────────

    private fun matches(page: Stored, filter: NotionFilter): Boolean {
        val view = page.toPage()
        return when (filter) {
            is NotionFilter.SelectEquals -> view.select(filter.property) == filter.name
            is NotionFilter.SelectDoesNotEqual -> view.select(filter.property) != filter.name
            is NotionFilter.SelectIsEmpty -> view.select(filter.property) == null
            is NotionFilter.SelectIsNotEmpty -> view.select(filter.property) != null
            is NotionFilter.CheckboxEquals -> view.checkbox(filter.property) == filter.value
            is NotionFilter.DateOnOrBefore -> view.date(filter.property)?.let { compareMoments(it.start!!, filter.iso) <= 0 } ?: false
            is NotionFilter.DateOnOrAfter -> view.date(filter.property)?.let { compareMoments(it.start!!, filter.iso) >= 0 } ?: false
            is NotionFilter.DateIsEmpty -> view.date(filter.property) == null
            is NotionFilter.DateIsNotEmpty -> view.date(filter.property) != null
            is NotionFilter.RelationContains -> filter.pageId in view.relation(filter.property)
            is NotionFilter.RelationIsEmpty -> view.relation(filter.property).isEmpty()
            is NotionFilter.RelationIsNotEmpty -> view.relation(filter.property).isNotEmpty()
            is NotionFilter.TextEquals -> textOf(view, filter.property, filter.kind) == filter.value
            is NotionFilter.TextContains -> textOf(view, filter.property, filter.kind).contains(filter.value, ignoreCase = true)
            is NotionFilter.LastEditedOnOrAfter -> !page.edited.isBefore(Instant.parse(filter.iso))
            is NotionFilter.And -> filter.all.all { matches(page, it) }
            is NotionFilter.Or -> filter.any.any { matches(page, it) }
        }
    }

    private fun textOf(page: NotionPage, property: String, kind: NotionFilter.TextKind): String =
        if (kind == NotionFilter.TextKind.TITLE) page.title(property) else page.text(property)

    /** Dates compare as dates when both are plain dates, as instants otherwise (a plain date is midnight UTC). */
    private fun compareMoments(a: String, b: String): Int =
        if (!a.contains('T') && !b.contains('T')) LocalDate.parse(a).compareTo(LocalDate.parse(b)) else moment(a).compareTo(moment(b))

    private fun moment(text: String): Instant =
        if (text.contains('T')) runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrElse { Instant.parse(text) }
        else LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()

    private fun comparatorFor(sorts: List<NotionSort>): Comparator<Stored> {
        var comparator: Comparator<Stored>? = null
        for (sort in sorts) {
            val next: Comparator<Stored> = when (sort) {
                is NotionSort.ByTimestamp ->
                    if (sort.field == NotionSort.Timestamp.CREATED_TIME) compareBy<Stored> { it.created } else compareBy<Stored> { it.edited }
                is NotionSort.ByProperty -> compareBy<Stored> { sortKey(it.toPage().properties[sort.property]) }
            }
            val directed = if (sort.ascending) next else next.reversed()
            comparator = comparator?.thenComparing(directed) ?: directed
        }
        return comparator!!.thenComparing(compareBy<Stored> { it.id })
    }

    private fun sortKey(value: NotionValue?): String = when (value) {
        is NotionValue.Title -> value.text
        is NotionValue.RichText -> value.text
        is NotionValue.Select -> value.name.orEmpty()
        is NotionValue.DateValue -> value.start.orEmpty()
        is NotionValue.Checkbox -> if (value.checked) "1" else "0"
        is NotionValue.NumberValue -> value.value?.let { "%020.6f".format(it + 1.0e9) }.orEmpty()
        is NotionValue.UniqueId -> "%020d".format(value.number)
        is NotionValue.UrlValue -> value.value.orEmpty()
        else -> ""
    }

    private fun encodeCursor(offset: Int): String = Base64.getEncoder().encodeToString("offset:$offset".toByteArray())

    private fun decodeCursor(cursor: String): Int =
        runCatching { String(Base64.getDecoder().decode(cursor)).removePrefix("offset:").toInt() }.getOrElse { throw invalid("start_cursor is invalid") }
}

/** The tables of one person's alfrd teamspace, as [installAlfrdTemplate] built them in a [FakeNotionApi]. */
data class FakeTemplate(val sources: NotionDataSources, val foreignDataSourceId: String)

private val SOURCES = setOf("Person said", "alfrd inferred", "Jacob pre-seeded", "Direct edit")
private val SENSITIVITY = setOf("Open", "Personal", "Sensitive")

/**
 * Builds the memory template in the fake with the columns the adapter relies on: the five tables
 * with their dual relations, the new Items `Op key`/`Focus`/`Zoom`, Sessions `Key`, Containers
 * `Tentative`/`Focus`/`Zoom`, and the persona page. Also defines one table outside the
 * configuration, to test that nothing reaches it.
 */
fun FakeNotionApi.installAlfrdTemplate(): FakeTemplate {
    // Data source ids are needed inside each other's relation schemas, so reserve them first.
    val containers = newId()
    val items = newId()
    val entities = newId()
    val connections = newId()
    val sessions = newId()
    val ids = mapOf("containers" to containers, "items" to items, "entities" to entities, "connections" to connections, "sessions" to sessions)
    val zoom = FakeProperty.Select(setOf("Zoomed out", "Binoculars", "Telescope"))
    val override = FakeProperty.Select(setOf("Pinned", "Suppressed"))

    val schemas = mapOf(
        containers to mapOf(
            "Name" to FakeProperty.Title,
            "Kind" to FakeProperty.Select(setOf("Domain", "Project")),
            "Parent" to FakeProperty.Relation(containers, "Children"),
            "Children" to FakeProperty.Relation(containers, "Parent"),
            "Items" to FakeProperty.Relation(items, "Container"),
            "Override" to override,
            "Focus" to FakeProperty.Checkbox,
            "Zoom" to zoom,
            "Status" to FakeProperty.Select(setOf("Active", "Paused", "Done")),
            "Due" to FakeProperty.Date,
            "Sensitivity" to FakeProperty.Select(SENSITIVITY),
            "Source" to FakeProperty.Select(SOURCES),
            "Tentative" to FakeProperty.Checkbox,
        ),
        items to mapOf(
            "Item" to FakeProperty.Title,
            "Kind" to FakeProperty.Select(setOf("Task", "Event", "Question", "Fact", "Insight")),
            "Container" to FakeProperty.Relation(containers, "Items"),
            "About" to FakeProperty.Relation(entities, "Items about"),
            "Session" to FakeProperty.Relation(sessions, "Items"),
            "Said" to FakeProperty.RichText,
            "Date" to FakeProperty.Date,
            "Source" to FakeProperty.Select(SOURCES + "alfrd asked"),
            "Tentative" to FakeProperty.Checkbox,
            "Sensitivity" to FakeProperty.Select(SENSITIVITY),
            "Override" to override,
            "Status" to FakeProperty.Select(setOf("Open", "Resolved", "Superseded")),
            "Supersedes" to FakeProperty.Relation(items, "Superseded by"),
            "Superseded by" to FakeProperty.Relation(items, "Supersedes"),
            "Related" to FakeProperty.Relation(items, null),
            "Op key" to FakeProperty.RichText,
            "Focus" to FakeProperty.Checkbox,
            "Zoom" to zoom,
        ),
        entities to mapOf(
            "Name" to FakeProperty.Title,
            "Type" to FakeProperty.Select(setOf("Self", "Person", "Client", "Organization", "Artist", "Work", "Place", "Thing")),
            "Detail" to FakeProperty.RichText,
            "Source" to FakeProperty.Select(SOURCES),
            "Tentative" to FakeProperty.Checkbox,
            "Sensitivity" to FakeProperty.Select(SENSITIVITY),
            "Items about" to FakeProperty.Relation(items, "About"),
        ),
        connections to mapOf(
            "Connection" to FakeProperty.Title,
            "From" to FakeProperty.Relation(entities, null),
            "To" to FakeProperty.Relation(entities, null),
            "Type" to FakeProperty.Select(setOf("Inspired by", "Played with", "Client of", "Works with", "Part of", "Related to")),
            "Said" to FakeProperty.RichText,
            "Source" to FakeProperty.Select(SOURCES),
            "Tentative" to FakeProperty.Checkbox,
        ),
        sessions to mapOf(
            "Session" to FakeProperty.Title,
            "Source" to FakeProperty.Select(setOf("alfrd", "Jacob")),
            "Date" to FakeProperty.Date,
            "Channel" to FakeProperty.Select(setOf("Voice", "Text", "Questionnaire")),
            "Key" to FakeProperty.RichText,
            "Items" to FakeProperty.Relation(items, "Session"),
        ),
    )
    schemas.forEach { (id, schema) -> defineDataSourceWithId(id, schema) }
    val foreign = defineDataSource(mapOf("Name" to FakeProperty.Title, "Notes" to FakeProperty.RichText))
    val persona = createPlainPage(listOf("Who they are: a test person.", "Off limits: nothing."))
    return FakeTemplate(
        NotionDataSources(ids.getValue("containers"), ids.getValue("items"), ids.getValue("entities"), ids.getValue("connections"), ids.getValue("sessions"), persona),
        foreign,
    )
}
