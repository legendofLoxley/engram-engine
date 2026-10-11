package app.alfrd.engram.notion

/**
 * A second table set in the fake, deliberately not the alfrd memory schema, so the guard and the
 * idempotent creator are exercised against something other than the tables they were first built for.
 *
 * - `cycles`: read-write, accepts page-body appends.
 * - `tasks`: read-write, opted in to an idempotency key (`Op key`). Its `Claim` relation is dual with
 *   the read-only `claims` table; its `Source` relation is one-way into the read-only `sources`.
 * - `claims`: read-only; does not accept incoming relations (the default for a read-only table).
 * - `sources`: read-only; marked as accepting incoming relations because the only link into it is one-way.
 *
 * Also defines one table outside the set.
 */
data class WorkerTemplate(val tableSet: NotionTableSet, val foreignDataSourceId: String) {
    val cycles: String get() = tableSet.table("cycles").dataSourceId
    val tasks: String get() = tableSet.table("tasks").dataSourceId
    val claims: String get() = tableSet.table("claims").dataSourceId
    val sources: String get() = tableSet.table("sources").dataSourceId
}

fun FakeNotionApi.installWorkerTemplate(): WorkerTemplate {
    val cycles = newId()
    val tasks = newId()
    val claims = newId()
    val sources = newId()
    val schemas = mapOf(
        cycles to mapOf(
            "Cycle" to FakeProperty.Title,
            "Status" to FakeProperty.Select(setOf("Queued", "Running", "Done")),
            "Tasks" to FakeProperty.Relation(tasks, "Cycle"),
        ),
        tasks to mapOf(
            "Task" to FakeProperty.Title,
            "Status" to FakeProperty.Select(setOf("Planned", "Done")),
            "Op key" to FakeProperty.RichText,
            "Cycle" to FakeProperty.Relation(cycles, "Tasks"),
            "Claim" to FakeProperty.Relation(claims, "Tasks"),
            "Source" to FakeProperty.Relation(sources, null),
        ),
        claims to mapOf(
            "Claim" to FakeProperty.Title,
            "Confidence" to FakeProperty.Select(setOf("Low", "High")),
            "Tasks" to FakeProperty.Relation(tasks, "Claim"),
        ),
        sources to mapOf(
            "Source" to FakeProperty.Title,
        ),
    )
    schemas.forEach { (id, schema) -> defineDataSourceWithId(id, schema) }
    val foreign = defineDataSource(mapOf("Name" to FakeProperty.Title))
    val set = NotionTableSet(
        "agent-worker",
        listOf(
            NotionTable("cycles", cycles, pageBodyAppend = true),
            NotionTable("tasks", tasks, idempotencyKeyProperty = "Op key"),
            NotionTable("claims", claims, TableAccess.READ_ONLY),
            NotionTable("sources", sources, TableAccess.READ_ONLY, allowIncomingRelations = true),
        ),
    )
    return WorkerTemplate(set, foreign)
}
