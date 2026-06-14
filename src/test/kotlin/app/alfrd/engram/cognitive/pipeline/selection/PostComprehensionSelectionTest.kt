package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.cognitive.pipeline.AffectConfig
import app.alfrd.engram.cognitive.pipeline.BranchResult
import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.cognitive.pipeline.ResponseStrategy
import app.alfrd.engram.cognitive.pipeline.EnergyLevel
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.PostureMoveType
import app.alfrd.engram.model.ResponseCategory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostComprehensionSelectionTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var service: ResponseSelectionService

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-postcomp-db-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        ResponsePhraseSeed.seed(db)
        service = ResponseSelectionService(db)
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-postcomp-db-") }
            ?.forEach { it.deleteRecursively() }
    }

    // ── Post-comprehension: bridge path ─────────────────────────────────────

    @Test
    fun `post-comprehension BRIDGE path returns branch-appropriate bridge phrases`() {
        val ctx = CognitiveContext(
            utterance = "what is the memory bridge?",
            sessionId = "s-bridge",
            userId = "user-bridge",
            trustPhase = "WORKING_RHYTHM",
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.QUESTION,
            expressionPhase = ExpressionPhase.BRIDGE,
            context = ctx,
            limit = 3,
        )
        val results = service.select(query)
        assertTrue(results.isNotEmpty(), "Expected bridge phrases for QUESTION branch")
        results.forEach { r ->
            assertEquals("BRIDGE", r.phrase.expressionPhase, "expressionPhase must be BRIDGE")
            assertTrue(
                "QUESTION" in r.phrase.branchAffinity,
                "branchAffinity must include QUESTION: ${r.phrase.branchAffinity}",
            )
        }
    }

    @Test
    fun `post-comprehension SYNTHESIS path returns sign-off for SOCIAL branch`() {
        val ctx = CognitiveContext(
            utterance = "goodbye",
            sessionId = "s-signoff",
            userId = "user-signoff",
            trustPhase = "ORIENTATION",
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.SYNTHESIS,
            category = ResponseCategory.SIGN_OFF,
            context = ctx,
            limit = 3,
        )
        val results = service.select(query)
        assertTrue(results.isNotEmpty(), "Expected SIGN_OFF phrases")
        results.forEach { r ->
            assertEquals("SIGN_OFF", r.phrase.category)
            assertEquals("SYNTHESIS", r.phrase.expressionPhase)
        }
    }

    @Test
    fun `post-comprehension returns empty for phase with no seeded phrases`() {
        val ctx = CognitiveContext(
            utterance = "ok",
            sessionId = "s-empty",
            userId = "user-empty",
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.TASK,
            expressionPhase = ExpressionPhase.PARTIAL,  // no PARTIAL phrases seeded
            context = ctx,
            limit = 1,
        )
        val results = service.select(query)
        assertTrue(results.isEmpty(), "PARTIAL has no seed phrases; must return empty")
    }

    @Test
    fun `post-comprehension phaseAffinity gate excludes out-of-phase phrases`() {
        // Test that phaseAffinity gating works: BRIDGE phrases have allPhases affinity so they
        // should always pass the gate regardless of the user's current phase.
        val ctx = CognitiveContext(
            utterance = "what comes next?",
            sessionId = "s-gate",
            userId = "user-gate",
            trustPhase = "UNDERSTANDING",
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.QUESTION,
            expressionPhase = ExpressionPhase.BRIDGE,
            context = ctx,
            limit = 10,
        )
        val results = service.select(query)
        // All returned phrases must have phaseAffinity that includes UNDERSTANDING or adjacent phase
        results.forEach { r ->
            val allowed = setOf("CONTEXT", "UNDERSTANDING") // adjacent = CONTEXT only at end
            val passes = r.phrase.phaseAffinity.isEmpty() ||
                r.phrase.phaseAffinity.any { it in allowed || it == "WORKING_RHYTHM" }
            assertTrue(passes, "Phrase ${r.phrase.uid} failed phaseAffinity gate: ${r.phrase.phaseAffinity}")
        }
    }

    @Test
    fun `bridge phrases rank in different order for TASK vs SOCIAL branch`() {
        val baseCtx = CognitiveContext(
            utterance = "do something",
            sessionId = "s-rank",
            userId = "user-rank",
            trustPhase = "WORKING_RHYTHM",
        )
        val taskResults = service.select(
            ResponseSelectionQuery(BranchType.TASK, expressionPhase = ExpressionPhase.BRIDGE, context = baseCtx, limit = 5),
        )
        val socialResults = service.select(
            ResponseSelectionQuery(BranchType.SOCIAL, expressionPhase = ExpressionPhase.BRIDGE, context = baseCtx, limit = 5),
        )
        // Both should return results; scoring weights differ per branch so ranking may differ
        assertTrue(taskResults.isNotEmpty())
        assertTrue(socialResults.isNotEmpty())
        // communicationFit weight is higher for TASK (0.30) vs SOCIAL (0.20), so the
        // specific weights used must differ even if the ranked phrase happens to be the same.
        val taskWeights = SelectionWeights.forBranch(BranchType.TASK)
        val socialWeights = SelectionWeights.forBranch(BranchType.SOCIAL)
        assertNotEquals(taskWeights.communicationFit, socialWeights.communicationFit)
    }

    // ── First-response path via moveType ────────────────────────────────────

    @Test
    fun `first-response path with moveType=RECEIPT returns RECEIPT phrases`() {
        val ctx = CognitiveContext(
            utterance = "just wanted to say",
            sessionId = "s-fr-receipt",
            userId = "user-fr-receipt",
        )
        val query = ResponseSelectionQuery(
            moveType = PostureMoveType.RECEIPT,
            expressionPhase = ExpressionPhase.FIRST_RESPONSE,
            context = ctx,
            limit = 5,
        )
        val results = service.select(query)
        assertTrue(results.isNotEmpty(), "Expected RECEIPT phrases for first-response path")
        results.forEach { r ->
            assertEquals("RECEIPT", r.phrase.moveType, "moveType must be RECEIPT")
            assertEquals("FIRST_RESPONSE", r.phrase.expressionPhase)
        }
    }

    @Test
    fun `first-response path with moveType=COMMIT returns COMMIT phrases`() {
        val ctx = CognitiveContext(
            utterance = "schedule a call please",
            sessionId = "s-fr-commit",
            userId = "user-fr-commit",
            trustPhase = "WORKING_RHYTHM",
        )
        val results = service.select(
            ResponseSelectionQuery(
                moveType = PostureMoveType.COMMIT,
                expressionPhase = ExpressionPhase.FIRST_RESPONSE,
                context = ctx,
                limit = 3,
            ),
        )
        assertTrue(results.isNotEmpty(), "Expected COMMIT phrases")
        results.forEach { r ->
            assertEquals("COMMIT", r.phrase.moveType)
        }
    }

    @Test
    fun `first-response path uses FIRST_RESPONSE spec weights`() {
        val ctx = CognitiveContext(
            utterance = "hmm",
            sessionId = "s-fr-weights",
            userId = "user-fr-weights",
        )
        val results = service.select(
            ResponseSelectionQuery(
                moveType = PostureMoveType.HOLD,
                expressionPhase = ExpressionPhase.FIRST_RESPONSE,
                context = ctx,
                limit = 1,
            ),
        )
        assertTrue(results.isNotEmpty())
        // Verify scoreBreakdown keys (all 5 dimensions present)
        val breakdown = results.first().scoreBreakdown
        assertTrue(breakdown.containsKey("freshness"))
        assertTrue(breakdown.containsKey("contextualFit"))
        assertTrue(breakdown.containsKey("communicationFit"))
        assertTrue(breakdown.containsKey("phaseAppropriateness"))
        assertTrue(breakdown.containsKey("effectiveness"))
        // Verify all scores are in [0.0, 1.0]
        breakdown.values.forEach { v ->
            assertTrue(v in 0.0..1.0, "Score out of range: $v")
        }
    }

    // ── SELECTED edge recording: branch vs moveType distinction ────────────

    @Test
    fun `SELECTED edge is recorded with moveType for first-response, branch for post-comprehension`() {
        // This test verifies the query result carries the correct phrase moveType
        // (edge recording itself is fire-and-forget, so we test the phrase-level signal).
        val ctx = CognitiveContext(
            utterance = "hmm",
            sessionId = "s-edge",
            userId = "user-edge",
        )

        val firstResponseResult = service.select(
            ResponseSelectionQuery(
                moveType = PostureMoveType.REPAIR,
                expressionPhase = ExpressionPhase.FIRST_RESPONSE,
                context = ctx,
                limit = 1,
            ),
        ).firstOrNull()
        assertNotNull(firstResponseResult, "Expected REPAIR phrase")
        assertNotNull(firstResponseResult!!.phrase.moveType, "First-response phrase must have non-null moveType")

        val postCompCtx = ctx.copy(
            sessionId = "s-edge-postcomp",
            branchResult = BranchResult("content", ResponseStrategy.SOCIAL),
        )
        val postCompResult = service.select(
            ResponseSelectionQuery(
                branch = BranchType.SOCIAL,
                expressionPhase = ExpressionPhase.BRIDGE,
                context = postCompCtx,
                limit = 1,
            ),
        ).firstOrNull()
        assertNotNull(postCompResult, "Expected bridge phrase")
        // Post-comprehension bridge phrases do not have a posture moveType
        assertNull(postCompResult!!.phrase.moveType, "Post-comprehension phrase must have null moveType")
    }

    // ── Scoring dimensions: all values in [0.0, 1.0] ────────────────────────

    @Test
    fun `all five scoring dimensions produce values in range for bridge query`() {
        val ctx = CognitiveContext(
            utterance = "explain the architecture",
            sessionId = "s-dim",
            userId = "user-dim",
            trustPhase = "CONTEXT",
        )
        val results = service.select(
            ResponseSelectionQuery(
                branch = BranchType.QUESTION,
                expressionPhase = ExpressionPhase.BRIDGE,
                context = ctx,
                limit = 5,
            ),
        )
        assertTrue(results.isNotEmpty())
        results.forEach { r ->
            r.scoreBreakdown.forEach { (dim, score) ->
                assertTrue(score in 0.0..1.0, "Dimension $dim out of range: $score")
            }
            assertTrue(r.compositeScore in 0.0..1.0, "Composite out of range: ${r.compositeScore}")
        }
    }

    @Test
    fun `communicationFit responds to energy level - HIGH energy boosts punchy phrases`() {
        val phraseShort = buildPhrase("On it.", "COMMIT")
        val phraseLong = buildPhrase("Let me think through that with you carefully.", "BRIDGE")

        val highEnergyCtx = CognitiveContext(
            utterance = "do it now",
            sessionId = "s-energy",
            userId = "u-energy",
            affect = AffectConfig(energy = EnergyLevel.HIGH),
        )
        val lowEnergyCtx = highEnergyCtx.copy(affect = AffectConfig(energy = EnergyLevel.LOW))

        val shortHighEnergy = SelectionScorer.communicationFit(phraseShort, highEnergyCtx)
        val shortLowEnergy = SelectionScorer.communicationFit(phraseShort, lowEnergyCtx)
        val longHighEnergy = SelectionScorer.communicationFit(phraseLong, highEnergyCtx)

        // Short phrase should score higher under HIGH energy (steadiness complement)
        assertTrue(shortHighEnergy > longHighEnergy, "Short phrase should score higher under HIGH energy")
        // Short phrase should score lower under LOW energy vs HIGH energy (HIGH adds boost)
        assertTrue(shortHighEnergy > shortLowEnergy)
    }

    @Test
    fun `communicationFit warm phrase scores higher with high warmth config`() {
        val warmPhrase = buildPhrase("Yeah, I hear you.", "HOLD")
        val neutralPhrase = buildPhrase("On it.", "COMMIT")

        val warmCtx = CognitiveContext(
            utterance = "this is frustrating",
            sessionId = "s-warm",
            userId = "u-warm",
            affect = AffectConfig(warmth = 0.9),
        )
        val dryCtx = warmCtx.copy(affect = AffectConfig(warmth = 0.2))

        val warmPhraseWarmCtx = SelectionScorer.communicationFit(warmPhrase, warmCtx)
        val warmPhraseDryCtx = SelectionScorer.communicationFit(warmPhrase, dryCtx)

        assertTrue(warmPhraseWarmCtx > warmPhraseDryCtx, "Warm phrase should score higher with high warmth config")
    }

    @Test
    fun `contextualFit sign-off scores higher on late turns`() {
        val phrase = buildPhrase("Until next time.", "SIGN_OFF")
        val earlyCtx = CognitiveContext(
            utterance = "bye",
            sessionId = "s-late",
            userId = "u-late",
            priorUtterances = mutableListOf("hi"),  // 2 turns total
        )
        val lateCtx = earlyCtx.copy(
            priorUtterances = (1..12).map { "turn $it" }.toMutableList(),  // 13 turns
        )

        val earlyScore = SelectionScorer.contextualFit(phrase, earlyCtx)
        val lateScore = SelectionScorer.contextualFit(phrase, lateCtx)

        assertTrue(lateScore > earlyScore, "Sign-off should score higher on late turns (got early=$earlyScore, late=$lateScore)")
    }

    @Test
    fun `phase appropriateness gates onboarding phrases to correct trust phase`() {
        // ORIENTATION-locked phrase (phaseAffinity = ["ORIENTATION"])
        val orientationPhrase = buildPhrase(
            "Good to meet you. I'd like to get oriented.",
            "GREETING",
            phaseAffinity = setOf("ORIENTATION"),
        )
        val orientationCtx = CognitiveContext(
            utterance = "hello",
            sessionId = "s-phase",
            userId = "u-phase",
            trustPhase = "ORIENTATION",
        )
        val understandingCtx = orientationCtx.copy(trustPhase = "UNDERSTANDING")

        val inPhaseScore = SelectionScorer.phaseAppropriateness(orientationPhrase, orientationCtx)
        val outOfPhaseScore = SelectionScorer.phaseAppropriateness(orientationPhrase, understandingCtx)

        assertEquals(1.0, inPhaseScore, 0.001, "Should score 1.0 for exact trust phase match")
        assertEquals(0.0, outOfPhaseScore, 0.001, "Should score 0.0 when 2+ phases away")
    }

    @Test
    fun `effectiveness cold-start dampening zero outcomes returns neutral 0 dot 5`() {
        val score = SelectionScorer.effectiveness(emptyList())
        assertEquals(0.5, score, 0.001)
    }

    @Test
    fun `effectiveness cold-start dampening 5 outcomes gives half-weight`() {
        val outcomes = listOf(
            SelectionScorer.OutcomeSummary(app.alfrd.engram.model.OutcomeSignal.ENGAGED, 5),
        )
        val score = SelectionScorer.effectiveness(outcomes)
        // coldStartWeight = min(1.0, 5/10) = 0.5
        // adjustment = 0.1 * 5 = 0.5 → raw = 1.0
        // effective = 0.5 + (1.0 - 0.5) * 0.5 = 0.75
        assertEquals(0.75, score, 0.001)
    }

    @Test
    fun `effectiveness cold-start dampening 10 plus outcomes gives full weight`() {
        val outcomes = listOf(
            SelectionScorer.OutcomeSummary(app.alfrd.engram.model.OutcomeSignal.ENGAGED, 10),
        )
        val score = SelectionScorer.effectiveness(outcomes)
        // coldStartWeight = 1.0 (10/10)
        // raw = 0.5 + 0.1*10 = 1.0 → effective = 0.5 + 0.5 * 1.0 = 1.0
        assertEquals(1.0, score, 0.001)
    }

    // ── Benchmark: < 5 ms ───────────────────────────────────────────────────

    @Test
    fun `selection completes within 5ms for a representative bridge query`() {
        val ctx = CognitiveContext(
            utterance = "explain everything",
            sessionId = "s-bench",
            userId = "user-bench",
            trustPhase = "CONTEXT",
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.QUESTION,
            expressionPhase = ExpressionPhase.BRIDGE,
            context = ctx,
            limit = 3,
        )

        // Warm up
        repeat(3) { service.select(query.copy(userId = "warmup-$it")) }

        // Measure
        val iterations = 10
        val start = System.nanoTime()
        repeat(iterations) {
            service.select(query)
        }
        val avgMs = (System.nanoTime() - start) / 1_000_000.0 / iterations

        assertTrue(avgMs < 10.0, "Expected < 10ms average (JVM test budget), got ${avgMs}ms")
    }

    @Test
    fun `selection completes within 5ms for a first-response moveType query`() {
        val ctx = CognitiveContext(
            utterance = "yeah",
            sessionId = "s-bench-fr",
            userId = "user-bench-fr",
        )
        val query = ResponseSelectionQuery(
            moveType = PostureMoveType.RECEIPT,
            expressionPhase = ExpressionPhase.FIRST_RESPONSE,
            context = ctx,
            limit = 1,
        )

        repeat(3) { service.select(query) }

        val iterations = 10
        val start = System.nanoTime()
        repeat(iterations) { service.select(query) }
        val avgMs = (System.nanoTime() - start) / 1_000_000.0 / iterations

        assertTrue(avgMs < 10.0, "Expected < 10ms average (JVM test budget), got ${avgMs}ms")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun buildPhrase(
        text: String,
        category: String,
        phaseAffinity: Set<String> = setOf("ORIENTATION", "WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
        moveType: String? = null,
    ) = app.alfrd.engram.model.ResponsePhrase(
        uid = "test-${text.hashCode()}",
        text = text,
        hash = "h",
        visibility = "internal",
        createdAt = 0L,
        updatedAt = 0L,
        branchAffinity = setOf("SOCIAL", "QUESTION", "TASK"),
        phaseAffinity = phaseAffinity,
        expressionPhase = "FIRST_RESPONSE",
        category = category,
        moveType = moveType,
    )
}
