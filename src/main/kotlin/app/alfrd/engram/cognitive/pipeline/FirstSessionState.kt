package app.alfrd.engram.cognitive.pipeline

/**
 * Per-pipeline state recorded when a first-session invited user is greeted with the
 * warm provenance intro. Survives on the [CognitivePipeline] instance for observability.
 */
data class FirstSessionState(
    /** True when both detection checks passed at initSession time. */
    val isFirstSession: Boolean = false,
    /** Copied from the INVITED edge — drives scaffold seeding at Turn 1. */
    val trustPhase: String? = null,
    /** Copied from the INVITED edge. */
    val engagementIntent: String? = null,
)
