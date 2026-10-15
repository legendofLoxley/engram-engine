package app.alfrd.engram.db

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.RecordItem
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.pipeline.horizon.SourceItemDraft
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.arcadedb.database.Database
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.UUID

class SourceStoreTest {

    private lateinit var manager: DatabaseManager
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private lateinit var commit: SourceCommitService
    private val ana = "debug+sources-a@test.alfrd.internal"
    private val ben = "debug+sources-b@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        val dir = Files.createTempDirectory("engram-sources-test-")
        manager = DatabaseManager(dir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana)
        DebugConverseService.ensureSyntheticUser(db, ben)
        store = SourceStore(db)
        val structure = ArcadeHorizonStructureStore(db)
        commit = SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db))
    }

    @AfterEach
    fun tearDown() = manager.close()

    private val doc = SourceInput(
        type = "notion_page", locator = "https://www.notion.so/abc123", title = "Kitchen plan",
        text = "# Budget\nThe kitchen budget is 12,000 euros.\n\n# Schedule\nThe cabinets arrive on 14 May.\n\n# Open\nWho signs the contract?",
    )
    private val email = SourceInput(
        type = "email_message", locator = "<m2@mail.example.org>", title = "Re: Kitchen plan", threadId = "thread-77",
        text = "Contract goes to Marta, she signs Friday.\n\nOn Tue, 3 Mar 2026 at 10:14, Ana <ana@example.org> wrote:\n> Who signs the contract?\n> The cabinets arrive on 14 May.",
    )

    /** Everything a second commit must leave exactly as it was. */
    private fun counts() = listOf("Source", "Chunk", "Phrase", "Task", "Event", "Question", "User").associateWith { db.countType(it, true) } +
        listOf("CONTAINS", "QUOTES", "ASSERTS", "TRUSTS").associateWith { db.countType(it, true) }

    // ── The model ────────────────────────────────────────────────────────────

    @Test
    fun `a source is stored with where it lives, a content hash, when and how much was read, and its chunks in order`() {
        val r = store.ingest(ana, doc.copy(readAt = 1_700_000_000_000L, readMode = "partial"))
        assertTrue(r.created)
        assertEquals(3, r.chunksCreated)
        val s = r.source
        assertEquals("notion_page", s.type); assertEquals(doc.locator, s.locator); assertEquals("Kitchen plan", s.title)
        assertEquals("partial", s.readMode); assertEquals(1_700_000_000_000L, s.readAt)
        assertEquals(SourceChunker.contentHash(doc.text), s.contentHash)
        assertEquals(listOf("section: Budget", "section: Schedule", "section: Open"), store.chunks(ana, s.uid).map { it.location })
        assertEquals(listOf(0, 1, 2), store.chunks(ana, s.uid).map { it.ordinal })
        assertTrue(store.chunks(ana, s.uid).all { it.hash == SourceChunker.contentHash(it.text) })
    }

    @Test
    fun `ingesting the same content twice adds nothing at all`() {
        store.ingest(ana, doc)
        val before = counts()
        val again = store.ingest(ana, doc.copy(readAt = 1L, title = "renamed in the app"))
        assertFalse(again.created)
        assertEquals(0, again.chunksCreated)
        assertEquals(before, counts())
        assertEquals(1, store.sources(ana).size)
    }

    @Test
    fun `changed content under the same locator is a new source and the old one stays`() {
        val first = store.ingest(ana, doc).source
        val second = store.ingest(ana, doc.copy(text = doc.text + "\n\n# Addendum\nA late change.")).source
        assertNotEquals(first.uid, second.uid)
        assertEquals(2, store.sources(ana).size)
        assertEquals(3, store.chunks(ana, first.uid).size)
        assertEquals(4, store.chunks(ana, second.uid).size)
    }

    @Test
    fun `one email message is one source, the thread is a property, and quoted history is not chunked`() {
        val first = store.ingest(ana, email.copy(locator = "<m1@mail.example.org>", title = "Kitchen plan", text = "Who signs the contract?\nThe cabinets arrive on 14 May.")).source
        val reply = store.ingest(ana, email).source
        assertNotEquals(first.uid, reply.uid)
        assertEquals("thread-77", reply.threadId)
        val chunks = store.chunks(ana, reply.uid)
        assertEquals(1, chunks.size)
        assertEquals("message, part 1", chunks[0].location)
        assertEquals("Contract goes to Marta, she signs Friday.", chunks[0].text)
        // The hash is of what was read, quoted history included: the same message read again is the same source.
        assertEquals(SourceChunker.contentHash(email.text), reply.contentHash)
        assertFalse(store.ingest(ana, email).created)
    }

    @Test
    fun `sources and chunks belong to one user and are invisible to another`() {
        val s = store.ingest(ana, doc).source
        assertEquals(emptyList<SourceRecord>(), store.sources(ben))
        assertEquals(emptyList<ChunkRecord>(), store.chunks(ben, s.uid))
        assertNull(store.chunk(ben, store.chunks(ana, s.uid).first().uid))
        // The same page read by the other user is that user's own source.
        assertTrue(store.ingest(ben, doc).created)
        assertThrows<IllegalArgumentException> { store.ingest("debug+nobody@test.alfrd.internal", doc) }
    }

    @Test
    fun `a chunk is not an item, nothing asserts it, and it is not a phrase`() {
        store.ingest(ana, doc)
        assertEquals(0, db.query("sql", "SELECT FROM ASSERTS WHERE @in.@type = 'Chunk'").use { rs -> var n = 0; while (rs.hasNext()) { rs.next(); n++ }; n })
        assertFalse(db.schema.getType("Chunk").isSubTypeOf("Phrase"))
    }

    @Test
    fun `sources that existed before are untouched by the new properties and are not listed as read sources`() {
        db.transaction { db.newVertex("Source").set("uid", "old-1").set("name", "onboarding_conversation:x").set("type", "onboarding_conversation").set("metadata", "{}").save() }
        SchemaBootstrap.bootstrap(db) // a second bootstrap over existing data
        val old = db.query("sql", "SELECT FROM Source WHERE uid = 'old-1'").use { it.next().toElement().asVertex() }
        assertEquals("onboarding_conversation", old.get("type"))
        assertNull(old.get("contentHash"))
        store.ingest(ana, doc)
        assertEquals(1, store.sources(ana).size)
        assertEquals(2, db.countType("Source", true))
    }

    // ── Items from a source ──────────────────────────────────────────────────

    private val items = listOf(
        SourceItemDraft(ItemKind.FACT, "Kitchen budget is 12,000 euros", "The kitchen budget is 12,000 euros.", chunk = 0, claimant = "Ana Silva"),
        SourceItemDraft(ItemKind.EVENT, "Cabinets arrive", "The cabinets arrive on 14 May.", chunk = 1),
        SourceItemDraft(ItemKind.QUESTION, "Who signs the contract?", "Who signs the contract?", chunk = 2, claimant = "Ana Silva"),
    )

    @Test
    fun `committed items trace to their chunk and location, with the writer and a separate claimant`() = runBlocking<Unit> {
        val report = commit.commit(ana, doc, items)
        assertTrue(report.sourceCreated)
        assertEquals(3, report.itemsCreated); assertEquals(0, report.itemsAlreadyThere)
        assertTrue(report.items.all { it.applied && it.created && it.linked })

        val fact = store.provenanceOf(ana, report.items[0].uid!!).single()
        assertEquals("section: Budget", fact.location)
        assertEquals("director", fact.writer)
        assertEquals("Ana Silva", fact.claimant)
        assertEquals(doc.locator, fact.sourceLocator); assertEquals("notion_page", fact.sourceType); assertEquals("full", fact.readMode)
        val event = store.provenanceOf(ana, report.items[1].uid!!).single()
        assertEquals("section: Schedule", event.location); assertNull(event.claimant)
        // The chunk text the item rests on can be read back.
        assertTrue(store.chunk(ana, fact.chunkUid)!!.text.contains("12,000 euros"))
        assertEquals(emptyList<ItemProvenance>(), store.provenanceOf(ben, report.items[0].uid!!), "another user cannot trace it")
    }

    @Test
    fun `committing the same content and items again adds nothing`() = runBlocking<Unit> {
        val first = commit.commit(ana, doc, items)
        val before = counts()
        val again = commit.commit(ana, doc.copy(readAt = 99L), items)
        assertFalse(again.sourceCreated)
        assertEquals(0, again.chunksCreated); assertEquals(0, again.itemsCreated); assertEquals(3, again.itemsAlreadyThere)
        assertEquals(first.items.map { it.uid }, again.items.map { it.uid })
        assertEquals(before, counts())
        assertEquals(before["User"], counts()["User"])
        val cycleSeq = db.query("sql", "SELECT lastCycleSeq FROM User WHERE email = :e", mapOf("e" to ana)).use { it.next().toMap()["lastCycleSeq"] }
        commit.commit(ana, doc, items)
        assertEquals(cycleSeq, db.query("sql", "SELECT lastCycleSeq FROM User WHERE email = :e", mapOf("e" to ana)).use { it.next().toMap()["lastCycleSeq"] }, "a repeat commit allocates no cycle")
    }

    @Test
    fun `a quote that is not in the chunk, or a chunk that is not the user's, writes no item and no edge`() = runBlocking<Unit> {
        val ingest = store.ingest(ana, doc)
        val chunk = store.chunks(ana, ingest.source.uid).first()
        val structure = ArcadeHorizonStructureStore(db)
        val applier = HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store)
        val before = counts()
        val bad = listOf(
            RecordItem(ItemKind.FACT, quote = "The budget is 99 euros.", title = "Wrong", chunkUid = chunk.uid),
            RecordItem(ItemKind.FACT, quote = "", title = "Empty quote", chunkUid = chunk.uid),
            RecordItem(ItemKind.FACT, quote = "The kitchen budget is 12,000 euros.", title = "Not yours", chunkUid = chunk.uid),
        )
        val own = applier.apply(ana, 1L, "bad-1", bad.take(2))
        assertTrue(own.none { it.applied })
        assertTrue(applier.apply(ben, 1L, "bad-2", bad.drop(2)).none { it.applied }, "another user's chunk")
        assertTrue(HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), null).apply(ana, 1L, "bad-3", bad.drop(2)).none { it.applied }, "no source store")
        assertEquals(before, counts())
    }

    @Test
    fun `a task taken from a second source that is already on the list gains the new chunk instead of a duplicate`() = runBlocking<Unit> {
        val a = commit.commit(ana, doc.copy(locator = "page-a", text = "# Plan\nBook the venue for the conference by Friday."), listOf(SourceItemDraft(ItemKind.TASK, "Book the conference venue", "Book the venue for the conference by Friday.", 0)))
        val b = commit.commit(ana, email.copy(locator = "<m9@x>", threadId = "t", text = "Reminder: book the venue for the conference by Friday please."), listOf(SourceItemDraft(ItemKind.TASK, "Book the venue for the conference", "book the venue for the conference by Friday", 0, claimant = "Ana")))
        assertEquals(1, db.countType("Task", true))
        assertEquals(a.items[0].uid, b.items[0].uid)
        assertEquals(2, store.provenanceOf(ana, a.items[0].uid!!).size)
    }

    // ── Logs ─────────────────────────────────────────────────────────────────

    @Test
    fun `no memory text reaches the logs`() = runBlocking<Unit> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        try {
            val secret = "Zebra-${UUID.randomUUID()}"
            val input = doc.copy(title = "title $secret", text = "# Budget\nThe $secret budget is 12,000 euros.\n\n# Other\nMore $secret.")
            commit.commit(ana, input, listOf(SourceItemDraft(ItemKind.FACT, "label $secret", "The $secret budget is 12,000 euros.", 0, claimant = "who $secret")))
            commit.commit(ana, input, listOf(SourceItemDraft(ItemKind.FACT, "label $secret", "The $secret budget is 12,000 euros.", 0, claimant = "who $secret")))
            assertTrue(appender.list.isNotEmpty())
            assertTrue(appender.list.none { it.formattedMessage.contains(secret) || it.throwableProxy?.message?.contains(secret) == true }, "memory text in a log line")
        } finally {
            root.detachAppender(appender)
        }
    }
}
