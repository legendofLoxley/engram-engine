package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HorizonStructureStoreTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var store: HorizonStructureStore

    @BeforeAll
    fun setUp() {
        dbManager = DatabaseManager("./data/test-horizon-structure-${System.currentTimeMillis()}")
        SchemaBootstrap.bootstrap(dbManager.getDatabase())
        store = ArcadeHorizonStructureStore(dbManager.getDatabase())
    }

    @AfterAll
    fun tearDown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-horizon-structure-") }
            ?.forEach { it.deleteRecursively() }
    }

    /** A fresh user with no Source yet — the store creates the conversational Source on first item, like ingest does. */
    private fun newUser(): String {
        val email = "structure-${UUID.randomUUID()}@example.test"
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
        return email
    }

    private fun count(sql: String, params: Map<String, Any?> = emptyMap()): Long =
        dbManager.getDatabase().query("sql", sql, params).use { rs -> (rs.next().toMap()["n"] as Number).toLong() }

    // ── Containers ────────────────────────────────────────────────────────────

    @Test
    fun `createContainer creates a project and returns the same one for the same normalized name`() = runBlocking {
        val user = newUser()
        val first = store.createContainer(user, 1, ContainerKind.PROJECT, "Demo  Project")
        assertNotNull(first)
        assertEquals("Demo  Project".trim(), first!!.name)
        assertEquals("demo project", first.normalizedName)
        assertEquals("active", first.status)

        val again = store.createContainer(user, 2, ContainerKind.PROJECT, "  demo project ")
        assertEquals(first.uid, again!!.uid)
        assertEquals(1, store.listContainers(user, ContainerKind.PROJECT).size)
    }

    @Test
    fun `a domain and a project may share a name, and two users may each have the same project name`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val domainA = store.createContainer(a, 1, ContainerKind.DOMAIN, "Work")!!
        val projectA = store.createContainer(a, 1, ContainerKind.PROJECT, "Work")!!
        val projectB = store.createContainer(b, 1, ContainerKind.PROJECT, "Work")!!
        assertNotEquals(domainA.uid, projectA.uid)
        assertNotEquals(projectA.uid, projectB.uid)
        assertEquals(2, store.listContainers(a).size)
        assertEquals(1, store.listContainers(b).size)
    }

    @Test
    fun `createContainer rejects blank names, unknown users, and a parent that is not the users own domain`() = runBlocking {
        val a = newUser()
        val b = newUser()
        assertNull(store.createContainer(a, 1, ContainerKind.PROJECT, "   "))
        assertNull(store.createContainer("nobody@example.test", 1, ContainerKind.PROJECT, "Demo"))
        val domainOfB = store.createContainer(b, 1, ContainerKind.DOMAIN, "B's domain")!!
        assertNull(store.createContainer(a, 1, ContainerKind.PROJECT, "Sneaky", parentDomainUid = domainOfB.uid))
        assertNull(store.createContainer(a, 1, ContainerKind.PROJECT, "Sneaky", parentDomainUid = UUID.randomUUID().toString()))
        assertEquals(0, store.listContainers(a).size, "a rejected create must leave nothing behind")
    }

    @Test
    fun `a project created under a domain is filed there and reported by projectsIn`() = runBlocking {
        val user = newUser()
        val domain = store.createContainer(user, 1, ContainerKind.DOMAIN, "Work")!!
        val project = store.createContainer(user, 1, ContainerKind.PROJECT, "Demo", parentDomainUid = domain.uid)!!
        assertEquals(domain.uid, project.parentUid)
        assertEquals(listOf(project.uid), store.projectsIn(user, domain.uid).map { it.uid })
        assertEquals(domain.uid, store.getContainer(user, project.uid)!!.parentUid)
    }

    @Test
    fun `containers are invisible to another user`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val project = store.createContainer(a, 1, ContainerKind.PROJECT, "Private plan")!!
        assertNull(store.getContainer(b, project.uid))
        assertTrue(store.listContainers(b).isEmpty())
        assertTrue(store.itemsIn(b, project.uid).isEmpty())
    }

    // ── Items ─────────────────────────────────────────────────────────────────

    @Test
    fun `each item kind is created as its own vertex type with its own fields and is owned like any claim`() = runBlocking {
        val user = newUser()
        val due = 1_900_000_000_000L
        val task = store.createItem(user, 1, ItemKind.TASK, "Send the invoice", dueAt = due)!!
        val event = store.createItem(user, 1, ItemKind.EVENT, "Sister's birthday", dueAt = due)!!
        val question = store.createItem(user, 1, ItemKind.QUESTION, "What have you been up to?", askedBy = "alfrd")!!
        val fact = store.createItem(user, 1, ItemKind.FACT, "My sister is allergic to peanuts")!!

        assertEquals(ItemKind.TASK, task.kind); assertEquals("open", task.status); assertEquals(due, task.dueAt)
        assertEquals(ItemKind.EVENT, event.kind); assertEquals("open", event.status); assertEquals(due, event.dueAt)
        assertEquals(ItemKind.QUESTION, question.kind); assertEquals("open", question.status)
        assertEquals(ItemKind.FACT, fact.kind); assertNull(fact.status); assertNull(fact.dueAt)

        val db = dbManager.getDatabase()
        assertEquals(1L, count("SELECT count(*) as n FROM Task WHERE uid = :u", mapOf("u" to task.uid)))
        assertEquals(1L, count("SELECT count(*) as n FROM Event WHERE uid = :u", mapOf("u" to event.uid)))
        assertEquals(1L, count("SELECT count(*) as n FROM Question WHERE uid = :u", mapOf("u" to question.uid)))
        // Every kind — including the subtypes — is a Phrase, and reachable through the user's own Source chain.
        for (uid in listOf(task.uid, event.uid, question.uid, fact.uid)) {
            assertEquals(1L, count("SELECT count(*) as n FROM Phrase WHERE uid = :u", mapOf("u" to uid)))
            assertTrue(HorizonOwnership.ownedAssertsEdges(db, user, uid).isNotEmpty(), "item $uid must be owned via TRUSTS -> Source -> ASSERTS")
        }
        val askedBy = db.query("sql", "SELECT askedBy FROM Question WHERE uid = :u", mapOf("u" to question.uid))
            .use { it.next().toMap()["askedBy"] as String }
        assertEquals("alfrd", askedBy)
    }

    @Test
    fun `sourceQuote is kept when the text is a label, and not stored when it would just repeat the text`() = runBlocking {
        val user = newUser()
        val labelled = store.createItem(user, 1, ItemKind.TASK, "Order the soil", sourceQuote = "I never got around to ordering the soil")!!
        val plain = store.createItem(user, 1, ItemKind.TASK, "book the room", sourceQuote = "book the room")!!
        val none = store.createItem(user, 1, ItemKind.FACT, "a fact")!!

        assertEquals("I never got around to ordering the soil", store.getItem(user, labelled.uid)!!.sourceQuote)
        assertNull(store.getItem(user, plain.uid)!!.sourceQuote)
        assertNull(store.getItem(user, none.uid)!!.sourceQuote)
    }

    @Test
    fun `an item is stamped with the cycle it was created in and shares the conversational Source`() = runBlocking {
        val user = newUser()
        val a = store.createItem(user, 7, ItemKind.FACT, "First claim")!!
        val b = store.createItem(user, 8, ItemKind.TASK, "Second claim")!!
        val db = dbManager.getDatabase()
        assertEquals(7L, (HorizonOwnership.ownedAssertsEdges(db, user, a.uid).single().get("cycleSeq") as Number).toLong())
        assertEquals(8L, (HorizonOwnership.ownedAssertsEdges(db, user, b.uid).single().get("cycleSeq") as Number).toLong())
        val sources = HorizonOwnership.trustedSourceUids(db, user)
        assertEquals(1, sources.size, "both items must hang off the one conversational Source")
    }

    @Test
    fun `createItem with the same opKey returns the first item instead of a duplicate, per user`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val first = store.createItem(a, 1, ItemKind.TASK, "Buy a gift", opKey = "req-1:0")!!
        val retry = store.createItem(a, 1, ItemKind.TASK, "Buy a gift", opKey = "req-1:0")!!
        assertEquals(first.uid, retry.uid)
        assertEquals(1L, count("SELECT count(*) as n FROM Task WHERE text = 'Buy a gift' AND opKey = :k", mapOf("k" to "$a|req-1:0")))

        val other = store.createItem(b, 1, ItemKind.TASK, "Buy a gift", opKey = "req-1:0")!!
        assertNotEquals(first.uid, other.uid, "the same opKey from a different user is a different item")
    }

    @Test
    fun `createItem rejects blank text, unknown users, and a project that is not the users own`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val projectOfB = store.createContainer(b, 1, ContainerKind.PROJECT, "B's project")!!
        assertNull(store.createItem(a, 1, ItemKind.FACT, "  "))
        assertNull(store.createItem("nobody@example.test", 1, ItemKind.FACT, "hello there friend"))
        assertNull(store.createItem(a, 1, ItemKind.TASK, "Sneak in", projectUid = projectOfB.uid))
        assertTrue(store.itemsIn(b, projectOfB.uid).isEmpty())
        assertEquals(0L, count("SELECT count(*) as n FROM Task WHERE text = 'Sneak in'"), "a rejected create must leave no orphan item")
    }

    @Test
    fun `an item created under a project is listed by itemsIn and knows its project`() = runBlocking {
        val user = newUser()
        val project = store.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val item = store.createItem(user, 2, ItemKind.TASK, "Book the room", projectUid = project.uid)!!
        assertEquals(project.uid, item.projectUid)
        assertEquals(listOf(item.uid), store.itemsIn(user, project.uid).map { it.uid })
        assertEquals(project.uid, store.getItem(user, item.uid)!!.projectUid)
    }

    @Test
    fun `an item is invisible to another user`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val item = store.createItem(a, 1, ItemKind.FACT, "A private thing")!!
        assertNull(store.getItem(b, item.uid))
    }

    // ── Filing ────────────────────────────────────────────────────────────────

    @Test
    fun `file moves an item between projects and a project between domains, and is a no-op for the parent it already has`() = runBlocking {
        val user = newUser()
        val p1 = store.createContainer(user, 1, ContainerKind.PROJECT, "One")!!
        val p2 = store.createContainer(user, 1, ContainerKind.PROJECT, "Two")!!
        val item = store.createItem(user, 1, ItemKind.FACT, "Something")!!

        assertTrue(store.file(user, 2, item.uid, p1.uid))
        assertTrue(store.file(user, 3, item.uid, p1.uid), "re-filing under the same parent succeeds")
        assertEquals(1L, count("SELECT count(*) as n FROM PART_OF WHERE @out.uid = :u", mapOf("u" to item.uid)))
        assertTrue(store.file(user, 4, item.uid, p2.uid))
        assertEquals(p2.uid, store.getItem(user, item.uid)!!.projectUid)
        assertTrue(store.itemsIn(user, p1.uid).isEmpty())
        assertEquals(1L, count("SELECT count(*) as n FROM PART_OF WHERE @out.uid = :u", mapOf("u" to item.uid)), "a move replaces the old edge, never stacks a second parent")

        val d1 = store.createContainer(user, 1, ContainerKind.DOMAIN, "Work")!!
        val d2 = store.createContainer(user, 1, ContainerKind.DOMAIN, "Home")!!
        assertTrue(store.file(user, 5, p1.uid, d1.uid))
        assertTrue(store.file(user, 6, p1.uid, d2.uid))
        assertEquals(d2.uid, store.getContainer(user, p1.uid)!!.parentUid)
        assertTrue(store.projectsIn(user, d1.uid).isEmpty())
    }

    @Test
    fun `file rejects pairings the hierarchy does not allow`() = runBlocking {
        val user = newUser()
        val domain = store.createContainer(user, 1, ContainerKind.DOMAIN, "Work")!!
        val project = store.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val other = store.createContainer(user, 1, ContainerKind.PROJECT, "Other")!!
        val item = store.createItem(user, 1, ItemKind.FACT, "Something")!!
        assertFalse(store.file(user, 2, item.uid, domain.uid), "an item files under a project, not a domain")
        assertFalse(store.file(user, 2, project.uid, other.uid), "a project files under a domain, not a project")
        assertFalse(store.file(user, 2, domain.uid, project.uid), "a domain has no parent")
        assertFalse(store.file(user, 2, item.uid, item.uid))
        assertNull(store.getItem(user, item.uid)!!.projectUid)
    }

    @Test
    fun `file cannot cross users in either direction`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val itemA = store.createItem(a, 1, ItemKind.FACT, "A's item")!!
        val projectA = store.createContainer(a, 1, ContainerKind.PROJECT, "A's project")!!
        val itemB = store.createItem(b, 1, ItemKind.FACT, "B's item")!!
        val projectB = store.createContainer(b, 1, ContainerKind.PROJECT, "B's project")!!
        assertFalse(store.file(b, 2, itemA.uid, projectB.uid), "B can't file A's item")
        assertFalse(store.file(b, 2, itemB.uid, projectA.uid), "B can't file into A's project")
        assertNull(store.getItem(a, itemA.uid)!!.projectUid)
        assertTrue(store.itemsIn(a, projectA.uid).isEmpty())
    }

    // ── Entity links ──────────────────────────────────────────────────────────

    @Test
    fun `linkAbout ties an item to a shared Concept found by normalized name and is idempotent`() = runBlocking {
        val user = newUser()
        val name = "Sister ${UUID.randomUUID()}"
        val one = store.createItem(user, 1, ItemKind.FACT, "My sister is allergic to peanuts")!!
        val two = store.createItem(user, 1, ItemKind.EVENT, "Sister's birthday is Friday")!!
        assertTrue(store.linkAbout(user, 1, one.uid, name))
        assertTrue(store.linkAbout(user, 2, one.uid, name.uppercase()), "the same concept under another casing is the same link")
        assertTrue(store.linkAbout(user, 2, two.uid, "  $name "))

        val norm = name.trim().lowercase()
        assertEquals(1L, count("SELECT count(*) as n FROM Concept WHERE normalizedName = :n", mapOf("n" to norm)), "one Concept hub, reused")
        assertEquals(2L, count("SELECT count(*) as n FROM ABOUT WHERE @in.normalizedName = :n", mapOf("n" to norm)), "one ABOUT edge per (item, concept)")
    }

    @Test
    fun `linkAbout rejects a blank concept and another users item`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val item = store.createItem(a, 1, ItemKind.FACT, "A's claim")!!
        assertFalse(store.linkAbout(a, 1, item.uid, "   "))
        assertFalse(store.linkAbout(b, 1, item.uid, "anything-${UUID.randomUUID()}"))
        assertEquals(0L, count("SELECT count(*) as n FROM ABOUT WHERE @out.uid = :u", mapOf("u" to item.uid)))
    }

    // ── Time and status ───────────────────────────────────────────────────────

    @Test
    fun `setDue sets and clears the time on a task, an event and a project, and refuses targets with no time`() = runBlocking {
        val user = newUser()
        val task = store.createItem(user, 1, ItemKind.TASK, "File taxes")!!
        val event = store.createItem(user, 1, ItemKind.EVENT, "Dinner")!!
        val project = store.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val fact = store.createItem(user, 1, ItemKind.FACT, "Just a fact")!!
        val question = store.createItem(user, 1, ItemKind.QUESTION, "Open question?")!!
        val domain = store.createContainer(user, 1, ContainerKind.DOMAIN, "Work")!!

        assertTrue(store.setDue(user, 2, task.uid, 1_000L))
        assertTrue(store.setDue(user, 2, task.uid, 1_000L), "setting the value already there succeeds")
        assertEquals(1_000L, store.getItem(user, task.uid)!!.dueAt)
        assertTrue(store.setDue(user, 3, event.uid, 2_000L))
        assertEquals(2_000L, store.getItem(user, event.uid)!!.dueAt)
        assertTrue(store.setDue(user, 3, project.uid, 3_000L))
        assertEquals(3_000L, store.getContainer(user, project.uid)!!.dueAt)

        assertTrue(store.setDue(user, 4, task.uid, null))
        assertNull(store.getItem(user, task.uid)!!.dueAt)

        assertFalse(store.setDue(user, 4, fact.uid, 1L))
        assertFalse(store.setDue(user, 4, question.uid, 1L))
        assertFalse(store.setDue(user, 4, domain.uid, 1L))
    }

    @Test
    fun `resolve closes and reopens a task, a question and a project, and refuses other targets`() = runBlocking {
        val user = newUser()
        val task = store.createItem(user, 1, ItemKind.TASK, "Send the gift")!!
        val question = store.createItem(user, 1, ItemKind.QUESTION, "What's new?")!!
        val project = store.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val fact = store.createItem(user, 1, ItemKind.FACT, "A fact")!!
        val event = store.createItem(user, 1, ItemKind.EVENT, "An event")!!
        val domain = store.createContainer(user, 1, ContainerKind.DOMAIN, "Work")!!

        assertTrue(store.resolve(user, 2, task.uid))
        assertTrue(store.resolve(user, 2, task.uid), "resolving twice is a no-op")
        assertEquals("resolved", store.getItem(user, task.uid)!!.status)
        assertTrue(store.resolve(user, 2, question.uid))
        assertEquals("resolved", store.getItem(user, question.uid)!!.status)
        assertTrue(store.resolve(user, 2, project.uid))
        assertEquals("done", store.getContainer(user, project.uid)!!.status)

        assertTrue(store.resolve(user, 3, task.uid, resolved = false))
        assertEquals("open", store.getItem(user, task.uid)!!.status)
        assertTrue(store.resolve(user, 3, project.uid, resolved = false))
        assertEquals("active", store.getContainer(user, project.uid)!!.status)

        assertFalse(store.resolve(user, 3, fact.uid))
        assertFalse(store.resolve(user, 3, domain.uid))
    }

    @Test
    fun `an event can be resolved and reopened, for a cancelled or past event`() = runBlocking {
        val user = newUser()
        val event = store.createItem(user, 1, ItemKind.EVENT, "Team offsite", dueAt = 5_000L)!!
        assertTrue(store.resolve(user, 2, event.uid))
        assertEquals("resolved", store.getItem(user, event.uid)!!.status)
        assertTrue(store.resolve(user, 3, event.uid, resolved = false))
        assertEquals("open", store.getItem(user, event.uid)!!.status)
    }

    @Test
    fun `setDue and resolve cannot touch another users items or containers`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val task = store.createItem(a, 1, ItemKind.TASK, "A's task", dueAt = 5_000L)!!
        val project = store.createContainer(a, 1, ContainerKind.PROJECT, "A's project")!!
        assertFalse(store.setDue(b, 2, task.uid, 9_000L))
        assertFalse(store.resolve(b, 2, task.uid))
        assertFalse(store.resolve(b, 2, project.uid))
        assertEquals(5_000L, store.getItem(a, task.uid)!!.dueAt)
        assertEquals("open", store.getItem(a, task.uid)!!.status)
        assertEquals("active", store.getContainer(a, project.uid)!!.status)
    }

    // ── Focus ─────────────────────────────────────────────────────────────────

    @Test
    fun `a pinned focus replaces the previous pinned focus and leaves nudges alone`() = runBlocking {
        val user = newUser()
        val demo = store.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val garden = store.createContainer(user, 1, ContainerKind.PROJECT, "Garden")!!
        val taxes = store.createContainer(user, 1, ContainerKind.PROJECT, "Taxes")!!

        assertTrue(store.setFocus(user, 1, demo.uid, FocusMode.PINNED))
        assertTrue(store.setFocus(user, 2, taxes.uid, FocusMode.NUDGE))
        assertTrue(store.setFocus(user, 3, garden.uid, FocusMode.PINNED))

        val focus = store.getFocus(user)
        assertEquals(listOf(garden.uid, taxes.uid), focus.map { it.targetUid }, "pinned first, the old pin is gone, the nudge survives")
        assertEquals(FocusMode.PINNED, focus[0].mode)
        assertEquals(FocusMode.NUDGE, focus[1].mode)
    }

    @Test
    fun `nudges accumulate and refresh, and a nudge never weakens a pin on the same target`() = runBlocking {
        val user = newUser()
        val demo = store.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val garden = store.createContainer(user, 1, ContainerKind.PROJECT, "Garden")!!

        assertTrue(store.setFocus(user, 1, garden.uid, FocusMode.NUDGE))
        assertTrue(store.setFocus(user, 2, demo.uid, FocusMode.NUDGE))
        assertTrue(store.setFocus(user, 5, garden.uid, FocusMode.NUDGE))
        assertEquals(2, store.getFocus(user).size)
        assertEquals(5L, store.getFocus(user).first { it.targetUid == garden.uid }.setCycleSeq, "a repeated mention refreshes the nudge")

        assertTrue(store.setFocus(user, 6, garden.uid, FocusMode.PINNED))
        assertEquals(FocusMode.PINNED, store.getFocus(user).first { it.targetUid == garden.uid }.mode, "an explicit instruction upgrades a nudge to a pin")
        assertEquals(1, store.getFocus(user).count { it.targetUid == garden.uid }, "the upgrade reuses the edge")

        assertTrue(store.setFocus(user, 7, garden.uid, FocusMode.NUDGE))
        val after = store.getFocus(user).first { it.targetUid == garden.uid }
        assertEquals(FocusMode.PINNED, after.mode, "a casual mention doesn't downgrade the pin")
        assertEquals(6L, after.setCycleSeq)
    }

    @Test
    fun `a focus defaults its zoom from the target kind, and honors an explicit zoom`() = runBlocking {
        val user = newUser()
        val domain = store.createContainer(user, 1, ContainerKind.DOMAIN, "Work")!!
        val project = store.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!
        val item = store.createItem(user, 1, ItemKind.TASK, "Book the room")!!

        store.setFocus(user, 1, domain.uid, FocusMode.NUDGE)
        store.setFocus(user, 1, project.uid, FocusMode.NUDGE)
        store.setFocus(user, 1, item.uid, FocusMode.NUDGE)
        val byUid = store.getFocus(user).associateBy { it.targetUid }
        assertEquals(FocusZoom.OVERVIEW, byUid[domain.uid]!!.zoom); assertEquals(FocusTargetKind.DOMAIN, byUid[domain.uid]!!.targetKind)
        assertEquals(FocusZoom.BINOCULARS, byUid[project.uid]!!.zoom); assertEquals(FocusTargetKind.PROJECT, byUid[project.uid]!!.targetKind)
        assertEquals(FocusZoom.TELESCOPE, byUid[item.uid]!!.zoom); assertEquals(FocusTargetKind.ITEM, byUid[item.uid]!!.targetKind)

        store.setFocus(user, 2, project.uid, FocusMode.PINNED, FocusZoom.TELESCOPE)
        assertEquals(FocusZoom.TELESCOPE, store.getFocus(user).first { it.targetUid == project.uid }.zoom)
    }

    @Test
    fun `releaseFocus removes one target, or every pin, or everything`() = runBlocking {
        val user = newUser()
        val a = store.createContainer(user, 1, ContainerKind.PROJECT, "A")!!
        val b = store.createContainer(user, 1, ContainerKind.PROJECT, "B")!!
        val c = store.createContainer(user, 1, ContainerKind.PROJECT, "C")!!

        store.setFocus(user, 1, a.uid, FocusMode.PINNED)
        store.setFocus(user, 1, b.uid, FocusMode.NUDGE)
        store.setFocus(user, 1, c.uid, FocusMode.NUDGE)

        assertEquals(1, store.releaseFocus(user, b.uid))
        assertEquals(listOf(a.uid, c.uid), store.getFocus(user).map { it.targetUid })
        assertEquals(1, store.releaseFocus(user, includeNudges = false), "only the pin goes")
        assertEquals(listOf(c.uid), store.getFocus(user).map { it.targetUid })
        assertEquals(1, store.releaseFocus(user), "zooming out clears the rest")
        assertTrue(store.getFocus(user).isEmpty())
        assertEquals(0, store.releaseFocus(user), "nothing left is a clean zero, not an error")
    }

    @Test
    fun `focus cannot be set on, or reveal, another users things`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val project = store.createContainer(a, 1, ContainerKind.PROJECT, "A's project")!!
        val item = store.createItem(a, 1, ItemKind.FACT, "A's item")!!
        assertFalse(store.setFocus(b, 1, project.uid, FocusMode.PINNED))
        assertFalse(store.setFocus(b, 1, item.uid, FocusMode.NUDGE))
        assertFalse(store.setFocus(b, 1, UUID.randomUUID().toString(), FocusMode.PINNED))
        assertTrue(store.getFocus(b).isEmpty())
        assertEquals(0, store.releaseFocus(b, project.uid))
    }

    @Test
    fun `containers, filing and focus are still there after the database is closed and reopened`() = runBlocking {
        val path = "./data/test-horizon-structure-reopen-${System.nanoTime()}"
        val user = "reopen-${UUID.randomUUID()}@example.test"
        try {
            val first = DatabaseManager(path)
            val projectUid: String
            val itemUid: String
            try {
                val db = first.getDatabase()
                SchemaBootstrap.bootstrap(db)
                db.transaction {
                    db.newVertex("User").apply {
                        set("uid", UUID.randomUUID().toString()); set("email", user); set("username", "reopen")
                        set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save()
                    }
                }
                val writer = ArcadeHorizonStructureStore(db)
                projectUid = writer.createContainer(user, 1, ContainerKind.PROJECT, "Demo")!!.uid
                itemUid = writer.createItem(user, 1, ItemKind.TASK, "Book the room", dueAt = 42L, projectUid = projectUid)!!.uid
                assertTrue(writer.setFocus(user, 4, projectUid, FocusMode.PINNED))
                assertTrue(writer.touchLastActive(user, 777L))
            } finally {
                first.close()
            }

            val second = DatabaseManager(path)
            try {
                val db = second.getDatabase()
                SchemaBootstrap.bootstrap(db) // bootstrapping a populated database must not disturb it
                val reader = ArcadeHorizonStructureStore(db)
                assertEquals(listOf(projectUid), reader.getFocus(user).map { it.targetUid })
                assertEquals(FocusMode.PINNED, reader.getFocus(user).single().mode)
                assertEquals(4L, reader.getFocus(user).single().setCycleSeq)
                val item = reader.getItem(user, itemUid)!!
                assertEquals(ItemKind.TASK, item.kind)
                assertEquals(42L, item.dueAt)
                assertEquals(projectUid, item.projectUid)
                assertEquals(777L, reader.lastActiveAt(user))
            } finally {
                second.close()
            }
        } finally {
            File("./data").listFiles()?.filter { it.name.startsWith(path.substringAfterLast('/')) }?.forEach { it.deleteRecursively() }
        }
    }

    // ── Activity ──────────────────────────────────────────────────────────────

    @Test
    fun `touchLastActive only moves forward and rejects an unknown user`() = runBlocking {
        val user = newUser()
        assertNull(store.lastActiveAt(user))
        assertTrue(store.touchLastActive(user, 5_000L))
        assertEquals(5_000L, store.lastActiveAt(user))
        assertTrue(store.touchLastActive(user, 3_000L), "an older stamp is accepted but ignored")
        assertEquals(5_000L, store.lastActiveAt(user))
        assertTrue(store.touchLastActive(user, 9_000L))
        assertEquals(9_000L, store.lastActiveAt(user))
        assertFalse(store.touchLastActive("nobody@example.test", 1L))
        assertNull(store.lastActiveAt("nobody@example.test"))
    }

    // ── Locking ───────────────────────────────────────────────────────────────

    @Test
    fun `a mutator is rejected, not run unguarded, when another writer holds the Horizon lock`() = runBlocking {
        val user = newUser()
        val impatient = ArcadeHorizonStructureStore(dbManager.getDatabase(), lockTimeoutMs = 100)
        val writeLock = HorizonConsistencyLock.forDatabase(dbManager.getDatabase()).writeLock()
        writeLock.lock()
        try {
            assertNull(impatient.createContainer(user, 1, ContainerKind.PROJECT, "Blocked"))
            assertNull(impatient.createItem(user, 1, ItemKind.FACT, "Blocked claim"))
            assertFalse(impatient.touchLastActive(user, 1L))
        } finally {
            writeLock.unlock()
        }
        assertTrue(store.listContainers(user).isEmpty(), "nothing leaked through while the lock was held")
        assertNotNull(impatient.createContainer(user, 1, ContainerKind.PROJECT, "Now it works"))
    }

    @Test
    fun `existing claim queries still see items created through the structure store`() = runBlocking {
        // The new item types are Phrase subtypes precisely so nothing that reads Phrase changes.
        val user = newUser()
        val fact = store.createItem(user, 1, ItemKind.FACT, "Polymorphic sanity check ${UUID.randomUUID()}")!!
        val task = store.createItem(user, 1, ItemKind.TASK, "Polymorphic task ${UUID.randomUUID()}")!!
        val db = dbManager.getDatabase()
        val viaPhrase = db.query("sql", "SELECT uid FROM Phrase WHERE uid IN :u", mapOf("u" to listOf(fact.uid, task.uid)))
            .use { rs -> generateSequence { if (rs.hasNext()) rs.next().toMap()["uid"] as String else null }.toList() }
        assertEquals(setOf(fact.uid, task.uid), viaPhrase.toSet())
        val viaSource = HorizonOwnership.findPhraseOwnedByUser(db, user, task.uid)
        assertNotNull(viaSource)
        assertEquals("Task", (viaSource as Vertex).typeName)
    }

    // ── Attention ─────────────────────────────────────────────────────────────

    @Test
    fun `attention pins or suppresses an item or a project and can be cleared, without resolving anything`() = runBlocking {
        val user = newUser()
        val task = store.createItem(user, 1, ItemKind.TASK, "Order the soil")!!
        val project = store.createContainer(user, 1, ContainerKind.PROJECT, "Garden")!!

        assertTrue(store.setAttention(user, 2, task.uid, AttentionDirective.SUPPRESSED))
        assertTrue(store.setAttention(user, 2, task.uid, AttentionDirective.SUPPRESSED), "setting it again is a no-op")
        assertEquals(AttentionDirective.SUPPRESSED, store.getItem(user, task.uid)!!.attention)
        assertEquals("open", store.getItem(user, task.uid)!!.status, "suppressing is not resolving")

        assertTrue(store.setAttention(user, 3, project.uid, AttentionDirective.PINNED))
        assertEquals(AttentionDirective.PINNED, store.getContainer(user, project.uid)!!.attention)

        assertTrue(store.setAttention(user, 4, task.uid, null))
        assertNull(store.getItem(user, task.uid)!!.attention)
    }

    @Test
    fun `attention cannot be set on another users things`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val task = store.createItem(a, 1, ItemKind.TASK, "A's task")!!
        assertFalse(store.setAttention(b, 2, task.uid, AttentionDirective.SUPPRESSED))
        assertNull(store.getItem(a, task.uid)!!.attention)
    }

    // ── Legacy Horizon compatibility ──────────────────────────────────────────

    @Test
    fun `a task or question carries the legacy open status, and resolving or reopening keeps it in step`() = runBlocking {
        val user = newUser()
        val db = dbManager.getDatabase()
        val task = store.createItem(user, 3, ItemKind.TASK, "Send the invoice")!!
        val question = store.createItem(user, 3, ItemKind.QUESTION, "What next?")!!
        val fact = store.createItem(user, 3, ItemKind.FACT, "A plain fact")!!
        fun legacy(uid: String) = HorizonOwnership.ownedAssertsEdges(db, user, uid).single().get("status") as String?

        assertEquals("open", legacy(task.uid))
        assertEquals("open", legacy(question.uid))
        assertNull(legacy(fact.uid), "a fact was never intention-shaped")

        assertTrue(store.resolve(user, 4, task.uid))
        assertEquals("resolved", legacy(task.uid))
        assertEquals(4L, (HorizonOwnership.ownedAssertsEdges(db, user, task.uid).single().get("statusCycleSeq") as Number).toLong())
        assertTrue(store.resolve(user, 5, task.uid, resolved = false))
        assertEquals("open", legacy(task.uid))
        val history = HorizonOwnership.ownedAssertsEdges(db, user, task.uid).single().get("statusHistory") as String
        assertEquals(3, Regex("\"state\"").findAll(history).count(), "open, resolved, open — every transition kept")
    }

    @Test
    fun `the current Horizon assembler surfaces a created task as open and drops it once resolved`() = runBlocking {
        val user = newUser()
        val assembler = ArcadeHorizonAssembler(dbManager.getDatabase())
        val text = "Send the unique invoice ${UUID.randomUUID()}"
        val task = store.createItem(user, 1, ItemKind.TASK, text)!!

        suspend fun surfaced(cycle: Long): List<HorizonItem> =
            ((assembler.assemble(user, cycle)) as AssembleOutcome.Assembled).horizon.items.filter { it.text.text == text }

        val open = surfaced(1)
        assertEquals(1, open.size)
        assertEquals(HorizonItemCategory.INTENTION, open.single().category)
        assertEquals(AssertionStatus.OPEN, open.single().status)

        assertTrue(store.resolve(user, 2, task.uid))
        assertTrue(surfaced(3).none { it.status == AssertionStatus.OPEN }, "a resolved task is no longer an open intention")
    }

    // ── Reads for the interpretation snapshot ─────────────────────────────────

    @Test
    fun `listRecentItems returns newest first, can be limited to open ones, and only shows the callers own`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val first = store.createItem(a, 1, ItemKind.TASK, "first")!!
        Thread.sleep(3)
        val second = store.createItem(a, 2, ItemKind.FACT, "second")!!
        Thread.sleep(3)
        val third = store.createItem(a, 3, ItemKind.TASK, "third")!!
        store.createItem(b, 1, ItemKind.TASK, "someone else's")
        store.resolve(a, 4, first.uid)

        assertEquals(listOf(third.uid, second.uid, first.uid), store.listRecentItems(a, 10).map { it.uid })
        assertEquals(listOf(third.uid, second.uid), store.listRecentItems(a, 2).map { it.uid })
        assertEquals(listOf(third.uid), store.listRecentItems(a, 10, openOnly = true).map { it.uid }, "the fact was never open, the first task is resolved")
        assertTrue(store.listRecentItems(a, 0).isEmpty())
        assertTrue(store.listRecentItems("nobody@example.test", 5).isEmpty())
    }

    @Test
    fun `an item reports the entities it is about`() = runBlocking {
        val user = newUser()
        val item = store.createItem(user, 1, ItemKind.FACT, "My sister is allergic to peanuts")!!
        store.linkAbout(user, 1, item.uid, "sister")
        store.linkAbout(user, 1, item.uid, "peanuts")
        assertEquals(setOf("sister", "peanuts"), store.getItem(user, item.uid)!!.about.toSet())
        assertEquals(setOf("sister", "peanuts"), store.listRecentItems(user, 5).single().about.toSet())
    }
}
