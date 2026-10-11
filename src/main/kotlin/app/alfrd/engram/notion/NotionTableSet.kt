package app.alfrd.engram.notion

/** What a client may do with the rows of one table. */
enum class TableAccess(val label: String) {
    /** Query and retrieve only. Create, update and page-body append are refused at the guard. */
    READ_ONLY("read-only"),

    /** Everything a [NotionTable] is configured to allow. */
    READ_WRITE("read-write");

    companion object {
        fun fromLabel(label: String): TableAccess? = entries.firstOrNull { it.label == label }
    }
}

/**
 * One table (a Notion *data source*) in a [NotionTableSet].
 *
 * - [dataSourceId] is the normalized dashed lowercase data source id, not a database id and not a
 *   URL (see `docs/notion-table-sets.md`).
 * - [pageBodyAppend] lets blocks be appended to this table's pages (the memory ledger lives in the
 *   body of a Sessions page). Off by default; only meaningful for a writable table.
 * - [idempotencyKeyProperty] opts the table in to idempotent creates: the named text property must
 *   be present on every create and can never be changed afterwards, so a write whose outcome is
 *   unknown can be found again by its key (see [IdempotentCreator]). Off by default.
 * - [allowIncomingRelations] says whether a row of *another* table may be given a relation to a row
 *   of this one. Notion mirrors a dual relation onto the target row, so such a write also edits this
 *   table; it therefore defaults to true only for a writable table. Set it on a read-only table only
 *   after confirming the relation is one-way.
 */
data class NotionTable(
    val name: String,
    val dataSourceId: String,
    val access: TableAccess = TableAccess.READ_WRITE,
    val pageBodyAppend: Boolean = false,
    val idempotencyKeyProperty: String? = null,
    val allowIncomingRelations: Boolean = access == TableAccess.READ_WRITE,
) {
    init {
        if (!NAME.matches(name)) throw NotionConfigException("table name must be 1-64 characters of letters, digits, '.', '_' or '-'")
        if (normalizeNotionId(dataSourceId) != dataSourceId) {
            throw NotionConfigException("table '$name' needs a normalized (dashed, lowercase) data source id")
        }
        if (access == TableAccess.READ_ONLY) {
            if (pageBodyAppend) throw NotionConfigException("table '$name' is read-only, so it cannot accept page-body appends")
            if (idempotencyKeyProperty != null) throw NotionConfigException("table '$name' is read-only, so it has no use for an idempotency key")
        }
        if (idempotencyKeyProperty != null && (idempotencyKeyProperty.isBlank() || idempotencyKeyProperty.length > MAX_PROPERTY_NAME)) {
            throw NotionConfigException("table '$name' has an invalid idempotencyKeyProperty")
        }
    }

    val writable: Boolean get() = access == TableAccess.READ_WRITE

    /** The same table with every write capability removed. Tightening is the only direction offered. */
    fun asReadOnly(): NotionTable =
        if (!writable) this
        else NotionTable(name, dataSourceId, TableAccess.READ_ONLY, pageBodyAppend = false, idempotencyKeyProperty = null, allowIncomingRelations = false)

    private companion object {
        val NAME = Regex("[A-Za-z0-9._-]{1,64}")
        const val MAX_PROPERTY_NAME = 100
    }
}

/** A plain page (not a table row) the client may read and never write, such as the persona page. */
data class NotionReadablePage(val name: String, val pageId: String) {
    init {
        if (!NAME.matches(name)) throw NotionConfigException("readable page name must be 1-64 characters of letters, digits, '.', '_' or '-'")
        if (normalizeNotionId(pageId) != pageId) throw NotionConfigException("readable page '$name' needs a normalized (dashed, lowercase) page id")
    }

    private companion object { val NAME = Regex("[A-Za-z0-9._-]{1,64}") }
}

/**
 * A named, fixed list of the tables one token may ever touch, each with its own access mode.
 * The same client serves any set: the alfrd memory set (five tables and a persona page), or an
 * Agent Work + PPS set (some tables read-write, some read-only).
 *
 * The allowlist is static. Which rows a particular goal may touch is not decided here; that is the
 * Director's job, above the client.
 */
data class NotionTableSet(
    val name: String,
    val tables: List<NotionTable>,
    val readablePages: List<NotionReadablePage> = emptyList(),
) {
    private val tablesByName: Map<String, NotionTable>
    private val tablesByDataSource: Map<String, NotionTable>

    /** Page ids that may be read (retrieve, list blocks) and never written. */
    val readablePageIds: Set<String>

    init {
        if (!SET_NAME.matches(name)) throw NotionConfigException("table set name must be 1-128 characters of letters, digits, '.', '_', ':' or '-'")
        if (tables.isEmpty()) throw NotionConfigException("table set '$name' needs at least one table")
        tables.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw NotionConfigException("table set '$name' has two tables named '$it'")
        }
        tables.groupBy { it.dataSourceId }.values.firstOrNull { it.size > 1 }?.let { same ->
            throw NotionConfigException("table set '$name': tables ${same.joinToString(" and ") { "'${it.name}'" }} point at the same data source")
        }
        readablePages.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw NotionConfigException("table set '$name' has two readable pages named '$it'")
        }
        readablePages.groupBy { it.pageId }.values.firstOrNull { it.size > 1 }?.let { same ->
            throw NotionConfigException("table set '$name': readable pages ${same.joinToString(" and ") { "'${it.name}'" }} are the same page")
        }
        val tableIds = tables.mapTo(HashSet()) { it.dataSourceId }
        readablePages.firstOrNull { it.pageId in tableIds }?.let {
            throw NotionConfigException("table set '$name': readable page '${it.name}' has the id of a table")
        }
        tablesByName = tables.associateBy { it.name }
        tablesByDataSource = tables.associateBy { it.dataSourceId }
        readablePageIds = readablePages.mapTo(LinkedHashSet()) { it.pageId }
    }

    fun table(name: String): NotionTable =
        tablesByName[name] ?: throw NotionConfigException("table set '${this.name}' has no table named '$name'")

    /** The table for a data source id given in any accepted spelling, or null when it is not in this set. */
    fun tableForDataSource(dataSourceId: String): NotionTable? = normalizeNotionId(dataSourceId)?.let(tablesByDataSource::get)

    /** The set with the named tables made read-only: for example, the PPS tables during a dry run. It can only tighten. */
    fun withReadOnly(tableNames: Collection<String>): NotionTableSet {
        val unknown = tableNames.filter { it !in tablesByName }
        if (unknown.isNotEmpty()) throw NotionConfigException("table set '$name' has no table named ${unknown.joinToString(", ") { "'$it'" }}")
        val chosen = tableNames.toSet()
        return copy(tables = tables.map { if (it.name in chosen) it.asReadOnly() else it })
    }

    /** The whole set, read-only. */
    fun asReadOnly(): NotionTableSet = copy(tables = tables.map { it.asReadOnly() })

    private companion object { val SET_NAME = Regex("[A-Za-z0-9._:-]{1,128}") }
}
