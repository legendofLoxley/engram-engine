package app.alfrd.engram.cognitive.pipeline.hermes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HermesActiveAssignmentRegistryTest {

    @Test
    fun `an unknown assignmentId reports NotActive`() {
        val registry = HermesActiveAssignmentRegistry()
        assertEquals(HermesCancellationRequestOutcome.NotActive, registry.requestCancellation("never-registered", "debug+u@test.alfrd.internal"))
    }

    @Test
    fun `a registered, still-active assignment reports Requested`() {
        val registry = HermesActiveAssignmentRegistry()
        registry.register("a1", "debug+u@test.alfrd.internal", HermesCancelHandle(), "document_summary", "doc.md", 1L)

        assertEquals(HermesCancellationRequestOutcome.Requested, registry.requestCancellation("a1", "debug+u@test.alfrd.internal"))
    }

    @Test
    fun `a mismatched userEmail reports NotActive, identically to unknown, preventing cross-user cancellation`() {
        val registry = HermesActiveAssignmentRegistry()
        registry.register("a1", "debug+owner@test.alfrd.internal", HermesCancelHandle(), "document_summary", "doc.md", 1L)

        assertEquals(HermesCancellationRequestOutcome.NotActive, registry.requestCancellation("a1", "debug+someone-else@test.alfrd.internal"))
    }

    @Test
    fun `after unregister, requestCancellation reports NotActive`() {
        val registry = HermesActiveAssignmentRegistry()
        registry.register("a1", "debug+u@test.alfrd.internal", HermesCancelHandle(), "document_summary", "doc.md", 1L)
        registry.unregister("a1")

        assertEquals(HermesCancellationRequestOutcome.NotActive, registry.requestCancellation("a1", "debug+u@test.alfrd.internal"))
    }

    @Test
    fun `a handle that already finished reports NotActive even though it is still registered`() {
        val registry = HermesActiveAssignmentRegistry()
        val handle = HermesCancelHandle()
        handle.checkCancelledAndFinish() // simulates the exchange having already committed to an outcome
        registry.register("a1", "debug+u@test.alfrd.internal", handle, "document_summary", "doc.md", 1L)

        assertEquals(HermesCancellationRequestOutcome.NotActive, registry.requestCancellation("a1", "debug+u@test.alfrd.internal"))
    }

    @Test
    fun `isolation from other runs — cancelling one assignment never affects a different, concurrently active one`() {
        val registry = HermesActiveAssignmentRegistry()
        val handleA = HermesCancelHandle()
        val handleB = HermesCancelHandle()
        registry.register("assignment-a", "debug+u@test.alfrd.internal", handleA, "document_summary", "a.md", 1L)
        registry.register("assignment-b", "debug+u@test.alfrd.internal", handleB, "document_summary", "b.md", 2L)

        assertEquals(HermesCancellationRequestOutcome.Requested, registry.requestCancellation("assignment-a", "debug+u@test.alfrd.internal"))

        // B's own handle must be completely untouched by A's cancellation.
        assertEquals(false, handleB.isCancelRequested())
        assertEquals(HermesCancellationRequestOutcome.Requested, registry.requestCancellation("assignment-b", "debug+u@test.alfrd.internal"))
    }

    // ── listActive — the read side a running-executions panel needs ────────────

    @Test
    fun `listActive is empty when nothing is registered`() {
        val registry = HermesActiveAssignmentRegistry()
        assertTrue(registry.listActive("debug+u@test.alfrd.internal", "document_summary").isEmpty())
    }

    @Test
    fun `listActive returns a registered assignment with its filename and issuedAt`() {
        val registry = HermesActiveAssignmentRegistry()
        registry.register("a1", "debug+u@test.alfrd.internal", HermesCancelHandle(), "document_summary", "director-hermes-project-brief.md", 12345L)

        val running = registry.listActive("debug+u@test.alfrd.internal", "document_summary")
        assertEquals(1, running.size)
        assertEquals(HermesActiveAssignmentSummary("a1", "director-hermes-project-brief.md", 12345L), running.single())
    }

    @Test
    fun `listActive excludes a different user's assignments`() {
        val registry = HermesActiveAssignmentRegistry()
        registry.register("a1", "debug+owner@test.alfrd.internal", HermesCancelHandle(), "document_summary", "doc.md", 1L)

        assertTrue(registry.listActive("debug+someone-else@test.alfrd.internal", "document_summary").isEmpty())
    }

    @Test
    fun `listActive excludes a different assignment kind`() {
        val registry = HermesActiveAssignmentRegistry()
        registry.register("a1", "debug+u@test.alfrd.internal", HermesCancelHandle(), "marker_check", "fixture.txt", 1L)

        assertTrue(registry.listActive("debug+u@test.alfrd.internal", "document_summary").isEmpty())
    }

    @Test
    fun `listActive no longer reports an assignment after unregister`() {
        val registry = HermesActiveAssignmentRegistry()
        registry.register("a1", "debug+u@test.alfrd.internal", HermesCancelHandle(), "document_summary", "doc.md", 1L)
        registry.unregister("a1")

        assertTrue(registry.listActive("debug+u@test.alfrd.internal", "document_summary").isEmpty())
    }
}
