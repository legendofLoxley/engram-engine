package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.cognitive.pipeline.CognitivePipeline
import app.alfrd.engram.cognitive.pipeline.HorizonCycleCoordinator
import app.alfrd.engram.cognitive.pipeline.Interpreter
import app.alfrd.engram.cognitive.pipeline.memory.DatabaseEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import app.alfrd.engram.cognitive.providers.ToolCall
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * The worked scenario, end to end, through the real production-wired path (`CognitivePipeline`
 * with `horizonCycleCoordinator` set — not the coordinator-disabled legacy path): an unfinished
 * priority statement, a topic change, a synthetic environment signal with its own provenance
 * (injected exactly as `/debug/environment-signal` would, through the same store/assembler/
 * propagator instances — never through the conversational LLM), continued unrelated
 * conversation whose refreshed Horizon actually contains the reactivated relevance (verified from
 * the controlled debug trace, independent of response prose), and an invited follow-up.
 *
 * Only the LLM is faked ([TestLlmClient], routed by whether `tools` is present — the
 * interpretation call always sets it, the Actor call never does). Every graph write, propagation
 * edge, and assembled Horizon here is produced by the real store/assembler/propagator — never
 * seeded by this test.
 */
class ContextHorizonConversationalCycleTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var testDbPath: String
    private lateinit var engramClient: DatabaseEngramClient
    private lateinit var horizonGraphStore: HorizonGraphStore
    private lateinit var horizonAssembler: HorizonAssembler
    private lateinit var cycleSequencer: CycleSequencer

    @BeforeEach
    fun setUp() {
        testDbPath = "./data/test-arx-scenario-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        engramClient = DatabaseEngramClient(dbManager.getDatabase())
        horizonGraphStore = ArcadeHorizonGraphStore(dbManager.getDatabase())
        horizonAssembler = ArcadeHorizonAssembler(dbManager.getDatabase())
        cycleSequencer = ArcadeCycleSequencer(dbManager.getDatabase())
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

    /** Directly exercises the same primitives `/debug/environment-signal` uses — distinct provenance, never through the conversational pipeline or Actor. */
    private suspend fun injectEnvironmentSignal(email: String, sourceName: String, text: String): PropagationOutcome {
        val propagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler)
        return PerUserCycleLock.withLock(email) {
            val cycleSeq = cycleSequencer.allocateCycle(email)!!
            val phraseUid = horizonGraphStore.ingestEnvironmentSignal(email, cycleSeq, sourceName, text)!!
            propagator.propagate(email, cycleSeq, listOf(phraseUid to text))
        }
    }

    /** Exercises [ActorEventIngestionService] directly — no Director turn, no pipeline, no LLM client — exactly what `/debug/actor-event` calls. */
    private suspend fun injectActorEvent(email: String, eventId: String, kind: ActorEventKind, sourceName: String): ActorEventIngestOutcome {
        val propagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler)
        val service = ActorEventIngestionService(cycleSequencer, horizonGraphStore, propagator)
        return service.ingest(email, eventId, kind, sourceName)
    }

    private fun buildPipeline(llmClient: TestLlmClient): CognitivePipeline {
        val propagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler)
        val coordinator = HorizonCycleCoordinator(
            cycleSequencer = cycleSequencer,
            requestLedger = ArcadeRequestLedger(dbManager.getDatabase()),
            interpreter = Interpreter(llmClient),
            engramClient = engramClient,
            horizonGraphStore = horizonGraphStore,
            horizonPropagator = propagator,
            horizonAssembler = horizonAssembler,
        )
        return CognitivePipeline(
            engramClient = engramClient,
            llmClient = llmClient,
            memoryWriteService = MemoryWriteService(engramClient),
            horizonCycleCoordinator = coordinator,
        )
    }

    private fun toolCall(quote: String): ToolCall = ToolCall(
        name = "assert_open_intention",
        input = buildJsonObject {
            put("quote", JsonPrimitive(quote))
            put("is_first_person_and_not_negated", JsonPrimitive(true))
        },
    )

    @Test
    fun `Arx priority, topic change, environment signal, reactivated relevance, invited surfacing`() = runBlocking {
        val email = "arx-scenario-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val arxUtterance = "Getting Alfrd running on Arx is a priority for me — I keep meaning to get to it."
        val arxQuote = "Getting Alfrd running on Arx is a priority for me"
        val groceryModelUtterance = "Anyway — what's a good way to structure the data model for my grocery list app?"
        val shoppingHistoryUtterance = "Okay, and how should I handle the shopping history — separate table or just a flag on each item?"
        val invitedUtterance = "Anything I should know about getting Arx running?"
        val environmentText = "Arx developer build v0.9.2 finished compiling and is ready to flash — no errors."

        val llmClient = TestLlmClient { request ->
            if (request.tools.isNotEmpty()) {
                // Interpretation call.
                if (request.prompt.contains(arxQuote)) {
                    LlmResponse(text = "", toolCalls = listOf(toolCall(arxQuote)), latencyMs = 5, retryCount = 0)
                } else {
                    LlmResponse(text = "", latencyMs = 5, retryCount = 0) // no tool call — NoOperation
                }
            } else {
                // Actor response call — scripted per turn; genuine model judgment (restraint vs.
                // surfacing) is exercised on the real deployed model during live verification, not
                // asserted deterministically here. What this test asserts is the INPUT the model
                // actually received, independent of prose.
                val text = when {
                    request.prompt.contains(arxQuote) -> "Got it, I'll keep that in mind."
                    request.prompt.contains("data model for my grocery list app") ->
                        "A normalized schema with separate Item and Category tables would work well."
                    request.prompt.contains("shopping history") ->
                        "I'd go with a separate table — it scales better as history grows."
                    request.prompt.contains("Anything I should know about getting Arx running") ->
                        "Yes — the Arx developer build finished compiling and is ready to flash whenever you want to pick that up."
                    else -> "Sure."
                }
                LlmResponse(text = text, latencyMs = 5, retryCount = 0)
            }
        }
        val pipeline = buildPipeline(llmClient)

        // ── Turn 1: the priority statement ──────────────────────────────────
        val turn1 = pipeline.processForDebug(arxUtterance, "s1", email, requestId = "req-t1")
        val cycle1 = turn1.trace.horizonCycle!!
        assertEquals("ProposedAssertion", cycle1.interpretOutcome)
        assertTrue(cycle1.mutationOutcomes.any { it.kind == "intention" && it.applied }, "the intention must be confirmed written before the response was composed")
        val horizonAfterTurn1 = (horizonAssembler.assemble(email, cycle1.cycleSeq!!) as AssembleOutcome.Assembled).horizon
        val arxItem = horizonAfterTurn1.items.single { it.status == AssertionStatus.OPEN }
        assertEquals(ProvenanceKind.EXPLICIT_USER_STATEMENT, arxItem.provenance)
        assertEquals(arxQuote, arxItem.text.text)

        // ── Turn 2: topic change — no intention fabricated, Arx item persists but recedes ────
        val turn2 = pipeline.processForDebug(groceryModelUtterance, "s1", email, requestId = "req-t2")
        val cycle2 = turn2.trace.horizonCycle!!
        assertEquals("NoOperation", cycle2.interpretOutcome)
        assertTrue(cycle2.mutationOutcomes.none { it.kind == "intention" }, "an ordinary subject change must never fabricate an intention")
        assertEquals("A normalized schema with separate Item and Category tables would work well.", turn2.chat.responseText)

        // ── Environment signal: its own provenance, injected out-of-band, not via the pipeline ──
        val envOutcome = injectEnvironmentSignal(email, "environment:arx-build-system", environmentText)
        assertTrue(envOutcome is PropagationOutcome.Propagated)
        val edges = (envOutcome as PropagationOutcome.Propagated).edgesCreated
        assertEquals(1, edges.size, "actual bounded propagation must derive exactly one relevance edge from graph evidence — never seeded")
        assertEquals(arxItem.sourceRefs.first().phraseUid, edges.single().toPhraseUid, "the derived edge must point at the real Arx phrase, not a harness-supplied target")

        // ── Turn 3: continued grocery discussion — refreshed Horizon contains the reactivated
        //    relevance, framed as optional, and the response answers the actual question ──────
        val turn3 = pipeline.processForDebug(shoppingHistoryUtterance, "s1", email, requestId = "req-t3")
        val cycle3 = turn3.trace.horizonCycle!!
        val reactivatedItem = cycle3.horizonAfterPropagation.singleOrNull { it.surfacing == "ActiveReactivation" }
        assertTrue(reactivatedItem != null, "the refreshed Horizon supplied for turn 3 must contain the propagation-derived reactivation, not just a dormant listing")
        assertEquals(arxQuote, reactivatedItem!!.text)
        val sentPrompt = cycle3.finalSystemPromptSent!!
        assertTrue(sentPrompt.contains(arxQuote), "the actual response invocation's input must contain the reactivated relevance")
        assertTrue(
            sentPrompt.contains("mention only what's genuinely relevant"),
            "the framing must invite judgment, never force interjection — no hard-coded Arx/grocery response policy",
        )
        assertEquals(
            "I'd go with a separate table — it scales better as history grows.", turn3.chat.responseText,
            "the response must answer the actual grocery question without the harness forcing an interjection",
        )

        // ── Turn 5 (4th conversational turn): invited surfacing — the same evidence is available
        //    when asked for directly ──────────────────────────────────────────────────────────
        val turn5 = pipeline.processForDebug(invitedUtterance, "s1", email, requestId = "req-t5")
        val cycle5 = turn5.trace.horizonCycle!!
        assertTrue(
            cycle5.horizonAfterPropagation.any { it.text == arxQuote },
            "the Arx evidence must still be available to be surfaced when the user actually invites it",
        )
        assertTrue(
            turn5.chat.responseText.contains("Arx"),
            "when invited directly, the response may surface the update — got: ${turn5.chat.responseText}",
        )
    }

    @Test
    fun `an independently ingested Actor event reaches the next real Director turn's actual prompt with correct provenance`() = runBlocking {
        val email = "actor-event-next-turn-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val observationText = "Nightly Arx CI pipeline finished successfully with zero failures"

        val llmClient = TestLlmClient { request ->
            if (request.tools.isNotEmpty()) {
                LlmResponse(text = "", latencyMs = 5, retryCount = 0) // NoOperation — no intention involved in this scenario
            } else {
                LlmResponse(text = "Sure, happy to help with whatever's next.", latencyMs = 5, retryCount = 0)
            }
        }
        val pipeline = buildPipeline(llmClient)

        // Independent ingestion — no pipeline, no session, no model call, while chat is idle.
        val ingestOutcome = injectActorEvent(email, "evt-nightly-ci", ActorEventKind.Observation(observationText), "hermes:nightly-ci")
        assertTrue(ingestOutcome is ActorEventIngestOutcome.Committed, "expected Committed, got $ingestOutcome")

        // The very next Director turn — entirely unrelated small talk, no manual renderer call.
        val turn = pipeline.processForDebug("What's a good name for a cat?", "s1", email, requestId = "req-next-turn")
        val cycle = turn.trace.horizonCycle!!

        val renderedItem = cycle.horizonAfterPropagation.singleOrNull { it.text == observationText }
        assertTrue(renderedItem != null, "the independently ingested evidence must reach the next turn's assembled Horizon")
        assertEquals("RecentActorEvidence", renderedItem!!.surfacing)

        val sentPrompt = cycle.finalSystemPromptSent!!
        assertTrue(sentPrompt.contains(observationText), "the actual response invocation's input — via the real production wiring, not a manually invoked renderer — must contain the independently ingested evidence")
        assertTrue(
            sentPrompt.contains("reported recently, independent of this conversation"),
            "the Actor-attributed framing must reach the actual prompt, distinguishing it from something the user said",
        )

        // Structural provenance, verified at the same committed cycle the trace reports — not inferred from prose.
        val horizonAtSameCycle = (horizonAssembler.assemble(email, cycle.cycleSeq!!) as AssembleOutcome.Assembled).horizon
        val structuredItem = horizonAtSameCycle.items.single { it.text.text == observationText }
        assertEquals(ProvenanceKind.ACTOR_OBSERVATION, structuredItem.provenance, "graph-level provenance must never default to EXPLICIT_USER_STATEMENT")
    }

    @Test
    fun `an independently ingested Actor interpretation reaches the next turn's prompt with its basis, distinct from an observation`() = runBlocking {
        val email = "actor-interp-next-turn-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val interpretationText = "The user appears to be blocked on the Arx deployment"
        val basis = "no commits referencing Arx in the last several days"

        val llmClient = TestLlmClient { request ->
            if (request.tools.isNotEmpty()) {
                LlmResponse(text = "", latencyMs = 5, retryCount = 0)
            } else {
                LlmResponse(text = "Sure, happy to help.", latencyMs = 5, retryCount = 0)
            }
        }
        val pipeline = buildPipeline(llmClient)

        val ingestOutcome = injectActorEvent(email, "evt-interp-1", ActorEventKind.Interpretation(interpretationText, basis), "hermes:pattern-analysis")
        assertTrue(ingestOutcome is ActorEventIngestOutcome.Committed, "expected Committed, got $ingestOutcome")

        val turn = pipeline.processForDebug("What's a good name for a cat?", "s1", email, requestId = "req-interp-turn")
        val cycle = turn.trace.horizonCycle!!

        val sentPrompt = cycle.finalSystemPromptSent!!
        assertTrue(sentPrompt.contains(interpretationText), "the interpretation's own text must reach the actual prompt")
        assertTrue(
            sentPrompt.contains("Actor interpretation, based on: \"$basis\""),
            "an interpretation must be distinguishable from a plain observation, and its basis must be preserved through to the actual prompt",
        )
        assertTrue(!sentPrompt.contains("[Actor-observed]"), "an interpretation must never be rendered as an unqualified observation")

        val horizonAtSameCycle = (horizonAssembler.assemble(email, cycle.cycleSeq!!) as AssembleOutcome.Assembled).horizon
        val structuredItem = horizonAtSameCycle.items.single { it.text.text == interpretationText }
        assertEquals(ProvenanceKind.ACTOR_INTERPRETATION, structuredItem.provenance)
        assertEquals(basis, structuredItem.actorMetadata?.basis, "the basis must survive all the way through assembly, not just the source-level ingestion metadata")
    }

    @Test
    fun `an independently ingested FAILED tool result reaches the next turn's prompt as a self-reported, unverified claim`() = runBlocking {
        val email = "actor-tool-fail-next-turn-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val toolResultText = "Deployment to staging failed with a timeout"

        val llmClient = TestLlmClient { request ->
            if (request.tools.isNotEmpty()) {
                LlmResponse(text = "", latencyMs = 5, retryCount = 0)
            } else {
                LlmResponse(text = "Sure, happy to help.", latencyMs = 5, retryCount = 0)
            }
        }
        val pipeline = buildPipeline(llmClient)

        val ingestOutcome = injectActorEvent(
            email, "evt-tool-fail-1",
            ActorEventKind.ToolResult(toolResultText, toolName = "deploy_service", toolSucceeded = false),
            "hermes:deploy-tool",
        )
        assertTrue(ingestOutcome is ActorEventIngestOutcome.Committed, "expected Committed, got $ingestOutcome")

        val turn = pipeline.processForDebug("What's a good name for a cat?", "s1", email, requestId = "req-tool-fail-turn")
        val cycle = turn.trace.horizonCycle!!

        val sentPrompt = cycle.finalSystemPromptSent!!
        assertTrue(sentPrompt.contains(toolResultText), "the tool result's own text must reach the actual prompt")
        assertTrue(sentPrompt.contains("reported failure"), "a FAILED tool result must be distinguishable from a successful one in the actual prompt, never collapsed to a generic tag")
        assertTrue(sentPrompt.contains("not independently verified"), "a tool's self-reported outcome must never be presented as independently verified fact")

        val horizonAtSameCycle = (horizonAssembler.assemble(email, cycle.cycleSeq!!) as AssembleOutcome.Assembled).horizon
        val structuredItem = horizonAtSameCycle.items.single { it.text.text == toolResultText }
        assertEquals(ProvenanceKind.ACTOR_TOOL_RESULT, structuredItem.provenance)
        assertEquals(false, structuredItem.actorMetadata?.toolSucceeded, "the self-reported failure must survive through assembly, never silently defaulted to success or dropped")
    }

    @Test
    fun `paraphrased and renamed equivalent still connects via shared vocabulary`() = runBlocking {
        val email = "arx-paraphrase-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "Getting Kestrel deployed to the staging cluster is something I need to finish"
        val llmClient = TestLlmClient { request ->
            if (request.tools.isNotEmpty()) {
                if (request.prompt.contains(quote)) {
                    LlmResponse(text = "", toolCalls = listOf(toolCall(quote)), latencyMs = 5, retryCount = 0)
                } else {
                    LlmResponse(text = "", latencyMs = 5, retryCount = 0)
                }
            } else {
                LlmResponse(text = "Understood.", latencyMs = 5, retryCount = 0)
            }
        }
        val pipeline = buildPipeline(llmClient)

        pipeline.processForDebug(quote, "s1", email, requestId = "req-p1")
        val envOutcome = injectEnvironmentSignal(
            email, "environment:kestrel-ci",
            "Kestrel staging deployment finished without errors and the cluster is healthy.",
        )

        assertTrue(envOutcome is PropagationOutcome.Propagated)
        assertEquals(1, (envOutcome as PropagationOutcome.Propagated).edgesCreated.size, "a renamed subject consistently referenced still connects via the shared distinctive term")
    }

    @Test
    fun `an unrelated environment event creates no relevance edge`() = runBlocking {
        val email = "arx-negative-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)
        val quote = "Getting Alfrd running on Arx is a priority for me"
        val llmClient = TestLlmClient { request ->
            if (request.tools.isNotEmpty()) {
                LlmResponse(text = "", toolCalls = listOf(toolCall(quote)), latencyMs = 5, retryCount = 0)
            } else {
                LlmResponse(text = "Got it.", latencyMs = 5, retryCount = 0)
            }
        }
        val pipeline = buildPipeline(llmClient)

        pipeline.processForDebug(quote, "s1", email, requestId = "req-n1")
        val envOutcome = injectEnvironmentSignal(
            email, "environment:weekly-backup",
            "Weekly backup completed successfully overnight with no issues.",
        )

        assertTrue(envOutcome is PropagationOutcome.Propagated)
        assertEquals(emptyList<RelevanceEdgeSummary>(), (envOutcome as PropagationOutcome.Propagated).edgesCreated, "an unrelated event must never be linked to the open intention")
    }

    @Test
    fun `propagation enabled vs disabled from identical graph state, same assembly machinery, edge never seeded`() = runBlocking {
        val emailEnabled = "arx-ab-enabled-${UUID.randomUUID()}@test.alfrd.internal"
        val emailDisabled = "arx-ab-disabled-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(emailEnabled)
        seedUser(emailDisabled)
        val quote = "Getting Alfrd running on Arx is a priority for me"

        // Identical matched state in both arms: the same open Arx intention plus enough other
        // open "noise" items that the target would be crowded out of assemble()'s own
        // response-prompt budget (HorizonBudget.DEFAULT.maxItems = 12) without a rank boost.
        for (email in listOf(emailEnabled, emailDisabled)) {
            val arxCycle = cycleSequencer.allocateCycle(email)!!
            val arxUid = horizonGraphStore.let { store ->
                val candidates = engramClient.ingest(
                    listOf(app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate(quote, "user", app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory.CONTEXT)),
                    email,
                )
                store.stampNewAssertion(email, arxCycle, candidates.single(), AssertionStatus.OPEN)
                candidates.single()
            }
            repeat(14) { i ->
                val cycle = cycleSequencer.allocateCycle(email)!!
                val uids = engramClient.ingest(
                    listOf(app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate("Unrelated open item number $i about something else entirely", "user", app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory.CONTEXT)),
                    email,
                )
                horizonGraphStore.stampNewAssertion(email, cycle, uids.single(), AssertionStatus.OPEN)
            }
            check(arxUid.isNotBlank())
        }

        val enabledPropagator: HorizonPropagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler)
        val disabledPropagator: HorizonPropagator = NoOpHorizonPropagator()

        val envCycleEnabled = cycleSequencer.allocateCycle(emailEnabled)!!
        val envUidEnabled = horizonGraphStore.ingestEnvironmentSignal(emailEnabled, envCycleEnabled, "environment:arx-build-system", "Arx developer build finished compiling and is ready to flash")!!
        enabledPropagator.propagate(emailEnabled, envCycleEnabled, listOf(envUidEnabled to "Arx developer build finished compiling and is ready to flash"))

        val envCycleDisabled = cycleSequencer.allocateCycle(emailDisabled)!!
        val envUidDisabled = horizonGraphStore.ingestEnvironmentSignal(emailDisabled, envCycleDisabled, "environment:arx-build-system", "Arx developer build finished compiling and is ready to flash")!!
        disabledPropagator.propagate(emailDisabled, envCycleDisabled, listOf(envUidDisabled to "Arx developer build finished compiling and is ready to flash"))

        val horizonEnabled = (horizonAssembler.assemble(emailEnabled, envCycleEnabled) as AssembleOutcome.Assembled).horizon
        val horizonDisabled = (horizonAssembler.assemble(emailDisabled, envCycleDisabled) as AssembleOutcome.Assembled).horizon

        assertTrue(
            horizonEnabled.items.any { it.surfacing is SurfacingReason.ActiveReactivation },
            "with propagation enabled, the assembled Horizon must contain a real ActiveReactivation item",
        )
        assertFalse(
            horizonDisabled.items.any { it.surfacing is SurfacingReason.ActiveReactivation },
            "with propagation disabled (NoOp), no reactivation can exist — same assembler, same starting state",
        )
    }
}
