package app.alfrd.engram.cognitive.pipeline.hermes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HermesAssignmentCompletionStoreTest {

    private val completed = HermesAssignmentOutcome.Completed(
        findingsText = "DH-FIXTURE-abc123", toolName = "read", toolTargetPath = "director-hermes-fixture.txt", toolSucceeded = true,
    )
    private val accepted = HermesCompletionDecision.Accepted("Hermes finished checking that — it reported: DH-FIXTURE-abc123")

    @Test
    fun `a recorded completion is returned for the exact assignmentId and userEmail`() {
        val store = HermesAssignmentCompletionStore()
        store.record("a1", "debug+u@test.alfrd.internal", completed, accepted, "Committed")

        val result = store.get("a1", "debug+u@test.alfrd.internal")

        assertEquals("a1", result?.assignmentId)
        assertEquals("debug+u@test.alfrd.internal", result?.userEmail)
        assertEquals(completed, result?.outcome)
        assertEquals(accepted, result?.decision)
        assertEquals("Committed", result?.graphIngestOutcome)
    }

    @Test
    fun `an unknown assignmentId returns null`() {
        val store = HermesAssignmentCompletionStore()
        assertNull(store.get("never-recorded", "debug+u@test.alfrd.internal"))
    }

    @Test
    fun `a mismatched userEmail returns null, preventing cross-session reads`() {
        val store = HermesAssignmentCompletionStore()
        store.record("a1", "debug+owner@test.alfrd.internal", completed, accepted, "Committed")

        assertNull(store.get("a1", "debug+someone-else@test.alfrd.internal"))
    }

    @Test
    fun `an entry older than maxAgeMs is pruned on the next record call`() {
        val store = HermesAssignmentCompletionStore(maxAgeMs = 1)
        store.record("old", "debug+u@test.alfrd.internal", completed, accepted, "Committed")
        Thread.sleep(5)

        // Triggers pruning as a side effect of recording a second, unrelated completion.
        store.record("new", "debug+u@test.alfrd.internal", completed, accepted, "Committed")

        assertNull(store.get("old", "debug+u@test.alfrd.internal"))
        assertEquals("new", store.get("new", "debug+u@test.alfrd.internal")?.assignmentId)
    }

    @Test
    fun `a Failed outcome round-trips exactly, alongside its own Withheld decision`() {
        val store = HermesAssignmentCompletionStore()
        val failed = HermesAssignmentOutcome.Failed("no tool call observed (stopReason=refusal)")
        val withheld = HermesCompletionDecision.Withheld(
            deliveryText = "Hermes wasn't able to complete that: no tool call observed (stopReason=refusal)",
            reason = "execution failed",
        )
        store.record("a2", "debug+u@test.alfrd.internal", failed, withheld, "Committed")

        val result = store.get("a2", "debug+u@test.alfrd.internal")

        assertEquals(failed, result?.outcome)
        assertEquals(withheld, result?.decision)
    }
}
