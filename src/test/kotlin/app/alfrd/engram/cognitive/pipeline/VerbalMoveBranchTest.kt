package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import app.alfrd.engram.model.PostureMoveType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — VerbalMoveBranch (director only: posture move classification)
// ─────────────────────────────────────────────────────────────────────────────

class VerbalMoveBranchTest {

    // ── Retrieval intent carries the computed move type ───────────────────────

    @Test
    fun `Disclosure branch produces a PhrasePool retrieval intent`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.Disclosure)
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertTrue(ctx.branchResult!!.retrieval is RetrievalIntent.PhrasePool)
    }

    @Test
    fun `FYI branch produces a PhrasePool retrieval intent`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.FYI)
        val ctx = CognitiveContext(utterance = "FYI I pushed the branch", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertTrue(ctx.branchResult!!.retrieval is RetrievalIntent.PhrasePool)
    }

    @Test
    fun `Continuation branch produces a PhrasePool retrieval intent`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.Continuation)
        val ctx = CognitiveContext(utterance = "um uh I was thinking about something", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertTrue(ctx.branchResult!!.retrieval is RetrievalIntent.PhrasePool)
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

    // ── HOLD move for emotionally loaded Disclosure ───────────────────────────

    @Test
    fun `Disclosure with neutral content resolves to RECEIPT move`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.Disclosure)
        // "My dog's name is Newton" — no emotional markers → surface energy ≈ 0 → RECEIPT
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        branch.execute(ctx)
        val retrieval = ctx.branchResult!!.retrieval as RetrievalIntent.PhrasePool
        assertEquals(PostureMoveType.RECEIPT, retrieval.moveType)
    }

    @Test
    fun `Disclosure with emotional content resolves to HOLD move`() = runTest {
        val branch = VerbalMoveBranch(turnShape = TurnShape.Disclosure)
        // Dense emotional markers push surface energy above 0.3 threshold → HOLD
        val ctx = CognitiveContext(
            utterance = "I feel really overwhelmed and stressed by everything honestly it was awful",
            sessionId = "s", userId = "u",
        )
        branch.execute(ctx)
        val retrieval = ctx.branchResult!!.retrieval as RetrievalIntent.PhrasePool
        assertEquals(PostureMoveType.HOLD, retrieval.moveType)
    }
}
