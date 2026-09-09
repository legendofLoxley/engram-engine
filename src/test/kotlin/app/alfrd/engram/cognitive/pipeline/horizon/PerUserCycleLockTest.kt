package app.alfrd.engram.cognitive.pipeline.horizon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Deterministic-barrier tests, not timing-based — mirrors the pattern
 * [ArcadeHorizonAssembler]'s `testMidAssemblySync` hook enables in `HorizonAssemblerTest`.
 */
class PerUserCycleLockTest {

    @Test
    fun `a second call for the same user waits for the first to release the lock`() = runTest {
        val userEmail = "lock-serialize-${UUID.randomUUID()}@test.alfrd.internal"
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val job1 = launch {
            PerUserCycleLock.withLock(userEmail) {
                order += "first-entered"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-exiting"
            }
        }

        firstEntered.await()
        // At this point job1 holds the lock. Start job2 and confirm it has NOT entered yet —
        // no timing assumption, just an explicit deferred that only completes once inside the lock.
        val secondEntered = CompletableDeferred<Unit>()
        val job2 = launch {
            PerUserCycleLock.withLock(userEmail) {
                order += "second-entered"
                secondEntered.complete(Unit)
            }
        }

        assertFalse(secondEntered.isCompleted, "Second call must not enter the lock while the first still holds it")

        releaseFirst.complete(Unit)
        job1.join()
        secondEntered.await()
        job2.join()

        assertEquals(listOf("first-entered", "first-exiting", "second-entered"), order)
    }

    @Test
    fun `different users never block each other`() = runTest {
        val userA = "lock-independent-a-${UUID.randomUUID()}@test.alfrd.internal"
        val userB = "lock-independent-b-${UUID.randomUUID()}@test.alfrd.internal"
        val aEntered = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val bEntered = CompletableDeferred<Unit>()

        val jobA = launch {
            PerUserCycleLock.withLock(userA) {
                aEntered.complete(Unit)
                releaseA.await()
            }
        }
        aEntered.await()

        // B must be able to enter its own lock while A still holds its own — genuinely
        // independent, not merely "eventually unblocks".
        val jobB = launch {
            PerUserCycleLock.withLock(userB) {
                bEntered.complete(Unit)
            }
        }
        bEntered.await()
        jobB.join()

        releaseA.complete(Unit)
        jobA.join()
    }
}
