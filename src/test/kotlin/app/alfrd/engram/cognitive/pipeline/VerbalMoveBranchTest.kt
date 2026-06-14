package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import app.alfrd.engram.model.PostureMoveType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — VerbalMoveBranch (no selection service, fallback paths only)
// ─────────────────────────────────────────────────────────────────────────────

class VerbalMoveBranchTest {

    // ── Fallback phrase content ───────────────────────────────────────────────

    @Test
    fun `Disclosure branch produces a non-blank acknowledgment`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.Disclosure)
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertTrue(ctx.branchResult!!.content.isNotBlank())
    }

    @Test
    fun `FYI branch produces a non-blank acknowledgment`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.FYI)
        val ctx = CognitiveContext(utterance = "FYI I pushed the branch", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertTrue(ctx.branchResult!!.content.isNotBlank())
    }

    @Test
    fun `Continuation branch produces a non-blank acknowledgment`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.Continuation)
        val ctx = CognitiveContext(utterance = "um uh I was thinking about something", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertTrue(ctx.branchResult!!.content.isNotBlank())
    }

    // ── No branch fallback stubs ──────────────────────────────────────────────

    @Test
    fun `Disclosure branch does not return task stub`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.Disclosure)
        val ctx = CognitiveContext(utterance = "I'm working on alfrd", sessionId = "s", userId = "u")
        branch.execute(ctx)
        val content = ctx.branchResult!!.content
        assertFalse(content.contains("task execution is coming soon"),
            "Verbal move must not return task stub, got: $content")
    }

    @Test
    fun `FYI branch does not return meta stub`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.FYI)
        val ctx = CognitiveContext(utterance = "FYI the API key rotated", sessionId = "s", userId = "u")
        branch.execute(ctx)
        val content = ctx.branchResult!!.content
        assertFalse(content.contains("Memory queries aren't available yet"),
            "Verbal move must not return meta stub, got: $content")
    }

    @Test
    fun `Disclosure branch does not return clarification prompt`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.Disclosure)
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        branch.execute(ctx)
        val content = ctx.branchResult!!.content
        assertFalse(content.contains("Could you say more about what you mean"),
            "Verbal move must not return clarification prompt, got: $content")
    }

    // ── Response strategy ─────────────────────────────────────────────────────

    @Test
    fun `Disclosure branch sets SOCIAL response strategy`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.Disclosure)
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(ResponseStrategy.SOCIAL, ctx.branchResult!!.responseStrategy)
    }

    @Test
    fun `FYI branch sets SOCIAL response strategy`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.FYI)
        val ctx = CognitiveContext(utterance = "FYI I pushed the branch", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals(ResponseStrategy.SOCIAL, ctx.branchResult!!.responseStrategy)
    }

    // ── Source tag ───────────────────────────────────────────────────────────

    @Test
    fun `verbal move source is pool`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.Disclosure)
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        branch.execute(ctx)
        assertEquals("pool", ctx.branchResult!!.source)
    }

    // ── HOLD move for emotionally loaded Disclosure ───────────────────────────

    @Test
    fun `Disclosure with neutral content resolves to RECEIPT fallback`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.Disclosure)
        // "My dog's name is Newton" — no emotional markers → surface energy ≈ 0 → RECEIPT
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        branch.execute(ctx)
        // RECEIPT fallback is "Got it." — check it's not a HOLD phrase
        val content = ctx.branchResult!!.content
        assertTrue(content.isNotBlank())
        assertFalse(content.contains("hear you"), "Neutral disclosure must not produce HOLD phrase")
    }

    @Test
    fun `Disclosure with emotional content may resolve to HOLD`() = runTest {
        val branch = VerbalMoveBranch(selectionService = null, turnShape = TurnShape.Disclosure)
        // Dense emotional markers push surface energy above 0.3 threshold → HOLD
        val ctx = CognitiveContext(
            utterance = "I feel really overwhelmed and stressed by everything honestly it was awful",
            sessionId = "s", userId = "u",
        )
        branch.execute(ctx)
        // HOLD fallback is "I hear you."
        assertEquals("I hear you.", ctx.branchResult!!.content)
    }
}
