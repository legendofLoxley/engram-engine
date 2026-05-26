package app.alfrd.engram.cognitive.pipeline

/**
 * Per-pipeline mutable state for the first-session identity verification flow.
 *
 * Lives on the [CognitivePipeline] instance — one pipeline = one session.
 * Persists across the two turns of the verification exchange.
 */
data class FirstSessionState(
    /** True when both detection checks passed at initSession time. */
    val isFirstSession: Boolean = false,
    /** True between Turn 1 (system greeting) and Turn 2 (user identity response). */
    val awaitingIdentityVerification: Boolean = false,
    /** Set to true after successful LLM match (confidence ≥ 0.6). */
    val identityVerified: Boolean = false,
    /** Set to true after failed match on second attempt — flagged for Jacob's review. */
    val identityFlagged: Boolean = false,
    /** Number of verification attempts so far (reask logic allows one retry). */
    val retryCount: Int = 0,
    /** Copied from the INVITED edge; set on verification success. */
    val trustPhase: String? = null,
    /** Copied from the INVITED edge; set on verification success. */
    val engagementIntent: String? = null,
    /** Cached from the INVITED edge at Turn 1 for comparison in Turn 2. */
    val relationshipContext: String? = null,
)
