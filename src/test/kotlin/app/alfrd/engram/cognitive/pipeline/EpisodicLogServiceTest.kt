package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.EngramClient
import app.alfrd.engram.cognitive.pipeline.memory.EpisodicLogService
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// EpisodicLogService - async write path tests
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodicLogServiceTest {

    // ── recordTurn is non-suspending and returns immediately ───────────────────

    @Test
    fun `recordTurn is non-blocking - function is not suspend`() {
        val engram = InMemoryEngramClient()
        val service = EpisodicLogService(engram, TestScope())
        // Called directly without runTest - must compile and not throw
        service.recordTurn(
            sessionId = "s1",
            userId = "user-1",
            turnIndex = 0,
            userUtterance = "Remind me to call the vet",
            alfrdResponse = "Noted!",
        )
        // No exception, no suspend required → non-blocking ✓
    }

    // ── turn recorded asynchronously ────────────────────────────────────────────

    @Test
    fun `turn is written asynchronously`() = runTest {
        val engram = InMemoryEngramClient()
        val service = EpisodicLogService(engram, this)

        service.recordTurn(
            sessionId = "s1",
            userId = "user-1",
            turnIndex = 0,
            userUtterance = "Remind me to call the vet",
            alfrdResponse = "Noted!",
        )

        assertTrue(engram.allEpisodicTurns().isEmpty(), "Expected no turns before coroutine runs")

        advanceUntilIdle()

        val turns = engram.allEpisodicTurns()
        assertEquals(2, turns.size, "Expected both sides of the turn to be recorded after advanceUntilIdle")
        assertEquals("user", turns[0].role)
        assertEquals("Remind me to call the vet", turns[0].text)
        assertEquals("alfrd", turns[1].role)
        assertEquals("Noted!", turns[1].text)
    }

    // ── write failure does not propagate ────────────────────────────────────────

    @Test
    fun `write failure does not propagate to caller`() = runTest {
        val delegate = InMemoryEngramClient()
        val throwingEngram = object : EngramClient by delegate {
            override suspend fun appendEpisodicTurn(
                sessionId: String, userId: String, turnIndex: Int, userUtterance: String, alfrdResponse: String,
            ): Unit = throw RuntimeException("simulated write failure")
        }
        val service = EpisodicLogService(throwingEngram, this)

        service.recordTurn(
            sessionId = "s1",
            userId = "user-1",
            turnIndex = 0,
            userUtterance = "anything",
            alfrdResponse = "anything",
        )
        advanceUntilIdle()
        // No exception thrown → pass
    }
}
