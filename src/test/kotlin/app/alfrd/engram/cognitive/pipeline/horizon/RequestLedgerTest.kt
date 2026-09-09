package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestLedgerTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var ledger: RequestLedger

    @BeforeAll
    fun setUp() {
        val testDbPath = "./data/test-request-ledger-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        ledger = ArcadeRequestLedger(dbManager.getDatabase(), pruneRetentionCycles = 5)
    }

    @AfterAll
    fun tearDown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-request-ledger-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `unknown request id returns null`() = runBlocking {
        assertNull(ledger.find("nobody@test.alfrd.internal", "req-${UUID.randomUUID()}"))
    }

    @Test
    fun `checkpoint moves through the state machine, each field visible once set`() = runBlocking {
        val email = "ledger-state-${UUID.randomUUID()}@test.alfrd.internal"
        val requestId = "req-${UUID.randomUUID()}"

        ledger.checkpoint(email, requestId, cycleSeq = 1, checkpoint = "cycle_allocated")
        var record = ledger.find(email, requestId)
        assertEquals("cycle_allocated", record?.checkpoint)
        assertEquals(1L, record?.cycleSeq)
        assertEquals(emptyList<String>(), record?.phraseUids)

        ledger.checkpoint(email, requestId, cycleSeq = 1, checkpoint = "facts_ingested", phraseUids = listOf("p1", "p2"))
        record = ledger.find(email, requestId)
        assertEquals("facts_ingested", record?.checkpoint)
        assertEquals(listOf("p1", "p2"), record?.phraseUids)

        ledger.checkpoint(email, requestId, cycleSeq = 1, checkpoint = "writes_committed", phraseUids = listOf("p1", "p2", "p3"), intentionPhraseUid = "p3")
        record = ledger.find(email, requestId)
        assertEquals("writes_committed", record?.checkpoint)
        assertEquals("p3", record?.intentionPhraseUid)

        // Advancing to "completed" without re-passing phraseUids/intentionPhraseUid must not erase them.
        ledger.checkpoint(email, requestId, cycleSeq = 1, checkpoint = "completed")
        record = ledger.find(email, requestId)
        assertEquals("completed", record?.checkpoint)
        assertEquals(listOf("p1", "p2", "p3"), record?.phraseUids, "Fields not passed on a later checkpoint call must not be cleared")
        assertEquals("p3", record?.intentionPhraseUid)
    }

    @Test
    fun `a failed checkpoint records the failure reason`() = runBlocking {
        val email = "ledger-failed-${UUID.randomUUID()}@test.alfrd.internal"
        val requestId = "req-${UUID.randomUUID()}"

        ledger.checkpoint(email, requestId, cycleSeq = 1, checkpoint = "failed", failureReason = "simulated failure")

        val record = ledger.find(email, requestId)
        assertEquals("failed", record?.checkpoint)
        assertEquals("simulated failure", record?.failureReason)
    }

    @Test
    fun `distinct request ids for the same user never collide`() = runBlocking {
        val email = "ledger-distinct-${UUID.randomUUID()}@test.alfrd.internal"
        val requestId1 = "req-${UUID.randomUUID()}"
        val requestId2 = "req-${UUID.randomUUID()}"

        ledger.checkpoint(email, requestId1, cycleSeq = 1, checkpoint = "completed", phraseUids = listOf("only-in-1"))
        ledger.checkpoint(email, requestId2, cycleSeq = 2, checkpoint = "completed", phraseUids = listOf("only-in-2"))

        assertEquals(listOf("only-in-1"), ledger.find(email, requestId1)?.phraseUids)
        assertEquals(listOf("only-in-2"), ledger.find(email, requestId2)?.phraseUids)
    }

    @Test
    fun `bounded pruning removes rows older than the retention window`() = runBlocking {
        val email = "ledger-prune-${UUID.randomUUID()}@test.alfrd.internal"
        val oldRequestId = "req-old-${UUID.randomUUID()}"

        // Retention is 5 cycles for this test's ledger instance. Write an old row at cycle 1,
        // then advance far enough past it that it should be pruned by a later checkpoint write.
        ledger.checkpoint(email, oldRequestId, cycleSeq = 1, checkpoint = "completed")
        assertTrue(ledger.find(email, oldRequestId) != null, "Sanity check: the old row exists before pruning")

        val newRequestId = "req-new-${UUID.randomUUID()}"
        ledger.checkpoint(email, newRequestId, cycleSeq = 10, checkpoint = "completed")

        assertNull(ledger.find(email, oldRequestId), "A row more than the retention window behind the current cycle must be pruned")
        assertEquals("completed", ledger.find(email, newRequestId)?.checkpoint, "The new row itself must survive")
    }
}
