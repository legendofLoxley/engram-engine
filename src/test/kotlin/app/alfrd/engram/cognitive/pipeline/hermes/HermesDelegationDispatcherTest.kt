package app.alfrd.engram.cognitive.pipeline.hermes

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.SalientTokenPropagator
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

private const val TEST_USER = "debug+dispatcher-test@test.alfrd.internal"

/**
 * Uses a real (test) ArcadeDB instance for [ActorEventIngestionService] — exactly
 * [DebugActorEventRoutesTest]'s own pattern — since faking [app.alfrd.engram.cognitive.pipeline.horizon.HorizonGraphStore]'s
 * full interface for these dispatcher-level tests would add far more boilerplate than it saves.
 * Only [HermesAcpClient] is faked, via a subclass overriding the one method that needs real
 * Docker in production (see that class's own `open` opt-in, added for exactly this).
 *
 * `runBlocking { ... scope = this ... }` is used deliberately: passing the runBlocking coroutine's
 * own scope into the dispatcher means `dispatchAsync`'s fire-and-forget `launch{}` is a *child* of
 * that block, so `runBlocking` does not return until it (and any graph write it triggers) is
 * genuinely finished — no arbitrary sleep-until-probably-done polling needed to observe the result.
 */
class HermesDelegationDispatcherTest {
    private lateinit var dbManager: DatabaseManager
    private lateinit var testDbPath: String
    private lateinit var completionStore: HermesAssignmentCompletionStore
    private lateinit var activeAssignments: HermesActiveAssignmentRegistry
    private lateinit var ingestionService: ActorEventIngestionService

    @BeforeEach
    fun setUp() {
        testDbPath = "./data/test-hermes-dispatcher-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        val db = dbManager.getDatabase()
        DebugConverseService.ensureSyntheticUser(db, TEST_USER)
        val horizonGraphStore = ArcadeHorizonGraphStore(db)
        val horizonAssembler = ArcadeHorizonAssembler(db)
        ingestionService = ActorEventIngestionService(
            ArcadeCycleSequencer(db), horizonGraphStore, SalientTokenPropagator(horizonGraphStore, horizonAssembler),
        )
        completionStore = HermesAssignmentCompletionStore()
        activeAssignments = HermesActiveAssignmentRegistry()
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(testDbPath).deleteRecursively()
    }

    private fun assignment(id: String) = HermesAssignment(
        assignmentId = id, userEmail = TEST_USER, task = "test task", originalRequest = "test request", issuedAtCycleSeq = null,
    )

    private class FakeHermesAcpClient(
        private val behavior: suspend (HermesAssignment, HermesCancelHandle) -> HermesAssignmentOutcome,
    ) : HermesAcpClient() {
        var summarizeDocumentCalls = 0
            private set
        var inspectFixtureCalls = 0
            private set

        override suspend fun inspectFixture(
            assignment: HermesAssignment,
            fixtureFilename: String,
            cancelHandle: HermesCancelHandle,
        ): HermesAssignmentOutcome {
            inspectFixtureCalls++
            return behavior(assignment, cancelHandle)
        }

        override suspend fun summarizeDocument(
            assignment: HermesAssignment,
            targetFilename: String,
            cancelHandle: HermesCancelHandle,
        ): HermesAssignmentOutcome {
            summarizeDocumentCalls++
            return behavior(assignment, cancelHandle)
        }
    }

    @Test
    fun `a normal completion is recorded Accepted, ingested, and no longer active afterward`() {
        val a = assignment("normal-completion")
        val client = FakeHermesAcpClient { _, _ ->
            HermesAssignmentOutcome.Completed("DH-FIXTURE-normal", "read", "path", toolSucceeded = true)
        }
        runBlocking {
            val dispatcher = HermesDelegationDispatcher(client, ingestionService, completionStore, activeAssignments, scope = this)
            dispatcher.dispatchAsync(a)
        }

        val completion = completionStore.get(a.assignmentId, TEST_USER)
        assertTrue(completion?.decision is HermesCompletionDecision.Accepted)
        assertEquals("Committed", completion?.graphIngestOutcome)
        assertEquals(
            HermesCancellationRequestOutcome.NotActive,
            activeAssignments.requestCancellation(a.assignmentId, TEST_USER),
            "a finished assignment must no longer be cancellable",
        )
    }

    @Test
    fun `a DocumentSummary assignment calls summarizeDocument, not inspectFixture`() {
        val a = assignment("doc-summary").copy(
            kind = HermesAssignmentKind.DocumentSummary(HermesDelegationTrigger.APPROVED_DOCUMENTS.first().filename),
        )
        val client = FakeHermesAcpClient { _, _ ->
            HermesAssignmentOutcome.Completed("Goal: ship the demo.", "read", "path", toolSucceeded = true)
        }
        runBlocking {
            val dispatcher = HermesDelegationDispatcher(client, ingestionService, completionStore, activeAssignments, scope = this)
            dispatcher.dispatchAsync(a)
        }

        assertEquals(1, client.summarizeDocumentCalls)
        assertEquals(0, client.inspectFixtureCalls)
        val completion = completionStore.get(a.assignmentId, TEST_USER)
        assertTrue(completion?.decision is HermesCompletionDecision.Accepted)
    }

