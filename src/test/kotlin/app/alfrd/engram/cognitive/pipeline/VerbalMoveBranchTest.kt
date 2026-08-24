package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — VerbalMoveBranch (director only: low-pressure verbal-move conditioner)
//
// No retrieval and no PostureMoveType-keyed phrase selection here — the emotional/posture
// nuance for these turn shapes (in particular a heavily-loaded Disclosure) reaches the actor
// through the pipeline-wide `attunement` conditioner (see PostureComputationTest for
// attunementDirective coverage), not through a canned line this branch picks.
// ─────────────────────────────────────────────────────────────────────────────

class VerbalMoveBranchTest {

    // ── No retrieval — posture reaches the actor as a directive, not a phrase-pool pick ────

    @Test
    fun `Disclosure branch produces no retrieval intent`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.Disclosure)
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(RetrievalIntent.None, ctx.branchResult!!.retrieval)
    }

    @Test
    fun `FYI branch produces no retrieval intent`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.FYI)
        val ctx = CognitiveContext(utterance = "FYI I pushed the branch", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(RetrievalIntent.None, ctx.branchResult!!.retrieval)
    }

    @Test
    fun `Continuation branch produces no retrieval intent`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.Continuation)
        val ctx = CognitiveContext(utterance = "um uh I was thinking about something", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(RetrievalIntent.None, ctx.branchResult!!.retrieval)
    }

    // ── Directive content ─────────────────────────────────────────────────────

    @Test
    fun `directive asks for a brief low-pressure acknowledgment`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.FYI)
        val ctx = CognitiveContext(utterance = "FYI I pushed the branch", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertTrue(
            ctx.branchResult!!.directive.contains("brief", ignoreCase = true),
            "Expected a brief-acknowledgment directive, got: ${ctx.branchResult!!.directive}",
        )
    }

    // ── Response strategy ─────────────────────────────────────────────────────

    @Test
    fun `Disclosure branch sets SOCIAL response strategy`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.Disclosure)
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(ResponseStrategy.SOCIAL, ctx.branchResult!!.responseStrategy)
    }

    @Test
    fun `FYI branch sets SOCIAL response strategy`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.FYI)
        val ctx = CognitiveContext(utterance = "FYI I pushed the branch", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(ResponseStrategy.SOCIAL, ctx.branchResult!!.responseStrategy)
    }

    // ── No dependencies — branch never touches EngramClient/LlmClient ────────

    @Test
    fun `verbal move branch has no I O dependencies`() {
        // Compile-time guarantee: VerbalMoveBranch(turnShape) takes only a TurnShape.
        VerbalMoveBranch(turnShape = TurnShape.Disclosure)
    }
}
