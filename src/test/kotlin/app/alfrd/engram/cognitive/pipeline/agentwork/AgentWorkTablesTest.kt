package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.NotionBlock
import app.alfrd.engram.notion.NotionConfigException
import app.alfrd.engram.notion.NotionDataSources
import app.alfrd.engram.notion.NotionGuardViolation
import app.alfrd.engram.notion.NotionIdentityConfig
import app.alfrd.engram.notion.NotionReadablePage
import app.alfrd.engram.notion.NotionSetRegistry
import app.alfrd.engram.notion.NotionTable
import app.alfrd.engram.notion.NotionTableSet
import app.alfrd.engram.notion.NotionTableSetConfig
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.RichSegment
import app.alfrd.engram.notion.TableAccess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The committed Agent Work configuration (Agent Work, the PPS ledger and Jacob's alfrd memory), and the
 * promise it makes: the Director reads all of it and writes only Cycles and Agent Tasks. The guard enforces
 * that, so these tests go through the guard, with the real ids and the real schemas.
 */
class AgentWorkTablesTest {

    private val configPath = "deploy/config/notion-agent-worker.json"
    private val config: NotionTableSetConfig = AgentWorkConfig.load(configPath)
    private val set: NotionTableSet = config.tableSet

    private lateinit var fake: FakeNotionApi
    private lateinit var template: AgentWorkTemplate
    private lateinit var api: AllowlistedNotionApi

    @BeforeEach
    fun setUp() {
        fake = FakeNotionApi()
        template = fake.installAgentWorkTemplate(set)
        api = AllowlistedNotionApi(fake, set)
    }

    private fun violation(block: suspend () -> Unit): NotionGuardViolation =
        assertThrows(NotionGuardViolation::class.java) { runBlocking { block() } }

    private fun rejected(block: () -> Unit): NotionConfigException = assertThrows(NotionConfigException::class.java, block)

    /** The name of each table's title property, as the real databases have it. */
    private fun titleOf(table: String): String = when (table) {
        AgentWorkTables.GOALS -> "Goal"
        AgentWorkTables.ESCALATIONS -> "Escalation"
        AgentWorkTables.QUESTIONS -> "Question"
        AgentWorkTables.FINDINGS -> "Claim"
        AgentWorkTables.SOURCES -> "Title"
        AgentWorkTables.MEMORY_CONTAINERS, AgentWorkTables.MEMORY_ENTITIES, AgentWorkTables.MEMORY_SOURCES -> "Name"
        AgentWorkTables.MEMORY_ITEMS -> "Item"
        AgentWorkTables.MEMORY_CONNECTIONS -> "Connection"
        AgentWorkTables.MEMORY_SESSIONS -> "Session"
        AgentWorkTables.MEMORY_TEMPLATE_CHANGES -> "Change"
        else -> error("no title property known for $table")
    }

    private val readOnlyTables: List<String> = AgentWorkTables.ALL.filter { it !in AgentWorkTables.WRITABLE }.sorted()

    // ── The committed file ───────────────────────────────────────────────────

    @Test
    fun `the committed configuration has the fourteen tables with the access modes the brief sets`() {
        assertEquals("agent-worker", set.name)
        assertEquals(14, set.tables.size)
        assertEquals(AgentWorkTables.ALL, set.tables.map { it.name }.toSet())
        assertEquals(setOf(AgentWorkTables.CYCLES, AgentWorkTables.AGENT_TASKS), set.tables.filter { it.writable }.map { it.name }.toSet())
        assertEquals(12, readOnlyTables.size)
        for (name in readOnlyTables) assertEquals(TableAccess.READ_ONLY, set.table(name).access, name)
        assertEquals("Op key", set.table(AgentWorkTables.AGENT_TASKS).idempotencyKeyProperty)
        assertTrue(set.tables.none { it.pageBodyAppend }, "no table takes page-body appends")
    }

    @Test
    fun `PPS Claims is Findings now and Evidence is gone`() {
        assertEquals(listOf(AgentWorkTables.QUESTIONS, AgentWorkTables.FINDINGS, AgentWorkTables.SOURCES), AgentWorkTables.PPS)
        assertEquals("b3a341cb-8915-4521-a1e4-59d0b87ea242", set.table(AgentWorkTables.FINDINGS).dataSourceId, "same id as the old Claims table")
        assertTrue(set.tables.none { it.name == "claims" || it.name == "evidence" })
        assertTrue(set.tableForDataSource("275e6390-ba93-40e6-a887-790ed35345ad") == null, "the archived Evidence table must not be reachable")
    }

    @Test
    fun `the ids are the data source ids from the collection references, not the database ids`() {
        val expected = mapOf(
            AgentWorkTables.GOALS to "2ee6db65-bade-4662-a3e5-0246bd5b733f",
            AgentWorkTables.CYCLES to "545ccad7-35ac-4131-b69c-cdbd278fe774",
            AgentWorkTables.AGENT_TASKS to "894c2022-6542-4312-b6bd-fb746f87af41",
            AgentWorkTables.ESCALATIONS to "b1794330-8d8a-47c4-81a4-30603528ea55",
            AgentWorkTables.QUESTIONS to "1bcee51b-37ac-41a3-897b-9d107f34be8f",
            AgentWorkTables.FINDINGS to "b3a341cb-8915-4521-a1e4-59d0b87ea242",
            AgentWorkTables.SOURCES to "99b80257-9ec4-4c2c-bf41-4ea0cfc66994",
            AgentWorkTables.MEMORY_CONTAINERS to "d67376eb-af39-4ee5-bae1-76b7875e15f5",
            AgentWorkTables.MEMORY_ITEMS to "e6e42d42-cb55-4ad1-8dd3-36e64fdad1fb",
            AgentWorkTables.MEMORY_ENTITIES to "c128ebfe-466e-4bd8-ab9f-8b6bc062354b",
            AgentWorkTables.MEMORY_CONNECTIONS to "1e931e85-7f0a-42ec-bbfb-12ccfe3f6c11",
            AgentWorkTables.MEMORY_SESSIONS to "50a754a0-1172-4250-ac22-e93d66332a52",
            AgentWorkTables.MEMORY_SOURCES to "977743ba-9eb5-4150-bcbf-4dc4c8d408e7",
            AgentWorkTables.MEMORY_TEMPLATE_CHANGES to "f661f9c4-4fae-450c-9693-12de515b7762",
        )
        assertEquals(AgentWorkTables.ALL, expected.keys)
        for ((name, id) in expected) assertEquals(id, set.table(name).dataSourceId, name)
        assertTrue(set.tables.all { it.databaseId != null && it.databaseId != it.dataSourceId })
        assertEquals(AgentWorkTables.SOURCES, set.tableForDatabase("6e9ea25de7334d899d7a52783509ff54")?.name)
        assertEquals(AgentWorkTables.MEMORY_SOURCES, set.tableForDatabase("668dc895f790492f950d89d2ef0a4aa2")?.name, "the memory Sources table is not the PPS one")
        assertEquals(AgentWorkTables.MEMORY_ITEMS, set.tableForDatabase("9ab7917ebda1471dbad8e1d6304b575b")?.name)
    }

    @Test
    fun `the committed allowlist is Persona and the one reading page`() {
        assertEquals(listOf(AgentWorkTables.PERSONA_PAGE, "reading-essay-excerpt"), set.readablePages.map { it.name })
        assertEquals("3f1d0721-d4e4-81d5-88c6-f320f5fa25f8", set.readablePages.first().pageId)
        assertEquals("3f8d0721-d4e4-81de-b87b-ec779d10acfc", set.readablePages.last().pageId)
    }

    @Test
    fun `the token is a file reference, so the JVM reads it and no environment variable carries it`() {
        assertEquals("file:/home/halo/development/hermes-webui-dev/.notion-worker-token", config.tokenRef)
    }

    @Test
    fun `a missing file is a configuration error that does not name the contents of anything`() {
        assertTrue(rejected { AgentWorkConfig.load("deploy/config/does-not-exist.json") }.message!!.contains("does not exist"))
    }

    // ── The startup check refuses anything that grants more than this increment needs ──

    private fun withTable(name: String, change: (NotionTable) -> NotionTable) =
        set.copy(tables = set.tables.map { if (it.name == name) change(it) else it })

    @Test
    fun `a read-only table that accepts incoming relations is refused, because the relation would be mirrored onto its rows`() {
        for (name in AgentWorkTables.ALL - AgentWorkTables.WRITABLE) {
            val e = rejected { AgentWorkTables.requireShape(withTable(name) { it.copy(allowIncomingRelations = true) }) }
            assertTrue(e.message!!.contains("incoming relations") && e.message!!.contains(name), "$name: ${e.message}")
        }
    }

    @Test
    fun `a set where any PPS or memory table is writable is refused at startup`() {
        for (name in AgentWorkTables.PPS + AgentWorkTables.MEMORY) {
            val e = rejected { AgentWorkTables.requireShape(withTable(name) { it.copy(access = TableAccess.READ_WRITE) }) }
            assertTrue(e.message!!.contains("read-write now") && e.message!!.contains(name), e.message)
        }
    }

    @Test
    fun `Goals and Escalations may not be writable either, and Cycles and Agent Tasks must be`() {
        rejected { AgentWorkTables.requireShape(withTable(AgentWorkTables.GOALS) { it.copy(access = TableAccess.READ_WRITE) }) }
        rejected { AgentWorkTables.requireShape(withTable(AgentWorkTables.ESCALATIONS) { it.copy(access = TableAccess.READ_WRITE) }) }
        rejected { AgentWorkTables.requireShape(withTable(AgentWorkTables.CYCLES) { it.asReadOnly() }) }
        rejected { AgentWorkTables.requireShape(withTable(AgentWorkTables.AGENT_TASKS) { it.asReadOnly() }) }
    }

    @Test
    fun `the Agent Tasks idempotency key and the set of tables are checked`() {
        rejected { AgentWorkTables.requireShape(withTable(AgentWorkTables.AGENT_TASKS) { it.copy(idempotencyKeyProperty = null) }) }
        rejected { AgentWorkTables.requireShape(withTable(AgentWorkTables.AGENT_TASKS) { it.copy(idempotencyKeyProperty = "Other key") }) }
        rejected { AgentWorkTables.requireShape(set.copy(tables = set.tables.filter { it.name != AgentWorkTables.MEMORY_ITEMS })) }
        rejected { AgentWorkTables.requireShape(set.copy(tables = set.tables.filter { it.name != AgentWorkTables.FINDINGS })) }
        val extra = NotionTable("extra", "99999999-9999-4999-8999-999999999999")
        assertTrue(rejected { AgentWorkTables.requireShape(set.copy(tables = set.tables + extra)) }.message!!.contains("extra"))
    }

    @Test
    fun `a fifteenth table is refused even when it is read-only, so the writable-set check is not what stops it`() {
        val extra = NotionTable("extra", "99999999-9999-4999-8999-999999999999", TableAccess.READ_ONLY)
        val e = rejected { AgentWorkTables.requireShape(set.copy(tables = set.tables + extra)) }
        assertTrue(e.message!!.contains("does not use") && e.message!!.contains("extra"), e.message)
    }

    @Test
    fun `no table may accept page-body appends`() {
        rejected { AgentWorkTables.requireShape(withTable(AgentWorkTables.CYCLES) { it.copy(pageBodyAppend = true) }) }
    }

    @Test
    fun `Persona is required, only reading-named pages can be added, and anything else is refused`() {
        val other = NotionReadablePage("home", "99999999-9999-4999-8999-999999999999")
        val e = rejected { AgentWorkTables.requireShape(set.copy(readablePages = set.readablePages + other)) }
        assertTrue(e.message!!.contains("reading pages") && e.message!!.contains("home"), e.message)
        rejected { AgentWorkTables.requireShape(set.copy(readablePages = emptyList())) }
        rejected { AgentWorkTables.requireShape(set.copy(readablePages = listOf(NotionReadablePage("home", "99999999-9999-4999-8999-999999999999")))) }
        rejected { AgentWorkTables.requireShape(set.copy(readablePages = set.readablePages.filter { it.name != AgentWorkTables.PERSONA_PAGE })) }
        AgentWorkTables.requireShape(set.copy(readablePages = set.readablePages + NotionReadablePage("reading-more", "99999999-9999-4999-8999-999999999999")))
        AgentWorkTables.requireShape(set.copy(readablePages = set.readablePages.filter { it.name == AgentWorkTables.PERSONA_PAGE }))
    }

    // ── The guard enforces the promise: every read-only table refuses writes ──

    @Test
    fun `an attempted create in any read-only table is refused by the guard before a request is made`() {
        val before = fake.requests.size
        for (name in readOnlyTables) {
            val e = violation { api.createPage(set.table(name).dataSourceId, mapOf(titleOf(name) to NotionValue.Title("sneaky"))) }
            assertTrue(e.message!!.contains("read-only") && e.message!!.contains(name), "$name: ${e.message}")
        }
        assertEquals(before, fake.requests.size, "a write to a read-only table reached Notion")
        assertTrue(readOnlyTables.all { fake.rows(set.table(it).dataSourceId).isEmpty() })
    }

    @Test
    fun `an attempted update or append to a row of any read-only table is refused and the row is unchanged`() = runBlocking {
        for (name in readOnlyTables) {
            val table = set.table(name)
            val title = titleOf(name)
            val seeded = fake.createPage(table.dataSourceId, mapOf(title to NotionValue.Title("original")))
            api.query(table.dataSourceId) // the row is now one the guard has seen; only the table's mode stops a write
            val before = fake.requestCount()

            violation { api.updatePage(seeded.id, mapOf(title to NotionValue.Title("rewritten"))) }
            violation { api.appendBlocks(seeded.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("note"))))) }

            assertEquals(before, fake.requestCount(), "$name: a refused write reached Notion")
            assertEquals("original", fake.page(seeded.id).title(title), name)
        }
    }

    @Test
    fun `a goal's Status cannot be changed by the Director, the one write QC must never make`() = runBlocking {
        val goal = fake.createPage(template.goals, mapOf("Goal" to NotionValue.Title("g"), "Status" to NotionValue.Select("Active")))
        api.query(template.goals)
        val e = violation { api.updatePage(goal.id, mapOf("Status" to NotionValue.Select("Met"))) }
        assertTrue(e.message!!.contains("read-only"), e.message)
        assertEquals("Active", fake.page(goal.id).select("Status"))
    }

    @Test
    fun `a listed reading page can be read, is never writable, and an unlisted page beside it stays unreachable`() = runBlocking<Unit> {
        val page = template.essayExcerptPageId
        assertEquals("Test material: essay excerpt", api.retrievePage(page).title("title"))
        assertEquals(1, api.listBlocks(page).blocks.size)
        val before = fake.requestCount()

        violation { api.updatePage(page, mapOf("title" to NotionValue.Title("edited"))) }
        violation { api.appendBlocks(page, listOf(NotionBlock.Paragraph(listOf(RichSegment("note"))))) }
        assertEquals(before, fake.requestCount(), "a write to a reading page reached Notion")

        val stranger = fake.createPlainPage(listOf("private"))
        violation { api.retrievePage(stranger) }
        violation { api.listBlocks(stranger) }
    }

    @Test
    fun `the Persona page can be read and never written, and no other plain page can be reached at all`() = runBlocking<Unit> {
        val persona = api.retrievePage(template.personaPageId)
        assertEquals("Persona", persona.title("title"))
        assertEquals(1, api.listBlocks(template.personaPageId).blocks.size)
        val before = fake.requestCount()

        violation { api.updatePage(template.personaPageId, mapOf("title" to NotionValue.Title("edited"))) }
        violation { api.appendBlocks(template.personaPageId, listOf(NotionBlock.Paragraph(listOf(RichSegment("note"))))) }
        assertEquals(before, fake.requestCount(), "a write to Persona reached Notion")

        val stranger = fake.createPlainPage(listOf("private"))
        violation { api.retrievePage(stranger) }
        violation { api.listBlocks(stranger) }
    }

    @Test
    fun `read-only tables can be read, and a page read returns the formula and rollup values a query leaves out`() = runBlocking {
        val finding = fake.createPage(template.findings, mapOf("Claim" to NotionValue.Title("a finding")))
        fake.setComputed(finding.id, "Confidence", NotionValue.Formula("number", number = 0.62))

        val queried = api.query(template.findings).pages.single()
        assertEquals(NotionValue.Unsupported("formula"), queried.properties["Confidence"])

        val read = api.retrievePage(finding.id)
        assertEquals(0.62, read.formula("Confidence")!!.number)
    }

    @Test
    fun `Goals and Escalations are read-only`() = runBlocking {
        val goal = fake.createPage(template.goals, mapOf("Goal" to NotionValue.Title("g")))
        val escalation = fake.createPage(template.escalations, mapOf("Escalation" to NotionValue.Title("e")))
        api.query(template.goals)
        api.query(template.escalations)

        violation { api.createPage(template.goals, mapOf("Goal" to NotionValue.Title("new"))) }
        violation { api.createPage(template.escalations, mapOf("Escalation" to NotionValue.Title("new"))) }
        violation { api.updatePage(goal.id, mapOf("Status" to NotionValue.Select("Active"))) }
        violation { api.updatePage(escalation.id, mapOf("Status" to NotionValue.Select("Resolved"))) }
        assertEquals(null, fake.page(goal.id).select("Status"))
    }

    @Test
    fun `Cycles and Agent Tasks are writable, and a task needs its Op key`() = runBlocking {
        val cycle = api.createPage(template.cycles, mapOf("Cycle" to NotionValue.Title("c")))
        api.updatePage(cycle.id, mapOf("Status" to NotionValue.Select("Running"), "Worker" to NotionValue.RichText("arx-1")))
        assertEquals("Running", fake.page(cycle.id).select("Status"))

        violation { api.createPage(template.tasks, mapOf("Task" to NotionValue.Title("no key"))) }
        val task = api.createPage(
            template.tasks,
            mapOf(
                "Task" to NotionValue.Title("with key"),
                "Op key" to NotionValue.RichText("cycle:CYCLE-1:v1:o1"),
                "Order" to NotionValue.NumberValue(1.0),
                "Cycle" to NotionValue.Relation(listOf(cycle.id)),
            ),
        )
        assertEquals(listOf(task.id), fake.page(cycle.id).relation("Tasks"), "Cycles is read-write, so the dual relation may be written")
    }

    @Test
    fun `a task's Result and Status can be written on Agent Tasks, and Goal Feedback on Cycles`() = runBlocking {
        val cycle = api.createPage(template.cycles, mapOf("Cycle" to NotionValue.Title("c")))
        val task = api.createPage(template.tasks, mapOf("Task" to NotionValue.Title("t"), "Op key" to NotionValue.RichText("k1")))
        api.updatePage(task.id, mapOf("Status" to NotionValue.Select("Done"), "Result" to NotionValue.RichText("found it")))
        api.updatePage(cycle.id, mapOf("Goal Feedback" to NotionValue.RichText("the goal left X unclear"), "Tasks Done" to NotionValue.NumberValue(1.0)))
        assertEquals("found it", fake.page(task.id).text("Result"))
        assertEquals("the goal left X unclear", fake.page(cycle.id).text("Goal Feedback"))
    }

    @Test
    fun `a relation into any read-only table is refused, so the rows of Goals and memory are never touched through a dual relation`() = runBlocking {
        val goal = fake.createPage(template.goals, mapOf("Goal" to NotionValue.Title("g")))
        val cycle = api.createPage(template.cycles, mapOf("Cycle" to NotionValue.Title("c")))
        api.query(template.goals)

        val e = violation { api.updatePage(cycle.id, mapOf("Goal" to NotionValue.Relation(listOf(goal.id)))) }
        assertTrue(e.message!!.contains("does not accept incoming relations"))
        assertEquals(emptyList<String>(), fake.page(goal.id).relation("Cycles"), "the read-only Goals row was edited through a dual relation")
    }

    @Test
    fun `the auto-increment ids on Cycles and Agent Tasks cannot be written`() = runBlocking<Unit> {
        val cycle = api.createPage(template.cycles, mapOf("Cycle" to NotionValue.Title("c")))
        violation { api.updatePage(cycle.id, mapOf("Cycle ID" to NotionValue.UniqueId("CYCLE", 99))) }
        violation { api.createPage(template.tasks, mapOf("Task" to NotionValue.Title("t"), "Op key" to NotionValue.RichText("k"), "Task ID" to NotionValue.UniqueId("TASK", 5))) }
    }

    // ── Isolation from other Notion sets (a Notion-backed memory store, a test teamspace) ──

    private fun dummyMemory() = NotionDataSources(
        "00000001-aaaa-4aaa-8aaa-000000000001", "00000001-aaaa-4aaa-8aaa-000000000002", "00000001-aaaa-4aaa-8aaa-000000000003",
        "00000001-aaaa-4aaa-8aaa-000000000004", "00000001-aaaa-4aaa-8aaa-000000000005", "00000001-aaaa-4aaa-8aaa-000000000006",
    )

    @Test
    fun `a separate memory store set with its own token and its own tables is accepted beside this one`() {
        NotionSetRegistry(listOf(config, NotionIdentityConfig("jacob", "env:ALFRD_NOTION_TOKEN_JACOB", dummyMemory())))
    }

    @Test
    fun `a memory store set cannot share this token`() {
        val sharesToken = NotionIdentityConfig("jacob", config.tokenRef, dummyMemory())
        assertTrue(rejected { NotionSetRegistry(listOf(config, sharesToken)) }.message!!.contains("share a token"))
    }

    @Test
    fun `a memory store set that points at any one of Jacob's real alfrd memory tables is refused, whichever slot it uses`() {
        val real = mapOf(
            "containers" to set.table(AgentWorkTables.MEMORY_CONTAINERS).dataSourceId,
            "items" to set.table(AgentWorkTables.MEMORY_ITEMS).dataSourceId,
            "entities" to set.table(AgentWorkTables.MEMORY_ENTITIES).dataSourceId,
            "connections" to set.table(AgentWorkTables.MEMORY_CONNECTIONS).dataSourceId,
            "sessions" to set.table(AgentWorkTables.MEMORY_SESSIONS).dataSourceId,
        )
        val dummy = dummyMemory()
        val variants = mapOf(
            "containers" to dummy.copy(containers = real.getValue("containers")),
            "items" to dummy.copy(items = real.getValue("items")),
            "entities" to dummy.copy(entities = real.getValue("entities")),
            "connections" to dummy.copy(connections = real.getValue("connections")),
            "sessions" to dummy.copy(sessions = real.getValue("sessions")),
            // A table slot in the other set pointing at this set's Cycles table is no better.
            "cycles" to dummy.copy(sessions = set.table(AgentWorkTables.CYCLES).dataSourceId),
        )
        for ((slot, sources) in variants) {
            val e = rejected { NotionSetRegistry(listOf(config, NotionIdentityConfig("test-teamspace", "env:OTHER", sources))) }
            assertTrue(e.message!!.contains("belongs to one set"), "$slot: ${e.message}")
        }
    }

    @Test
    fun `a memory store set that reuses the Persona page is refused`() {
        val reusesPersona = dummyMemory().copy(personaPageId = template.personaPageId)
        assertTrue(rejected { NotionSetRegistry(listOf(config, NotionIdentityConfig("test-teamspace", "env:OTHER", reusesPersona))) }.message!!.contains("belongs to one set"))
    }

    @Test
    fun `a set that reuses one of these database ids is also refused`() {
        val workerDatabase = set.table(AgentWorkTables.SOURCES).databaseId!!
        val reusesDatabase = NotionTableSet("other", listOf(NotionTable("t", dummyMemory().items, databaseId = workerDatabase)))
        val binding = object : app.alfrd.engram.notion.NotionSetBinding {
            override val tokenRef = "env:OTHER"
            override val tableSet = reusesDatabase
        }
        assertTrue(rejected { NotionSetRegistry(listOf(config, binding)) }.message!!.contains("belongs to one set"))
        assertFalse(NotionSetRegistry(listOf(config)).sets.isEmpty())
    }
}
