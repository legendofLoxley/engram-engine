package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeRequestLedger
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.AssertionStatus
import app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPropagator
import app.alfrd.engram.cognitive.pipeline.horizon.RequestLedger
import app.alfrd.engram.cognitive.pipeline.horizon.SalientTokenPropagator
import app.alfrd.engram.cognitive.pipeline.memory.DatabaseEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Against real `DatabaseEngramClient`/`ArcadeHorizonGraphStore`/`ArcadeHorizonAssembler`/
 * `SalientTokenPropagator` — only [Interpreter] is faked (a subclass override, per this
 * codebase's testable-service-classes convention), since these tests target the coordinator's
 * own orchestration and checkpoint-resume logic, not interpretation itself (covered separately
 * in [InterpreterTest]). This is "the production-wired path" the coordinator-disabled legacy
 * path cannot stand in for.
 */
class HorizonCycleCoordinatorTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var engramClient: DatabaseEngramClient
    private lateinit var horizonGraphStore: HorizonGraphStore
    private lateinit var horizonAssembler: HorizonAssembler
    private lateinit var propagator: HorizonPropagator
    private lateinit var cycleSequencer: CycleSequencer
    private lateinit var requestLedger: RequestLedger
    private lateinit var testDbPath: String

    @BeforeEach
    fun setUp() {
        testDbPath = "./data/test-coordinator-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        engramClient = DatabaseEngramClient(dbManager.getDatabase())
        horizonGraphStore = ArcadeHorizonGraphStore(dbManager.getDatabase())
        horizonAssembler = ArcadeHorizonAssembler(dbManager.getDatabase())
        propagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler)
        cycleSequencer = ArcadeCycleSequencer(dbManager.getDatabase())
        requestLedger = ArcadeRequestLedger(dbManager.getDatabase())
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(testDbPath).deleteRecursively()
    }

    private fun seedUser(email: String) {
        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", email.substringBefore("@"))
                set("email", email)
                set("tier", -1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
        }
    }

    private fun coordinator(interpreter: Interpreter) = HorizonCycleCoordinator(
        cycleSequencer = cycleSequencer,
        requestLedger = requestLedger,
        interpreter = interpreter,
        engramClient = engramClient,
        horizonGraphStore = horizonGraphStore,
        horizonPropagator = propagator,
        horizonAssembler = horizonAssembler,
    )

    private fun fixedInterpreter(outcome: InterpretOutcome): Interpreter = object : Interpreter(null) {
        override suspend fun interpret(utterance: String): InterpretOutcome = outcome
    }

    @Test
    fun `an ordinary fact utterance is captured and Horizon-visible the same cycle`() = runBlocking {
        val email = "coord-fact-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val c = coordinator(fixedInterpreter(InterpretOutcome.NoOperation))

        val result = c.runCycle(email, requestId = "req-1", utterance = "My dog's name is Newton.")

        assertTrue(result.mutationOutcomes.any { it is MutationOutcome.Fact && it.applied }, "the fact must be confirmed written")
        assertTrue(result.mutationOutcomes.none { it is MutationOutcome.Intention }, "no intention was proposed")
        val horizon = (result.assembleOutcome as AssembleOutcome.Assembled).horizon
        assertTrue(horizon.items.any { it.text.text.contains("Newton") }, "the fact must be Horizon-visible immediately, same cycle")
    }

    @Test
    fun `an intention proposal is captured as an OPEN item`() = runBlocking {
        val email = "coord-intention-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "Getting Alfrd running on Arx is a priority for me"
        val c = coordinator(fixedInterpreter(InterpretOutcome.ProposedAssertion(quote, latencyMs = 5)))

        val result = c.runCycle(email, requestId = "req-2", utterance = "$quote, I keep meaning to get to it.")

        val intentionOutcome = result.mutationOutcomes.filterIsInstance<MutationOutcome.Intention>().single()
        assertTrue(intentionOutcome.applied)
        assertEquals(quote, intentionOutcome.quote)
        val horizon = (result.assembleOutcome as AssembleOutcome.Assembled).horizon
        val item = horizon.items.single { it.text.text == quote }
        assertEquals(AssertionStatus.OPEN, item.status)
    }

    @Test
    fun `a combined fact-and-intention turn writes both without dropping either`() = runBlocking {
        val email = "coord-combined-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "getting Alfrd running on Arx is a priority for me"
        val utterance = "My dog's name is Newton. Also, $quote."
        val c = coordinator(fixedInterpreter(InterpretOutcome.ProposedAssertion(quote, latencyMs = 5)))

        val result = c.runCycle(email, requestId = "req-3", utterance = utterance)

        assertTrue(result.mutationOutcomes.any { it is MutationOutcome.Fact && it.applied }, "the fact must not be discarded")
        assertTrue(result.mutationOutcomes.any { it is MutationOutcome.Intention && it.applied }, "the intention must also be written")
        val horizon = (result.assembleOutcome as AssembleOutcome.Assembled).horizon
        assertTrue(horizon.items.any { it.text.text.contains("Newton") })
        assertTrue(horizon.items.any { it.status == AssertionStatus.OPEN })
    }

    @Test
    fun `a NoOperation interpretation never fabricates an intention, ordinary fact capture is unaffected`() = runBlocking {
        // decompose()'s naive heuristic treats any non-blank utterance as fact-shaped — that is
        // correct, existing behavior this task must preserve, not something to suppress. What
        // this coordinator must guarantee is narrower: no InterpretOutcome.NoOperation ever
        // produces an OPEN-status (intention) item.
        val email = "coord-noop-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val c = coordinator(fixedInterpreter(InterpretOutcome.NoOperation))

        val result = c.runCycle(email, requestId = "req-4", utterance = "hey")

        assertNotNull(result.cycleSeq)
        assertTrue(result.mutationOutcomes.none { it is MutationOutcome.Intention }, "no intention was proposed, so none must be written")
        val horizon = (result.assembleOutcome as AssembleOutcome.Assembled).horizon
        assertTrue(horizon.items.none { it.status == AssertionStatus.OPEN }, "no OPEN item may appear without an actual proposal")
    }

    // ── Failed writes must be visible to response handling, never silently dropped ──

    @Test
    fun `decompose() throwing is a visible failed write, never a silent no-op`() = runBlocking {
        val email = "coord-decompose-fail-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val failingClient = object : EngramClient by engramClient {
            override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> {
                throw RuntimeException("simulated decompose failure")
            }
        }
        val c = HorizonCycleCoordinator(
            cycleSequencer = cycleSequencer,
            requestLedger = requestLedger,
            interpreter = fixedInterpreter(InterpretOutcome.NoOperation),
            engramClient = failingClient,
            horizonGraphStore = horizonGraphStore,
            horizonPropagator = propagator,
            horizonAssembler = horizonAssembler,
        )

        val result = c.runCycle(email, requestId = "req-decompose-fail", utterance = "My dog's name is Newton")

        assertNotNull(result.cycleSeq, "the cycle itself is still allocated — this is a partial failure, not a total one")
        val lostFact = result.mutationOutcomes.filterIsInstance<MutationOutcome.Fact>().single()
        assertEquals(null, lostFact.phraseUid, "a candidate that never became a Phrase has no uid to report")
        assertTrue(!lostFact.applied)
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat, "a failed write must produce a visible caveat, never silence")
        assertTrue(caveat!!.contains("not confirmed recorded"))
    }

    @Test
    fun `ingest() throwing loses the fact candidate visibly and still marks a proposed intention unconfirmed`() = runBlocking {
        val email = "coord-ingest-fail-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "getting Alfrd running on Arx is a priority for me"
        val failingClient = object : EngramClient by engramClient {
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String): List<String> {
                throw RuntimeException("simulated ingest failure")
            }
        }
        val c = HorizonCycleCoordinator(
            cycleSequencer = cycleSequencer,
            requestLedger = requestLedger,
            interpreter = fixedInterpreter(InterpretOutcome.ProposedAssertion(quote, latencyMs = 5)),
            engramClient = failingClient,
            horizonGraphStore = horizonGraphStore,
            horizonPropagator = propagator,
            horizonAssembler = horizonAssembler,
        )
        // No '.', '!', '?', or contrastive marker — stays one decompose() segment, so exactly one
        // fact candidate is lost (kept simple to assert against, not a claim about segmentation).
        val result = c.runCycle(email, requestId = "req-ingest-fail", utterance = "My dog's name is Newton and $quote")

        assertNotNull(result.cycleSeq)
        val lostFact = result.mutationOutcomes.filterIsInstance<MutationOutcome.Fact>().single()
        assertEquals(null, lostFact.phraseUid)
        assertTrue(!lostFact.applied)
        val intentionOutcome = result.mutationOutcomes.filterIsInstance<MutationOutcome.Intention>().single()
        assertTrue(!intentionOutcome.applied, "the intention must also be reported unconfirmed, never silently dropped")
        assertEquals(quote, intentionOutcome.quote)
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat)
        assertTrue(caveat!!.contains("NOT confirmed"), "the intention-specific caveat must still fire")
        assertTrue(caveat.contains("not confirmed recorded"), "the fact-loss caveat must also fire")
    }

    @Test
    fun `allocation failure for an unknown user is reported explicitly, not silently`() = runBlocking {
        val c = coordinator(fixedInterpreter(InterpretOutcome.NoOperation))

        val result = c.runCycle("nobody-${UUID.randomUUID()}@test.alfrd.internal", requestId = "req-unknown", utterance = "hey")

        assertTrue(result.allocationFailed)
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat, "an allocation failure must be visible to response handling, not silent")
        assertTrue(caveat!!.contains("Nothing from this turn could be confirmed recorded"))
    }

    // ── Checkpoint resume — a crash artifact, never a live concurrent attempt (see PerUserCycleLock) ──

    @Test
    fun `resumes from facts_ingested without re-running interpretation or duplicating the write`() = runBlocking {
        val email = "coord-resume-facts-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "getting Alfrd running on Arx is a priority for me"

        // Simulate a crash right after the facts_ingested checkpoint: the phrase already exists
        // (written via ingest(), exactly as the coordinator itself would have), but stamping and
        // everything after never happened.
        val uid = engramClient.ingest(listOf(PhraseCandidate(quote, "interpreter", PhraseCategory.CONTEXT)), email).single()
        requestLedger.checkpoint(email, "req-resume-1", cycleSeq = 1, checkpoint = "facts_ingested", phraseUids = listOf(uid), intentionPhraseUid = uid)

        var interpretCalls = 0
        val c = coordinator(object : Interpreter(null) {
            override suspend fun interpret(utterance: String): InterpretOutcome {
                interpretCalls++
                return InterpretOutcome.NoOperation
            }
        })

        val result = c.runCycle(email, requestId = "req-resume-1", utterance = "irrelevant on resume")

        assertEquals(0, interpretCalls, "resuming from facts_ingested must never re-run interpretation")
        assertTrue(result.mutationOutcomes.any { it is MutationOutcome.Intention && it.applied && it.quote == quote })
        val horizon = (result.assembleOutcome as AssembleOutcome.Assembled).horizon
        assertEquals(1, horizon.items.count { it.text.text == quote }, "resume must not create a duplicate Phrase for the same content")
    }

    @Test
    fun `resumes from writes_committed by skipping straight to propagate and assemble`() = runBlocking {
        val email = "coord-resume-committed-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "getting Alfrd running on Arx is a priority for me"
        val uid = engramClient.ingest(listOf(PhraseCandidate(quote, "interpreter", PhraseCategory.CONTEXT)), email).single()
        horizonGraphStore.stampNewAssertion(email, cycleSeq = 1, phraseUid = uid, status = AssertionStatus.OPEN)
        requestLedger.checkpoint(email, "req-resume-2", cycleSeq = 1, checkpoint = "writes_committed", phraseUids = listOf(uid), intentionPhraseUid = uid)

        var interpretCalls = 0
        val c = coordinator(object : Interpreter(null) {
            override suspend fun interpret(utterance: String): InterpretOutcome {
                interpretCalls++
                return InterpretOutcome.NoOperation
            }
        })

        val result = c.runCycle(email, requestId = "req-resume-2", utterance = "irrelevant on resume")

        assertEquals(0, interpretCalls, "resuming from writes_committed must never re-run interpretation")
        val horizon = (result.assembleOutcome as AssembleOutcome.Assembled).horizon
        assertEquals(1, horizon.items.count { it.text.text == quote }, "resume must not create a duplicate Phrase")
    }

    @Test
    fun `duplicate-after-commit returns a fresh assemble without re-writing or re-propagating`() = runBlocking {
        val email = "coord-dup-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "getting Alfrd running on Arx is a priority for me"
        val c = coordinator(fixedInterpreter(InterpretOutcome.ProposedAssertion(quote, latencyMs = 5)))

        val first = c.runCycle(email, requestId = "req-dup-1", utterance = quote)
        val second = c.runCycle(email, requestId = "req-dup-1", utterance = quote)

        assertEquals(first.cycleSeq, second.cycleSeq, "a retried requestId must not allocate a new cycle")
        assertEquals(emptyList<MutationOutcome>(), second.mutationOutcomes, "a duplicate-after-commit call performs no new writes")
        // Counted by OPEN status, not raw text match: the first call legitimately writes the
        // quote twice — once via ordinary decompose()-based fact capture (status=null, preserved
        // per this task's own requirement not to discard ordinary evidence) and once as the
        // confirmed intention (status=OPEN) — that is accepted, intentional redundancy, not a
        // retry bug. What the retry must not do is add a *second* intention.
        val horizonAfterBoth = (second.assembleOutcome as AssembleOutcome.Assembled).horizon
        assertEquals(1, horizonAfterBoth.items.count { it.status == AssertionStatus.OPEN }, "the retry must not create a second OPEN intention")
    }

    @Test
    fun `a new request id for the same text is a genuinely new event, not deduplicated`() = runBlocking {
        val email = "coord-new-event-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "getting Alfrd running on Arx is a priority for me"
        val c = coordinator(fixedInterpreter(InterpretOutcome.ProposedAssertion(quote, latencyMs = 5)))

        val first = c.runCycle(email, requestId = "req-distinct-1", utterance = quote)
        val second = c.runCycle(email, requestId = "req-distinct-2", utterance = quote)

        assertNotEquals(first.cycleSeq, second.cycleSeq, "a genuinely new requestId must allocate its own cycle")
        // Counted by OPEN status — see the duplicate-after-commit test above for why raw text
        // matching over-counts (ordinary fact capture legitimately duplicates the intention text).
        val horizon = (second.assembleOutcome as AssembleOutcome.Assembled).horizon
        assertEquals(2, horizon.items.count { it.status == AssertionStatus.OPEN }, "two distinct conversational events each produce their own OPEN intention, even with identical text")
    }
}
