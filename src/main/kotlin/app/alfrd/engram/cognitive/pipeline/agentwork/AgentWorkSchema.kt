package app.alfrd.engram.cognitive.pipeline.agentwork

/**
 * Property names and select options of the four Agent Work databases, exactly as they are in the
 * workspace (read from the live schemas on 2026-10-12). One place, so a rename in Notion is a
 * one-line change here and a typo is a compile error everywhere else.
 */
object AgentWorkSchema {

    object Goals {
        const val GOAL = "Goal"
        const val GOAL_ID = "Goal ID"
        const val TYPE = "Goal Type"
        const val STATUS = "Status"
        const val INTENT = "Intent"
        const val SCOPE = "Scope"
        const val TARGET_SPACE = "Target Space"
        const val TARGET_LINKS = "Target Links"
        const val METRIC = "Metric"
        const val DONE_CRITERIA = "Done Criteria"
        const val CYCLE_BUDGET = "Cycle Budget"
        const val REPLAN_CAP = "Replan Cap"
        const val DEADLINE = "Deadline"
    }

    object Cycles {
        const val CYCLE = "Cycle"
        const val CYCLE_ID = "Cycle ID"
        const val STATUS = "Status"
        const val GOAL = "Goal"
        const val TASK_BUDGET = "Task Budget"
        const val TASKS_DONE = "Tasks Done"
        const val LEASE_UNTIL = "Lease Until"
        const val WORKER = "Worker"
        const val STARTED = "Started"
        const val FINISHED = "Finished"
        const val SUMMARY = "Summary"
    }

    object Tasks {
        const val TASK = "Task"
        const val TASK_ID = "Task ID"
        const val TYPE = "Type"
        const val STATUS = "Status"
        const val ORDER = "Order"
        const val PLAN_VERSION = "Plan Version"
        const val INSTRUCTIONS = "Instructions"
        const val TARGET_LINKS = "Target Links"
        const val CYCLE = "Cycle"
    }

    object Escalations {
        const val ESCALATION = "Escalation"
        const val ESCALATION_ID = "Escalation ID"
        const val KIND = "Kind"
        const val SEVERITY = "Severity"
        const val STATUS = "Status"
        const val ASK = "Ask"
        const val RESOLUTION = "Resolution"
        const val GOAL = "Goal"
        const val CYCLE = "Cycle"
    }
}

enum class GoalStatus(val label: String) {
    DRAFT("Draft"), QUEUED("Queued"), ACTIVE("Active"), MET("Met"), ABANDONED("Abandoned"), EXPIRED("Expired");

    companion object { fun fromLabel(label: String?): GoalStatus? = entries.firstOrNull { it.label == label } }
}

enum class GoalType(val label: String) {
    WORK("Work"), REVIEW("Review");

    companion object { fun fromLabel(label: String?): GoalType? = entries.firstOrNull { it.label == label } }
}

enum class CycleStatus(val label: String) {
    QUEUED("Queued"), RUNNING("Running"), PAUSED("Paused"), DONE("Done"), FAILED("Failed"), CANCELLED("Cancelled");

    companion object { fun fromLabel(label: String?): CycleStatus? = entries.firstOrNull { it.label == label } }
}

/**
 * The kinds of Agent Task. **Orchestrate, Gate and QC are the Director's own steps**: it records
 * them itself in later increments and never takes them from a model's proposal. The other five are
 * the work a plan may ask for; none of them writes anything by itself.
 */
enum class TaskType(val label: String, val proposable: Boolean) {
    ORCHESTRATE("Orchestrate", false),
    GATE("Gate", false),
    QC("QC", false),
    READ("Read", true),
    CHECK("Check", true),
    HYPOTHESIS("Hypothesis", true),
    CONSOLIDATE("Consolidate", true),
    ESCALATE("Escalate", true);

    companion object {
        fun fromLabel(label: String?): TaskType? = entries.firstOrNull { it.label == label }
        val PROPOSABLE: List<TaskType> = entries.filter { it.proposable }
    }
}

enum class TaskStatus(val label: String) {
    PLANNED("Planned"), RUNNING("Running"), DONE("Done"), BLOCKED("Blocked"), SUPERSEDED("Superseded"), FAILED("Failed");

    companion object { fun fromLabel(label: String?): TaskStatus? = entries.firstOrNull { it.label == label } }
}
