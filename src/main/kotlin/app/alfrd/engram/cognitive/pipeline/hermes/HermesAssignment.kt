package app.alfrd.engram.cognitive.pipeline.hermes

/**
 * A single correlated Director → Hermes delegation, for the first bounded real-execution
 * slice (see [HermesDelegationTrigger] — read-only fixture inspection only). This is
 * deliberately narrow rather than a general task/job schema: every field is exactly what
 * this one slice needs, not a speculative shape for future assignment kinds. [assignmentId]
 * is the correlation key threaded through to [app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService.ingest]'s
 * existing `assignmentId` parameter — it already exists there as a write-only/fingerprint
 * field with no dedicated lifecycle type; this is the first real value that ever fills it.
 */
data class HermesAssignment(
    val assignmentId: String,
    val userEmail: String,
    /** Sent verbatim as the ACP `session/prompt` text — the original request, not a rewritten command. */
    val task: String,
    val originalRequest: String,
    /**
     * The turn's `cycleSeq` boundary at issue time, recorded for provenance only. Per the design
     * contract's own correction record, `cycleSeq` equality is ordering metadata, not proof of
     * commitment or permission — this field is never used as a freshness gate.
     */
    val issuedAtCycleSeq: Long?,
    val issuedAt: Long = System.currentTimeMillis(),
)
