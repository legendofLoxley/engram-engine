package app.alfrd.engram.cognitive.pipeline.hermes

import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService
import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** What [CognitivePipeline][app.alfrd.engram.cognitive.pipeline.CognitivePipeline] depends on — an interface (not the concrete class) so a turn-level test can fake dispatch without constructing a real [ActorEventIngestionService]/[HermesAcpClient]. */
fun interface HermesDelegationDispatching {
    fun dispatchAsync(assignment: HermesAssignment)
}

/**
 * Dispatches one [HermesAssignment] to the real (isolated dev) Hermes runtime and, once it
 * completes, does two independent things with the result:
 *
 * 1. Ingests the attributed result via [ActorEventIngestionService.ingest] directly — exactly
 *    the path [app.alfrd.engram.api.DebugActorEventRoutes]'s own class doc names as what "a
 *    production Hermes adapter would be." This is graph evidence: it becomes eligible for a
 *    *later* turn through the existing `SurfacingReason.RecentActorEvidence` Horizon pool,
 *    subject to that pool's own eligibility/budget rules — as the design contract itself notes,
 *    "a committed result is not proof that the user received it."
 * 2. Records the completion in [HermesAssignmentCompletionStore] — the explicit, independent
 *    delivery channel a caller (the WebUI runner adapter) polls by `assignmentId` to learn the
 *    outstanding assignment's outcome and push it into the *originating* conversation, without
 *    depending on Horizon selection at all. See that store's doc for why this is deliberately
 *    separate from graph ingestion, not derived from it.
 *
 * Deliberately fire-and-forget, same idiom as [app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService]'s
 * own `CoroutineScope(Dispatchers.IO + SupervisorJob())` — the calling Director turn issues the
 * assignment and returns its own reply immediately; this coroutine keeps running after that
 * response has already gone to the browser.
 */
class HermesDelegationDispatcher(
    private val client: HermesAcpClient,
    private val ingestionService: ActorEventIngestionService,
    private val completionStore: HermesAssignmentCompletionStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : HermesDelegationDispatching {
    private val logger = LoggerFactory.getLogger(HermesDelegationDispatcher::class.java)

    override fun dispatchAsync(assignment: HermesAssignment) {
        logger.info(
            "hermes-delegation dispatching assignmentId={} userEmail={} task={}",
            assignment.assignmentId, assignment.userEmail, assignment.task,
        )
        scope.launch {
            val outcome = client.inspectFixture(assignment, HermesDelegationTrigger.FIXTURE_FILENAME)
            val kind = when (outcome) {
                is HermesAssignmentOutcome.Completed -> ActorEventKind.ToolResult(
                    text = outcome.findingsText,
                    toolName = outcome.toolName,
                    toolSucceeded = outcome.toolSucceeded,
                )
                is HermesAssignmentOutcome.Failed -> ActorEventKind.ToolResult(
                    text = "Hermes assignment could not be completed: ${outcome.reason}",
                    toolName = "read",
                    toolSucceeded = false,
                )
            }
            val ingestResult = ingestionService.ingest(
                userEmail = assignment.userEmail,
                eventId = "hermes-assignment-${assignment.assignmentId}",
                kind = kind,
                sourceName = "hermes",
                assignmentId = assignment.assignmentId,
                occurredAt = System.currentTimeMillis(),
            )
            // The one decision boundary: whether/how this executed assignment's findings are
            // actually delivered is decided HERE, by the Director-side HermesCompletionDirector —
            // never implicitly by whatever transport later renders it. See that object's doc.
            val decision = HermesCompletionDirector.decide(assignment, outcome)
            completionStore.record(
                assignmentId = assignment.assignmentId,
                userEmail = assignment.userEmail,
                outcome = outcome,
                decision = decision,
                graphIngestOutcome = ingestResult::class.simpleName ?: "Unknown",
            )
            logger.info(
                "hermes-delegation completed assignmentId={} userEmail={} outcome={} decision={} ingestOutcome={}",
                assignment.assignmentId, assignment.userEmail, outcome::class.simpleName, decision::class.simpleName, ingestResult::class.simpleName,
            )
        }
    }
}
