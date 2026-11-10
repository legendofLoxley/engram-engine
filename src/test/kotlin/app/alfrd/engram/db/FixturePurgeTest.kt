package app.alfrd.engram.db

import com.arcadedb.database.Database
import com.arcadedb.graph.MutableVertex
import com.arcadedb.graph.Vertex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * The offline fixture-purge tool, on a graph with: several listed users, an unlisted user, a pps-style user, the protected owner, a Source
 * trusted by a listed and an unlisted user, a Phrase asserted from two users, a shared Concept, shared seed content, and ChangeRecords owned
 * by a listed user and by the protected owner (with the engine's delete veto registered, as it is in the running engine).
 */
class FixturePurgeTest {
    private lateinit var manager: DatabaseManager
    private lateinit var db: Database
    private lateinit var path: String

    private fun email(id: String) = "debug+$id@test.alfrd.internal"
    private val a = email("fx-a")
    private val b = email("fx-b")
    private val c = email("fx-c-nothing")   // listed, but only a bare User
    private val u = email("fx-unlisted")
    private val pps = email("pps-keep")
    private val jacob = email("owner-jacob")
    private val listed get() = listOf(a, b, c)

    private fun v(type: String, vararg props: Pair<String, Any?>): MutableVertex = db.newVertex(type).apply {
        props.forEach { (k, x) -> if (x != null) set(k, x) }
        save()
    }
    private fun user(e: String, tier: Int = -1, username: String = "debug-synthetic") = v("User", "uid" to "uid-$e", "email" to e, "username" to username, "tier" to tier)
    private fun edge(type: String, from: Vertex, to: Vertex, vararg props: Pair<String, Any?>) {
        val ed = from.modify().newEdge(type, to, false)
        props.forEach { (k, x) -> if (x != null) ed.set(k, x) }
        ed.save()
    }
    private fun item(type: String, uid: String, owner: String?, state: String? = null) =
        v(type, "uid" to uid, "text" to uid, "ownerEmail" to owner, "state" to state, "createdAt" to 1L, "visibility" to "private")
    private fun count(type: String) = db.countType(type, false)
    private fun exists(type: String, uid: String) = db.query("sql", "SELECT FROM $type WHERE uid = :u", mapOf("u" to uid)).use { it.hasNext() }

    private lateinit var cShared: MutableVertex
    private lateinit var cOnlyA: MutableVertex

    @BeforeEach
    fun setUp() {
        path = "./data/test-fixture-purge-${System.nanoTime()}"
        manager = DatabaseManager(path)
        db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)   // also registers the ChangeRecord delete veto in this process
        db.transaction {
            val ua = user(a); val ub = user(b); user(c); val uu = user(u); val up = user(pps); val uj = user(jacob)
            val srcA = v("Source", "uid" to "src-a", "name" to "a conv", "type" to "conversation")
            val srcB = v("Source", "uid" to "src-b", "name" to "b conv", "type" to "conversation")
            val srcAB = v("Source", "uid" to "src-ab", "name" to "a and b", "type" to "conversation")
            val srcShared = v("Source", "uid" to "src-shared", "name" to "a and unlisted", "type" to "conversation")
            val srcU = v("Source", "uid" to "src-u", "name" to "u", "type" to "conversation")
            val srcPps = v("Source", "uid" to "src-pps", "name" to "pps", "type" to "conversation")
            val srcJ = v("Source", "uid" to "src-j", "name" to "jacob", "type" to "conversation")
            val seed = v("Source", "uid" to "src-seed", "name" to "seed", "type" to "seed")
            edge("TRUSTS", ua, srcA); edge("TRUSTS", ub, srcB); edge("TRUSTS", ua, srcAB); edge("TRUSTS", ub, srcAB)
            edge("TRUSTS", ua, srcShared); edge("TRUSTS", uu, srcShared); edge("TRUSTS", uu, srcU); edge("TRUSTS", up, srcPps); edge("TRUSTS", uj, srcJ)
            listOf(ua, ub, uu, up, uj).forEach { edge("TRUSTS", it, seed) }

            // items
            val phA = item("Phrase", "ph-a", a); val phANull = item("Phrase", "ph-a-null", null); val taskA = item("Task", "task-a", a, "open")
            val phB = item("Phrase", "ph-b", b); val qAB = item("Question", "q-ab", null, "open")
            val phTwice = item("Phrase", "ph-twice", a); val phU = item("Phrase", "ph-u", u); val phUInShared = item("Phrase", "ph-u-in-shared", u)
            val phPps = item("Phrase", "ph-pps", pps); val phJ = item("Phrase", "ph-j", jacob); val taskJ = item("Task", "task-j", jacob, "open")
            val seedPhrase = item("Phrase", "seed-phrase", null)
            srcA.let { edge("ASSERTS", it, phA); edge("ASSERTS", it, phANull); edge("ASSERTS", it, taskA); edge("ASSERTS", it, phTwice) }
            edge("ASSERTS", srcB, phB); edge("ASSERTS", srcAB, qAB); edge("ASSERTS", srcAB, item("Phrase", "ph-ab", a))
            edge("ASSERTS", srcU, phU); edge("ASSERTS", srcU, phTwice)            // asserted from two users
            edge("ASSERTS", srcShared, phUInShared)
            edge("ASSERTS", srcPps, phPps); edge("ASSERTS", srcJ, phJ); edge("ASSERTS", srcJ, taskJ)
            edge("ASSERTS", seed, seedPhrase)                                     // asserted by a Source several users trust (A3i)

            // chunks and a passage
            val chA = v("Chunk", "uid" to "ch-a", "ownerEmail" to a, "sourceUid" to "src-a", "text" to "x"); edge("CONTAINS", srcA, chA)
            val chB = v("Chunk", "uid" to "ch-b", "ownerEmail" to b, "sourceUid" to "src-b", "text" to "x"); edge("CONTAINS", srcB, chB)
            val chShared = v("Chunk", "uid" to "ch-shared", "ownerEmail" to a, "sourceUid" to "src-shared", "text" to "x"); edge("CONTAINS", srcShared, chShared)
            val chPps = v("Chunk", "uid" to "ch-pps", "ownerEmail" to pps, "sourceUid" to "src-pps", "text" to "x"); edge("CONTAINS", srcPps, chPps)
            val chJ = v("Chunk", "uid" to "ch-j", "ownerEmail" to jacob, "sourceUid" to "src-j", "text" to "x"); edge("CONTAINS", srcJ, chJ)
            val passage = v("Phrase", "uid" to "passage-a", "text" to "p", "role" to "passage", "ownerEmail" to a, "createdAt" to 1L)
            edge("QUOTES", phA, chA, "ownerEmail" to a); edge("QUOTES", phPps, chPps, "ownerEmail" to pps)
            edge("QUOTES", passage, chA, "ownerEmail" to a)

            // concepts: one shared with another owner, one reached only from the listed user
            cShared = v("Concept", "uid" to "concept-shared", "name" to "Shared", "type" to "org")
            cOnlyA = v("Concept", "uid" to "concept-only-a", "name" to "Only A", "type" to "org")
            edge("ABOUT", phA, cShared, "ownerEmail" to a); edge("ABOUT", phU, cShared, "ownerEmail" to u); edge("ABOUT", phA, cOnlyA, "ownerEmail" to a)
            edge("ABOUT", phPps, cShared, "ownerEmail" to pps)
            edge("CONFIDENT_IN", ua, cOnlyA, "score" to 0.5)

            // keyed rows
            for ((e, tag) in listOf(a to "a", b to "b", u to "u", pps to "pps", jacob to "j")) {
                v("Utterance", "uid" to "utt-$tag", "userId" to e, "sessionId" to "s", "role" to "user", "text" to "t", "turnIndex" to 0)
                v("ProcessedRequest", "requestId" to "req-$tag", "userEmail" to e, "createdAt" to 1L)
                v("EntityAlias", "uid" to "alias-$tag", "ownerEmail" to e, "alias" to tag, "aliasKey" to tag, "canonical" to tag)
                v("ExtractionRun", "uid" to "run-$tag", "ownerEmail" to e, "runId" to "r-$tag")
                v("ClaimDisagreement", "uid" to "dis-$tag", "ownerEmail" to e, "dkey" to tag)
            }
            v("UserScaffoldState", "userId" to a, "trustPhase" to "x"); v("UserScaffoldState", "userId" to jacob, "trustPhase" to "x")
            val rp = v("ResponsePhrase", "uid" to "rp-1", "text" to "hi")
            edge("SELECTED", ua, rp, "userId" to a); edge("OUTCOME", ua, rp, "userId" to a); edge("SELECTED", uj, rp, "userId" to jacob)

            // change records: one owned by a listed user (on its own task), one by the protected owner
            for ((t, owner) in listOf(taskA to a, taskJ to jacob)) {
                val rec = v("ChangeRecord", "uid" to "cr-${owner.take(9)}", "ownerEmail" to owner, "targetUid" to t.getString("uid"), "targetType" to "Task",
                    "family" to "change", "field" to "state", "oldValue" to "open", "newValue" to "done", "changedAt" to 5L, "cause" to "t", "actor" to owner)
                edge("HAS_CHANGE", t, rec)
            }
        }
    }

    @AfterEach
    fun tearDown() {
        manager.close()
        File(path).deleteRecursively()
    }

    private fun snapshot(vararg types: String) = types.associateWith { count(it) }
    private val everyType = arrayOf("User", "Source", "Chunk", "Phrase", "Task", "Question", "Concept", "Utterance", "ProcessedRequest", "EntityAlias", "ExtractionRun",
        "ClaimDisagreement", "UserScaffoldState", "ChangeRecord", "ResponsePhrase", "ASSERTS", "ABOUT", "QUOTES", "TRUSTS", "CONTAINS", "SELECTED", "OUTCOME", "CONFIDENT_IN", "HAS_CHANGE")

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `any bad entry refuses the whole run and names every problem`() {
        for ((bad, why) in listOf(jacob to "protected", pps to "pps", "someone@example.com" to "not a debug+", "debug+x@example.com" to "not a debug+")) {
            val ex = assertThrows<FixturePurge.Refusal> { FixturePurge.validate(db, listOf(a, bad)) }
            assertTrue(ex.problems.any { bad in it }, "$why: ${ex.problems}")
        }
        // a user that exists but is not a plain fixture user
        db.transaction { user(email("real-person"), tier = 2, username = "jane"); user(email("wrong-tier"), tier = 0) }
        val ex = assertThrows<FixturePurge.Refusal> { FixturePurge.validate(db, listOf(a, email("real-person"), email("wrong-tier"))) }
        assertTrue(ex.problems.any { "username 'jane'" in it }, ex.problems.toString())
        assertTrue(ex.problems.any { "tier 2" in it } && ex.problems.any { "tier 0" in it }, ex.problems.toString())
        assertTrue(ex.problems.none { a in it }, "the good entry is not blamed")
        assertThrows<FixturePurge.Refusal> { FixturePurge.validate(db, emptyList()) }
        FixturePurge.validate(db, listed)   // the good list passes
    }

    @Test
    fun `the Director tick identity and the configured protected set are refused too`() {
        val prot = FixturePurge.protectedFor(mapOf("DIRECTOR_TICK_IDENTITY" to "fx-b", "PURGE_PROTECTED_IDENTITIES" to "fx-c-nothing"))
        val ex = assertThrows<FixturePurge.Refusal> { FixturePurge.validate(db, listed, prot) }
        assertTrue(ex.problems.any { b in it } && ex.problems.any { c in it } && ex.problems.none { a in it }, ex.problems.toString())
        assertTrue(FixturePurge.protectedFor(emptyMap()).isProtected(jacob), "owner-jacob is protected even with an empty environment")
    }

    @Test
    fun `parseList reads ids and emails, skips comments and blanks`() {
        assertEquals(listOf(a, b), FixturePurge.parseList(listOf("# fixtures", "", " fx-b ", "DEBUG+FX-A@test.alfrd.internal  # trailing", "fx-b")))
    }

    // ── plan ─────────────────────────────────────────────────────────────────

    @Test
    fun `plan changes nothing and lists exactly what would go, per user`() {
        val before = snapshot(*everyType)
        val plan = FixturePurge.plan(db, listed)
        assertEquals(before, snapshot(*everyType), "a plan is read-only")
        val pa = plan.users.getValue(a); val pb = plan.users.getValue(b)
        // src-ab is trusted by two listed users only and asserts an ownerless phrase: that is the shape of shared seed content (A3i), so it stays
        assertEquals(1, pa.count("Source")); assertEquals(1, pb.count("Source"))
        assertEquals(2, plan.totals.getValue("Source"), "src-a and src-b; src-shared is also trusted by an unlisted user, src-ab holds a shared-seed-shaped phrase")
        assertEquals(2, plan.totals.getValue("Chunk"), "ch-a and ch-b; ch-shared sits in a kept Source")
        assertEquals(4, plan.totals.getValue("Phrase"), "ph-a, ph-a-null, ph-b and the passage; ph-twice is also asserted from outside")
        assertEquals(1, plan.totals.getValue("Task")); assertNull(plan.totals["Question"], "q-ab is shared-seed shaped")
        assertEquals(3, plan.totals.getValue("User")); assertEquals(1, plan.totals.getValue("UserScaffoldState"))
        for (t in listOf("Utterance", "ProcessedRequest", "EntityAlias", "ExtractionRun", "ClaimDisagreement")) assertEquals(2, plan.totals.getValue(t), t)
        assertEquals(1, plan.totals.getValue("ChangeRecord"))
        assertFalse(plan.users.getValue(c).isEmpty, "a listed user with no data still has its User row")
        assertTrue(plan.blocking.isEmpty() && plan.unhandled.isEmpty(), plan.render())
        // kept and reported
        val keptReasons = plan.kept.map { "${it.type}:${it.id}:${it.reason}" }
        assertTrue(keptReasons.any { it.startsWith("Source:src-shared:also trusted outside") }, keptReasons.toString())
        assertTrue(keptReasons.any { it.startsWith("Phrase:ph-twice:also asserted by a Source that is kept") }, keptReasons.toString())
        assertTrue(keptReasons.any { it.startsWith("Chunk:ch-shared:sits in a Source that is kept") }, keptReasons.toString())
        assertTrue(keptReasons.any { it.startsWith("Source:src-ab:asserts shared seed phrases") }, keptReasons.toString())
        assertTrue(keptReasons.any { it.startsWith("Phrase:ph-ab:also asserted by a Source that is kept") }, keptReasons.toString())
        assertTrue(plan.kept.none { it.id == "seed-phrase" }, "the seed phrase belongs to no listed user's data, so it is not even a candidate")
        assertTrue(plan.render().contains("| **total** |"))
    }

    // ── apply ────────────────────────────────────────────────────────────────

    @Test
    fun `apply removes the listed data and nothing else`() {
        val plan = FixturePurge.plan(db, listed)
        val before = snapshot(*everyType)
        val result = FixturePurge.apply(db, plan, listed)
        assertTrue(result.failed.isEmpty(), result.toString())

        // listed data is gone
        for (uid in listOf("ph-a", "ph-a-null", "task-a", "ph-b", "passage-a")) assertFalse(exists("Phrase", uid) || exists("Task", uid) || exists("Question", uid), uid)
        for (uid in listOf("src-a", "src-b")) assertFalse(exists("Source", uid), uid)
        for (uid in listOf("ch-a", "ch-b")) assertFalse(exists("Chunk", uid), uid)
        for (t in listOf("Utterance", "ProcessedRequest", "EntityAlias", "ExtractionRun", "ClaimDisagreement")) {
            assertEquals(0, db.query("sql", "SELECT FROM $t WHERE (uid IS NOT NULL AND uid LIKE '%-a') OR (uid IS NOT NULL AND uid LIKE '%-b')").use { rs -> rs.stream().count().toInt() }, t)
        }
        assertEquals(0L, db.query("sql", "SELECT FROM User WHERE email IN [:a, :b, :c]", mapOf("a" to a, "b" to b, "c" to c)).use { it.stream().count() })
        assertEquals(1L, count("UserScaffoldState"), "only the protected owner's scaffold state is left")
        assertEquals(1L, count("ChangeRecord"), "the listed user's record went through the veto, the protected owner's stayed")
        assertTrue(exists("ChangeRecord", "cr-${jacob.take(9)}"))

        // unlisted, shared, pps and protected data is untouched
        for (uid in listOf("ph-u", "ph-u-in-shared", "ph-pps", "ph-j", "task-j", "seed-phrase", "ph-twice")) assertTrue(exists("Phrase", uid) || exists("Task", uid), uid)
        for (uid in listOf("src-shared", "src-u", "src-pps", "src-j", "src-seed", "src-ab")) assertTrue(exists("Source", uid), uid)
        assertTrue(exists("Question", "q-ab") && exists("Phrase", "ph-ab"), "a seed-shaped phrase and what sits beside it in its Source stay")
        for (uid in listOf("ch-shared", "ch-pps", "ch-j")) assertTrue(exists("Chunk", uid), uid)
        for (tag in listOf("u", "pps", "j")) {
            for ((t, uid) in listOf("Utterance" to "utt-$tag", "EntityAlias" to "alias-$tag", "ExtractionRun" to "run-$tag", "ClaimDisagreement" to "dis-$tag")) assertTrue(exists(t, uid), uid)
        }
        assertEquals(3L, count("ProcessedRequest"), "u, pps, jacob")
        assertEquals(3L, count("User"), "unlisted, pps, protected owner")
        assertEquals(before.getValue("ResponsePhrase"), count("ResponsePhrase"))

        // concepts are never deleted
        assertEquals(before.getValue("Concept"), count("Concept"))
        assertTrue(exists("Concept", "concept-shared") && exists("Concept", "concept-only-a"))
        val cr = FixturePurge.conceptReport(db, plan, listed)
        assertEquals(listOf("concept-only-a"), cr.touchedNoAbout, "reached only from the listed user, now with no ABOUT edge")
        assertEquals(listOf("concept-shared"), cr.touchedShared, "still linked from other owners")
        assertEquals(1, cr.noAboutAll)
        // the other owners' edges survive; nothing dangles
        assertEquals(2L, count("ABOUT"), "u's and pps's edges to the shared Concept")
        val audit = SchemaAudit(db).run()
        assertEquals(0L, audit.finding("A8").count, "no dangling edge: " + audit.finding("A8"))
    }

    @Test
    fun `a second apply deletes nothing more`() {
        FixturePurge.apply(db, FixturePurge.plan(db, listed), listed)
        val after = snapshot(*everyType)
        val plan2 = FixturePurge.plan(db, listed)
        assertEquals(0, plan2.totalRows, plan2.render())
        assertTrue(plan2.users.values.none { it.exists })
        val again = FixturePurge.apply(db, plan2, listed)
        assertTrue(again.failed.isEmpty() && again.users.all { it.deleted.isEmpty() })
        assertEquals(after, snapshot(*everyType))
    }

    @Test
    fun `a ChangeRecord is deleted through the veto bypass only for a listed owner, and the veto is back afterwards`() {
        FixturePurge.apply(db, FixturePurge.plan(db, listOf(a)), listOf(a))
        assertFalse(exists("ChangeRecord", "cr-${a.take(9)}"))
        // the veto is registered again: a delete of the protected owner's record is still refused
        val rec = db.query("sql", "SELECT FROM ChangeRecord WHERE ownerEmail = :o", mapOf("o" to jacob)).use { it.next().toElement() }
        runCatching { db.transaction { rec.delete() } }
        assertTrue(exists("ChangeRecord", "cr-${jacob.take(9)}"), "the append-only veto still protects every other record")
    }

    @Test
    fun `a blocking boundary refuses the run before anything is deleted`() {
        // a Source only the listed user trusts asserts a Phrase that belongs to someone else
        db.transaction {
            val srcA = db.query("sql", "SELECT FROM Source WHERE uid = 'src-a'").use { it.next().toElement().asVertex() }
            val foreign = item("Phrase", "foreign-in-a", u)
            edge("ASSERTS", srcA, foreign)
        }
        val before = snapshot(*everyType)
        val plan = FixturePurge.plan(db, listed)
        assertEquals(1, plan.blocking.size, plan.render())
        val ex = assertThrows<FixturePurge.Refusal> { FixturePurge.apply(db, plan, listed) }
        assertTrue(ex.problems.any { "foreign" !in it && u in it }, ex.problems.toString())
        assertEquals(before, snapshot(*everyType), "nothing changed")
    }

    @Test
    fun `a failure leaves that user untouched and the others deleted`() {
        // a's Phrase carries a userId stamp for someone outside the list: the last-moment check aborts a's transaction
        db.transaction {
            db.query("sql", "SELECT FROM Phrase WHERE uid = 'ph-a'").use { it.next().toElement().asVertex().modify().set("userId", u).save() }
        }
        val plan = FixturePurge.plan(db, listed)
        val result = FixturePurge.apply(db, plan, listed)
        assertEquals(listOf(a), result.failed.map { it.email })
        assertTrue(exists("Phrase", "ph-a") && exists("Source", "src-a") && exists("Task", "task-a"), "a is whole")
        assertEquals(1, db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to a)).use { it.stream().count() }.toInt())
        assertFalse(exists("Phrase", "ph-b"), "b was deleted")
        assertFalse(db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to b)).use { it.hasNext() })
    }

    @Test
    fun `a row of a type the tool does not know that carries a listed owner key blocks the run`() {
        db.schema.createVertexType("Surprise").also { it.createProperty("ownerEmail", com.arcadedb.schema.Type.STRING) }
        db.transaction { v("Surprise", "ownerEmail" to a) }
        val plan = FixturePurge.plan(db, listed)
        assertEquals(listOf("Surprise: 1"), plan.unhandled)
        assertThrows<FixturePurge.Refusal> { FixturePurge.apply(db, plan, listed) }
        assertNotNull(db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to a)).use { it.next() })
    }
}
