package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeRequestLedger
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.AssertionStatus
import app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind
import app.alfrd.engram.cognitive.pipeline.horizon.CreateContainer
import app.alfrd.engram.cognitive.pipeline.horizon.CycleFollowUp
import app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.FocusMode
import app.alfrd.engram.cognitive.pipeline.horizon.HoldForChoice
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperation
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPropagator
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonSeeder
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.OperationPlanCodec
import app.alfrd.engram.cognitive.pipeline.horizon.OperationRejection
import app.alfrd.engram.cognitive.pipeline.horizon.RecordItem
import app.alfrd.engram.cognitive.pipeline.horizon.RequestLedger
import app.alfrd.engram.cognitive.pipeline.horizon.ResolveItem
import app.alfrd.engram.cognitive.pipeline.horizon.SalientTokenPropagator
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshot
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshotBuilder
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Against real `DatabaseEngramClient`/`ArcadeHorizonGraphStore`/`ArcadeHorizonStructureStore`/
 * `ArcadeHorizonAssembler`/`SalientTokenPropagator` — only [Interpreter] is faked (a subclass
 * override, per this codebase's testable-service-classes convention), since these tests target the
 * coordinator's own orchestration and checkpoint-resume logic, not interpretation itself (covered
 * in [InterpreterTest] and [app.alfrd.engram.cognitive.pipeline.horizon.OperationParserTest]).
 */
class HorizonCycleCoordinatorTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var engramClient: DatabaseEngramClient
    private lateinit var horizonGraphStore: HorizonGraphStore
    private lateinit var structureStore: HorizonStructureStore
    private lateinit var horizonAssembler: HorizonAssembler
    private lateinit var propagator: HorizonPropagator
    private lateinit var cycleSequencer: CycleSequencer
    private lateinit var requestLedger: RequestLedger
    private lateinit var applier: HorizonOperationApplier
    private lateinit var testDbPath: String
    private var clockNow = System.currentTimeMillis()

    @BeforeEach
    fun setUp() {
        testDbPath = "./data/test-coordinator-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        engramClient = DatabaseEngramClient(dbManager.getDatabase())
        horizonGraphStore = ArcadeHorizonGraphStore(dbManager.getDatabase())
        structureStore = ArcadeHorizonStructureStore(dbManager.getDatabase())
        horizonAssembler = ArcadeHorizonAssembler(dbManager.getDatabase())
        propagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler)
        cycleSequencer = ArcadeCycleSequencer(dbManager.getDatabase())
        requestLedger = ArcadeRequestLedger(dbManager.getDatabase())
        applier = HorizonOperationApplier(structureStore, horizonGraphStore)
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

    private fun coordinator(interpreter: Interpreter, client: EngramClient = engramClient) = HorizonCycleCoordinator(
        cycleSequencer = cycleSequencer,
        requestLedger = requestLedger,
        interpreter = interpreter,
        engramClient = client,
        horizonGraphStore = horizonGraphStore,
        horizonPropagator = propagator,
        horizonAssembler = horizonAssembler,
        viewSnapshotBuilder = ViewSnapshotBuilder(structureStore),
        operationApplier = applier,
        horizonSeeder = HorizonSeeder(structureStore, clock = { clockNow }),
    )

    /** An interpreter that always returns [outcome] and records what it was shown. */
    private class FixedInterpreter(private val outcome: InterpretOutcome) : Interpreter(null) {
        var calls = 0
        var lastSnapshot: ViewSnapshot? = null
        override suspend fun interpret(utterance: String, snapshot: ViewSnapshot, now: ZonedDateTime): InterpretOutcome {
            calls++
            lastSnapshot = snapshot
            return outcome
        }
    }

    private fun proposes(vararg ops: HorizonOperation, rejections: List<OperationRejection> = emptyList()) =
        FixedInterpreter(InterpretOutcome.Operations(ops.toList(), rejections, latencyMs = 5))

    private fun noOperation() = FixedInterpreter(InterpretOutcome.NoOperation)
    private fun modelDown() = FixedInterpreter(InterpretOutcome.LlmFailure("model unavailable"))

    private fun assembled(result: HorizonCycleResult) = (result.assembleOutcome as AssembleOutcome.Assembled).horizon

    // ── The model's operations ───────────────────────────────────────────────

    @Test
    fun `a recorded fact is written and Horizon-visible the same cycle`() = runBlocking {
        val email = "coord-fact-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val c = coordinator(proposes(RecordItem(ItemKind.FACT, "My dog's name is Newton")))

        val result = c.runCycle(email, requestId = "req-1", utterance = "My dog's name is Newton.")

        val op = result.mutationOutcomes.filterIsInstance<MutationOutcome.Operation>().single().result
        assertTrue(op.applied)
        assertTrue(assembled(result).items.any { it.text.text.contains("Newton") }, "the fact must be Horizon-visible immediately, same cycle")
    }

    @Test
    fun `a recorded task is captured as an OPEN item`() = runBlocking {
        val email = "coord-task-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "Getting Alfrd running on Arx is a priority for me"
        val c = coordinator(proposes(RecordItem(ItemKind.TASK, quote)))

        val result = c.runCycle(email, requestId = "req-2", utterance = "$quote, I keep meaning to get to it.")

        assertTrue(result.mutationOutcomes.filterIsInstance<MutationOutcome.Operation>().single().result.applied)
        val item = assembled(result).items.single { it.text.text == quote }
        assertEquals(AssertionStatus.OPEN, item.status)
    }

    @Test
    fun `several operations in one turn each land, and one failing does not hide the others`() = runBlocking {
        val email = "coord-multi-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val c = coordinator(
            proposes(
                RecordItem(ItemKind.FACT, "My dog's name is Newton"),
                ResolveItem(UUID.randomUUID().toString(), "that is done"),
                RecordItem(ItemKind.TASK, "getting Alfrd running on Arx is a priority for me"),
            ),
        )

        val result = c.runCycle(email, requestId = "req-3", utterance = "irrelevant")

        val applied = result.mutationOutcomes.filterIsInstance<MutationOutcome.Operation>().map { it.result }
        assertEquals(listOf(true, false, true), applied.map { it.applied })
        val h = assembled(result)
        assertTrue(h.items.any { it.text.text.contains("Newton") })
        assertTrue(h.items.any { it.status == AssertionStatus.OPEN })
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat, "the failed operation must be visible to response handling")
        assertTrue(caveat!!.contains("NOT confirmed"))
    }

    @Test
    fun `no operations writes nothing at all, and does not fall back to capturing claims`() = runBlocking {
        val email = "coord-noop-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val c = coordinator(noOperation())

        // A declarative sentence the deterministic decomposer WOULD capture — the model said it needs nothing, and that stands.
        val result = c.runCycle(email, requestId = "req-4", utterance = "My dog's name is Newton.")

        assertNotNull(result.cycleSeq)
        assertTrue(result.mutationOutcomes.isEmpty(), "a valid empty answer is respected: ${result.mutationOutcomes}")
        assertTrue(assembled(result).items.isEmpty())
    }

    @Test
    fun `rejected proposals are carried on the outcome and write nothing`() = runBlocking {
        val email = "coord-rejected-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val interpreter = proposes(rejections = listOf(OperationRejection("resolve_item", "negation_marker_detected", "sending that invoice")))
        val result = coordinator(interpreter).runCycle(email, requestId = "req-rej", utterance = "I never got around to sending that invoice")

        assertTrue(result.mutationOutcomes.isEmpty())
        val outcome = result.interpretOutcome as InterpretOutcome.Operations
        assertEquals("negation_marker_detected", outcome.rejections.single().reason)
    }

    @Test
    fun `the interpreter is shown the users containers and focus`() = runBlocking {
        val email = "coord-snapshot-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val project = structureStore.createContainer(email, 1, ContainerKind.PROJECT, "Demo")!!
        structureStore.setFocus(email, 1, project.uid, FocusMode.PINNED)
        val interpreter = noOperation()

        coordinator(interpreter).runCycle(email, requestId = "req-snap", utterance = "hello there")

        val shown = interpreter.lastSnapshot!!
        assertEquals(listOf("Demo"), shown.containers.map { it.name })
        assertEquals("c1", shown.containers.single().ref)
        assertTrue(shown.focusLines.single().contains("Demo"))
    }

    // ── What a turn leaves for the selector ──────────────────────────────────

    @Test
    fun `a turn records that the user was here, and mentions in it are nudged before the Horizon is assembled`() = runBlocking {
        val email = "coord-seed-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val demo = structureStore.createContainer(email, 1, ContainerKind.PROJECT, "Demo")!!
        structureStore.createItem(email, 0, ItemKind.TASK, "Book the room", projectUid = demo.uid) // an earlier cycle: not "just said"
        // Nothing else is drawing "Book the room" in: it is in an undated task inside a project, and there is no focus.
        assertNull(assembled(coordinator(noOperation()).runCycle(email, "req-before", "Hello there")).items.firstOrNull { it.text.text == "Book the room" })

        val result = coordinator(noOperation()).runCycle(email, requestId = "req-mention", utterance = "How is the demo going?")

        assertEquals(1, result.seeded!!.mentionedContainers)
        val item = assembled(result).items.first { it.text.text == "Book the room" }
        assertTrue(item.surfacing is app.alfrd.engram.cognitive.pipeline.horizon.SurfacingReason.Activated, "brought in by the mention, this same turn: ${item.surfacing}")
        assertNotNull(structureStore.lastActiveAt(email), "and the turn is what the next silence is measured from")
    }

    @Test
    fun `what the interpreter touched this turn is nudged`() = runBlocking {
        val email = "coord-touch-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val task = structureStore.createItem(email, 1, ItemKind.TASK, "Send the invoice")!!

        val result = coordinator(proposes(ResolveItem(task.uid, "I sent it"))).runCycle(email, requestId = "req-touch", utterance = "I sent it")

        assertEquals(1, result.seeded!!.touched)
        assertTrue(structureStore.getFocus(email).any { it.targetUid == task.uid && it.mode == FocusMode.NUDGE })
    }

    @Test
    fun `after a long silence the turn opens a question, and the next turn closes it`() = runBlocking {
        val email = "coord-silence-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        structureStore.touchLastActive(email, clockNow)
        clockNow += 9 * 86_400_000L

        val back = coordinator(noOperation()).runCycle(email, requestId = "req-back", utterance = "morning")

        assertTrue(back.silenceQuestionAsked)
        val question = assembled(back).items.single { it.text.text == "What have you been up to?" }
        assertEquals("alfrd", question.askedBy)
        assertTrue(HorizonItemsRenderer.render(assembled(back)).any { it.renderedLine.contains("a question you want to ask them") })

        clockNow += 60_000
        val answered = coordinator(noOperation()).runCycle(email, requestId = "req-answer", utterance = "I was travelling")

        assertTrue(!answered.silenceQuestionAsked)
        assertTrue(assembled(answered).items.none { it.text.text == "What have you been up to?" }, "answered, it goes")
    }

    @Test
    fun `retrying a request after a long silence asks the question once`() = runBlocking {
        val email = "coord-silence-retry-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        structureStore.touchLastActive(email, clockNow)
        clockNow += 9 * 86_400_000L
        val c = coordinator(noOperation())

        c.runCycle(email, requestId = "req-once", utterance = "morning")
        c.runCycle(email, requestId = "req-once", utterance = "morning")

        assertEquals(1, structureStore.listRecentItems(email, 20).count { it.text == "What have you been up to?" })
    }

    @Test
    fun `a failure in seeding never costs the turn`() = runBlocking {
        val email = "coord-seed-fail-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val exploding = object : HorizonStructureStore by structureStore {
            override suspend fun listContainers(userEmail: String, kind: ContainerKind?) = throw IllegalStateException("boom")
        }
        val c = HorizonCycleCoordinator(
            cycleSequencer, requestLedger, noOperation(), engramClient, horizonGraphStore, propagator, horizonAssembler,
            ViewSnapshotBuilder(structureStore), applier, HorizonSeeder(exploding),
        )

        val result = c.runCycle(email, requestId = "req-boom", utterance = "How is the demo going?")

        assertNotNull(result.assembleOutcome)
        assertNull(result.seeded)
    }

    // ── Follow-ups the reply owes the user ───────────────────────────────────

    @Test
    fun `holding a feeling writes nothing and asks the user what they want`() = runBlocking {
        val email = "coord-hold-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "I'm really stressed about the demo"
        val c = coordinator(proposes(HoldForChoice(quote)))

        val result = c.runCycle(email, requestId = "req-hold", utterance = "$quote, I don't think it'll be ready")

        assertEquals(CycleFollowUp.AskEmotionChoice(quote), result.followUp)
        assertTrue(assembled(result).items.isEmpty(), "nothing the user felt is filed as a claim")
        assertNull(HorizonItemsRenderer.composeChangeNotes(result), "a held statement is not a change to report")
        assertTrue(HorizonItemsRenderer.composeFollowUpDirective(result)!!.contains("Nothing about it has been saved"))
    }

    @Test
    fun `an item saved unfiled asks which project, naming the users projects`() = runBlocking {
        val email = "coord-ask-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        structureStore.createContainer(email, 1, ContainerKind.PROJECT, "Demo")
        structureStore.createContainer(email, 1, ContainerKind.PROJECT, "Garden redesign")
        val c = coordinator(proposes(RecordItem(ItemKind.TASK, "review the budget", askWhichProject = true)))

        val result = c.runCycle(email, requestId = "req-ask", utterance = "review the budget")

        val followUp = result.followUp as CycleFollowUp.AskWhichProject
        assertEquals("review the budget", followUp.itemText)
        assertEquals(listOf("Demo", "Garden redesign"), followUp.projectNames)
        assertNull(structureStore.getItem(email, followUp.itemUid)!!.projectUid, "saved, unfiled")
        val directive = HorizonItemsRenderer.composeFollowUpDirective(result)!!
        assertTrue(directive.contains("Demo, Garden redesign") && directive.contains("ask which project"))
    }

    @Test
    fun `the pending statement from last turn is handed to the interpreter`() = runBlocking {
        val email = "coord-pending-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val interpreter = noOperation()

        coordinator(interpreter).runCycle(
            email, requestId = "req-pend", utterance = "yes, make a task",
            pending = PendingInterpretation(emotionQuote = "I'm really stressed about the demo"),
        )

        assertEquals("I'm really stressed about the demo", interpreter.lastSnapshot!!.pendingEmotionQuote)
    }

    // ── Model unavailable: fall back to plain claim capture, loudly ──────────

    @Test
    fun `when the model is unavailable a plain claim is still captured, and the failure is reported`() = runBlocking {
        val email = "coord-fallback-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val c = coordinator(modelDown())

        val result = c.runCycle(email, requestId = "req-fb", utterance = "My dog's name is Newton.")

        assertTrue(result.mutationOutcomes.any { it is MutationOutcome.Fact && it.applied }, "a model outage must not become a memory outage")
        assertTrue(assembled(result).items.any { it.text.text.contains("Newton") })
        assertTrue(result.interpretOutcome is InterpretOutcome.LlmFailure)
        assertTrue(HorizonItemsRenderer.composeIntegrityCaveat(result)!!.contains("could not be fully interpreted"))
    }

    @Test
    fun `the fallback never captures a question or a greeting as a claim`() = runBlocking {
        val email = "coord-fallback-q-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val result = coordinator(modelDown()).runCycle(email, requestId = "req-fbq", utterance = "What was worrying me about the demo?")

        assertTrue(result.mutationOutcomes.isEmpty(), "no claim, so no write to confirm or fail: ${result.mutationOutcomes}")
        assertTrue(assembled(result).items.none { it.text.text.contains("worrying") })
    }

    // ── Failed writes must be visible to response handling, never silently dropped ──

    @Test
    fun `fallback decompose() throwing is a visible failed write, never a silent no-op`() = runBlocking {
        val email = "coord-decompose-fail-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val failingClient = object : EngramClient by engramClient {
            override suspend fun decompose(text: String, context: List<String>): List<PhraseCandidate> {
                throw RuntimeException("simulated decompose failure")
            }
        }

        val result = coordinator(modelDown(), failingClient).runCycle(email, requestId = "req-decompose-fail", utterance = "My dog's name is Newton")

        assertNotNull(result.cycleSeq, "the cycle itself is still allocated — this is a partial failure, not a total one")
        val lostFact = result.mutationOutcomes.filterIsInstance<MutationOutcome.Fact>().single()
        assertNull(lostFact.phraseUid, "a candidate that never became a Phrase has no uid to report")
        assertTrue(!lostFact.applied)
        assertTrue(HorizonItemsRenderer.composeIntegrityCaveat(result)!!.contains("not confirmed recorded"))
    }

    @Test
    fun `fallback ingest() throwing loses the fact candidate visibly`() = runBlocking {
        val email = "coord-ingest-fail-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val failingClient = object : EngramClient by engramClient {
            override suspend fun ingest(candidates: List<PhraseCandidate>, userEmail: String): List<String> {
                throw RuntimeException("simulated ingest failure")
            }
        }

        val result = coordinator(modelDown(), failingClient).runCycle(email, requestId = "req-ingest-fail", utterance = "My dog's name is Newton")

        assertNotNull(result.cycleSeq)
        val lostFact = result.mutationOutcomes.filterIsInstance<MutationOutcome.Fact>().single()
        assertNull(lostFact.phraseUid)
        assertTrue(!lostFact.applied)
        assertTrue(HorizonItemsRenderer.composeIntegrityCaveat(result)!!.contains("not confirmed recorded"))
    }

    @Test
    fun `allocation failure for an unknown user is reported explicitly, not silently`() = runBlocking {
        val result = coordinator(noOperation()).runCycle("nobody-${UUID.randomUUID()}@test.alfrd.internal", requestId = "req-unknown", utterance = "hey")

        assertTrue(result.allocationFailed)
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat, "an allocation failure must be visible to response handling, not silent")
        assertTrue(caveat!!.contains("Nothing from this turn could be confirmed recorded"))
    }

    // ── Checkpoint resume — a crash artifact, never a live concurrent attempt (see PerUserCycleLock) ──

    @Test
    fun `the validated plan is checkpointed before any write, and a completed cycle is marked so`() = runBlocking {
        val email = "coord-plan-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val op = RecordItem(ItemKind.TASK, "send the invoice")
        coordinator(proposes(op)).runCycle(email, requestId = "req-plan", utterance = "send the invoice")

        val row = requestLedger.find(email, "req-plan")!!
        assertEquals("completed", row.checkpoint)
        assertEquals(listOf<HorizonOperation>(op), OperationPlanCodec.decode(row.opsPlan), "the plan that was applied is the plan that was stored")
    }

    @Test
    fun `resumes from ops_planned by replaying the stored plan, never calling the model`() = runBlocking {
        val email = "coord-resume-plan-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "getting Alfrd running on Arx is a priority for me"
        // A crash right after the plan was checkpointed: nothing was written yet.
        requestLedger.checkpoint(email, "req-resume-plan", cycleSeq = 1, checkpoint = "ops_planned", opsPlan = OperationPlanCodec.encode(listOf(RecordItem(ItemKind.TASK, quote))))

        val interpreter = noOperation()
        val result = coordinator(interpreter).runCycle(email, requestId = "req-resume-plan", utterance = "irrelevant on resume")

        assertEquals(0, interpreter.calls, "resuming from ops_planned must never re-run interpretation")
        assertEquals(1L, result.cycleSeq)
        assertEquals(1, assembled(result).items.count { it.text.text == quote && it.status == AssertionStatus.OPEN })
        assertEquals("completed", requestLedger.find(email, "req-resume-plan")!!.checkpoint)
    }

    @Test
    fun `replaying a plan that was already fully applied creates no duplicates`() = runBlocking {
        val email = "coord-resume-applied-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val plan = listOf(
            CreateContainer(ContainerKind.PROJECT, "Demo", namedByUser = true),
            RecordItem(ItemKind.TASK, "book the room", projectName = "Demo"),
        )
        // The crash happened after applying but before the writes_committed checkpoint.
        applier.apply(email, 1, "req-resume-applied", plan)
        requestLedger.checkpoint(email, "req-resume-applied", cycleSeq = 1, checkpoint = "ops_planned", opsPlan = OperationPlanCodec.encode(plan))

        val result = coordinator(noOperation()).runCycle(email, requestId = "req-resume-applied", utterance = "irrelevant on resume")

        assertTrue(result.mutationOutcomes.all { (it as MutationOutcome.Operation).result.applied })
        assertEquals(1, structureStore.listContainers(email, ContainerKind.PROJECT).size)
        val demo = structureStore.listContainers(email, ContainerKind.PROJECT).single()
        assertEquals(1, structureStore.itemsIn(email, demo.uid).size, "the replayed record_item is keyed by requestId:index and returns the first item")
        assertEquals(1, structureStore.listRecentItems(email, 10).count { it.text == "book the room" })
    }

    @Test
    fun `a failed operation leaves the row at ops_planned so a retry re-applies instead of losing it`() = runBlocking {
        val email = "coord-retry-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val c = coordinator(proposes(ResolveItem(UUID.randomUUID().toString(), "it is done")))

        c.runCycle(email, requestId = "req-retry", utterance = "it is done")

        assertEquals("ops_planned", requestLedger.find(email, "req-retry")!!.checkpoint)
    }

    @Test
    fun `resumes from facts_ingested without re-running interpretation or duplicating the write`() = runBlocking {
        val email = "coord-resume-facts-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val text = "getting Alfrd running on Arx is a priority for me"
        // Simulate a crash right after the fallback path's facts_ingested checkpoint: the phrase already
        // exists (written via ingest()), but stamping and everything after never happened.
        val uid = engramClient.ingest(listOf(PhraseCandidate(text, "user", PhraseCategory.CONTEXT)), email).single()
        requestLedger.checkpoint(email, "req-resume-1", cycleSeq = 1, checkpoint = "facts_ingested", phraseUids = listOf(uid))

        val interpreter = noOperation()
        val result = coordinator(interpreter).runCycle(email, requestId = "req-resume-1", utterance = "irrelevant on resume")

        assertEquals(0, interpreter.calls, "resuming from facts_ingested must never re-run interpretation")
        assertTrue(result.mutationOutcomes.any { it is MutationOutcome.Fact && it.applied })
        assertEquals(1, assembled(result).items.count { it.text.text == text }, "resume must not create a duplicate Phrase for the same content")
    }

    @Test
    fun `resumes from writes_committed by skipping straight to propagate and assemble`() = runBlocking {
        val email = "coord-resume-committed-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val text = "getting Alfrd running on Arx is a priority for me"
        val task = structureStore.createItem(email, 1, ItemKind.TASK, text)!!
        requestLedger.checkpoint(email, "req-resume-2", cycleSeq = 1, checkpoint = "writes_committed", phraseUids = listOf(task.uid))

        val interpreter = noOperation()
        val result = coordinator(interpreter).runCycle(email, requestId = "req-resume-2", utterance = "irrelevant on resume")

        assertEquals(0, interpreter.calls, "resuming from writes_committed must never re-run interpretation")
        assertEquals(1, assembled(result).items.count { it.text.text == text }, "resume must not create a duplicate")
    }

    @Test
    fun `duplicate-after-commit returns a fresh assemble without re-writing or re-propagating`() = runBlocking {
        val email = "coord-dup-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "getting Alfrd running on Arx is a priority for me"
        val interpreter = proposes(RecordItem(ItemKind.TASK, quote))
        val c = coordinator(interpreter)

        val first = c.runCycle(email, requestId = "req-dup-1", utterance = quote)
        val second = c.runCycle(email, requestId = "req-dup-1", utterance = quote)

        assertEquals(first.cycleSeq, second.cycleSeq, "a retried requestId must not allocate a new cycle")
        assertEquals(emptyList<MutationOutcome>(), second.mutationOutcomes, "a duplicate-after-commit call performs no new writes")
        assertEquals(1, interpreter.calls, "the retry must not call the model again")
        assertEquals(1, assembled(second).items.count { it.status == AssertionStatus.OPEN }, "the retry must not create a second OPEN task")
    }

    @Test
    fun `a new request id for the same text is a genuinely new event, and the task it names is already on the list`() = runBlocking {
        val email = "coord-new-event-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "getting Alfrd running on Arx is a priority for me"
        val c = coordinator(proposes(RecordItem(ItemKind.TASK, quote)))

        val first = c.runCycle(email, requestId = "req-distinct-1", utterance = quote)
        val second = c.runCycle(email, requestId = "req-distinct-2", utterance = quote)

        assertNotEquals(first.cycleSeq, second.cycleSeq, "a genuinely new requestId is processed as its own event, never mistaken for a retry")
        val again = second.mutationOutcomes.filterIsInstance<MutationOutcome.Operation>().single().result
        assertTrue(again.applied && again.summary.startsWith("Already on the list"), "the same open task said twice is one task: ${again.summary}")
        assertEquals(1, assembled(second).items.count { it.status == AssertionStatus.OPEN }, "and there is still only the one")
    }

    // ── A message with a date that produced nothing gets one more look ────────

    /** Answers the first look with [first] and the dated look with [second], counting both. */
    private class TwoLookInterpreter(private val first: InterpretOutcome, private val second: InterpretOutcome) : Interpreter(null) {
        var firstLooks = 0
        var datedLooks = 0
        override suspend fun interpret(utterance: String, snapshot: ViewSnapshot, now: ZonedDateTime): InterpretOutcome { firstLooks++; return first }
        override suspend fun interpretDated(utterance: String, snapshot: ViewSnapshot, now: ZonedDateTime): InterpretOutcome { datedLooks++; return second }
    }

    private val birthdayEvent = RecordItem(ItemKind.EVENT, "My sister's birthday is on the 22nd", dueAt = System.currentTimeMillis() + 2 * 86_400_000L, about = listOf("sister"), title = "Sister's birthday")
    private val foundBirthday = InterpretOutcome.Operations(listOf(birthdayEvent), emptyList(), 5)

    @Test
    fun `a dated statement the first look skipped is recorded by the second, like any other operation`() = runBlocking {
        val email = "coord-dated-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val interpreter = TwoLookInterpreter(InterpretOutcome.NoOperation, foundBirthday)

        val result = coordinator(interpreter).runCycle(email, "req-d1", "My sister's birthday is on the 22nd.")

        assertEquals(1, interpreter.firstLooks)
        assertEquals(1, interpreter.datedLooks)
        assertEquals("recorded 1", result.datedBackstop)
        assertTrue(result.mutationOutcomes.filterIsInstance<MutationOutcome.Operation>().single().result.applied)
        assertTrue(assembled(result).items.any { it.text.text == "Sister's birthday" && it.dueAt != null && it.why.any { w -> w.kind == "urgency" } }, "it is a real dated item: in view, with its deadline counting toward it")
        assertTrue(result.interpretOutcome is InterpretOutcome.Operations)
    }

    @Test
    fun `the plan the second look made is checkpointed, so a retry replays it without asking the model again`() = runBlocking {
        val email = "coord-dated-retry-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val interpreter = TwoLookInterpreter(InterpretOutcome.NoOperation, foundBirthday)
        val c = coordinator(interpreter)
        c.runCycle(email, "req-d2", "My sister's birthday is on the 22nd.")

        c.runCycle(email, "req-d2", "My sister's birthday is on the 22nd.")

        assertEquals(1, interpreter.firstLooks)
        assertEquals(1, interpreter.datedLooks)
        assertEquals(1, structureStore.listRecentItems(email, 10).count { it.text == "Sister's birthday" }, "the retry wrote nothing twice")
    }

    @Test
    fun `no second look without a date, for a question, after operations, or when answering something Alfrd asked`() = runBlocking {
        val email = "coord-dated-skip-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        fun run(first: InterpretOutcome, utterance: String, pending: PendingInterpretation = PendingInterpretation.NONE): TwoLookInterpreter {
            val interpreter = TwoLookInterpreter(first, foundBirthday)
            runBlocking { coordinator(interpreter).runCycle(email, "req-${UUID.randomUUID()}", utterance, pending) }
            return interpreter
        }

        assertEquals(0, run(InterpretOutcome.NoOperation, "My sister is lovely.").datedLooks, "no date")
        assertEquals(0, run(InterpretOutcome.NoOperation, "Is her birthday on the 22nd?").datedLooks, "a question")
        assertEquals(0, run(InterpretOutcome.Operations(listOf(RecordItem(ItemKind.FACT, "x")), emptyList(), 5), "Her birthday is on the 22nd").datedLooks, "the first look already produced operations")
        assertEquals(0, run(InterpretOutcome.NoOperation, "Yes, by Friday", PendingInterpretation(emotionQuote = "I'm stressed")).datedLooks, "it answers a question Alfrd asked")
        assertEquals(1, run(InterpretOutcome.NoOperation, "Her birthday is on the 22nd").datedLooks, "control: this one does get the second look")
    }

    @Test
    fun `when the second look finds nothing, or fails, the turn goes on as if it had not run`() = runBlocking {
        val email = "coord-dated-none-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val none = coordinator(TwoLookInterpreter(InterpretOutcome.NoOperation, InterpretOutcome.NoOperation)).runCycle(email, "req-n1", "I was born on the 3rd.")
        assertEquals("nothing dated to record", none.datedBackstop)
        assertTrue(none.mutationOutcomes.isEmpty())

        val failed = coordinator(TwoLookInterpreter(InterpretOutcome.NoOperation, InterpretOutcome.LlmFailure("timeout"))).runCycle(email, "req-n2", "Party on the 3rd.")
        assertEquals("failed: timeout", failed.datedBackstop)
        assertTrue(failed.mutationOutcomes.isEmpty())
        assertNotNull(failed.cycleSeq)
        assertTrue(HorizonItemsRenderer.composeIntegrityCaveat(failed) == null, "nothing was claimed and nothing failed to write")
    }
}
