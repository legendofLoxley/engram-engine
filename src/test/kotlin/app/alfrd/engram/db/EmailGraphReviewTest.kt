package app.alfrd.engram.db

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonBudget
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.pipeline.horizon.SourceItemDraft
import com.arcadedb.database.Database
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/** Review of the email slice: addresses as headers really print them, the recipient cap, a repeat with other text through the commit, and that nothing here reads as an item. */
class EmailGraphReviewTest {
    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: Database
    private lateinit var store: SourceStore
    private lateinit var commit: SourceCommitService
    private val ana = "debug+email-review-a@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-email-review-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana)
        store = SourceStore(db)
        val structure = ArcadeHorizonStructureStore(db)
        commit = SourceCommitService(store, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), store), ArcadeCycleSequencer(db))
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private fun count(type: String) = db.countType(type, false)
    private fun mail(locator: String = "<r1@x.org>", facts: EmailGraph.Facts? = EmailGraph.Facts(from = EmailGraph.Party("sender@x.org")), text: String = "The venue costs 3,000 euros and the deposit is due Friday.") =
        SourceInput("email_message", locator, "Subject", text, email = facts)
    private fun addresses(): List<String> = db.query("sql", "SELECT address FROM EmailAddress ORDER BY address").use { rs -> val out = mutableListOf<String>(); while (rs.hasNext()) out += rs.next().getProperty<String>("address"); out }

    // ── addresses ────────────────────────────────────────────────────────────

    @Test
    fun `addresses are normalized, and what is not an address is not recorded whatever it looks like`() {
        val parties = listOf(
            "José@Example.ORG", "  <UPPER@CASE.COM>  ", "a.b+tag@sub.example.co.uk", "x@localhost",
            "mailto:bad@x.org", "bad\u0000nul@x.org", "bad\nline@x.org", "two@@x.org", "@nolocal.org", "nodomain@", "a@b@c.org", "with space@x.org",
            "\"quoted\"@x.org", "list@x.org,other@x.org", "x".repeat(400) + "@x.org", "", "   ", "undisclosed-recipients:;",
        )
        val uid = store.ingest(ana, mail(facts = EmailGraph.Facts(to = parties.map { EmailGraph.Party(it) }))).source.uid
        assertEquals(listOf("a.b+tag@sub.example.co.uk", "josé@example.org", "upper@case.com", "x@localhost"), addresses())
        assertEquals(4, count("SENT_TO"))
        assertEquals(4, store.emailView(ana, uid)!!.to.size)
    }

    @Test
    fun `a display name is kept clean on its edge, once, and an address with no name has none`() {
        val uid = store.ingest(ana, mail(facts = EmailGraph.Facts(
            from = EmailGraph.Party("Boss@X.org", "  Dr.\tAna \n  “The   Boss”\u0000  "), to = listOf(EmailGraph.Party("quiet@x.org"), EmailGraph.Party("long@x.org", "n".repeat(900))),
        ))).source.uid
        val view = store.emailView(ana, uid)!!
        assertEquals("Dr. Ana “The Boss”", view.fromName)
        val names = db.query("sql", "SELECT displayName FROM SENT_TO ORDER BY displayName").use { rs -> val out = mutableListOf<String?>(); while (rs.hasNext()) out += rs.next().getProperty<String?>("displayName"); out }
        assertEquals(listOf(null, "n".repeat(200)), names.sortedBy { it?.length ?: 0 })
        assertNull(EmailGraph.cleanName("  \n\t "))
    }

    @Test
    fun `duplicates inside one message collapse by kind and by case, and the sender can also be a recipient`() {
        val f = EmailGraph.Facts(
            from = EmailGraph.Party("me@x.org"),
            to = listOf(EmailGraph.Party("me@x.org"), EmailGraph.Party("ME@X.ORG"), EmailGraph.Party("you@x.org"), EmailGraph.Party("<you@x.org>")),
            cc = listOf(EmailGraph.Party("you@x.org")), bcc = listOf(EmailGraph.Party("you@x.org")),
        )
        store.ingest(ana, mail(facts = f))
        assertEquals(listOf("me@x.org", "you@x.org"), addresses())
        assertEquals(1, count("SENT_BY")); assertEquals(1 + 1 + 1 + 1, count("SENT_TO"), "me/to, you/to, you/cc, you/bcc")
    }

    @Test
    fun `exactly the recipient cap is stored and one more is refused with nothing written`() {
        fun rec(n: Int, p: String) = (1..n).map { EmailGraph.Party("$p$it@x.org") }
        store.ingest(ana, mail("<at-cap@x.org>", EmailGraph.Facts(to = rec(300, "t"), cc = rec(150, "c"), bcc = rec(50, "b"))))
        assertEquals(500, count("SENT_TO"))
        val emails = count("Email"); val nodes = count("EmailAddress"); val chunks = count("Chunk")
        assertThrows<IllegalArgumentException> { store.ingest(ana, mail("<over@x.org>", EmailGraph.Facts(to = rec(300, "t"), cc = rec(150, "c"), bcc = rec(51, "z")))) }
        assertEquals(listOf(emails, nodes, chunks), listOf(count("Email"), count("EmailAddress"), count("Chunk")), "the refused message left nothing behind")
        // The cap counts entries, not distinct addresses: a blast that repeats one address is as large as it looks.
        assertThrows<IllegalArgumentException> { store.ingest(ana, mail("<repeats@x.org>", EmailGraph.Facts(to = List(501) { EmailGraph.Party("same@x.org") }))) }
    }

    // ── a repeat with other text, through the commit ─────────────────────────

    private val drafts = listOf(SourceItemDraft(ItemKind.FACT, "Venue costs 3,000 euros", "The venue costs 3,000 euros", 0, null))

    @Test
    fun `the commit of a Message-ID already stored with other text writes nothing and says the text differed`() {
        val first = runBlocking { commit.commit(ana, mail(), drafts) }
        assertTrue(first.sourceCreated); assertFalse(first.sourceTextDiffered); assertEquals(1, first.itemsCreated)
        val before = listOf("Email", "EmailAddress", "Chunk", "Phrase", "Source", "QUOTES", "ASSERTS", "CONTAINS").associateWith { count(it) }
        val edited = mail(text = "The venue now costs 9,000 euros and the deposit is waived entirely.")
        val again = runBlocking { commit.commit(ana, edited, listOf(SourceItemDraft(ItemKind.FACT, "Venue costs 9,000 euros", "The venue now costs 9,000 euros", 0, null))) }
        assertTrue(again.sourceTextDiffered); assertFalse(again.sourceCreated); assertEquals(first.sourceUid, again.sourceUid)
        assertEquals(0, again.itemsCreated); assertTrue(again.items.isEmpty())
        assertEquals(before, before.keys.associateWith { count(it) }, "not one row, edge or item was added")
        val stored = store.chunks(ana, first.sourceUid).joinToString { it.text }
        assertTrue(stored.contains("3,000 euros") && !stored.contains("9,000"), "the first text stays the evidence")
        // The same text again is a plain repeat, not a difference.
        val same = runBlocking { commit.commit(ana, mail(), drafts) }
        assertFalse(same.sourceTextDiffered); assertFalse(same.sourceCreated); assertEquals(0, same.itemsCreated)
    }

    // ── nothing here is an item, and a quote cites the email ─────────────────

    @Test
    fun `the Horizon shows the item taken from an email and never the Email, its address nodes or its chunks`() {
        val facts = EmailGraph.Facts(subject = "UniqueSubjectLine", from = EmailGraph.Party("marta.unique@example.org", "Marta Unique"), to = listOf(EmailGraph.Party("ana.unique@example.org")))
        val r = runBlocking { commit.commit(ana, mail(facts = facts), drafts) }
        assertEquals(1, r.itemsCreated)
        val horizon = (runBlocking { ArcadeHorizonAssembler(db).assemble(ana, db.query("sql", "SELECT max(cycleSeq) AS m FROM ASSERTS").use { it.next().getProperty<Number>("m").toLong() }, budget = HorizonBudget(40, 0, false)) } as AssembleOutcome.Assembled).horizon
        val texts = horizon.items.map { it.text.text }
        assertTrue(texts.any { it.contains("Venue costs 3,000 euros") }, "the item is there: $texts")
        assertTrue(texts.none { it.contains("marta.unique") || it.contains("ana.unique") || it.contains("UniqueSubjectLine") || it.contains("The venue costs 3,000 euros and the deposit") }, "no Email, address or chunk read as an item: $texts")
        // The quote cites the Email: the item's provenance chain ends at the Email source.
        val source = store.sourceByUid(ana, r.sourceUid)!!
        assertEquals("email_message", source.type)
        assertEquals(1, db.query("sql", "SELECT count(*) AS n FROM QUOTES WHERE @in.uid IN (SELECT uid FROM Chunk WHERE ownerEmail = :o)", mapOf("o" to ana)).use { it.next().getProperty<Number>("n").toInt() })
        assertEquals(source.uid, store.chunks(ana, source.uid).first().sourceUid)
    }
}
