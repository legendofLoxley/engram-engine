package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

/**
 * [CycleSequencer] claims only pure atomic allocation — no retry/idempotency claims (that's
 * [RequestLedger]'s job, tested separately). These tests verify exactly that: monotonic per-user
 * sequence, per-user isolation, and safety under real concurrent callers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CycleSequencerTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var sequencer: CycleSequencer

    @BeforeAll
    fun setUp() {
        val testDbPath = "./data/test-cycle-sequencer-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        sequencer = ArcadeCycleSequencer(dbManager.getDatabase())
    }

    @AfterAll
    fun tearDown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-cycle-sequencer-") }
            ?.forEach { it.deleteRecursively() }
    }

    private fun seedUser(email: String) {
        val db = dbManager.getDatabase()
        val now = System.currentTimeMillis()
        db.transaction {
            db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", email.substringBefore("@"))
                set("email", email)
                set("tier", -1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
        }
    }

    @Test
    fun `allocates a monotonically increasing sequence per user`() = runBlocking {
        val email = "cycle-seq-mono-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val first = sequencer.allocateCycle(email)
        val second = sequencer.allocateCycle(email)
        val third = sequencer.allocateCycle(email)

        assertEquals(1L, first)
        assertEquals(2L, second)
        assertEquals(3L, third)
    }

    @Test
    fun `different users have independent sequences`() = runBlocking {
        val emailA = "cycle-seq-a-${UUID.randomUUID()}@test.alfrd.internal"
        val emailB = "cycle-seq-b-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(emailA)
        seedUser(emailB)

        sequencer.allocateCycle(emailA)
        sequencer.allocateCycle(emailA)
        val bFirst = sequencer.allocateCycle(emailB)

        assertEquals(1L, bFirst, "A new user's sequence must start at 1, unaffected by another user's allocations")
    }

    @Test
    fun `returns null for an unknown user rather than allocating`() = runBlocking {
        val unknownEmail = "cycle-seq-unknown-${UUID.randomUUID()}@test.alfrd.internal"
        assertNull(sequencer.allocateCycle(unknownEmail))
    }

    @Test
    fun `concurrent allocations for the same user never collide or lose an update`() = runBlocking {
        val email = "cycle-seq-concurrent-${UUID.randomUUID()}@test.alfrd.internal"
        seedUser(email)

        val results = (1..20).map { async { sequencer.allocateCycle(email) } }.awaitAll()

        assertTrue(results.all { it != null }, "Every concurrent allocation must succeed")
        val values = results.filterNotNull().sorted()
        assertEquals((1L..20L).toList(), values, "20 concurrent allocations must yield exactly 1..20, no duplicates or gaps")
    }
}
