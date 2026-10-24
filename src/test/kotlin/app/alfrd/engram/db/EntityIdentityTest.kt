package app.alfrd.engram.db

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.claims.ClaimantResolver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID

/** Entity identity from code and recorded decisions: parent from the Source's issuer, kind by rule, near-name links, the alias list, name checks. */
class EntityIdentityTest {

    private lateinit var manager: DatabaseManager
    private lateinit var tempDir: java.io.File
    private lateinit var db: com.arcadedb.database.Database
    private lateinit var registry: AliasRegistry
    private val ana = "debug+identity-a@test.alfrd.internal"
    private val bea = "debug+identity-b@test.alfrd.internal"

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("engram-identity-test-").toFile()
        manager = DatabaseManager(tempDir.resolve("db").toString())
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        DebugConverseService.ensureSyntheticUser(db, ana); DebugConverseService.ensureSyntheticUser(db, bea)
        registry = AliasRegistry(db)
    }

    @AfterEach
    fun tearDown() { manager.close(); tempDir.deleteRecursively() }

    private fun concepts(owner: String = ana) = ConceptStore(db, registry.resolverFor(owner))

    /** A claim of [owner] that is about [conceptUid], as extraction leaves one. */
    private fun claimAbout(conceptUid: String, owner: String = ana, source: String = "src-1"): String {
        val uid = UUID.randomUUID().toString()
        db.transaction {
            db.newVertex("Phrase").apply { set("uid", uid); set("text", "claim $uid"); set("ownerEmail", owner); set("claimSourceUid", source); save() }
        }
        concepts(owner).linkAbout(owner, uid, conceptUid, "run")
        return uid
    }

    @Test
    fun `a school with no parent printed takes the Source's issuer, and says where the parent came from`() {
        val clark = concepts().resolve(ana, "Clark", "school", null, issuer = "Portland Public Schools")
        assertEquals("Portland Public Schools", clark.parent)
        assertEquals("issuer", concepts().candidates(ana, "Clark").single().parentOrigin)
        val stated = concepts().resolve(ana, "Lincoln", "school", "Salem-Keizer", issuer = "Portland Public Schools")
        assertEquals("Salem-Keizer", stated.parent, "a parent the page names is never replaced by the issuer")
        assertEquals("stated", concepts().candidates(ana, "Lincoln").single().parentOrigin)
        assertNull(concepts().resolve(ana, "Portland Public Schools", "public body", null, issuer = "Portland Public Schools").parent, "a body is not its own parent")
        assertNull(concepts().resolve(ana, "SCENARIO A", "scenario", null, issuer = "Portland Public Schools").parent)
    }

    @Test
    fun `the same Source read twice gives the same things`() {
        val a = concepts().resolve(ana, "Clark", "school", null, issuer = "PPS")
        val b = concepts().resolve(ana, "Clark", "school", null, issuer = "PPS")
        assertEquals(a.uid, b.uid)
        assertEquals(1L, db.query("sql", "SELECT count(*) AS n FROM Concept").use { it.next().getProperty<Number>("n").toLong() })
    }

    @Test
    fun `a grade band is filed as a grade band, never as a school, whatever the model said`() {
        val band = concepts().resolve(ana, "K–5", "school", null, issuer = "PPS")
        assertEquals("grade band", band.kind)
        assertNull(band.parent, "a grade band takes no issuer parent")
    }

    @Test
    fun `names that differ only by a designator are linked as possibly the same and never merged`() {
        val clark = concepts().resolve(ana, "Clark", "school", null, issuer = "PPS")
        val elem = concepts().resolve(ana, "Clark Elementary", "school", null, issuer = "PPS")
        assertNotEquals(clark.uid, elem.uid)
        val twins = concepts().candidates(ana, "Clark").flatMap { it.possiblySame }
        assertTrue(twins.any { it.uid == elem.uid && it.reason.startsWith("same name apart from a designator") }, twins.toString())
        // A lookup by either name finds both.
        assertEquals(setOf("Clark", "Clark Elementary"), concepts().candidates(ana, "Clark Elementary").map { it.name }.toSet())

        val bev = concepts().resolve(ana, "Beverly Cleary", "school", null, issuer = "PPS")
        val bev68 = concepts().resolve(ana, "Beverly Cleary 6–8", "school", null, issuer = "PPS")
        assertNotEquals(bev.uid, bev68.uid, "a grade band marks a different program")
        assertTrue(concepts().candidates(ana, "Beverly Cleary").flatMap { it.possiblySame }.any { it.uid == bev68.uid })
    }

    @Test
    fun `one name as a school and as a person stays two things`() {
        val school = concepts().resolve(ana, "Clark", "school", null, issuer = "PPS")
        val person = concepts().resolve(ana, "Clark", "person", null, issuer = "PPS")
        assertNotEquals(school.uid, person.uid)
        assertTrue(concepts().candidates(ana, "Clark").flatMap { it.possiblySame }.any { it.reason.contains("kinds differ") })
    }

    @Test
    fun `a short form a page defines merges new claims into the long name, and turning the entry off splits them again`() {
        val entry = registry.add(ana, "Portland Public Schools", "PPS", "document", "src-1", "pdf p. 1")!!
        val long = concepts().resolve(ana, "Portland Public Schools", "public body", null)
        val short = concepts().resolve(ana, "PPS", "public body", null)
        assertEquals(long.uid, short.uid, "the alias resolves to the one Concept")
        assertTrue(registry.setActive(ana, entry.uid, false))
        val split = concepts().resolve(ana, "PPS", "public body", null)
        assertNotEquals(long.uid, split.uid, "with the entry off, the next run files PPS apart")
        assertTrue(concepts().candidates(ana, "PPS", registry).isNotEmpty())
    }

    @Test
    fun `the alias list is the owner's own`() {
        registry.add(ana, "Portland Public Schools", "PPS", "document")
        val mine = concepts(ana).resolve(ana, "Portland Public Schools", "public body", null)
        val theirs = concepts(bea).resolve(bea, "PPS", "public body", null)
        assertNotEquals(mine.uid, theirs.uid, "another owner's list does not apply to this one")
        assertEquals(emptyList<AliasEntry>(), registry.list(bea))
    }

    @Test
    fun `a conflicting entry is passed over, so a stored list can never fail a run`() {
        registry.add(ana, "Portland Public Schools", "PPS", "document")
        registry.add(ana, "Pacific Pottery Society", "PPS", "confirmed")
        val resolver = registry.resolverFor(ana)
        assertEquals("Portland Public Schools", resolver.canonical("PPS"), "the older entry wins")
    }

    @Test
    fun `an undecided pair is listed once, a decision is reused, and same records an alias with its origin`() {
        val clark = concepts().resolve(ana, "Clark", "school", null, issuer = "PPS")
        val elem = concepts().resolve(ana, "Clark Elementary", "school", null, issuer = "PPS")
        claimAbout(clark.uid); claimAbout(clark.uid); claimAbout(elem.uid)
        val checks = concepts().nameChecks(ana)
        assertEquals(1, checks.size)
        assertEquals(setOf("Clark", "Clark Elementary"), setOf(checks[0].a.name, checks[0].b.name))
        assertEquals(setOf(2, 1), setOf(checks[0].aClaims, checks[0].bClaims))
        assertEquals(emptyList<ConceptStore.NameCheck>(), concepts(bea).nameChecks(bea), "another owner sees none of these")

        assertTrue(concepts().decide(ana, clark.uid, elem.uid, same = true, registry = registry))
        assertEquals(emptyList<ConceptStore.NameCheck>(), concepts().nameChecks(ana), "an answered pair is not asked again")
        val entry = registry.list(ana).single()
        assertEquals("Clark", entry.canonical, "the name with more claims is the canonical one"); assertEquals("Clark Elementary", entry.alias); assertEquals("confirmed", entry.origin)
        assertFalse(concepts().decide(ana, clark.uid, UUID.randomUUID().toString(), same = true, registry = registry), "not a pair waiting for an answer")

        // New claims about either name now go to one Concept, and the entities view shows one thing with both names.
        assertEquals(clark.uid, concepts().resolve(ana, "Clark Elementary", "school", null, issuer = "PPS").uid)
        val entities = concepts().entities(ana, registry)
        val one = entities.single { it.name == "Clark" }
        assertEquals(listOf("Clark Elementary"), one.aliases); assertEquals(3, one.claims); assertEquals(2, one.conceptUids.size)
    }

    @Test
    fun `different keeps the pair apart and out of the list, and the entities view still shows them as two things`() {
        val clark = concepts().resolve(ana, "Clark", "school", null, issuer = "PPS")
        val elem = concepts().resolve(ana, "Clark Elementary", "school", null, issuer = "PPS")
        claimAbout(clark.uid); claimAbout(elem.uid)
        assertTrue(concepts().decide(ana, clark.uid, elem.uid, same = false, registry = registry))
        assertEquals(emptyList<ConceptStore.NameCheck>(), concepts().nameChecks(ana))
        assertEquals(emptyList<AliasEntry>(), registry.list(ana))
        val entities = concepts().entities(ana, registry)
        assertEquals(setOf("Clark", "Clark Elementary"), entities.map { it.name }.toSet())
        assertTrue(entities.all { it.mayBeSameAs.isEmpty() }, "a decided pair is no longer offered as possibly the same")
    }

    @Test
    fun `an unresolved pair shows in the entities view as may-be-the-same, and turning the alias off splits the entity`() {
        val clark = concepts().resolve(ana, "Clark", "school", null, issuer = "PPS")
        val elem = concepts().resolve(ana, "Clark Elementary", "school", null, issuer = "PPS")
        claimAbout(clark.uid); claimAbout(elem.uid)
        val before = concepts().entities(ana, registry)
        assertEquals(2, before.size)
        assertTrue(before.single { it.name == "Clark" }.mayBeSameAs.any { it.name == "Clark Elementary" })
        val entry = registry.add(ana, "Clark", "Clark Elementary", "confirmed")!!
        assertEquals(1, concepts().entities(ana, registry).size)
        registry.setActive(ana, entry.uid, false)
        assertEquals(2, concepts().entities(ana, registry).size, "nothing was lost: the two Concepts were never merged in the graph")
    }

    @Test
    fun `resolver plus keeps its own answer for a name already listed`() {
        val base = ClaimantResolver(mapOf("Portland Public Schools" to listOf("PPS")))
        val merged = base.plus(mapOf("Pacific Pottery Society" to listOf("PPS", "Pottery Society")))
        assertEquals("Portland Public Schools", merged.canonical("PPS"))
        assertEquals("Pacific Pottery Society", merged.canonical("Pottery Society"))
    }
}
