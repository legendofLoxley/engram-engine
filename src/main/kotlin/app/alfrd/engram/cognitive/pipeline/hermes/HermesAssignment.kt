package app.alfrd.engram.cognitive.pipeline.hermes

/**
 * Which read-only Hermes exchange an assignment actually runs — every variant names its own
 * approved target file, resolved and validated in code against the approved workspace root (see
 * [HermesWorkspacePath]) before anything is sent to Hermes. Deliberately a small closed set, not
 * an open task-kind enum: adding a third kind means adding a third variant here and a third
 * [HermesAcpClient] method, never a generic "run this instruction against this arbitrary path"
 * escape hatch (see [HermesDelegationTrigger]'s own doc for why that boundary matters).
 */
sealed interface HermesAssignmentKind {
    val targetFilename: String

    /** The original bounded slice: read the demonstration fixture, report its marker verbatim. */
    data class MarkerCheck(override val targetFilename: String) : HermesAssignmentKind

    /** Read an approved document and summarize its goal, deadlines, risks, and next actions. */
    data class DocumentSummary(override val targetFilename: String) : HermesAssignmentKind
}

/**
 * A single correlated Director → Hermes delegation, for the first bounded real-execution
 * slice (see [HermesDelegationTrigger] — read-only fixture/document inspection only). This is
 * deliberately narrow rather than a general task/job schema: every field is exactly what
 * this one slice needs, not a speculative shape for future assignment kinds. [assignmentId]
 * is the correlation key threaded through to [app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService.ingest]'s
 * existing `assignmentId` parameter — it already exists there as a write-only/fingerprint
 * field with no dedicated lifecycle type; this is the first real value that ever fills it.
 */
data class HermesAssignment(
    val assignmentId: String,
    val userEmail: String,
    /**
     * A human-readable description of what was asked — logged and shown in the Director's own
     * directive/trace. NOT sent verbatim as the ACP `session/prompt` text: `HermesAcpClient`
     * builds that prompt itself around an absolute target path (see its own doc for why a
     * bare relative filename proved unreliable against the real runtime).
     */
    val task: String,
    val originalRequest: String,
    /**
     * The turn's `cycleSeq` boundary at issue time, recorded for provenance only. Per the design
     * contract's own correction record, `cycleSeq` equality is ordering metadata, not proof of
     * commitment or permission — this field is never used as a freshness gate.
     */
    val issuedAtCycleSeq: Long?,
    /**
     * Defaults to the original marker-check slice so every existing caller/test that never names
     * a kind explicitly keeps behaving exactly as before — see [HermesDelegationDispatcher]'s own
     * call site for how this drives which [HermesAcpClient] method actually runs.
     */
    val kind: HermesAssignmentKind = HermesAssignmentKind.MarkerCheck(HermesDelegationTrigger.FIXTURE_FILENAME),
    val issuedAt: Long = System.currentTimeMillis(),
)
