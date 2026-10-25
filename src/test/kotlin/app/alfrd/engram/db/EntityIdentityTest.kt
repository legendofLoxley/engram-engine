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

    /** A Concept as extraction leaves one: resolved, then linked from a claim of [owner], which is what makes it visible to them. */
    private fun thing(name: String, kind: String, parent: String?, issuer: String? = null, owner: String = ana): ConceptRef {
        val c = concepts(owner).resolve(owner, name, kind, parent, issuer)
        claimAbout(c.uid, owner)
        return c
    }

    @Test
    fun `a school with no parent printed takes the Source's issuer, and says where the parent came from`() {
        val clark = thing("Clark", "school", null, "Portland Public Schools")
        assertEquals("Portland Public Schools", clark.parent)
        assertEquals("issuer", concepts().candidates(ana, "Clark").single().parentOrigin)
        val stated = thing("Lincoln", "school", "Salem-Keizer", "Portland Public Schools")
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
        val clark = thing("Clark", "school", null, "PPS")
        val elem = thing("Clark Elementary", "school", null, "PPS")
        assertNotEquals(clark.uid, elem.uid)
        val twins = concepts().candidates(ana, "Clark").flatMap { it.possiblySame }
        assertTrue(twins.any { it.uid == elem.uid && it.reason.startsWith("same name apart from a designator") }, twins.toString())
        // A lookup by either name finds both.
        assertEquals(setOf("Clark", "Clark Elementary"), concepts().candidates(ana, "Clark Elementary").map { it.name }.toSet())

        val bev = thing("Beverly Cleary", "school", null, "PPS")
        val bev68 = thing("Beverly Cleary 6–8", "school", null, "PPS")
        assertNotEquals(bev.uid, bev68.uid, "a grade band marks a different program")
        assertTrue(concepts().candidates(ana, "Beverly Cleary").flatMap { it.possiblySame }.any { it.uid == bev68.uid })
    }

    @Test
    fun `one name as a school and as a person stays two things`() {
        val school = thing("Clark", "school", null, "PPS")
        val person = thing("Clark", "person", null, "PPS")
        assertNotEquals(school.uid, person.uid)
        assertTrue(concepts().candidates(ana, "Clark").flatMap { it.possiblySame }.any { it.reason.contains("kinds differ") })
    }

    @Test
    fun `a short form a page defines groups the two names when read, and turning the entry off splits them with nothing rewritten`() {
        val entry = registry.add(ana, "Portland Public Schools", "PPS", "document", "src-1", "pdf p. 1")!!
        val long = concepts().resolve(ana, "Portland Public Schools", "public body", null)
        val short = concepts().resolve(ana, "PPS", "public body", null)
        assertNotEquals(long.uid, short.uid, "each name is filed as printed: the alias list never merges in the graph")
        val a = claimAbout(long.uid); val b = claimAbout(short.uid)
        val one = concepts().entities(ana, registry).single()
        assertEquals("Portland Public Schools", one.name); assertEquals(listOf("PPS"), one.aliases); assertEquals(2, one.claims)
        assertEquals(setOf(long.uid, short.uid), concepts().candidates(ana, "PPS", registry).map { it.uid }.toSet(), "a lookup by either name finds both")
        val linksBefore = db.query("sql", "SELECT count(*) AS n FROM ABOUT").use { it.next().getProperty<Number>("n").toLong() }

        assertTrue(registry.setActive(ana, entry.uid, false))
        val split = concepts().entities(ana, registry)
        assertEquals(setOf("Portland Public Schools", "PPS"), split.map { it.name }.toSet(), "split at once, without a new run")
        assertTrue(split.all { it.claims == 1 }, "no claim lost or moved")
        assertEquals(listOf(short.uid), concepts().candidates(ana, "PPS", registry).map { it.uid }, "the lookup no longer reaches the long name")
        assertEquals(linksBefore, db.query("sql", "SELECT count(*) AS n FROM ABOUT").use { it.next().getProperty<Number>("n").toLong() }, "no link was written or removed")
        assertEquals(listOf(long.uid), concepts().about(ana, a).map { it.uid }); assertEquals(listOf(short.uid), concepts().about(ana, b).map { it.uid })
        assertEquals(short.uid, concepts().resolve(ana, "PPS", "public body", null).uid, "a later run files PPS where it always was")
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
        val clark = thing("Clark", "school", null, "PPS")
        val elem = thing("Clark Elementary", "school", null, "PPS")
        claimAbout(clark.uid)
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

        // Each name keeps its own Concept; the entities view shows one thing with both names.
        assertEquals(elem.uid, concepts().resolve(ana, "Clark Elementary", "school", null, issuer = "PPS").uid)
        val entities = concepts().entities(ana, registry)
        val one = entities.single { it.name == "Clark" }
        assertEquals(listOf("Clark Elementary"), one.aliases); assertEquals(3, one.claims); assertEquals(2, one.conceptUids.size)
    }

    @Test
    fun `different keeps the pair apart and out of the list, and the entities view still shows them as two things`() {
        val clark = thing("Clark", "school", null, "PPS")
        val elem = thing("Clark Elementary", "school", null, "PPS")
        assertTrue(concepts().decide(ana, clark.uid, elem.uid, same = false, registry = registry))
        assertEquals(emptyList<ConceptStore.NameCheck>(), concepts().nameChecks(ana))
        assertEquals(emptyList<AliasEntry>(), registry.list(ana))
        val entities = concepts().entities(ana, registry)
        assertEquals(setOf("Clark", "Clark Elementary"), entities.map { it.name }.toSet())
        assertTrue(entities.all { it.mayBeSameAs.isEmpty() }, "a decided pair is no longer offered as possibly the same")
    }

    @Test
    fun `an unresolved pair shows in the entities view as may-be-the-same, and turning the alias off splits the entity`() {
        val clark = thing("Clark", "school", null, "PPS")
        val elem = thing("Clark Elementary", "school", null, "PPS")
        val before = concepts().entities(ana, registry)
        assertEquals(2, before.size)
        assertTrue(before.single { it.name == "Clark" }.mayBeSameAs.any { it.name == "Clark Elementary" })
        val entry = registry.add(ana, "Clark", "Clark Elementary", "confirmed")!!
        assertEquals(1, concepts().entities(ana, registry).size)
        registry.setActive(ana, entry.uid, false)
        assertEquals(2, concepts().entities(ana, registry).size, "nothing was lost: the two Concepts were never merged in the graph")
    }

    @Test
    fun `another owner's Concepts are never seen, offered as a twin, or counted`() {
        val theirs = thing("Clark", "school", null, "PPS", owner = bea)
        val mine = thing("Clark", "school", "Salem-Keizer", "PPS")
        assertNotEquals(theirs.uid, mine.uid)
        val cands = concepts().candidates(ana, "Clark")
        assertEquals(listOf(mine.uid), cands.map { it.uid }, "only this owner's evidence is listed")
        assertTrue(cands.single().possiblySame.isEmpty(), "bea's Clark is not offered as a twin")
        assertEquals(emptyList<ConceptStore.NameCheck>(), concepts().nameChecks(ana))
        assertEquals(listOf(theirs.uid), concepts(bea).candidates(bea, "Clark").map { it.uid })
        // A shared Concept carries the other owner's links too: they stay out of this owner's view of it.
        val shared0 = thing("Clark", "school", null, "PPS", owner = bea)
        assertEquals(theirs.uid, shared0.uid)
        val third = thing("Clark Elementary", "school", null, "PPS", owner = bea)
        assertEquals(listOf(theirs.uid, third.uid).toSet(), concepts(bea).candidates(bea, "Clark").map { it.uid }.toSet())
        val meAgain = thing("Clark", "school", null, "PPS")
        assertEquals(theirs.uid, meAgain.uid, "the same name, kind and parent is the one shared Concept")
        assertTrue(concepts().candidates(ana, "Clark").flatMap { c -> c.possiblySame }.none { it.uid == third.uid }, "bea's twin link is not shown to ana")
        // The same name, kind and parent is one shared Concept, seen by each owner through their own links.
        val shared = thing("Lincoln", "school", null, "PPS", owner = bea)
        assertEquals(shared.uid, thing("Lincoln", "school", null, "PPS").uid)
        assertEquals(1, concepts().candidates(ana, "Lincoln").single().claims)
    }

    @Test
    fun `resolver plus keeps its own answer for a name already listed`() {
        val base = ClaimantResolver(mapOf("Portland Public Schools" to listOf("PPS")))
        val merged = base.plus(mapOf("Pacific Pottery Society" to listOf("PPS", "Pottery Society")))
        assertEquals("Portland Public Schools", merged.canonical("PPS"))
        assertEquals("Pacific Pottery Society", merged.canonical("Pottery Society"))
    }

    @Test
    fun `a Concept found rather than made is still linked to its namesake once this owner has evidence about both`() {
        // An earlier owner made "SCENARIO A" with no parent; this owner's first claim is about "SCENARIO A" under the issuer (made new, no
        // evidence yet of this owner's on the other); the next is about the one with no parent.
        thing("SCENARIO A", "scenario", null, owner = bea)
        val underIssuer = thing("SCENARIO A", "scenario", "Portland Public Schools", owner = ana)
        val bare = thing("SCENARIO A", "scenario", null, owner = ana)
        assertNotEquals(underIssuer.uid, bare.uid.takeIf { it == underIssuer.uid } ?: "")
        val pairs = concepts(ana).nameChecks(ana)
        assertEquals(1, pairs.size, pairs.toString())
        assertEquals(setOf(underIssuer.uid, bare.uid), setOf(pairs[0].a.uid, pairs[0].b.uid))
        assertTrue(pairs[0].reason.startsWith("same name; parents differ"), pairs[0].reason)
        // Linking again adds nothing, and the other owner has no pair of their own.
        claimAbout(bare.uid, ana); claimAbout(underIssuer.uid, ana)
        assertEquals(1, concepts(ana).nameChecks(ana).size)
        assertEquals(0, concepts(bea).nameChecks(bea).size)
        // Never merged: two Concepts remain.
        assertEquals(2, concepts(ana).candidates(ana, "SCENARIO A").size)
    }
}
