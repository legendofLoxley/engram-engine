package app.alfrd.engram.notion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A token reference plus the table set it unlocks. Anything with a token and a set of tables
 * implements this, so a [NotionSetRegistry] can check that no two of them overlap.
 */
interface NotionSetBinding {
    /** A reference (`env:NAME` or `file:/path`), never the token. */
    val tokenRef: String
    val tableSet: NotionTableSet
}

/**
 * Configuration for a table set that is not a person's memory: for example the Agent Work + PPS
 * set one worker token reads and writes.
 *
 * ```json
 * {
 *   "setName": "agent-worker",
 *   "tokenRef": "env:ALFRD_WORKER_NOTION_TOKEN",
 *   "tables": [
 *     { "name": "goals",     "dataSourceId": "collection://…", "access": "read-write" },
 *     { "name": "tasks",     "dataSourceId": "collection://…", "access": "read-write", "idempotencyKeyProperty": "Op key" },
 *     { "name": "claims",    "dataSourceId": "collection://…", "access": "read-only" }
 *   ],
 *   "readablePages": [ { "name": "agent-work-home", "pageId": "…" } ]
 * }
 * ```
 * `access` has no default: every table says whether it can be written. Data source ids are bare ids
 * or `collection://` references; a URL is refused because it names a database or a page, not a data
 * source. Unknown keys are rejected so a misspelled one cannot silently leave a table unconfigured.
 */
data class NotionTableSetConfig(
    override val tokenRef: String,
    override val tableSet: NotionTableSet,
) : NotionSetBinding {

    companion object {
        private val TOP_LEVEL_KEYS = setOf("setName", "tokenRef", "tables", "readablePages")
        private val TABLE_KEYS = setOf("name", "dataSourceId", "access", "pageBodyAppend", "idempotencyKeyProperty", "allowIncomingRelations")
        private val PAGE_KEYS = setOf("name", "pageId")

        fun parse(json: String): NotionTableSetConfig {
            val root = parseJsonObject(json, "Notion table-set configuration")
            rejectUnknownKeys(root, TOP_LEVEL_KEYS, "configuration")

            val setName = root.requiredString("setName")
            val tokenRef = root.requiredString("tokenRef")
            requireTokenRef(tokenRef)

            val tables = (root["tables"] as? JsonArray ?: throw NotionConfigException("tables is required and must be an array"))
                .mapIndexed { index, element -> parseTable(element, "tables[$index]") }

            val pages = when (val raw = root["readablePages"]) {
                null -> emptyList()
                is JsonArray -> raw.mapIndexed { index, element -> parsePage(element, "readablePages[$index]") }
                else -> throw NotionConfigException("readablePages must be an array")
            }
            return NotionTableSetConfig(tokenRef, NotionTableSet(setName, tables, pages))
        }

        private fun parseTable(element: JsonElement, where: String): NotionTable {
            val obj = element as? JsonObject ?: throw NotionConfigException("$where must be an object")
            rejectUnknownKeys(obj, TABLE_KEYS, where)
            val name = obj.requiredString("name", where)
            val label = obj.requiredString("access", where)
            val access = TableAccess.fromLabel(label)
                ?: throw NotionConfigException("$where.access must be \"read-only\" or \"read-write\"")
            return NotionTable(
                name = name,
                dataSourceId = dataSourceId(obj.requiredString("dataSourceId", where), where),
                access = access,
                pageBodyAppend = obj.optionalBoolean("pageBodyAppend", where) ?: false,
                idempotencyKeyProperty = idempotencyKeyProperty(obj, where),
                allowIncomingRelations = obj.optionalBoolean("allowIncomingRelations", where) ?: (access == TableAccess.READ_WRITE),
            )
        }

        private fun parsePage(element: JsonElement, where: String): NotionReadablePage {
            val obj = element as? JsonObject ?: throw NotionConfigException("$where must be an object")
            rejectUnknownKeys(obj, PAGE_KEYS, where)
            val id = normalizeNotionId(obj.requiredString("pageId", where)) ?: throw NotionConfigException("$where.pageId is not a Notion id")
            return NotionReadablePage(obj.requiredString("name", where), id)
        }

        /** Absent means the table has not opted in. Present but blank, null or not a string is a mistake, and must not quietly turn the protection off. */
        private fun idempotencyKeyProperty(obj: JsonObject, where: String): String? {
            if (!obj.containsKey("idempotencyKeyProperty")) return null
            return obj.optionalString("idempotencyKeyProperty")
                ?: throw NotionConfigException("$where.idempotencyKeyProperty must be a non-empty string when present; leave it out to opt out")
        }

        /** The whole value must be a data source id, bare or as an exact `collection://` reference. Text around an id is not stripped off. */
        private val DATA_SOURCE_ID = Regex("(?:collection://)?([0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12})")

        private fun dataSourceId(raw: String, where: String): String {
            if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                throw NotionConfigException("$where.dataSourceId must be a data source id (bare, or a collection:// reference), not a URL: a URL names a database or a page")
            }
            val exact = DATA_SOURCE_ID.matchEntire(raw)
            if (exact != null) return normalizeNotionId(exact.groupValues[1])!!
            if (normalizeNotionId(raw) == null) throw NotionConfigException("$where.dataSourceId is not a Notion id")
            throw NotionConfigException("$where.dataSourceId must be a bare data source id or an exact collection:// reference; it has other text around the id, which may be a URL or a typo")
        }
    }
}
