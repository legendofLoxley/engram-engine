package app.alfrd.engram.cognitive.pipeline.hermes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The exact race this handle exists to make safe: a cancellation request arriving concurrently
 * with the exchange committing to its own final outcome. See [HermesCancelHandle]'s own doc for
 * why [checkCancelledAndFinish] must be the single, atomic point that decides who "won."
 */
class HermesCancelHandleTest {

    @Test
    fun `never requested — checkCancelledAndFinish reports null`() {
        val handle = HermesCancelHandle()
        assertNull(handle.checkCancelledAndFinish())
        assertFalse(handle.isCancelRequested())
    }

    @Test
    fun `requested before the exchange finishes — checkCancelledAndFinish reports the request`() {
        val handle = HermesCancelHandle()
        assertTrue(handle.requestCancel())
        assertTrue(handle.isCancelRequested())
        assertTrue(handle.cancelRequestedAtMs() != null)

        val at = handle.checkCancelledAndFinish()
        assertTrue(at != null)
    }

    @Test
    fun `completion racing with cancellation — a request arriving after finish reports too late`() {
        val handle = HermesCancelHandle()
        // The exchange commits to a final (non-cancelled) outcome first...
        assertNull(handle.checkCancelledAndFinish())
        // ...and only then does a cancellation request arrive. It must not be able to retroactively
        // change an outcome that was already decided.
        assertFalse(handle.requestCancel())
        assertFalse(handle.isCancelRequested())
    }

    @Test
    fun `a request is idempotent while still open — repeated calls keep returning true without moving the timestamp`() {
        val handle = HermesCancelHandle()
        assertTrue(handle.requestCancel())
        val firstTimestamp = handle.cancelRequestedAtMs()
        assertTrue(handle.requestCancel())
        assertEquals(firstTimestamp, handle.cancelRequestedAtMs())
    }

    @Test
    fun `attach after a request was already made still reports it to checkCancelledAndFinish`() {
        // attach() needs a real Process to send the ACP notification through; using a trivial
        // one confirms attach() does not blow up and that request state survives regardless of
        // attach ordering — the actual notification delivery itself needs real Docker and is
        // exercised only by the live demonstration, not this unit test.
        val handle = HermesCancelHandle()
        assertTrue(handle.requestCancel())
        val process = ProcessBuilder("true").start()
        try {
            handle.attach(process, "fake-session-id")
            assertTrue(handle.checkCancelledAndFinish() != null)
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `isolation from other handles — requesting cancellation on one handle never affects another`() {
        val handleA = HermesCancelHandle()
        val handleB = HermesCancelHandle()

        assertTrue(handleA.requestCancel())

        assertTrue(handleA.isCancelRequested())
        assertFalse(handleB.isCancelRequested())
        assertNull(handleB.checkCancelledAndFinish())
    }
}