    @Test
    fun `a default MarkerCheck assignment calls inspectFixture, not summarizeDocument`() {
        val a = assignment("marker-check")
        val client = FakeHermesAcpClient { _, _ ->
            HermesAssignmentOutcome.Completed("DH-FIXTURE-normal", "read", "path", toolSucceeded = true)
        }
        runBlocking {
            val dispatcher = HermesDelegationDispatcher(client, ingestionService, completionStore, activeAssignments, scope = this)
            dispatcher.dispatchAsync(a)
        }

        assertEquals(1, client.inspectFixtureCalls)
        assertEquals(0, client.summarizeDocumentCalls)
    }

    @Test
    fun `cancellation while running is honored even though the fake exchange still reports content`() {
        val a = assignment("cancel-while-running")
        val client = FakeHermesAcpClient { _, cancelHandle ->
            delay(100)
            if (cancelHandle.isCancelRequested()) {
                HermesAssignmentOutcome.Cancelled(partialText = "DH-FIXTURE-late", reason = "test: cancellation honored mid-exchange")
            } else {
                HermesAssignmentOutcome.Completed("DH-FIXTURE-late", "read", "path", toolSucceeded = true)
            }
        }
        lateinit var requestOutcome: HermesCancellationRequestOutcome
        runBlocking {
            val dispatcher = HermesDelegationDispatcher(client, ingestionService, completionStore, activeAssignments, scope = this)
            dispatcher.dispatchAsync(a)
            delay(20) // give the fake exchange a chance to actually start before requesting cancellation
            requestOutcome = activeAssignments.requestCancellation(a.assignmentId, TEST_USER)
        }

        assertEquals(HermesCancellationRequestOutcome.Requested, requestOutcome)
        val completion = completionStore.get(a.assignmentId, TEST_USER)
        assertTrue(completion?.outcome is HermesAssignmentOutcome.Cancelled)
        assertTrue(
            completion?.decision is HermesCompletionDecision.Withheld,
            "a cancelled run must never be recorded as an ordinary accepted completion",
        )
    }

    @Test
    fun `completion racing with cancellation — a request that arrives too late does not retroactively cancel an already-decided outcome`() {
        val a = assignment("racing-completion")
        val client = FakeHermesAcpClient { _, _ ->
            // Finishes immediately, well before the test ever gets a chance to request cancellation.
            HermesAssignmentOutcome.Completed("DH-FIXTURE-fast", "read", "path", toolSucceeded = true)
        }
        lateinit var requestOutcome: HermesCancellationRequestOutcome
        runBlocking {
            val dispatcher = HermesDelegationDispatcher(client, ingestionService, completionStore, activeAssignments, scope = this)
            dispatcher.dispatchAsync(a)
        }
        // By now the dispatch has genuinely finished (runBlocking waited for it) — this cancellation
        // request is unambiguously too late, and must say so honestly rather than claim success.
        requestOutcome = activeAssignments.requestCancellation(a.assignmentId, TEST_USER)

        assertEquals(HermesCancellationRequestOutcome.NotActive, requestOutcome)
        val completion = completionStore.get(a.assignmentId, TEST_USER)
        assertTrue(
            completion?.decision is HermesCompletionDecision.Accepted,
            "a completion that genuinely finished before the cancel request arrived must stay a normal accepted reply",
        )
    }

    @Test
    fun `isolation from other runs — cancelling one assignment leaves a different concurrent one to complete normally`() {
        val cancelled = assignment("concurrent-cancelled")
        val untouched = assignment("concurrent-untouched")
        val client = FakeHermesAcpClient { thisAssignment, cancelHandle ->
            delay(100)
            if (thisAssignment.assignmentId == cancelled.assignmentId && cancelHandle.isCancelRequested()) {
                HermesAssignmentOutcome.Cancelled(partialText = null, reason = "test: cancelled by isolation test")
            } else {
                HermesAssignmentOutcome.Completed("DH-${thisAssignment.assignmentId}", "read", "path", toolSucceeded = true)
            }
        }
        lateinit var requestOutcome: HermesCancellationRequestOutcome
        runBlocking {
            val dispatcher = HermesDelegationDispatcher(client, ingestionService, completionStore, activeAssignments, scope = this)
            dispatcher.dispatchAsync(cancelled)
            dispatcher.dispatchAsync(untouched)
            delay(20)
            requestOutcome = activeAssignments.requestCancellation(cancelled.assignmentId, TEST_USER)
        }

        assertEquals(HermesCancellationRequestOutcome.Requested, requestOutcome)
        assertTrue(completionStore.get(cancelled.assignmentId, TEST_USER)?.outcome is HermesAssignmentOutcome.Cancelled)

        val untouchedCompletion = completionStore.get(untouched.assignmentId, TEST_USER)
        assertTrue(untouchedCompletion?.outcome is HermesAssignmentOutcome.Completed)
        assertTrue(untouchedCompletion?.decision is HermesCompletionDecision.Accepted)
        assertTrue(
            (untouchedCompletion?.outcome as HermesAssignmentOutcome.Completed).toolSucceeded,
            "the untouched assignment's own completion must be entirely unaffected by cancelling a different one",
        )
    }
}
