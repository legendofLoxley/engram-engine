package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.FakeBlock
import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.FakeProperty
import app.alfrd.engram.notion.NotionTableSet

/**
 * The fourteen tables of the Agent Work table set (Agent Work, the PPS Evidence Ledger and Jacob's alfrd
 * memory) and the Persona page, installed in a [FakeNotionApi] under the ids a [NotionTableSet] gives them.
 * Property names, types and select options of the Agent Work tables are exactly those of the real
 * databases (read from the workspace on 2026-10-13); the PPS and memory tables carry the properties the
 * tests use, with the real names.
 *
 * The Goals schema also keeps the **retired** fields (Intent, Metric, Done Criteria, Target Links, Target
 * Space, Goal Type, Metric Locked, Metric Note) because the live database still has them until Jacob removes
 * them; a test fills them with a sentinel to prove nothing reads them.
 *
 * Auto-increment prefixes are guesses (`GOAL` is confirmed by a real goal row; the others are not).
 */
class AgentWorkTemplate(val tableSet: NotionTableSet) {
    val goals: String get() = tableSet.table(AgentWorkTables.GOALS).dataSourceId
    val cycles: String get() = tableSet.table(AgentWorkTables.CYCLES).dataSourceId
    val tasks: String get() = tableSet.table(AgentWorkTables.AGENT_TASKS).dataSourceId
    val escalations: String get() = tableSet.table(AgentWorkTables.ESCALATIONS).dataSourceId
    val questions: String get() = tableSet.table(AgentWorkTables.QUESTIONS).dataSourceId
    val findings: String get() = tableSet.table(AgentWorkTables.FINDINGS).dataSourceId
    val sources: String get() = tableSet.table(AgentWorkTables.SOURCES).dataSourceId
    val containers: String get() = tableSet.table(AgentWorkTables.MEMORY_CONTAINERS).dataSourceId
    val items: String get() = tableSet.table(AgentWorkTables.MEMORY_ITEMS).dataSourceId
    val entities: String get() = tableSet.table(AgentWorkTables.MEMORY_ENTITIES).dataSourceId
    val connections: String get() = tableSet.table(AgentWorkTables.MEMORY_CONNECTIONS).dataSourceId
    val sessions: String get() = tableSet.table(AgentWorkTables.MEMORY_SESSIONS).dataSourceId
    val memorySources: String get() = tableSet.table(AgentWorkTables.MEMORY_SOURCES).dataSourceId
    val templateChanges: String get() = tableSet.table(AgentWorkTables.MEMORY_TEMPLATE_CHANGES).dataSourceId

    /** The allowlisted Persona page, under the id the configuration pins. */
    val personaPageId: String get() = tableSet.readablePages.single { it.name == AgentWorkTables.PERSONA_PAGE }.pageId
}

private fun options(vararg names: String) = FakeProperty.Select(names.toSet())

