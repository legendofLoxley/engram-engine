package app.alfrd.engram.cognitive.pipeline.agentwork

/**
 * One task of a validated plan, ready to write. [order] is assigned by code (1, 2, 3, ...) and never
 * taken from a model; [targetLinks] are URLs the validator has already checked against the allowed set.
 */
data class PlannedTask(
    val order: Int,
    val title: String,
    val type: TaskType,
    val instructions: String,
    val targetLinks: List<String>,
)
