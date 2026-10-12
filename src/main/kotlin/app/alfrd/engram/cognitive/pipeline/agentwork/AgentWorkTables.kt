package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionConfigException
import app.alfrd.engram.notion.NotionTableSet
import app.alfrd.engram.notion.NotionTableSetConfig
import app.alfrd.engram.notion.TableAccess
import java.io.File

/**
 * The eight tables of the Agent Work + PPS Evidence Ledger table set, by the names the committed
 * configuration (`deploy/config/notion-agent-worker.json`) gives them.
 *
 * **Agent Work** is the Director's own run log. **PPS** is the ledger where the work itself lands
 * in later increments; in this one it is only read.
 */
object AgentWorkTables {
    const val GOALS = "goals"
    const val CYCLES = "cycles"
    const val AGENT_TASKS = "agent-tasks"
    const val ESCALATIONS = "escalations"

    const val QUESTIONS = "questions"
    const val CLAIMS = "claims"
    const val SOURCES = "sources"
    const val EVIDENCE = "evidence"

    val PPS: List<String> = listOf(QUESTIONS, CLAIMS, SOURCES, EVIDENCE)
    val ALL: Set<String> = setOf(GOALS, CYCLES, AGENT_TASKS, ESCALATIONS) + PPS

    /** The text property on Agent Tasks that makes a create idempotent: `cycle:<Cycle ID>:v<Plan Version>:o<Order>`. */
    const val OP_KEY_PROPERTY = "Op key"

    /**
     * This increment's promise, enforced where the guard is configured and not in planner code:
     * the Director reads Goals and Escalations, reads PPS, and writes only Cycles and Agent Tasks.
     * A configuration that grants more is refused at startup, so loosening it is a deliberate edit
     * to this check in a later increment and not a one-line change to a JSON file.
     */
    fun requireDryRunShape(set: NotionTableSet) {
        val names = set.tables.map { it.name }.toSet()
        val missing = ALL - names
        if (missing.isNotEmpty()) throw NotionConfigException("table set '${set.name}' is missing table(s): ${missing.sorted().joinToString(", ")}")
        val extra = names - ALL
        if (extra.isNotEmpty()) throw NotionConfigException("table set '${set.name}' has table(s) this increment does not use: ${extra.sorted().joinToString(", ")}")

        val writable = set.tables.filter { it.access == TableAccess.READ_WRITE }.map { it.name }.toSet()
        if (writable != setOf(CYCLES, AGENT_TASKS)) {
            throw NotionConfigException(
                "table set '${set.name}' must make exactly ${CYCLES} and ${AGENT_TASKS} read-write in this increment; " +
                    "read-write now: ${writable.sorted().joinToString(", ").ifEmpty { "none" }}",
            )
        }
        if (set.table(AGENT_TASKS).idempotencyKeyProperty != OP_KEY_PROPERTY) {
            throw NotionConfigException("table '$AGENT_TASKS' must use '$OP_KEY_PROPERTY' as its idempotency key")
        }
        if (set.table(CYCLES).pageBodyAppend || set.table(AGENT_TASKS).pageBodyAppend) {
            throw NotionConfigException("no table may accept page-body appends in this increment")
        }
        if (set.readablePages.isNotEmpty()) {
            throw NotionConfigException("this increment reads no page that is not a row of a configured table")
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

/** Loads and checks the Agent Work + PPS configuration. The token is only a reference in the file; nothing here reads it. */
object AgentWorkConfig {
    fun load(path: String): NotionTableSetConfig {
        val file = File(path)
        if (!file.isFile) throw NotionConfigException("the Notion worker configuration file does not exist")
        val config = NotionTableSetConfig.parse(file.readText())
        AgentWorkTables.requireDryRunShape(config.tableSet)
        return config
    }
}
