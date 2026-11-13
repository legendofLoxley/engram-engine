package app.alfrd.engram.db

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.NON_ITEM_VERTEX_TYPES
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.pipeline.horizon.SourceItemDraft
import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.util.UUID

/**
 * Email in the graph, first slice: the Email subtype of Source, Email Address nodes, SENT_BY / SENT_TO, the unique (owner, type, locator) index, and every
 * scan that goes by exact type (SchemaAudit, FixturePurge, HorizonSelector.isItem, SchemaConstraints).
 */
class EmailGraphTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private lateinit var commit: SourceCommitService
    private val ana = "debug+email-a@test.alfrd.internal"
    private val ben = "debug+email-b@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-email-test-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana)
        DebugConverseService.ensureSyntheticUser(db, ben)
        store = SourceStore(db)
        val structure = ArcadeHorizonStructureStore(db)
        commit = SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db))
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private val facts = EmailGraph.Facts(
        subject = "Re: Kitchen plan", sentAt = 1_700_000_000_000L, gmailMessageId = "18c0ffee", gmailThreadId = "thread-77",
        from = EmailGraph.Party("Marta@Example.org", "Marta Ruiz"),
        to = listOf(EmailGraph.Party("ana@example.org", "Ana"), EmailGraph.Party("  <Sam@Example.com> ")),
        cc = listOf(EmailGraph.Party("cc@example.org")), bcc = listOf(EmailGraph.Party("secret@example.org")),
    )
    private val mail = SourceInput(
        type = "email_message", locator = "<m1@mail.example.org>", title = "Re: Kitchen plan", threadId = "thread-77",
        text = "Contract goes to Marta, she signs Friday.\n\nOn Tue, 3 Mar 2026 at 10:14, Ana <ana@example.org> wrote:\n> Who signs the contract?",
        email = facts,
    )
    private val doc = SourceInput(type = "notion_page", locator = "https://www.notion.so/abc123", title = "Plan", text = "# Budget\nThe budget is 12,000 euros.\n\n# Open\nWho signs?")

    private fun count(type: String, poly: Boolean = false) = db.countType(type, poly)
    private fun rejected(body: () -> Unit): Boolean = try { body(); false } catch (_: Exception) { true }
    private fun everything() = listOf("Source", "Email", "EmailAddress", "Chunk", "Phrase", "User").associateWith { count(it) } +
        listOf("CONTAINS", "TRUSTS", "SENT_BY", "SENT_TO", "QUOTES", "ASSERTS").associateWith { count(it) }

    // ── the Email ────────────────────────────────────────────────────────────

    @Test
    fun `an email message is stored as an Email, a subtype of Source, and keeps its type string`() {
        val r = store.ingest(ana, mail)
        assertTrue(r.created)
        assertTrue(db.schema.getType(EmailGraph.EMAIL).isSubTypeOf("Source"))
        assertEquals(1, count("Email")); assertEquals(0, count("Source"), "no plain Source row was written for it")
        assertEquals(1, count("Source", poly = true), "a query on Source finds it")
        assertEquals("email_message", r.source.type)
        assertEquals(listOf(r.source.uid), store.sources(ana).map { it.uid })
        val v = emailVertex(r.source.uid)
        assertEquals("Re: Kitchen plan", v.get("subject")); assertEquals(1_700_000_000_000L, (v.get("sentAt") as Number).toLong())
        assertEquals("18c0ffee", v.get("gmailMessageId")); assertEquals("thread-77", v.get("gmailThreadId")); assertEquals("thread-77", v.get("threadId"))
        assertEquals(1, count("TRUSTS"), "the owner trusts it like any Source")
    }

    @Test
    fun `an email body is chunked with the quoted reply stripped, and a quote cites the email`() {
        val report = runBlocking {
            commit.commit(
                ana, mail,
                listOf(SourceItemDraft(ItemKind.TASK, "Marta signs the contract on Friday", "Contract goes to Marta, she signs Friday.", 0, claimant = "Marta")),
            )
        }
        assertTrue(report.sourceCreated)
        val chunks = store.chunks(ana, report.sourceUid)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.contains("she signs Friday") && !chunks[0].text.contains("Who signs the contract"), "quoted history is not evidence")
        val item = report.items.single()
        val provenance = store.provenanceOf(ana, item.uid!!)
        assertEquals(1, provenance.size)
        assertEquals(report.sourceUid, provenance[0].sourceUid); assertEquals("email_message", provenance[0].sourceType); assertEquals("<m1@mail.example.org>", provenance[0].sourceLocator)
        assertEquals(chunks[0].uid, provenance[0].chunkUid)
    }

    @Test
    fun `an email sent without facts is still an Email, with no address nodes`() {
        val r = store.ingest(ana, mail.copy(locator = "<bare@mail.example.org>", email = null))
        assertEquals(1, count("Email")); assertEquals(0, count("EmailAddress")); assertEquals(0, count("SENT_BY") + count("SENT_TO"))
        assertEquals(null, store.emailView(ana, r.source.uid)?.from)
    }

    // ── address nodes and edges ─────────────────────────────────────────────

    @Test
    fun `the sender and each recipient become one address node, with SENT_BY and SENT_TO edges that name the kind`() {
        val uid = store.ingest(ana, mail).source.uid
        val view = store.emailView(ana, uid)!!
        assertEquals("marta@example.org", view.from); assertEquals("Marta Ruiz", view.fromName)
        assertEquals(listOf("ana@example.org", "sam@example.com"), view.to)
        assertEquals(listOf("cc@example.org"), view.cc); assertEquals(listOf("secret@example.org"), view.bcc)
        assertEquals(5, count("EmailAddress")); assertEquals(1, count("SENT_BY")); assertEquals(4, count("SENT_TO"))
        val marta = db.query("sql", "SELECT FROM EmailAddress WHERE ownerEmail = :o AND address = 'marta@example.org'", mapOf("o" to ana)).use { it.next().toElement().asVertex() }
        assertEquals("example.org", marta.get("domain")); assertEquals(ana, marta.get("ownerEmail"))
        assertEquals(1, marta.getEdges(Vertex.DIRECTION.IN, "SENT_BY").count(), "the emails of an address are readable by incoming traversal")
    }

    @Test
    fun `an address seen again is the same node, and another owner has a node of their own`() {
        store.ingest(ana, mail)
        store.ingest(ana, mail.copy(locator = "<m2@mail.example.org>", text = "Another message entirely about the cabinets arriving.", email = facts.copy(to = listOf(EmailGraph.Party("ANA@example.org")))))
        assertEquals(5, count("EmailAddress"), "marta and ana are reused; nothing new")
        assertEquals(2, count("SENT_BY"))
        store.ingest(ben, mail.copy(locator = "<m3@mail.example.org>", text = "Ben got a message from Marta about the contract.", email = facts))
        assertEquals(10, count("EmailAddress"), "addresses are per owner")
    }

    @Test
    fun `an address repeated within a kind is one edge, in two kinds is two, and a header that is not an address is not recorded`() {
        val f = facts.copy(
            to = listOf(EmailGraph.Party("a@x.org"), EmailGraph.Party("A@X.ORG"), EmailGraph.Party("undisclosed-recipients:;"), EmailGraph.Party("not an address")),
            cc = listOf(EmailGraph.Party("a@x.org")), bcc = emptyList(),
        )
        val uid = store.ingest(ana, mail.copy(email = f)).source.uid
        val view = store.emailView(ana, uid)!!
        assertEquals(listOf("a@x.org"), view.to); assertEquals(listOf("a@x.org"), view.cc)
        assertEquals(2, count("SENT_TO")); assertEquals(2, count("EmailAddress"), "the sender and a@x.org")
    }

    @Test
    fun `email facts belong to an email source, and a message is stored with a bounded number of recipients`() {
        assertThrows<IllegalArgumentException> { store.ingest(ana, doc.copy(email = facts)) }
        val many = facts.copy(to = (1..EmailGraph.MAX_RECIPIENTS + 1).map { EmailGraph.Party("r$it@x.org") })
        assertThrows<IllegalArgumentException> { store.ingest(ana, mail.copy(email = many)) }
        assertEquals(0, count("Email")); assertEquals(0, count("EmailAddress"))
    }

    // ── one Message-ID, once ────────────────────────────────────────────────

    @Test
    fun `ingesting the same Message-ID twice stores one Email and writes nothing the second time`() {
        val first = store.ingest(ana, mail)
        val before = everything()
        val second = store.ingest(ana, mail)
        assertFalse(second.created); assertEquals(first.source.uid, second.source.uid); assertFalse(second.contentChanged)
        assertEquals(before, everything())
        assertEquals(1, count("Email"))
    }

    @Test
    fun `the same Message-ID with a changed body is still one Email, the first text stays, and the result says the text differed`() {
        val first = store.ingest(ana, mail)
        val before = everything()
        val again = store.ingest(ana, mail.copy(text = "A different body, edited after it was sent, saying something else."))
        assertFalse(again.created); assertTrue(again.contentChanged); assertEquals(first.source.uid, again.source.uid)
        assertEquals(first.source.contentHash, again.source.contentHash, "the stored hash is the first text's")
        assertEquals(before, everything())
    }

    @Test
    fun `two owners can each hold the same Message-ID`() {
        store.ingest(ana, mail); store.ingest(ben, mail)
        assertEquals(2, count("Email"))
    }

    @Test
    fun `the database itself refuses a second Email with the same owner, type and locator`() {
        store.ingest(ana, mail)
        fun second(owner: String, locator: String) = db.transaction {
            db.newVertex(EmailGraph.EMAIL).apply {
                set("uid", UUID.randomUUID().toString()); set("ownerEmail", owner); set("type", "email_message"); set("locator", locator); set("contentHash", "h"); save()
            }
        }
        assertTrue(rejected { second(ana, mail.locator) }, "the index, not only the code, stops it")
        assertFalse(rejected { second(ben, mail.locator) }, "another owner is not a clash")
        assertFalse(rejected { second(ana, "<other@x>") }, "another locator is not a clash")
    }

    @Test
    fun `the unique index is on Email only, so a changed Notion page is still a second Source and the older one is superseded`() {
        val v1 = store.ingest(ana, doc)
        val v2 = store.ingest(ana, doc.copy(text = doc.text + "\n\n# New\nA section added later."))
        assertTrue(v1.created && v2.created); assertNotEquals(v1.source.uid, v2.source.uid)
        assertEquals(2, count("Source")); assertEquals(0, count("Email"))
        assertFalse(store.ingest(ana, doc).created, "the same text is still found by its hash")
        assertEquals(1, store.markCurrent(ana, v2.source.uid))
    }

    @Test
    fun `a message stored as a plain Source before the subtype existed is found by its hash and no Email is made beside it`() {
        db.transaction {
            db.newVertex("Source").apply {
                set("uid", UUID.randomUUID().toString()); set("ownerEmail", ana); set("type", "email_message"); set("locator", mail.locator); set("name", "old")
                set("contentHash", SourceChunker.contentHash(mail.text)); set("readAt", 1L); set("readMode", "full"); save()
            }
        }
        val r = store.ingest(ana, mail)
        assertFalse(r.created); assertEquals(0, count("Email"))
    }

    // ── purge ────────────────────────────────────────────────────────────────

    @Test
    fun `purging a test user removes their emails, address nodes, edges and chunks, and leaves another user's alone`() {
        store.ingest(ana, mail); store.ingest(ana, mail.copy(locator = "<m2@x>", text = "Second message from Marta about the schedule.")); store.ingest(ana, doc)
        store.ingest(ben, mail)
        val benBefore = db.query("sql", "SELECT FROM Email WHERE ownerEmail = :o", mapOf("o" to ben)).use { it.stream().count() }
        val plan = FixturePurge.plan(db, listOf(ana))
        assertTrue(plan.unhandled.isEmpty() && plan.blocking.isEmpty(), "Email and EmailAddress are types the tool knows:\n" + plan.render())
        assertEquals(2, plan.totals.getValue("Email")); assertEquals(1, plan.totals.getValue("Source")); assertEquals(5, plan.totals.getValue("EmailAddress"))
        val result = FixturePurge.apply(db, plan, listOf(ana))
        assertTrue(result.failed.isEmpty(), result.failed.toString())
        listOf("Email", "EmailAddress", "Source").forEach { t ->
            assertEquals(0, db.query("sql", "SELECT FROM $t WHERE ownerEmail = :o", mapOf("o" to ana)).use { it.stream().count() }, "$t of the purged user")
        }
        assertEquals(0, db.query("sql", "SELECT FROM Chunk WHERE ownerEmail = :o", mapOf("o" to ana)).use { it.stream().count() })
        assertEquals(benBefore, db.query("sql", "SELECT FROM Email WHERE ownerEmail = :o", mapOf("o" to ben)).use { it.stream().count() })
        assertEquals(5, count("EmailAddress")); assertEquals(1, count("SENT_BY")); assertEquals(4, count("SENT_TO"), "only ben's edges are left")
    }

    @Test
    fun `a Source subtype the purge has not heard of is still planned with the sources, never left behind or reported unhandled`() {
        db.transaction { db.schema.createVertexType("OddMail").addSuperType("Source") }
        db.transaction { db.newVertex("OddMail").apply { set("uid", "odd-1"); set("ownerEmail", ana); set("type", "x"); save() } }
        val plan = FixturePurge.plan(db, listOf(ana))
        assertEquals(1, plan.totals["OddMail"] ?: 0)
        assertTrue(plan.unhandled.isEmpty(), plan.render())
    }

    // ── the exact-type scans ─────────────────────────────────────────────────

    @Test
    fun `no Source subtype or address node can read as an item`() {
        val subtypes = db.schema.types.filter { it.name != "Source" && it.isSubTypeOf("Source") }.map { it.name }
        assertTrue(subtypes.contains("Email"))
        subtypes.forEach { assertTrue(it in NON_ITEM_VERTEX_TYPES, "$it is a Source subtype and must be listed in NON_ITEM_VERTEX_TYPES") }
        assertTrue("EmailAddress" in NON_ITEM_VERTEX_TYPES && "Source" in NON_ITEM_VERTEX_TYPES)
    }

    @Test
    fun `the audit reads an Email as a Source row, finds it clean, and sees a uid shared with a plain Source`() {
        store.ingest(ana, mail); store.ingest(ana, doc)
        val clean = SchemaAudit(db).run()
        assertEquals(0L, clean.finding("A1").count, clean.toMarkdown()); assertEquals(0L, clean.finding("A2").count, clean.toMarkdown())
        assertEquals(1L, clean.totals["vertices:Email"]); assertEquals(5L, clean.totals["vertices:EmailAddress"])
        val shared = db.query("sql", "SELECT uid FROM Source WHERE type = 'notion_page'").use { it.next().getProperty<String>("uid") }
        db.transaction { db.newVertex(EmailGraph.EMAIL).apply { set("uid", shared); set("ownerEmail", ana); set("type", "email_message"); set("locator", "<dup@x>"); save() } }
        val after = SchemaAudit(db).run()
        assertEquals(1L, after.finding("A2").count, "an Email and a Source sharing a uid is one duplicate (they share one uid index)")
    }

    // ── constraints ──────────────────────────────────────────────────────────

    @Test
    fun `the constraint rules cover the new types, and the rule count is the old 46 plus four`() {
        val outcome = SchemaConstraints.apply(db)
        assertTrue(outcome.skipped.isEmpty(), outcome.skipped.toString())
        val all = outcome.applied + outcome.alreadyInPlace
        assertTrue(listOf("uid-mandatory:EmailAddress", "uid-unique:EmailAddress", "identity-unique:Email", "identity-unique:EmailAddress").all { it in all }, all.toString())
        assertTrue(all.none { it == "uid-unique:Email" || it == "uid-mandatory:Email" }, "Email inherits Source's uid rules, as Task inherits Phrase's")
        assertEquals(50, all.size, "46 before this slice; EmailAddress adds two uid rules and the two identity indexes add two")
        assertEquals(all.size, (SchemaConstraints.apply(db).let { it.applied + it.alreadyInPlace }).size, "idempotent")
    }

    @Test
    fun `with the constraints on, an Email must have a uid and cannot share one with a Source`() {
        store.ingest(ana, doc)
        SchemaConstraints.apply(db)
        val sourceUid = db.query("sql", "SELECT uid FROM Source").use { it.next().getProperty<String>("uid") }
        assertTrue(rejected { db.transaction { db.newVertex(EmailGraph.EMAIL).apply { set("ownerEmail", ana); set("type", "email_message"); set("locator", "<nouid@x>"); save() } } }, "no uid")
        assertTrue(rejected { db.transaction { db.newVertex(EmailGraph.EMAIL).apply { set("uid", sourceUid); set("ownerEmail", ana); set("type", "email_message"); set("locator", "<dup@x>"); save() } } }, "uid of a Source")
        assertEquals(0, count("Email"))
    }

    @Test
    fun `the identity rule skips with a warning, and applies nothing, when the rows already break it`() {
        // A graph from before the unique index: drop it, write a duplicate, and the rule must refuse rather than fail.
        val idx = db.schema.getType(EmailGraph.EMAIL).getAllIndexes(false).first { it.propertyNames == listOf("ownerEmail", "type", "locator") }
        db.schema.dropIndex(idx.name)
        db.schema.getType(EmailGraph.EMAIL).getOrCreateTypeIndex(com.arcadedb.schema.Schema.INDEX_TYPE.LSM_TREE, false, "ownerEmail", "type", "locator")
        repeat(2) { n ->
            db.transaction { db.newVertex(EmailGraph.EMAIL).apply { set("uid", "dup-$n"); set("ownerEmail", ana); set("type", "email_message"); set("locator", "<same@x>"); save() } }
        }
        val outcome = SchemaConstraints.apply(db)
        assertTrue("identity-unique:Email" in outcome.skipped, outcome.skipped.toString())
    }

    @Test
    fun `the schema has the Email index, the address index and the inherited Source indexes`() {
        val idx = db.schema.getType(EmailGraph.EMAIL).getAllIndexes(false)
        assertTrue(idx.any { it.isUnique && it.propertyNames == listOf("ownerEmail", "type", "locator") })
        assertTrue(db.schema.getType(EmailGraph.ADDRESS).getAllIndexes(false).any { it.isUnique && it.propertyNames == listOf("ownerEmail", "address") })
        assertTrue(db.schema.getType("Source").getAllIndexes(false).none { it.propertyNames == listOf("ownerEmail", "type", "locator") }, "Source itself has no such index")
        assertTrue(db.schema.existsType("SENT_BY") && db.schema.existsType("SENT_TO"))
    }

    private fun emailVertex(uid: String): Vertex =
        db.query("sql", "SELECT FROM Email WHERE uid = :u", mapOf("u" to uid)).use { it.next().toElement().asVertex() }
}
