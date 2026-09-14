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
 * completes, ingests the attributed result via [ActorEventIngestionService.ingest] directly —
 * exactly the path [app.alfrd.engram.api.DebugActorEventRoutes]'s own class doc names as what
 * "a production Hermes adapter would be," as opposed to going through its debug-token-gated
 * HTTP surface.
 *
 * Deliberately fire-and-forget, same idiom as [app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService]'s
 * own `CoroutineScope(Dispatchers.IO + SupervisorJob())` — the calling Director turn issues the
 * assignment and returns its own reply immediately; this coroutine keeps running after that
 * response has already gone to the browser. This is the concrete mechanism behind the design
 * contract's "let completion ingest when no Director turn is active": there is no code path
 * here that blocks or extends the turn that triggered the assignment. The result becomes
 * visible only on a *later* turn, through the already-existing, already-proven
 * `SurfacingReason.RecentActorEvidence` Horizon pool — no new delivery channel back into an
 * in-flight response.
 */
class HermesDelegationDispatcher(
    private val client: HermesAcpClient,
    private val ingestionService: ActorEventIngestionService,
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
            logger.info(
                "hermes-delegation completed assignmentId={} userEmail={} outcome={} ingestOutcome={}",
                assignment.assignmentId, assignment.userEmail, outcome::class.simpleName, ingestResult::class.simpleName,
            )
        }
    }
}
