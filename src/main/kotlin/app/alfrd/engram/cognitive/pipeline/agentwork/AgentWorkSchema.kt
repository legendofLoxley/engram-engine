package app.alfrd.engram.cognitive.pipeline.agentwork

/**
 * Property names and select options of the four Agent Work databases, exactly as they are in the
 * workspace (read from the live schemas on 2026-10-12). One place, so a rename in Notion is a
 * one-line change here and a typo is a compile error everywhere else.
 */
object AgentWorkSchema {

    /**
     * The converged goal ("Goals in memory: converged schema", October 12, 2026): a sentence (Goal), a check
     * (Done when), the pages and databases it may touch (Scope, read from @-mentions only), the Container
     * it serves, its Status, and three hidden defaults. Intent, Metric, Done Criteria (renamed Done when),
     * Target Links, Target Space, Goal Type, Metric Locked and Metric Note are retired: nothing here names
     * them, so removing them from the database cannot break the Director.
     */
    object Goals {
        const val GOAL = "Goal"
        const val GOAL_ID = "Goal ID"
        const val STATUS = "Status"
        const val DONE_WHEN = "Done when"
        const val SCOPE = "Scope"
        const val SERVES = "Serves"
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

        /** Written by QC: what the goal left unclear and what alfrd had to guess. */
        const val GOAL_FEEDBACK = "Goal Feedback"
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
        const val RESULT = "Result"
        const val STARTED = "Started"
        const val FINISHED = "Finished"
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

/**
 * A goal's Status. Only [ACTIVE] lets a cycle run; the rest are read so a message can say what the goal
 * is instead. The live database offers Draft, Queued, Active, Met, Abandoned and Expired (read from the
 * workspace on 2026-10-13); the converged design names Draft, Active, Done and Stopped. Both vocabularies
 * are known here, so renaming the options in Notion changes nothing that matters.
 */
enum class GoalStatus(val label: String) {
    DRAFT("Draft"), QUEUED("Queued"), ACTIVE("Active"), MET("Met"), ABANDONED("Abandoned"), EXPIRED("Expired"), DONE("Done"), STOPPED("Stopped");

    companion object { fun fromLabel(label: String?): GoalStatus? = entries.firstOrNull { it.label == label } }
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
