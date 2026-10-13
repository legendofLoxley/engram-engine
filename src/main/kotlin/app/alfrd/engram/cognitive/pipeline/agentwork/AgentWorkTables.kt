package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionConfigException
import app.alfrd.engram.notion.NotionTableSet
import app.alfrd.engram.notion.NotionTableSetConfig
import app.alfrd.engram.notion.TableAccess
import java.io.File

/**
 * The tables of the Agent Work table set, by the names the committed configuration
 * (`deploy/config/notion-agent-worker.json`) gives them.
 *
 * - **Agent Work** is the Director's own run log: Goals and Escalations are read; Cycles and Agent Tasks
 *   are the only tables it writes.
 * - **PPS Evidence Ledger** (Questions, Findings, Sources): read-only. Findings was Claims until the ledger
 *   was redesigned on October 12 (same data source id). Evidence is being archived and is not in the set.
 * - **alfrd memory** is Jacob's real teamspace: Containers, Items, Entities, Connections, Sessions,
 *   Sources and Template Changes, all read-only, plus the Persona page as the one allowlisted plain page.
 *   Memory tables carry a `memory-` prefix because PPS already has a table called `sources`.
 */
object AgentWorkTables {
    const val GOALS = "goals"
    const val CYCLES = "cycles"
    const val AGENT_TASKS = "agent-tasks"
    const val ESCALATIONS = "escalations"

    const val QUESTIONS = "questions"
    const val FINDINGS = "findings"
    const val SOURCES = "sources"

    const val MEMORY_CONTAINERS = "memory-containers"
    const val MEMORY_ITEMS = "memory-items"
    const val MEMORY_ENTITIES = "memory-entities"
    const val MEMORY_CONNECTIONS = "memory-connections"
    const val MEMORY_SESSIONS = "memory-sessions"
    const val MEMORY_SOURCES = "memory-sources"
    const val MEMORY_TEMPLATE_CHANGES = "memory-template-changes"

    /** The one plain page the client may read (and never write). */
    const val PERSONA_PAGE = "persona"

    val AGENT_WORK: List<String> = listOf(GOALS, CYCLES, AGENT_TASKS, ESCALATIONS)
    val PPS: List<String> = listOf(QUESTIONS, FINDINGS, SOURCES)
    val MEMORY: List<String> = listOf(
        MEMORY_CONTAINERS, MEMORY_ITEMS, MEMORY_ENTITIES, MEMORY_CONNECTIONS, MEMORY_SESSIONS, MEMORY_SOURCES, MEMORY_TEMPLATE_CHANGES,
    )
    val ALL: Set<String> = (AGENT_WORK + PPS + MEMORY).toSet()

    /** The only tables this channel may write. Everything else, memory and PPS included, is read-only. */
    val WRITABLE: Set<String> = setOf(CYCLES, AGENT_TASKS)

    val READABLE_PAGES: Set<String> = setOf(PERSONA_PAGE)

    /** The text property on Agent Tasks that makes a create idempotent: `cycle:<Cycle ID>:v<Plan Version>:o<Order>`. */
    const val OP_KEY_PROPERTY = "Op key"

    /**
     * This increment's promise, enforced where the guard is configured and not in planner code: the
     * Director reads every table, and writes only Cycles and Agent Tasks. A configuration that grants more
     * (a writable memory or PPS table, a page-body append, an extra readable page) is refused at startup,
     * so loosening it is a deliberate edit to this check and not a one-line change to a JSON file.
     */
    fun requireShape(set: NotionTableSet) {
        val names = set.tables.map { it.name }.toSet()
        val missing = ALL - names
        if (missing.isNotEmpty()) throw NotionConfigException("table set '${set.name}' is missing table(s): ${missing.sorted().joinToString(", ")}")
        val extra = names - ALL
        if (extra.isNotEmpty()) throw NotionConfigException("table set '${set.name}' has table(s) this increment does not use: ${extra.sorted().joinToString(", ")}")

        val writable = set.tables.filter { it.access == TableAccess.READ_WRITE }.map { it.name }.toSet()
        if (writable != WRITABLE) {
            throw NotionConfigException(
                "table set '${set.name}' must make exactly ${CYCLES} and ${AGENT_TASKS} read-write in this increment; " +
                    "read-write now: ${writable.sorted().joinToString(", ").ifEmpty { "none" }}",
            )
        }
        if (set.table(AGENT_TASKS).idempotencyKeyProperty != OP_KEY_PROPERTY) {
            throw NotionConfigException("table '$AGENT_TASKS' must use '$OP_KEY_PROPERTY' as its idempotency key")
        }
        if (set.tables.any { it.pageBodyAppend }) {
            throw NotionConfigException("no table may accept page-body appends in this increment")
        }
        val pages = set.readablePages.map { it.name }.toSet()
        if (pages != READABLE_PAGES) {
            throw NotionConfigException(
                "table set '${set.name}' must allowlist exactly the page(s) ${READABLE_PAGES.sorted().joinToString(", ")} for reading; " +
                    "allowlisted now: ${pages.sorted().joinToString(", ").ifEmpty { "none" }}",
            )
        }
        // A relation is replaced as a whole and Notion mirrors it onto the other table's row. A read-only table
        // that accepts incoming relations would therefore be written to, through the table that points at it.
        set.tables.firstOrNull { it.access == TableAccess.READ_ONLY && it.allowIncomingRelations }?.let {
            throw NotionConfigException(
                "table '${it.name}' is read-only but allows incoming relations; a relation written from another table would be mirrored onto its rows",
            )
        }
    }
}

/** Loads and checks the Agent Work configuration. The token is only a reference in the file; nothing here reads it. */
object AgentWorkConfig {
    fun load(path: String): NotionTableSetConfig {
        val file = File(path)
        if (!file.isFile) throw NotionConfigException("the Notion worker configuration file does not exist")
        val config = NotionTableSetConfig.parse(file.readText())
        AgentWorkTables.requireShape(config.tableSet)
        return config
    }
}
