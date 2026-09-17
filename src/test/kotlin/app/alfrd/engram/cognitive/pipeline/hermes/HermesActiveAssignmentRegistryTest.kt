package app.alfrd.engram.cognitive.pipeline.hermes

import org.junit.jupiter.api.Assertions.assertEquals
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
        registry.register("a1", "debug+u@test.alfrd.internal", HermesCancelHandle())

        assertEquals(HermesCancellationRequestOutcome.Requested, registry.requestCancellation("a1", "debug+u@test.alfrd.internal"))
    }

    @Test
    fun `a mismatched userEmail reports NotActive, identically to unknown, preventing cross-user cancellation`() {
        val registry = HermesActiveAssignmentRegistry()
        registry.register("a1", "debug+owner@test.alfrd.internal", HermesCancelHandle())

        assertEquals(HermesCancellationRequestOutcome.NotActive, registry.requestCancellation("a1", "debug+someone-else@test.alfrd.internal"))
    }

    @Test
    fun `after unregister, requestCancellation reports NotActive`() {
        val registry = HermesActiveAssignmentRegistry()
        registry.register("a1", "debug+u@test.alfrd.internal", HermesCancelHandle())
        registry.unregister("a1")

        assertEquals(HermesCancellationRequestOutcome.NotActive, registry.requestCancellation("a1", "debug+u@test.alfrd.internal"))
    }

    @Test
    fun `a handle that already finished reports NotActive even though it is still registered`() {
        val registry = HermesActiveAssignmentRegistry()
        val handle = HermesCancelHandle()
        handle.checkCancelledAndFinish() // simulates the exchange having already committed to an outcome
        registry.register("a1", "debug+u@test.alfrd.internal", handle)

        assertEquals(HermesCancellationRequestOutcome.NotActive, registry.requestCancellation("a1", "debug+u@test.alfrd.internal"))
    }

    @Test
    fun `isolation from other runs — cancelling one assignment never affects a different, concurrently active one`() {
        val registry = HermesActiveAssignmentRegistry()
        val handleA = HermesCancelHandle()
        val handleB = HermesCancelHandle()
        registry.register("assignment-a", "debug+u@test.alfrd.internal", handleA)
        registry.register("assignment-b", "debug+u@test.alfrd.internal", handleB)

        assertEquals(HermesCancellationRequestOutcome.Requested, registry.requestCancellation("assignment-a", "debug+u@test.alfrd.internal"))

        // B's own handle must be completely untouched by A's cancellation.
        assertEquals(false, handleB.isCancelRequested())
        assertEquals(HermesCancellationRequestOutcome.Requested, registry.requestCancellation("assignment-b", "debug+u@test.alfrd.internal"))
    }
}
