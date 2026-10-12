package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.FakeProperty
import app.alfrd.engram.notion.NotionTableSet

/**
 * The eight tables of the Agent Work + PPS Evidence Ledger, installed in a [FakeNotionApi] under the
 * ids a [NotionTableSet] gives them. Property names, types and select options are exactly those of
 * the real databases (read from the workspace on 2026-10-12), including which properties are
 * formulas, rollups and auto-increment ids.
 *
 * Auto-increment prefixes are guesses (`GOAL` is confirmed by a real goal row; the others are not).
 */
class AgentWorkTemplate(val tableSet: NotionTableSet) {
    val goals: String get() = tableSet.table(AgentWorkTables.GOALS).dataSourceId
    val cycles: String get() = tableSet.table(AgentWorkTables.CYCLES).dataSourceId
    val tasks: String get() = tableSet.table(AgentWorkTables.AGENT_TASKS).dataSourceId
    val escalations: String get() = tableSet.table(AgentWorkTables.ESCALATIONS).dataSourceId
    val questions: String get() = tableSet.table(AgentWorkTables.QUESTIONS).dataSourceId
    val claims: String get() = tableSet.table(AgentWorkTables.CLAIMS).dataSourceId
    val sources: String get() = tableSet.table(AgentWorkTables.SOURCES).dataSourceId
    val evidence: String get() = tableSet.table(AgentWorkTables.EVIDENCE).dataSourceId
}

private fun options(vararg names: String) = FakeProperty.Select(names.toSet())

fun FakeNotionApi.installAgentWorkTemplate(tableSet: NotionTableSet): AgentWorkTemplate {
    val t = AgentWorkTemplate(tableSet)

    defineDataSourceWithId(
        t.goals,
        mapOf(
            "Goal" to FakeProperty.Title,
            "Goal ID" to FakeProperty.UniqueId("GOAL"),
            "Goal Type" to options("Work", "Review"),
            "Status" to options("Draft", "Queued", "Active", "Met", "Abandoned", "Expired"),
            "Intent" to FakeProperty.RichText,
            "Scope" to FakeProperty.RichText,
            "Target Space" to options("PPS Evidence Ledger"),
            "Target Links" to FakeProperty.RichText,
            "Metric" to FakeProperty.RichText,
            "Metric Locked" to FakeProperty.Date,
            "Metric Note" to FakeProperty.RichText,
            "Done Criteria" to FakeProperty.RichText,
            "Deadline" to FakeProperty.Date,
            "Cycle Budget" to FakeProperty.Number,
            "Replan Cap" to FakeProperty.Number,
            "Cycles" to FakeProperty.Relation(t.cycles, "Goal"),
            "Escalations" to FakeProperty.Relation(t.escalations, "Goal"),
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

    defineDataSourceWithId(
        t.questions,
        mapOf(
            "Question" to FakeProperty.Title,
            "Answer Standard" to FakeProperty.RichText,
            "Asked of" to options("Board", "Superintendent", "Legislature", "State Agency", "Other"),
            "Section" to options("Budget & Funding", "Enrollment & Facilities", "Students & Programs", "Contracts & Bonds", "Governance & Process", "Community & Transportation"),
            "Weight" to FakeProperty.Number,
            "Weight Rationale" to FakeProperty.RichText,
            "Standard Locked" to FakeProperty.Date,
            "Lock Note" to FakeProperty.RichText,
            "Follow-up to" to FakeProperty.Relation(t.questions, "Follow-ups"),
            "Follow-ups" to FakeProperty.Relation(t.questions, "Follow-up to"),
            "Related Claims" to FakeProperty.Relation(t.claims, "Bears on"),
            "Required Claims" to FakeProperty.Relation(t.claims, "Required by"),
            "Status" to FakeProperty.Formula,
            "Answer Score" to FakeProperty.Formula,
            "Required Claim Confidence" to FakeProperty.Rollup,
        ),
    )
    defineDataSourceWithId(
        t.claims,
        mapOf(
            "Claim" to FakeProperty.Title,
            "Claimed by" to options("PPS", "Board Member", "Save PDX", "Union", "Press", "State Agency", "Anonymous", "Standard (no claimant)", "Other"),
            "Due" to FakeProperty.Date,
            "Bears on" to FakeProperty.Relation(t.questions, "Related Claims"),
            "Required by" to FakeProperty.Relation(t.questions, "Required Claims"),
            "Evidence" to FakeProperty.Relation(t.evidence, "Claim"),
            "Conflicts with" to FakeProperty.Relation(t.claims, "Conflicted by"),
            "Conflicted by" to FakeProperty.Relation(t.claims, "Conflicts with"),
            "Confidence" to FakeProperty.Formula,
            "Impact" to FakeProperty.Formula,
            "Kind" to FakeProperty.Formula,
            "Lead Score" to FakeProperty.Formula,
            "Overdue" to FakeProperty.Formula,
            "Publish Score" to FakeProperty.Formula,
            "Best Support" to FakeProperty.Rollup,
            "Best Contradiction" to FakeProperty.Rollup,
            "Weight (Bears on)" to FakeProperty.Rollup,
            "Weight (Required)" to FakeProperty.Rollup,
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
            "Evidence" to FakeProperty.Relation(t.evidence, "Source"),
            "Default Strength" to FakeProperty.Formula,
        ),
    )
    defineDataSourceWithId(
        t.evidence,
        mapOf(
            "Evidence" to FakeProperty.Title,
            "Claim" to FakeProperty.Relation(t.claims, "Evidence"),
            "Source" to FakeProperty.Relation(t.sources, "Evidence"),
            "Stance" to options("Supports", "Partial", "Doesn't establish", "Contradicts"),
            "Note" to FakeProperty.RichText,
            "Strength Override" to FakeProperty.Number,
            "Strength" to FakeProperty.Formula,
            "Value" to FakeProperty.Formula,
            "Pro Value" to FakeProperty.Formula,
            "Con Value" to FakeProperty.Formula,
            "Source Default" to FakeProperty.Rollup,
        ),
    )
    return t
}
