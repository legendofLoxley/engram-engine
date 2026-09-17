package app.alfrd.engram.cognitive.pipeline.hermes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ownership-boundary test for [HermesCompletionDirector]: the Director, not any downstream
 * transport, decides whether Hermes's wording is delivered — see that object's class doc for the
 * gap this closes (a `toolSucceeded=false` `Completed` outcome was previously delivered exactly
 * like a trustworthy one, since the runner adapter never checked it).
 */
class HermesCompletionDirectorTest {

    private val assignment = HermesAssignment(
        assignmentId = "a1",
        userEmail = "debug+u@test.alfrd.internal",
        task = "Please read the file director-hermes-fixture.txt...",
        originalRequest = "can you check director-hermes-fixture.txt for me?",
        issuedAtCycleSeq = 42L,
    )

    @Test
    fun `a successful tool call is accepted and its wording delivered verbatim, no extra model call needed`() {
        val outcome = HermesAssignmentOutcome.Completed(
            findingsText = "DH-FIXTURE-abc123", toolName = "read",
            toolTargetPath = "/home/hermes/workspace/director-hermes-fixture.txt", toolSucceeded = true,
        )

        val decision = HermesCompletionDirector.decide(assignment, outcome)

        assertTrue(decision is HermesCompletionDecision.Accepted)
        assertTrue(decision.deliveryText.contains("DH-FIXTURE-abc123"), "accepted wording must include Hermes's own findings")
    }

    @Test
    fun `a completed outcome with toolSucceeded false is withheld, never delivered as if trustworthy`() {
        val outcome = HermesAssignmentOutcome.Completed(
            findingsText = "SECRET-UNRELATED-CONTENT", toolName = "read",
            toolTargetPath = "/opt/hermes-agent/director-hermes-fixture.txt", toolSucceeded = false,
        )

        val decision = HermesCompletionDirector.decide(assignment, outcome)

        assertTrue(decision is HermesCompletionDecision.Withheld)
        assertFalse(
            decision.deliveryText.contains("SECRET-UNRELATED-CONTENT"),
            "an unverified tool result must never be repeated to the user — this is the gap the runner adapter used to have",
        )
    }

    @Test
    fun `an execution failure is withheld with an honest, Director-composed explanation`() {
        val outcome = HermesAssignmentOutcome.Failed("no tool call observed (stopReason=refusal)")

        val decision = HermesCompletionDirector.decide(assignment, outcome)

        assertTrue(decision is HermesCompletionDecision.Withheld)
        assertTrue(decision.deliveryText.contains("no tool call observed"), "the failure reason should still be honestly reported")
        assertEquals(
            (decision as HermesCompletionDecision.Withheld).reason.contains("execution failed"),
            true,
        )
    }

    @Test
    fun `a cancelled outcome with no partial result is withheld, never delivered as an ordinary success`() {
        val outcome = HermesAssignmentOutcome.Cancelled(partialText = null, reason = "hermes confirmed the cancellation itself (stopReason=cancelled)")

        val decision = HermesCompletionDirector.decide(assignment, outcome)

        assertTrue(decision is HermesCompletionDecision.Withheld)
        assertTrue(decision.deliveryText.contains("cancelled"))
    }

    @Test
    fun `a cancelled outcome that produced a real result anyway is still withheld — the result is never delivered as confirmed`() {
        val outcome = HermesAssignmentOutcome.Cancelled(
            partialText = "DH-FIXTURE-7f2a91c4",
            reason = "cancellation was requested; hermes nonetheless reported stopReason=end_turn — honoring the cancellation regardless",
        )

        val decision = HermesCompletionDirector.decide(assignment, outcome)

        assertTrue(decision is HermesCompletionDecision.Withheld)
        assertFalse(
            decision.deliveryText.contains("DH-FIXTURE-7f2a91c4"),
            "a cancelled run must never subsequently receive what looks like an ordinary successful completion reply",
        )
    }

    @Test
    fun `deciding twice for the same assignment and outcome is deterministic — a pure function, no hidden state`() {
        val outcome = HermesAssignmentOutcome.Completed(
            findingsText = "DH-FIXTURE-abc123", toolName = "read",
            toolTargetPath = "/home/hermes/workspace/director-hermes-fixture.txt", toolSucceeded = true,
        )

        val first = HermesCompletionDirector.decide(assignment, outcome)
        val second = HermesCompletionDirector.decide(assignment, outcome)

        assertEquals(first, second)
    }
}