fun FakeNotionApi.installAgentWorkTemplate(tableSet: NotionTableSet, personaText: List<FakeBlock> = listOf(FakeBlock(text = "Persona: how alfrd should be."))): AgentWorkTemplate {
    val t = AgentWorkTemplate(tableSet)

    defineDataSourceWithId(
        t.goals,
        mapOf(
            "Goal" to FakeProperty.Title,
            "Goal ID" to FakeProperty.UniqueId("GOAL"),
            "Status" to options("Draft", "Queued", "Active", "Met", "Abandoned", "Expired"),
            "Done when" to FakeProperty.RichText,
            "Scope" to FakeProperty.RichText,
            "Serves" to FakeProperty.Relation(t.containers, null),
            "Deadline" to FakeProperty.Date,
            "Cycle Budget" to FakeProperty.Number,
            "Replan Cap" to FakeProperty.Number,
            "Cycles" to FakeProperty.Relation(t.cycles, "Goal"),
            "Escalations" to FakeProperty.Relation(t.escalations, "Goal"),
            // Retired by the converged schema; still on the live database until Jacob removes them.
            "Goal Type" to options("Work", "Review"),
            "Intent" to FakeProperty.RichText,
            "Metric" to FakeProperty.RichText,
            "Metric Locked" to FakeProperty.Date,
            "Metric Note" to FakeProperty.RichText,
            "Target Space" to options("PPS Evidence Ledger"),
            "Target Links" to FakeProperty.RichText,
        ),
    )
    defineDataSourceWithId(
        t.cycles,
        mapOf(
            "Cycle" to FakeProperty.Title,
            "Cycle ID" to FakeProperty.UniqueId("CYCLE"),
            "Status" to options("Queued", "Running", "Paused", "Done", "Failed", "Cancelled"),
            "Goal" to FakeProperty.Relation(t.goals, "Cycles"),
            "Task Budget" to FakeProperty.Number,
            "Replans Used" to FakeProperty.Number,
            "Tasks Done" to FakeProperty.Number,
            "Lease Until" to FakeProperty.Date,
            "Worker" to FakeProperty.RichText,
            "Started" to FakeProperty.Date,
            "Finished" to FakeProperty.Date,
            "Metric Before" to FakeProperty.RichText,
            "Metric After" to FakeProperty.RichText,
            "Score" to FakeProperty.Number,
            "QC Spot Check" to FakeProperty.RichText,
            "Review Batch" to FakeProperty.RichText,
            "Summary" to FakeProperty.RichText,
            "Goal Feedback" to FakeProperty.RichText,
            "Tasks" to FakeProperty.Relation(t.tasks, "Cycle"),
            "Escalations" to FakeProperty.Relation(t.escalations, "Cycle"),
        ),
    )
    defineDataSourceWithId(
        t.tasks,
        mapOf(
            "Task" to FakeProperty.Title,
            "Task ID" to FakeProperty.UniqueId("TASK"),
            "Type" to options("Orchestrate", "Gate", "QC", "Read", "Check", "Hypothesis", "Consolidate", "Escalate"),
            "Status" to options("Planned", "Running", "Done", "Blocked", "Superseded", "Failed"),
            "Order" to FakeProperty.Number,
            "Plan Version" to FakeProperty.Number,
            "Instructions" to FakeProperty.RichText,
            "Target Links" to FakeProperty.RichText,
            "Result" to FakeProperty.RichText,
            "Plan Impact" to options("None", "Minor", "Major"),
            "Impact Reason" to FakeProperty.RichText,
            "Op key" to FakeProperty.RichText,
            "Started" to FakeProperty.Date,
            "Finished" to FakeProperty.Date,
            "Lease Until" to FakeProperty.Date,
            "Cycle" to FakeProperty.Relation(t.cycles, "Tasks"),
        ),
    )
    defineDataSourceWithId(
        t.escalations,
        mapOf(
            "Escalation" to FakeProperty.Title,
            "Escalation ID" to FakeProperty.UniqueId("ESC"),
            "Kind" to options("Review digest", "Contradiction", "Error found", "Replan cap", "Goal ended"),
            "Severity" to options("Digest", "Block"),
            "Status" to options("Open", "Resolved", "Dismissed"),
            "Ask" to FakeProperty.RichText,
            "Resolution" to FakeProperty.RichText,
            "Goal" to FakeProperty.Relation(t.goals, "Escalations"),
            "Cycle" to FakeProperty.Relation(t.cycles, "Escalations"),
        ),
    )

    // ── PPS Evidence Ledger (read-only). Evidence is being archived and is not in the set. ──
    defineDataSourceWithId(
        t.questions,
        mapOf(
            "Question" to FakeProperty.Title,
            "Answer Standard" to FakeProperty.RichText,
            "Weight" to FakeProperty.Number,
            "Standard Locked" to FakeProperty.Date,
            "Lock Note" to FakeProperty.RichText,
            "Status" to FakeProperty.Formula,
            "Answer Score" to FakeProperty.Formula,
        ),
    )
    defineDataSourceWithId(
        t.findings,
        mapOf(
            "Claim" to FakeProperty.Title,
            "Claimed by" to options("PPS", "Board Member", "Save PDX", "Union", "Press", "State Agency", "Anonymous", "Standard (no claimant)", "Other"),
            "Due" to FakeProperty.Date,
            "Confidence" to FakeProperty.Formula,
        ),
    )
    defineDataSourceWithId(
        t.sources,
        mapOf(
            "Title" to FakeProperty.Title,
            "Type" to options(
                "Official Record", "PPS Document", "Audit / State Report", "Research Study", "Records Archive", "Press",
                "Advocacy", "Union", "Testimony", "AI Summary", "Anonymous Tip",
            ),
            "URL" to FakeProperty.Url,
            "Date" to FakeProperty.Date,
            "Notes" to FakeProperty.RichText,
            "Default Strength" to FakeProperty.Formula,
        ),
    )

    // ── alfrd memory (Jacob's real teamspace; read-only here) ──
    defineDataSourceWithId(
        t.containers,
        mapOf(
            "Name" to FakeProperty.Title,
            "Kind" to options("Domain", "Project"),
            "Status" to options("Active", "Paused", "Done"),
            "Sensitivity" to options("Open", "Personal", "Sensitive"),
            "Focus" to FakeProperty.Checkbox,
            "Due" to FakeProperty.Date,
        ),
    )
    defineDataSourceWithId(
        t.items,
        mapOf(
            "Item" to FakeProperty.Title,
            "Kind" to options("Task", "Event", "Question", "Fact", "Insight"),
            "Status" to options("Open", "Resolved", "Superseded"),
            "Said" to FakeProperty.RichText,
            "Sensitivity" to options("Open", "Personal", "Sensitive"),
            "Container" to FakeProperty.Relation(t.containers, null),
            "Date" to FakeProperty.Date,
            "Op key" to FakeProperty.RichText,
        ),
    )
    defineDataSourceWithId(
        t.entities,
        mapOf("Name" to FakeProperty.Title, "Type" to options("Self", "Person", "Client", "Organization", "Artist", "Work", "Place", "Thing"), "Detail" to FakeProperty.RichText),
    )
    defineDataSourceWithId(
        t.connections,
        mapOf("Connection" to FakeProperty.Title, "Type" to options("Inspired by", "Played with", "Client of", "Works with", "Part of", "Related to"), "Said" to FakeProperty.RichText),
    )
    defineDataSourceWithId(
        t.sessions,
        mapOf("Session" to FakeProperty.Title, "Key" to FakeProperty.RichText, "Date" to FakeProperty.Date, "Notes" to FakeProperty.RichText),
    )
    defineDataSourceWithId(
        t.memorySources,
        mapOf("Name" to FakeProperty.Title, "Kind" to options("Notion", "Google", "GitHub", "Other"), "Access" to options("Off", "Read", "Read and write"), "What it holds" to FakeProperty.RichText),
    )
    defineDataSourceWithId(
        t.templateChanges,
        mapOf(
            "Change" to FakeProperty.Title,
            "Status" to options("Proposed", "Approved", "Applied", "Rejected"),
            "Area" to options("Schema", "Standard kit", "Persona page", "Beta Terms", "Disclosures", "Backend prompt", "Other"),
            "Why" to FakeProperty.RichText,
        ),
    )

    createPlainPageWithId(t.personaPageId, title = "Persona", content = personaText)
    return t
}
