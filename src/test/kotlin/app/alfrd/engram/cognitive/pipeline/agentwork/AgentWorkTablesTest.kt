package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.NotionBlock
import app.alfrd.engram.notion.NotionConfigException
import app.alfrd.engram.notion.NotionDataSources
import app.alfrd.engram.notion.NotionGuardViolation
import app.alfrd.engram.notion.NotionIdentityConfig
import app.alfrd.engram.notion.NotionSetRegistry
import app.alfrd.engram.notion.NotionTable
import app.alfrd.engram.notion.NotionTableSet
import app.alfrd.engram.notion.NotionTableSetConfig
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.RichSegment
import app.alfrd.engram.notion.TableAccess
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The committed Agent Work + PPS configuration, and the promise it makes: this increment reads PPS
 * and never writes it. The guard enforces that, so these tests go through the guard, with the real
 * ids and the real schemas.
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

    // ── The committed file ───────────────────────────────────────────────────

    @Test
    fun `the committed configuration has the eight tables with the access modes the brief sets`() {
        assertEquals("agent-worker", set.name)
        assertEquals(AgentWorkTables.ALL, set.tables.map { it.name }.toSet())
        assertEquals(setOf(AgentWorkTables.CYCLES, AgentWorkTables.AGENT_TASKS), set.tables.filter { it.writable }.map { it.name }.toSet())
        for (name in listOf(AgentWorkTables.GOALS, AgentWorkTables.ESCALATIONS) + AgentWorkTables.PPS) {
            assertEquals(TableAccess.READ_ONLY, set.table(name).access, name)
        }
        assertEquals("Op key", set.table(AgentWorkTables.AGENT_TASKS).idempotencyKeyProperty)
    }

    @Test
    fun `the ids are the data source ids from the collection references, not the database ids`() {
        val expected = mapOf(
            AgentWorkTables.GOALS to "2ee6db65-bade-4662-a3e5-0246bd5b733f",
            AgentWorkTables.CYCLES to "545ccad7-35ac-4131-b69c-cdbd278fe774",
            AgentWorkTables.AGENT_TASKS to "894c2022-6542-4312-b6bd-fb746f87af41",
            AgentWorkTables.ESCALATIONS to "b1794330-8d8a-47c4-81a4-30603528ea55",
            AgentWorkTables.QUESTIONS to "1bcee51b-37ac-41a3-897b-9d107f34be8f",
            AgentWorkTables.CLAIMS to "b3a341cb-8915-4521-a1e4-59d0b87ea242",
            AgentWorkTables.SOURCES to "99b80257-9ec4-4c2c-bf41-4ea0cfc66994",
            AgentWorkTables.EVIDENCE to "275e6390-ba93-40e6-a887-790ed35345ad",
        )
        for ((name, id) in expected) assertEquals(id, set.table(name).dataSourceId, name)
        assertTrue(set.tables.all { it.databaseId != null && it.databaseId != it.dataSourceId })
        assertEquals(AgentWorkTables.SOURCES, set.tableForDatabase("6e9ea25de7334d899d7a52783509ff54")?.name)
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
    fun `a set where a PPS table is writable is refused at startup`() {
        for (pps in AgentWorkTables.PPS) {
            val e = rejected { AgentWorkTables.requireDryRunShape(withTable(pps) { it.copy(access = TableAccess.READ_WRITE) }) }
            assertTrue(e.message!!.contains("read-write now") && e.message!!.contains(pps), e.message)
        }
    }

    @Test
    fun `Goals and Escalations may not be writable either, and Cycles and Agent Tasks must be`() {
        rejected { AgentWorkTables.requireDryRunShape(withTable(AgentWorkTables.GOALS) { it.copy(access = TableAccess.READ_WRITE) }) }
        rejected { AgentWorkTables.requireDryRunShape(withTable(AgentWorkTables.ESCALATIONS) { it.copy(access = TableAccess.READ_WRITE) }) }
        rejected { AgentWorkTables.requireDryRunShape(withTable(AgentWorkTables.CYCLES) { it.asReadOnly() }) }
        rejected { AgentWorkTables.requireDryRunShape(withTable(AgentWorkTables.AGENT_TASKS) { it.asReadOnly() }) }
    }

    @Test
    fun `the Agent Tasks idempotency key and the set of tables are checked`() {
        rejected { AgentWorkTables.requireDryRunShape(withTable(AgentWorkTables.AGENT_TASKS) { it.copy(idempotencyKeyProperty = null) }) }
        rejected { AgentWorkTables.requireDryRunShape(withTable(AgentWorkTables.AGENT_TASKS) { it.copy(idempotencyKeyProperty = "Other key") }) }
        rejected { AgentWorkTables.requireDryRunShape(set.copy(tables = set.tables.filter { it.name != AgentWorkTables.EVIDENCE })) }
        val extra = NotionTable("extra", "99999999-9999-4999-8999-999999999999")
        assertTrue(rejected { AgentWorkTables.requireDryRunShape(set.copy(tables = set.tables + extra)) }.message!!.contains("extra"))
    }

    @Test
    fun `a read-only table that accepts incoming relations is refused, because the relation would be mirrored onto its rows`() {
        for (name in listOf(AgentWorkTables.GOALS, AgentWorkTables.ESCALATIONS) + AgentWorkTables.PPS) {
            val e = rejected { AgentWorkTables.requireDryRunShape(withTable(name) { it.copy(allowIncomingRelations = true) }) }
            assertTrue(e.message!!.contains("incoming relations") && e.message!!.contains(name), "$name: ${e.message}")
        }
    }

    @Test
    fun `a ninth table is refused even when it is read-only, so the writable-set check is not what stops it`() {
        val extra = NotionTable("extra", "99999999-9999-4999-8999-999999999999", TableAccess.READ_ONLY)
        val e = rejected { AgentWorkTables.requireDryRunShape(set.copy(tables = set.tables + extra)) }
        assertTrue(e.message!!.contains("does not use") && e.message!!.contains("extra"), e.message)
    }

    @Test
    fun `no table may accept page-body appends and no free-standing page may be readable`() {
        rejected { AgentWorkTables.requireDryRunShape(withTable(AgentWorkTables.CYCLES) { it.copy(pageBodyAppend = true) }) }
        rejected {
            AgentWorkTables.requireDryRunShape(set.copy(readablePages = listOf(app.alfrd.engram.notion.NotionReadablePage("home", "99999999-9999-4999-8999-999999999999"))))
        }
    }

    // ── The guard enforces the promise ───────────────────────────────────────

    @Test
    fun `an attempted create in any PPS table is refused by the guard before a request is made`() {
        val before = fake.requests.size
        for (pps in AgentWorkTables.PPS) {
            val e = violation { api.createPage(set.table(pps).dataSourceId, mapOf(titleOf(pps) to NotionValue.Title("sneaky"))) }
            assertTrue(e.message!!.contains("read-only") && e.message!!.contains(pps), e.message)
        }
        assertEquals(before, fake.requests.size, "a write to a PPS table reached Notion")
    }

    @Test
    fun `an attempted update or append to a PPS row is refused and the row is unchanged`() = runBlocking {
        val claim = fake.createPage(template.claims, mapOf("Claim" to NotionValue.Title("original claim")))
        api.query(template.claims)
        val before = fake.requestCount()

        violation { api.updatePage(claim.id, mapOf("Claim" to NotionValue.Title("rewritten"))) }
        violation { api.updatePage(claim.id, mapOf("Claimed by" to NotionValue.Select("PPS"))) }
        violation { api.appendBlocks(claim.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("note"))))) }

        assertEquals(before, fake.requestCount(), "a refused write reached Notion")
        assertEquals("original claim", fake.page(claim.id).title("Claim"))
    }

    @Test
    fun `PPS rows can be read, and a page read returns the formula and rollup values a query leaves out`() = runBlocking {
        val claim = fake.createPage(template.claims, mapOf("Claim" to NotionValue.Title("a claim")))
        fake.setComputed(claim.id, "Confidence", NotionValue.Formula("number", number = 0.62))
        fake.setComputed(claim.id, "Best Support", NotionValue.Rollup("number", function = "max", number = 4.0))

        val queried = api.query(template.claims).pages.single()
        assertEquals(NotionValue.Unsupported("formula"), queried.properties["Confidence"])

        val read = api.retrievePage(claim.id)
        assertEquals(0.62, read.formula("Confidence")!!.number)
        assertEquals(4.0, read.rollup("Best Support")!!.number)
    }

    @Test
    fun `Goals and Escalations are read-only in this increment`() = runBlocking {
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
    fun `a relation into a read-only table is refused, so the Goal of a Cycle and the rows of Goals are never touched`() = runBlocking {
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

    // ── Isolation from the alfrd memory sets ─────────────────────────────────

    @Test
    fun `an alfrd memory set cannot share this token or any of these tables`() {
        val memory = NotionDataSources(
            "00000001-aaaa-4aaa-8aaa-000000000001", "00000001-aaaa-4aaa-8aaa-000000000002", "00000001-aaaa-4aaa-8aaa-000000000003",
            "00000001-aaaa-4aaa-8aaa-000000000004", "00000001-aaaa-4aaa-8aaa-000000000005", "00000001-aaaa-4aaa-8aaa-000000000006",
        )
        NotionSetRegistry(listOf(config, NotionIdentityConfig("jacob", "env:ALFRD_NOTION_TOKEN_JACOB", memory)))

        val sharesToken = NotionIdentityConfig("jacob", config.tokenRef, memory)
        assertTrue(rejected { NotionSetRegistry(listOf(config, sharesToken)) }.message!!.contains("share a token"))

        val sharesTable = NotionIdentityConfig("jacob", "env:OTHER", memory.copy(sessions = set.table(AgentWorkTables.CYCLES).dataSourceId))
        assertTrue(rejected { NotionSetRegistry(listOf(config, sharesTable)) }.message!!.contains("belongs to one set"))
    }

    @Test
    fun `a memory set that reuses one of these database ids is also refused`() {
        val memory = NotionDataSources(
            "00000001-aaaa-4aaa-8aaa-000000000001", "00000001-aaaa-4aaa-8aaa-000000000002", "00000001-aaaa-4aaa-8aaa-000000000003",
            "00000001-aaaa-4aaa-8aaa-000000000004", "00000001-aaaa-4aaa-8aaa-000000000005", "00000001-aaaa-4aaa-8aaa-000000000006",
        )
        val workerDatabase = set.table(AgentWorkTables.SOURCES).databaseId!!
        val reusesDatabase = NotionTableSet("other", listOf(NotionTable("t", memory.items, databaseId = workerDatabase)))
        val binding = object : app.alfrd.engram.notion.NotionSetBinding {
            override val tokenRef = "env:OTHER"
            override val tableSet = reusesDatabase
        }
        assertTrue(rejected { NotionSetRegistry(listOf(config, binding)) }.message!!.contains("belongs to one set"))
    }

    private fun titleOf(table: String): String = when (table) {
        AgentWorkTables.QUESTIONS -> "Question"
        AgentWorkTables.CLAIMS -> "Claim"
        AgentWorkTables.SOURCES -> "Title"
        else -> "Evidence"
    }
}
