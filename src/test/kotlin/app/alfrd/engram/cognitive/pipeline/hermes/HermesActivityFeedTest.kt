package app.alfrd.engram.cognitive.pipeline.hermes

import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService
import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventKind
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.SalientTokenPropagator
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Real ArcadeDB throughout (same convention as [app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionServiceTest])
 * — [HermesActivityFeed] is entirely a durable-graph reader, so proving it works means proving it
 * against the real write path ([ActorEventIngestionService.ingest], exactly as
 * [HermesDelegationDispatcher] calls it), never a hand-built fixture that could silently drift from
 * what production actually writes. **No [HermesAssignmentCompletionStore] anywhere in this file** —
 * that is the entire point: this feed must work from graph state alone.
 */
class HermesActivityFeedTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var store: HorizonGraphStore
    private lateinit var service: ActorEventIngestionService
    private lateinit var testDbPath: String

    @BeforeEach
    fun setUp() {
        testDbPath = "./data/test-hermes-activity-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        dbManager = DatabaseManager(testDbPath)
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        val cycleSequencer = ArcadeCycleSequencer(dbManager.getDatabase())
        store = ArcadeHorizonGraphStore(dbManager.getDatabase())
        val assembler = ArcadeHorizonAssembler(dbManager.getDatabase())
        val propagator = SalientTokenPropagator(store, assembler)
        service = ActorEventIngestionService(cycleSequencer, store, propagator)
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        File(testDbPath).deleteRecursively()
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

    /** Mirrors exactly what [HermesDelegationDispatcher] writes for one document-summary assignment. */
    private fun ingestDocumentSummary(
        userEmail: String,
        assignmentId: String,
        targetFilename: String,
        executionOutcome: String,
        text: String = "Goal: ...",
    ) = runBlocking {
        service.ingest(
            userEmail = userEmail,
            eventId = "hermes-assignment-$assignmentId",
            kind = ActorEventKind.ToolResult(
                text = text,
                toolName = "read",
                toolSucceeded = executionOutcome == "completed",
                assignmentKind = "document_summary",
                targetFilename = targetFilename,
                executionOutcome = executionOutcome,
            ),
            sourceName = "hermes",
            assignmentId = assignmentId,
            occurredAt = System.currentTimeMillis(),
        )
    }

    private fun list(userEmail: String) = runBlocking { HermesActivityFeed.list(userEmail, store) }

    @Test
    fun `a completed document summary appears labeled completed, with a short summary naming the file — never the full findings text`() {
        seedUser("debug+a@test.alfrd.internal")
        ingestDocumentSummary(
            "debug+a@test.alfrd.internal", "a1", "director-hermes-project-brief.md", "completed",
            text = "SECRET-FULL-SUMMARY-TEXT-do-not-leak-verbatim",
        )
        val result = list("debug+a@test.alfrd.internal") as HermesActivityFeedResult.Ok
        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals("completed", item.state)
        assertEquals("director-hermes-project-brief.md", item.targetFilename)
        assertEquals("Summarized director-hermes-project-brief.md", item.summary)
        assertFalse(item.summary.contains("SECRET-FULL-SUMMARY-TEXT"), "must never be a copy of the delivered reply text")
    }

    @Test
    fun `a failed assignment is labeled failed, distinct from cancelled`() {
        seedUser("debug+b@test.alfrd.internal")
        ingestDocumentSummary("debug+b@test.alfrd.internal", "b1", "director-hermes-release-checklist.md", "failed")
        val item = (list("debug+b@test.alfrd.internal") as HermesActivityFeedResult.Ok).items.single()
        assertEquals("failed", item.state)
        assertEquals("Could not summarize director-hermes-release-checklist.md", item.summary)
    }

    @Test
    fun `a cancelled assignment is labeled cancelled, never shown as failed or completed`() {
        seedUser("debug+c@test.alfrd.internal")
        ingestDocumentSummary("debug+c@test.alfrd.internal", "c1", "director-hermes-project-brief.md", "cancelled")
        val item = (list("debug+c@test.alfrd.internal") as HermesActivityFeedResult.Ok).items.single()
        assertEquals("cancelled", item.state)
        assertEquals("Summary of director-hermes-project-brief.md was cancelled", item.summary)
    }

    @Test
    fun `a marker-check assignment (a different event family) is excluded entirely`() {
        seedUser("debug+d@test.alfrd.internal")
        runBlocking {
            service.ingest(
                userEmail = "debug+d@test.alfrd.internal",
                eventId = "hermes-assignment-d1",
                kind = ActorEventKind.ToolResult(
                    text = "DH-FIXTURE-abc123", toolName = "read", toolSucceeded = true,
                    assignmentKind = "marker_check", targetFilename = "director-hermes-fixture.txt", executionOutcome = "completed",
                ),
                sourceName = "hermes",
                assignmentId = "d1",
            )
        }
        assertTrue((list("debug+d@test.alfrd.internal") as HermesActivityFeedResult.Ok).items.isEmpty())
    }

    @Test
    fun `an event with no assignmentKind at all (pre-existing metadata shape) is excluded, never guessed into a label`() {
        seedUser("debug+e@test.alfrd.internal")
        runBlocking {
            service.ingest(
                userEmail = "debug+e@test.alfrd.internal",
                eventId = "hermes-assignment-e1",
                kind = ActorEventKind.ToolResult(text = "legacy event", toolName = "read", toolSucceeded = true),
                sourceName = "hermes",
                assignmentId = "e1",
            )
        }
        assertTrue((list("debug+e@test.alfrd.internal") as HermesActivityFeedResult.Ok).items.isEmpty())
    }

    @Test
    fun `an event from a different Source name is excluded — the Source filter, not just the metadata filter, scopes this feed`() {
        seedUser("debug+f@test.alfrd.internal")
        runBlocking {
            store.ingestEnvironmentSignal(
                userEmail = "debug+f@test.alfrd.internal",
                cycleSeq = 1L,
                sourceName = "not-hermes",
                text = "unrelated environment signal",
            )
        }
        assertTrue((list("debug+f@test.alfrd.internal") as HermesActivityFeedResult.Ok).items.isEmpty())
    }

    @Test
    fun `identity scoping — one user's committed activity never appears for a different user`() {
        seedUser("debug+owner@test.alfrd.internal")
        seedUser("debug+other@test.alfrd.internal")
        ingestDocumentSummary("debug+owner@test.alfrd.internal", "own1", "director-hermes-project-brief.md", "completed")
        val ownItems = (list("debug+owner@test.alfrd.internal") as HermesActivityFeedResult.Ok).items
        val otherItems = (list("debug+other@test.alfrd.internal") as HermesActivityFeedResult.Ok).items
        assertEquals(1, ownItems.size)
        assertTrue(otherItems.isEmpty())
    }

    @Test
    fun `an unknown user returns a confirmed-empty Ok, never Failed`() {
        val result = list("debug+never-seen@test.alfrd.internal")
        assertTrue(result is HermesActivityFeedResult.Ok)
        assertTrue((result as HermesActivityFeedResult.Ok).items.isEmpty())
    }

    @Test
    fun `retention is a display cap only — the 51st-oldest item drops out of the view but its graph evidence is still independently confirmable`() {
        val email = "debug+g@test.alfrd.internal"
        seedUser(email)
        // 55 eligible events, oldest first, so ids ending "01".."55" — cycleSeq is allocated in
        // ingestion order, so "g01" is genuinely the oldest.
        for (i in 1..55) {
            ingestDocumentSummary(email, "g%02d".format(i), "director-hermes-project-brief.md", "completed")
        }
        val items = (list(email) as HermesActivityFeedResult.Ok).items
        assertEquals(HermesActivityFeed.MAX_ITEMS, items.size)
        val presentIds = items.map { it.eventId }.toSet()
        assertFalse(presentIds.contains("hermes-assignment-g01"), "the oldest event must fall out of the bounded view")
        assertTrue(presentIds.contains("hermes-assignment-g55"), "the newest event must remain in the view")

        // The dropped event's evidence itself must still be independently readable — retention is a
        // VIEW boundary, never a deletion.
        val stillCommitted = runBlocking { store.findActorEventByEventId(email, "hermes-assignment-g01") }
        assertTrue(stillCommitted is HorizonGraphStore.ActorEventLookupResult.Found, "dropped-from-view evidence must not have been deleted from the graph")
    }

    @Test
    fun `items are ordered most-recent-first`() {
        val email = "debug+h@test.alfrd.internal"
        seedUser(email)
        ingestDocumentSummary(email, "h1", "director-hermes-project-brief.md", "completed")
        ingestDocumentSummary(email, "h2", "director-hermes-release-checklist.md", "completed")
        val items = (list(email) as HermesActivityFeedResult.Ok).items
        assertEquals(listOf("hermes-assignment-h2", "hermes-assignment-h1"), items.map { it.eventId })
    }

    @Test
    fun `activity survives a fresh read against the same durable database — never depends on any in-process store`() {
        val email = "debug+i@test.alfrd.internal"
        seedUser(email)
        ingestDocumentSummary(email, "i1", "director-hermes-project-brief.md", "completed")

        // A brand-new HorizonGraphStore instance against the SAME already-open database — this
        // class holds no state of its own between calls, which is what a real backend restart
        // (reopening the same on-disk database) would also look like from HermesActivityFeed's
        // point of view. No HermesAssignmentCompletionStore is constructed anywhere in this file.
        val freshStore = ArcadeHorizonGraphStore(dbManager.getDatabase())
        val items = (runBlocking { HermesActivityFeed.list(email, freshStore) } as HermesActivityFeedResult.Ok).items
        assertEquals(1, items.size)
        assertEquals("hermes-assignment-i1", items.single().eventId)
    }

    @Test
    fun `a failed underlying graph read is reported as Failed, never silently rendered as an empty list`() {
        val email = "debug+j@test.alfrd.internal"
        seedUser(email)
        val failingStore = object : HorizonGraphStore by store {
            override suspend fun listRecentActorEvents(userEmail: String, sourceName: String, limit: Int) =
                HorizonGraphStore.ActorEventListResult.Failed("simulated transient graph read failure")
        }
        val result = runBlocking { HermesActivityFeed.list(email, failingStore) }
        assertTrue(result is HermesActivityFeedResult.Failed)
    }

    // ── Running items — omitting activeAssignments preserves today's terminal-only behavior ──

    @Test
    fun `omitting activeAssignments behaves exactly as before — terminal history only, no running items`() {
        val email = "debug+k@test.alfrd.internal"
        seedUser(email)
        ingestDocumentSummary(email, "k1", "director-hermes-project-brief.md", "completed")
        val items = (list(email) as HermesActivityFeedResult.Ok).items
        assertEquals(1, items.size)
        assertEquals("completed", items.single().state)
    }

    @Test
    fun `a registered, still-active assignment appears as running, with no cycleSeq`() {
        val email = "debug+l@test.alfrd.internal"
        seedUser(email)
        val registry = HermesActiveAssignmentRegistry()
        registry.register("l1", email, HermesCancelHandle(), "document_summary", "director-hermes-release-checklist.md", 999L)

        val items = (runBlocking { HermesActivityFeed.list(email, store, registry) } as HermesActivityFeedResult.Ok).items
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("running", item.state)
        assertEquals("l1", item.assignmentId)
        assertEquals(null, item.cycleSeq)
        assertEquals("director-hermes-release-checklist.md", item.targetFilename)
        assertEquals(999L, item.occurredAt)
    }

    @Test
    fun `a marker_check active assignment is excluded from running, same as terminal already excludes it`() {
        val email = "debug+m@test.alfrd.internal"
        seedUser(email)
        val registry = HermesActiveAssignmentRegistry()
        registry.register("m1", email, HermesCancelHandle(), "marker_check", "fixture.txt", 1L)

        val items = (runBlocking { HermesActivityFeed.list(email, store, registry) } as HermesActivityFeedResult.Ok).items
        assertTrue(items.isEmpty())
    }

    @Test
    fun `an active assignment's own user isolation matches the existing terminal isolation`() {
        val owner = "debug+n-owner@test.alfrd.internal"
        val other = "debug+n-other@test.alfrd.internal"
        seedUser(owner)
        seedUser(other)
        val registry = HermesActiveAssignmentRegistry()
        registry.register("n1", owner, HermesCancelHandle(), "document_summary", "doc.md", 1L)

        val ownerItems = (runBlocking { HermesActivityFeed.list(owner, store, registry) } as HermesActivityFeedResult.Ok).items
        val otherItems = (runBlocking { HermesActivityFeed.list(other, store, registry) } as HermesActivityFeedResult.Ok).items
        assertEquals(1, ownerItems.size)
        assertTrue(otherItems.isEmpty())
    }

    @Test
    fun `the narrow race window — once terminal, an assignment is never also shown as running`() {
        val email = "debug+o@test.alfrd.internal"
        seedUser(email)
        // Simulates the exact window HermesDelegationDispatcher can momentarily be in: the graph
        // write and completion record have both already landed, but unregister() (a `finally`
        // block after both) hasn't run yet — so the registry still reports it active.
        val registry = HermesActiveAssignmentRegistry()
        registry.register("o1", email, HermesCancelHandle(), "document_summary", "director-hermes-project-brief.md", 1L)
        ingestDocumentSummary(email, "o1", "director-hermes-project-brief.md", "completed")

        val items = (runBlocking { HermesActivityFeed.list(email, store, registry) } as HermesActivityFeedResult.Ok).items
        assertEquals(1, items.size, "terminal must win — the same assignment must never appear twice")
        assertEquals("completed", items.single().state)
    }

    @Test
    fun `running items are unaffected by the terminal history's MAX_ITEMS display cap`() {
        val email = "debug+p@test.alfrd.internal"
        seedUser(email)
        for (i in 1..HermesActivityFeed.MAX_ITEMS) {
            ingestDocumentSummary(email, "p%02d".format(i), "director-hermes-project-brief.md", "completed")
        }
        val registry = HermesActiveAssignmentRegistry()
        registry.register("running1", email, HermesCancelHandle(), "document_summary", "director-hermes-release-checklist.md", 1L)

        val items = (runBlocking { HermesActivityFeed.list(email, store, registry) } as HermesActivityFeedResult.Ok).items
        assertTrue(items.any { it.assignmentId == "running1" && it.state == "running" }, "a running item must not be crowded out by a full terminal history")
    }
}
