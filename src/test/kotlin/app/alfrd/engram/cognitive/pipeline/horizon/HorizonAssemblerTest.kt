package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.database.Database
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Exercises the worked Arx fixture from the "Define conversational Context Horizon fixtures"
 * Notion task against real [HorizonGraphStore]/[HorizonAssembler] production code — every
 * `relevant_to`/`supersedes` edge is seeded directly by the test, standing in for a future
 * propagation step, never a demonstration of propagation itself.
 */
class HorizonAssemblerTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var store: HorizonGraphStore
    private lateinit var assembler: HorizonAssembler
    private val tempDirPrefixes = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        val prefix = "test-horizon-asm-${System.nanoTime()}"
        tempDirPrefixes += prefix
        dbManager = DatabaseManager("./data/$prefix")
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        store = ArcadeHorizonGraphStore(dbManager.getDatabase())
        assembler = ArcadeHorizonAssembler(dbManager.getDatabase())
    }

    @AfterEach
    fun tearDown() {
        dbManager.close()
        tempDirPrefixes.forEach { prefix ->
            File("./data").listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.deleteRecursively() }
        }
    }

    /** Seeds a User + conversational Source→ASSERTS→Phrase with an explicit cycleSeq stamped on the ASSERTS edge. */
    private fun seedConversationalPhraseWithCycle(db: Database, email: String, text: String, cycleSeq: Long): String {
        val now = System.currentTimeMillis()
        val phraseUid = UUID.randomUUID().toString()
        db.transaction {
            var userVertex = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
            if (userVertex == null) {
                userVertex = db.newVertex("User").apply {
                    set("uid", UUID.randomUUID().toString())
                    set("username", email.substringBefore("@"))
                    set("email", email)
                    set("tier", -1)
                    set("createdAt", now)
                    set("updatedAt", now)
                    save()
                }
            }
            val sourceName = "onboarding_conversation:$email"
            var sourceVertex = db.query("sql", "SELECT FROM Source WHERE name = :n", mapOf("n" to sourceName))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null }
            if (sourceVertex == null) {
                sourceVertex = db.newVertex("Source").apply {
                    set("uid", UUID.randomUUID().toString())
                    set("name", sourceName)
                    set("type", "onboarding_conversation")
                    set("metadata", "{}")
                    save()
                }
                userVertex.modify().newEdge("TRUSTS", sourceVertex, false).apply { set("scores", "[]"); save() }
            }
            val phraseVertex = db.newVertex("Phrase").apply {
                set("uid", phraseUid)
                set("text", text)
                set("hash", phraseUid)
                set("visibility", "private")
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            // Ordinary conversational ingestion (DatabaseEngramClient.ingest(), untouched this
            // increment) does not stamp cycleSeq — this test stands in for that future ingest-path
            // update, the same way the preceding increment wrote environment-sourced phrases
            // directly because EngramClient.ingest() couldn't represent them either.
            sourceVertex.newEdge("ASSERTS", phraseVertex, false).apply {
                set("context", "onboarding_conversation")
                set("timestamp", now)
                set("scores", """[{"type":"trust","perspective":"user","value":0.7}]""")
                set("cycleSeq", cycleSeq)
                save()
            }
        }
        return phraseUid
    }

    private fun seedConversationalPhraseWithCycle(email: String, text: String, cycleSeq: Long): String =
        seedConversationalPhraseWithCycle(dbManager.getDatabase(), email, text, cycleSeq)

    private fun phraseExists(db: Database, phraseUid: String): Boolean =
        db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseUid)).use { it.hasNext() }

    // ── Worked Arx fixture: full classification table, driven by cycleSeq not timestamps ────────

    @Test
    fun `worked Arx fixture classifies every item correctly across closely-spaced turns`() = runBlocking {
        val email = "horizon-fixture-${UUID.randomUUID()}@test.alfrd.internal"

        // Turn 1 (cycleSeq=1): the priority statement — an open intention, executed with zero delay
        // before the assertions below run, proving classification is cycle-identity based, not
        // wall-clock-gap based.
        val arxUid = seedConversationalPhraseWithCycle(
            email, "Getting Alfrd running on Arx is a priority for me — I keep meaning to get to it.", cycleSeq = 1,
        )
        assertTrue(store.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN))

        val afterTurn1 = assembler.assemble(email, currentCycleSeq = 1)
        val arxAtTurn1 = afterTurn1.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertEquals(HorizonItemCategory.INTENTION, arxAtTurn1.category)
        assertEquals(AssertionStatus.OPEN, arxAtTurn1.status)
        assertEquals(ProvenanceKind.EXPLICIT_USER_STATEMENT, arxAtTurn1.provenance)
        assertEquals(SurfacingReason.JustAsserted(1), arxAtTurn1.surfacing)

        // Turn 2 (cycleSeq=2) — an unrelated subject change. No status is ever attached to it.
        val groceryUid = seedConversationalPhraseWithCycle(
            email, "Anyway — what's a good way to structure the data model for my grocery list app?", cycleSeq = 2,
        )

        val afterTurn2 = assembler.assemble(email, currentCycleSeq = 2)
        val groceryItem = afterTurn2.items.single { it.sourceRefs.first().phraseUid == groceryUid }
        assertEquals(HorizonItemCategory.FACT, groceryItem.category)
        assertNull(groceryItem.status)
        assertEquals(SurfacingReason.JustAsserted(2), groceryItem.surfacing)

        val arxAtTurn2 = afterTurn2.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertEquals(
            SurfacingReason.DormantOpen(1), arxAtTurn2.surfacing,
            "ordinary topic change — no explicit pin instruction — must recede to dormant, not vanish",
        )

        // Environment event, ingested in the same cycle as turn 2, BEFORE any relevant_to edge exists.
        val eventUid = store.ingestEnvironmentSignal(
            email, cycleSeq = 2, sourceName = "environment:arx-build-system",
            text = "Arx developer build v0.9.2 finished compiling and is ready to flash — no errors.",
        )!!

        val beforePropagation = assembler.assemble(email, currentCycleSeq = 2)
        val eventItem = beforePropagation.items.single { it.sourceRefs.first().phraseUid == eventUid }
        assertEquals(HorizonItemCategory.ENVIRONMENT_EVENT, eventItem.category)
        assertEquals(ProvenanceKind.ENVIRONMENT_SIGNAL, eventItem.provenance)
        assertEquals(SurfacingReason.JustAsserted(2), eventItem.surfacing)
        val arxBeforePropagation = beforePropagation.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertEquals(
            SurfacingReason.DormantOpen(1), arxBeforePropagation.surfacing,
            "nothing connects the event to the priority item yet — propagation has not run",
        )

        // Test setup standing in for a future propagation decision — NOT a demonstration of
        // propagation. Seeded directly, exactly as the preceding increment's fixture test did.
        assertTrue(store.markRelevant(email, cycleSeq = 2, fromPhraseUid = eventUid, toPhraseUid = arxUid, strength = 1.0))

        // Turn 3 (cycleSeq=3), immediately after — the user continues the unrelated task.
        val turn3Uid = seedConversationalPhraseWithCycle(
            email, "Okay, and how should I handle the shopping history — separate table or just a flag on each item?",
            cycleSeq = 3,
        )

        val afterTurn3 = assembler.assemble(email, currentCycleSeq = 3)
        val arxReactivated = afterTurn3.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertTrue(arxReactivated.surfacing is SurfacingReason.ActiveReactivation)
        val info = (arxReactivated.surfacing as SurfacingReason.ActiveReactivation).info
        assertEquals(eventUid, info.triggeringPhraseUid)
        assertEquals(2L, info.edgeCycleSeq)
        assertEquals(HorizonItemCategory.INTENTION, arxReactivated.category, "reactivation must not change what kind of content this is")
        assertEquals(AssertionStatus.OPEN, arxReactivated.status)

        val turn3Item = afterTurn3.items.single { it.sourceRefs.first().phraseUid == turn3Uid }
        assertEquals(SurfacingReason.JustAsserted(3), turn3Item.surfacing)

        assertTrue(
            afterTurn3.items.none { it.sourceRefs.first().phraseUid == groceryUid },
            "turn 2's grocery phrase has no status and is no longer JustAsserted — it must not linger as a Horizon candidate",
        )
    }

    // ── Reactivation lifecycle: decays by cycle count, never proven via a real sleep ─────────────

    @Test
    fun `reactivation decays by cycle count, not wall-clock time`() = runBlocking {
        val email = "horizon-lifecycle-${UUID.randomUUID()}@test.alfrd.internal"
        val arxUid = seedConversationalPhraseWithCycle(email, "Arx priority.", cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN)
        val eventUid = store.ingestEnvironmentSignal(email, cycleSeq = 1, sourceName = "environment:x", text = "event")!!
        store.markRelevant(email, cycleSeq = 1, fromPhraseUid = eventUid, toPhraseUid = arxUid)

        val within = assembler.assemble(email, currentCycleSeq = 3, activeRelevanceCycles = 3)
        assertTrue(within.items.single { it.sourceRefs.first().phraseUid == arxUid }.surfacing is SurfacingReason.ActiveReactivation)

        val past = assembler.assemble(email, currentCycleSeq = 10, activeRelevanceCycles = 3)
        assertEquals(
            SurfacingReason.DormantOpen(1),
            past.items.single { it.sourceRefs.first().phraseUid == arxUid }.surfacing,
            "reactivation must decay once the cycle window has passed, not persist forever",
        )
    }

    // ── Consistent, committed read ────────────────────────────────────────────────────────────

    @Test
    fun `an awaited write is always visible to the very next assemble call`() = runBlocking {
        val email = "horizon-consistency-${UUID.randomUUID()}@test.alfrd.internal"
        val phraseUid = seedConversationalPhraseWithCycle(email, "some fact", cycleSeq = 1)
        assertTrue(store.markAssertionStatus(email, cycleSeq = 1, phraseUid = phraseUid, status = AssertionStatus.OPEN))

        val horizon = assembler.assemble(email, currentCycleSeq = 1)
        assertTrue(horizon.items.any { it.sourceRefs.first().phraseUid == phraseUid && it.status == AssertionStatus.OPEN })
    }

    // ── Adversarial cross-user read: defense in depth even if a bad edge already exists ─────────

    @Test
    fun `assemble never surfaces another user's phrase even via a store-bypassing raw edge`() = runBlocking {
        val emailA = "horizon-xread-a-${UUID.randomUUID()}@test.alfrd.internal"
        val emailB = "horizon-xread-b-${UUID.randomUUID()}@test.alfrd.internal"
        val arxUidA = seedConversationalPhraseWithCycle(emailA, "User A's private open intention.", cycleSeq = 1)
        store.markAssertionStatus(emailA, cycleSeq = 1, phraseUid = arxUidA, status = AssertionStatus.OPEN)
        val attackerPhraseB = seedConversationalPhraseWithCycle(emailB, "User B's private attack-payload phrase.", cycleSeq = 1)

        val db = dbManager.getDatabase()
        db.transaction {
            val from = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to attackerPhraseB))
                .use { it.next().toElement().asVertex().modify() }
            val to = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to arxUidA))
                .use { it.next().toElement().asVertex().modify() }
            RelatedToEdges.createRelevantTo(from, to, strength = 1.0, cycleSeq = 1, createdAt = System.currentTimeMillis())
        }

        val horizon = assembler.assemble(emailA, currentCycleSeq = 1)
        val arxItem = horizon.items.single { it.sourceRefs.first().phraseUid == arxUidA }
        assertTrue(
            arxItem.surfacing !is SurfacingReason.ActiveReactivation,
            "a cross-user edge injected outside HorizonGraphStore must not be trusted as an active reactivation",
        )
        val allUids = horizon.items.flatMap { it.sourceRefs.map { ref -> ref.phraseUid } } +
            horizon.items.mapNotNull { (it.surfacing as? SurfacingReason.ActiveReactivation)?.info?.triggeringPhraseUid }
        assertFalse(attackerPhraseB in allUids, "user B's phrase uid must never appear anywhere in user A's snapshot")
        val allText = horizon.items.map { it.text.text } +
            horizon.items.mapNotNull { (it.surfacing as? SurfacingReason.ActiveReactivation)?.info?.triggeringPhraseText?.text }
        assertTrue(allText.none { it.contains("attack-payload") }, "user B's phrase text must never appear anywhere in user A's snapshot")
    }

    // ── Bounded output: honest lower-bound accounting, never an unbounded list ───────────────────

    @Test
    fun `overflow beyond budget is bounded, honest, and every omitted entry is traceable`() = runBlocking {
        val email = "horizon-bounded-${UUID.randomUUID()}@test.alfrd.internal"
        // Distinct cycleSeq per item avoids ORDER BY ties, keeping which items get kept vs.
        // omitted deterministic.
        (1..10).forEach { i ->
            val uid = seedConversationalPhraseWithCycle(email, "Open item number $i", cycleSeq = i.toLong())
            store.markAssertionStatus(email, cycleSeq = i.toLong(), phraseUid = uid, status = AssertionStatus.OPEN)
        }

        val budget = HorizonBudget(maxItems = 3, itemCount = 0, truncated = false)
        val horizon = assembler.assemble(email, currentCycleSeq = 100, budget = budget)

        assertEquals(3, horizon.items.size)
        assertTrue(horizon.budget.truncated)
        // poolLimit = 3 * CANDIDATE_POOL_OVERFETCH(3) = 9 of the 10 seeded items are even fetched;
        // 3 are kept, so 6 are omitted from the fetched pool — a true, deterministic lower bound.
        assertEquals(6, horizon.omittedAtLeast)
        assertTrue(horizon.omittedSample.size <= HorizonLimits.OMITTED_SAMPLE_CAP)
        assertTrue(horizon.moreCandidatesAvailable, "the pool itself hit its cap — the 10th item was never even fetched")

        val db = dbManager.getDatabase()
        horizon.omittedSample.forEach { omitted ->
            assertTrue(phraseExists(db, omitted.sourceRef.phraseUid), "every omitted entry must still resolve to a live graph vertex")
        }
    }

    // ── Serialized-output budget: truncation marked, full text always recoverable ────────────────

    @Test
    fun `long text is truncated and marked, full text stays recoverable by uid`() = runBlocking {
        val email = "horizon-truncation-${UUID.randomUUID()}@test.alfrd.internal"
        val longText = "x".repeat(HorizonLimits.MAX_ITEM_TEXT_LENGTH + 50)
        val phraseUid = seedConversationalPhraseWithCycle(email, longText, cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = phraseUid, status = AssertionStatus.OPEN)

        val horizon = assembler.assemble(email, currentCycleSeq = 1)
        val item = horizon.items.single { it.sourceRefs.first().phraseUid == phraseUid }
        assertTrue(item.text.truncated)
        assertTrue(item.text.text.length <= HorizonLimits.MAX_ITEM_TEXT_LENGTH + 1)

        val fullText = dbManager.getDatabase().query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseUid))
            .use { it.next().toElement().asVertex().get("text") as String }
        assertEquals(longText, fullText, "the full text must always be recoverable via the phrase uid, regardless of truncation")
    }

    @Test
    fun `a long triggering phrase's text is also truncated, not inlined unbounded`() = runBlocking {
        val email = "horizon-truncation-trigger-${UUID.randomUUID()}@test.alfrd.internal"
        val arxUid = seedConversationalPhraseWithCycle(email, "Arx priority.", cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN)
        val longEventText = "y".repeat(HorizonLimits.MAX_ITEM_TEXT_LENGTH + 50)
        val eventUid = store.ingestEnvironmentSignal(email, cycleSeq = 1, sourceName = "environment:x", text = longEventText)!!
        store.markRelevant(email, cycleSeq = 1, fromPhraseUid = eventUid, toPhraseUid = arxUid)

        val horizon = assembler.assemble(email, currentCycleSeq = 1)
        val info = (horizon.items.single { it.sourceRefs.first().phraseUid == arxUid }.surfacing as SurfacingReason.ActiveReactivation).info
        assertTrue(info.triggeringPhraseText.truncated)
        assertTrue(info.triggeringPhraseText.text.length <= HorizonLimits.MAX_ITEM_TEXT_LENGTH + 1)
    }

    // ── Scale/index verification: not merely assumed bounded ─────────────────────────────────────

    @Test
    fun `open-item query does not regress into an unindexed full scan at 5000+ edges`() = runBlocking {
        val baselineEmail = "horizon-scale-baseline-${UUID.randomUUID()}@test.alfrd.internal"
        seedManyOpenItemsInOneTransaction(dbManager.getDatabase(), baselineEmail, count = 20)
        val baselineMs = measureTimeMillis { assembler.assemble(baselineEmail, currentCycleSeq = 1_000_000) }

        val scaleEmail = "horizon-scale-large-${UUID.randomUUID()}@test.alfrd.internal"
        seedManyOpenItemsInOneTransaction(dbManager.getDatabase(), scaleEmail, count = 5_000)
        val scaleMs = measureTimeMillis { assembler.assemble(scaleEmail, currentCycleSeq = 1_000_000) }

        assertTrue(
            scaleMs < baselineMs * 50 + 2_000,
            "open-item query took ${scaleMs}ms at 5000 edges vs ${baselineMs}ms at 20 — looks like an unindexed full scan",
        )
    }

    /** Bulk-seeds [count] open-status phrases on one Source in a single transaction — test setup, not what's being measured. */
    private fun seedManyOpenItemsInOneTransaction(db: Database, email: String, count: Int) {
        val now = System.currentTimeMillis()
        db.transaction {
            val userVertex = db.newVertex("User").apply {
                set("uid", UUID.randomUUID().toString())
                set("username", email.substringBefore("@"))
                set("email", email)
                set("tier", -1)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            val sourceVertex = db.newVertex("Source").apply {
                set("uid", UUID.randomUUID().toString())
                set("name", "onboarding_conversation:$email")
                set("type", "onboarding_conversation")
                set("metadata", "{}")
                save()
            }
            userVertex.newEdge("TRUSTS", sourceVertex, false).apply { set("scores", "[]"); save() }
            repeat(count) { i ->
                val uid = UUID.randomUUID().toString()
                val phraseVertex = db.newVertex("Phrase").apply {
                    set("uid", uid)
                    set("text", "Open item number $i")
                    set("hash", uid)
                    set("visibility", "private")
                    set("createdAt", now)
                    set("updatedAt", now)
                    save()
                }
                sourceVertex.newEdge("ASSERTS", phraseVertex, false).apply {
                    set("context", "onboarding_conversation")
                    set("timestamp", now)
                    set("scores", "[]")
                    set("status", "open")
                    set("statusHistory", """[{"state":"open","at":$now}]""")
                    set("cycleSeq", i.toLong())
                    save()
                }
            }
        }
    }

    // ── listCandidates: full, explicitly paginated enumeration ────────────────────────────────

    @Test
    fun `listCandidates pages through the full open-item set with no gaps or duplicates`() = runBlocking {
        val email = "horizon-paging-${UUID.randomUUID()}@test.alfrd.internal"
        val seededUids = (1..25).map { i ->
            val uid = seedConversationalPhraseWithCycle(email, "Open item $i", cycleSeq = i.toLong())
            store.markAssertionStatus(email, cycleSeq = i.toLong(), phraseUid = uid, status = AssertionStatus.OPEN)
            uid
        }.toSet()

        val collected = mutableSetOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = assembler.listCandidates(email, status = AssertionStatus.OPEN, limit = 7, cursor = cursor)
            collected += page.items.map { it.phraseUid }
            cursor = page.nextCursor
            pages++
            assertTrue(pages < 100, "pagination must terminate")
        } while (cursor != null)

        assertEquals(seededUids, collected)
    }

    // ── Restart/rebuild equivalence ────────────────────────────────────────────────────────────

    @Test
    fun `restart recovers an equivalent Horizon from the same graph state`() = runBlocking {
        val path = "./data/test-horizon-asm-restart-${System.nanoTime()}"
        tempDirPrefixes += "test-horizon-asm-restart"
        var manager = DatabaseManager(path)
        SchemaBootstrap.bootstrap(manager.getDatabase())
        var localStore: HorizonGraphStore = ArcadeHorizonGraphStore(manager.getDatabase())
        var localAssembler: HorizonAssembler = ArcadeHorizonAssembler(manager.getDatabase())

        val email = "horizon-restart-${UUID.randomUUID()}@test.alfrd.internal"
        val arxUid = seedConversationalPhraseWithCycle(manager.getDatabase(), email, "Arx priority.", cycleSeq = 1)
        localStore.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN)
        val eventUid = localStore.ingestEnvironmentSignal(email, cycleSeq = 2, sourceName = "environment:x", text = "event")!!
        localStore.markRelevant(email, cycleSeq = 2, fromPhraseUid = eventUid, toPhraseUid = arxUid)

        val before = localAssembler.assemble(email, currentCycleSeq = 2)

        manager.close()
        manager = DatabaseManager(path) // reopen the same on-disk path — no re-bootstrap needed
        localAssembler = ArcadeHorizonAssembler(manager.getDatabase())

        val after = localAssembler.assemble(email, currentCycleSeq = 2)

        assertEquals(signature(before), signature(after))
        manager.close()
    }

    /** The equivalence definition from the design doc: (phraseUid, category, status, cycleSeq) tuples. */
    private fun signature(horizon: ContextHorizon): Set<List<Any?>> = horizon.items.map { item ->
        val cycleSeq = when (val s = item.surfacing) {
            is SurfacingReason.ActiveReactivation -> s.info.edgeCycleSeq
            is SurfacingReason.JustAsserted -> s.cycleSeq
            is SurfacingReason.DormantOpen -> s.lastStatusChangeCycleSeq
        }
        listOf(item.sourceRefs.first().phraseUid, item.category, item.status, cycleSeq)
    }.toSet()

    // ── Provenance: explicit user statements carry no inferred reason ────────────────────────────

    @Test
    fun `an explicit user statement carries verbatim text and explicit provenance, no inferred reason`() = runBlocking {
        val email = "horizon-provenance-${UUID.randomUUID()}@test.alfrd.internal"
        val text = "Getting Alfrd running on Arx is a priority for me."
        val arxUid = seedConversationalPhraseWithCycle(email, text, cycleSeq = 1)
        store.markAssertionStatus(email, cycleSeq = 1, phraseUid = arxUid, status = AssertionStatus.OPEN)

        val horizon = assembler.assemble(email, currentCycleSeq = 1)
        val item = horizon.items.single { it.sourceRefs.first().phraseUid == arxUid }
        assertEquals(ProvenanceKind.EXPLICIT_USER_STATEMENT, item.provenance)
        assertEquals(text, item.text.text, "must carry the user's own words verbatim, not a paraphrase or inferred reason")
    }
}
