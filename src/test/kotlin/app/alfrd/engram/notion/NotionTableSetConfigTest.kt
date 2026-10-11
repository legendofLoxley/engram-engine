package app.alfrd.engram.notion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotionTableSetConfigTest {

    private fun id(n: Int) = "%08x-abcd-4abc-8abc-%012x".format(n, n)

    private val goodTables = """
        [
          {"name":"cycles","dataSourceId":"collection://${id(1)}","access":"read-write","pageBodyAppend":true},
          {"name":"tasks","dataSourceId":"${id(2)}","access":"read-write","idempotencyKeyProperty":"Op key"},
          {"name":"claims","dataSourceId":"${id(3).replace("-", "").uppercase()}","access":"read-only"},
          {"name":"sources","dataSourceId":"collection://${id(4)}","access":"read-only","allowIncomingRelations":true}
        ]
    """.trimIndent()

    private fun json(
        setName: String = "agent-worker",
        tokenRef: String = "env:ALFRD_WORKER_NOTION_TOKEN",
        tables: String = goodTables,
        extra: String = "",
    ) = """{"setName":"$setName","tokenRef":"$tokenRef","tables":$tables$extra}"""

    private fun rejected(text: String): NotionConfigException =
        assertThrows(NotionConfigException::class.java) { NotionTableSetConfig.parse(text) }

    @Test
    fun `a well-formed configuration is parsed, with ids normalized whatever their spelling`() {
        val config = NotionTableSetConfig.parse(json(extra = ""","readablePages":[{"name":"home","pageId":"https://www.notion.so/Home-${id(9).replace("-", "")}"}]"""))
        assertEquals("env:ALFRD_WORKER_NOTION_TOKEN", config.tokenRef)
        val set = config.tableSet
        assertEquals("agent-worker", set.name)
        assertEquals(listOf("cycles", "tasks", "claims", "sources"), set.tables.map { it.name })
        assertEquals(id(1), set.table("cycles").dataSourceId)
        assertEquals(id(3), set.table("claims").dataSourceId)
        assertEquals(setOf(id(9)), set.readablePageIds)
    }

    @Test
    fun `each table carries its own access mode and options`() {
        val set = NotionTableSetConfig.parse(json()).tableSet
        assertEquals(TableAccess.READ_WRITE, set.table("cycles").access)
        assertTrue(set.table("cycles").pageBodyAppend)
        assertEquals("Op key", set.table("tasks").idempotencyKeyProperty)
        assertFalse(set.table("tasks").pageBodyAppend)
        assertEquals(TableAccess.READ_ONLY, set.table("claims").access)
        assertFalse(set.table("claims").allowIncomingRelations)
        assertTrue(set.table("sources").allowIncomingRelations)
        assertTrue(set.table("tasks").allowIncomingRelations)
        assertNull(set.table("claims").idempotencyKeyProperty)
    }

    @Test
    fun `access has no default, so every table has to say whether it can be written`() {
        val e = rejected(json(tables = """[{"name":"goals","dataSourceId":"${id(1)}"}]"""))
        assertTrue(e.message!!.contains("tables[0].access"))
    }

    @Test
    fun `access must be one of the two labels exactly`() {
        for (bad in listOf("readonly", "Read-Only", "rw", "write", "true")) {
            val e = rejected(json(tables = """[{"name":"goals","dataSourceId":"${id(1)}","access":"$bad"}]"""))
            assertTrue(e.message!!.contains("\"read-only\" or \"read-write\""), e.message)
        }
    }

    @Test
    fun `a URL is refused as a data source id because it names a database or a page`() {
        val e = rejected(json(tables = """[{"name":"goals","dataSourceId":"https://www.notion.so/${id(1).replace("-", "")}","access":"read-write"}]"""))
        assertTrue(e.message!!.contains("not a URL"), e.message)
        assertTrue(e.message!!.contains("tables[0].dataSourceId"))
    }

    @Test
    fun `something that is not an id is refused`() {
        assertTrue(rejected(json(tables = """[{"name":"goals","dataSourceId":"not-an-id","access":"read-write"}]""")).message!!.contains("not a Notion id"))
    }

    @Test
    fun `unknown keys are rejected at every level, so a misspelling cannot leave a table unconfigured`() {
        assertTrue(rejected(json().replace("\"tables\"", "\"tabels\"")).message!!.contains("tabels"))
        assertTrue(rejected(json(extra = ""","extra":1""")).message!!.contains("extra"))
        assertTrue(rejected(json(tables = """[{"name":"g","dataSourceId":"${id(1)}","access":"read-write","acess":"x"}]""")).message!!.contains("acess"))
        assertTrue(rejected(json(extra = ""","readablePages":[{"name":"p","pageId":"${id(9)}","nmae":"x"}]""")).message!!.contains("nmae"))
    }

    @Test
    fun `structure errors name the offending field`() {
        assertTrue(rejected("not json").message!!.contains("not a JSON object"))
        assertTrue(rejected("""{"setName":"s","tokenRef":"env:X"}""").message!!.contains("tables is required"))
        assertTrue(rejected("""{"setName":"s","tokenRef":"env:X","tables":{}}""").message!!.contains("tables is required"))
        assertTrue(rejected("""{"setName":"s","tokenRef":"env:X","tables":[]}""").message!!.contains("at least one table"))
        assertTrue(rejected(json(tables = """["goals"]""")).message!!.contains("tables[0] must be an object"))
        assertTrue(rejected(json(extra = ""","readablePages":{}""")).message!!.contains("readablePages must be an array"))
        assertTrue(rejected(json().replace("\"setName\":\"agent-worker\",", "")).message!!.contains("setName is required"))
        assertTrue(rejected(json(tables = """[{"dataSourceId":"${id(1)}","access":"read-write"}]""")).message!!.contains("tables[0].name"))
    }

    @Test
    fun `option values must be real booleans`() {
        for (bad in listOf("\"yes\"", "1", "null", "\"true\"")) {
            val e = rejected(json(tables = """[{"name":"g","dataSourceId":"${id(1)}","access":"read-write","pageBodyAppend":$bad}]"""))
            assertTrue(e.message!!.contains("tables[0].pageBodyAppend must be true or false"), e.message)
        }
    }

    @Test
    fun `a read-only table cannot be configured with write options`() {
        assertTrue(rejected(json(tables = """[{"name":"g","dataSourceId":"${id(1)}","access":"read-only","pageBodyAppend":true}]""")).message!!.contains("read-only"))
        assertTrue(rejected(json(tables = """[{"name":"g","dataSourceId":"${id(1)}","access":"read-only","idempotencyKeyProperty":"Op key"}]""")).message!!.contains("read-only"))
    }

    @Test
    fun `duplicate tables are rejected however the id is spelled`() {
        val dup = """[{"name":"a","dataSourceId":"${id(1)}","access":"read-write"},{"name":"b","dataSourceId":"collection://${id(1).replace("-", "")}","access":"read-only"}]"""
        assertTrue(rejected(json(tables = dup)).message!!.contains("same data source"))
        val names = """[{"name":"a","dataSourceId":"${id(1)}","access":"read-write"},{"name":"a","dataSourceId":"${id(2)}","access":"read-write"}]"""
        assertTrue(rejected(json(tables = names)).message!!.contains("two tables named 'a'"))
    }

    @Test
    fun `a readable page cannot be a table`() {
        val e = rejected(json(extra = ""","readablePages":[{"name":"p","pageId":"${id(1)}"}]"""))
        assertTrue(e.message!!.contains("id of a table"))
    }

    @Test
    fun `a token pasted where a reference belongs is refused and never echoed`() {
        val pasted = "ntn_1234567890abcdefSECRETSECRETSECRET"
        val e = rejected(json(tokenRef = pasted))
        assertTrue(e.message!!.contains("tokenRef must be"))
        assertFalse(e.message!!.contains("SECRET"), "the refusal echoed what was pasted")
        rejected(json(tokenRef = "env:"))
        rejected(json(tokenRef = "file:"))
    }

    // ── Strictness that must not fail open ──────────────────────────────────

    private fun oneTable(dataSourceId: String, extra: String = "") =
        json(tables = """[{"name":"goals","dataSourceId":"$dataSourceId","access":"read-write"$extra}]""")

    @Test
    fun `a data source id with any text around it is refused rather than quietly extracted`() {
        val bare = id(1)
        val noDashes = bare.replace("-", "")
        val surrounded = listOf(
            "collections://$bare",
            "garbage-$bare",
            "notion.so/Some-Database-$noDashes",
            "www.notion.so/$noDashes?v=$noDashes",
            "$bare?v=abc",
            "collection://$bare/",
            "COLLECTION://$bare",
            "${bare}x",
            "db:$bare",
        )
        for (bad in surrounded) {
            val e = rejected(oneTable(bad))
            assertTrue(e.message!!.contains("exact collection:// reference"), "accepted or mis-reported '$bad': ${e.message}")
        }
    }

    @Test
    fun `the plain spellings of a data source id are all accepted and normalized`() {
        val bare = id(1)
        for (ok in listOf(bare, bare.replace("-", ""), bare.uppercase(), "collection://$bare", "collection://${bare.replace("-", "")}", "collection://${bare.uppercase()}")) {
            assertEquals(bare, NotionTableSetConfig.parse(oneTable(ok)).tableSet.table("goals").dataSourceId, "rejected or mis-normalized '$ok'")
        }
    }

    @Test
    fun `an idempotency key property that is present but unusable is an error, not a silent opt-out`() {
        for (bad in listOf("\"\"", "\"   \"", "123", "null", "true", "[]", "{}")) {
            val e = rejected(oneTable(id(1), ""","idempotencyKeyProperty":$bad"""))
            assertTrue(e.message!!.contains("tables[0].idempotencyKeyProperty must be a non-empty string"), "'$bad' was accepted or mis-reported: ${e.message}")
        }
    }

    @Test
    fun `leaving the idempotency key property out opts out, and a real name opts in`() {
        assertNull(NotionTableSetConfig.parse(oneTable(id(1))).tableSet.table("goals").idempotencyKeyProperty)
        assertEquals("Op key", NotionTableSetConfig.parse(oneTable(id(1), ""","idempotencyKeyProperty":"Op key"""")).tableSet.table("goals").idempotencyKeyProperty)
    }

    @Test
    fun `a file token reference is accepted`() {
        assertEquals("file:/run/secrets/alfrd-worker", NotionTableSetConfig.parse(json(tokenRef = "file:/run/secrets/alfrd-worker")).tokenRef)
    }

    @Test
    fun `the configuration implements the shared binding, and a read-only view of it still has the same token and tables`() {
        val binding: NotionSetBinding = NotionTableSetConfig.parse(json())
        val view = binding.tableSet.withReadOnly(listOf("claims", "sources"))
        assertEquals(binding.tableSet.tables.map { it.dataSourceId }, view.tables.map { it.dataSourceId })
        assertEquals(TableAccess.READ_ONLY, view.table("sources").access)
    }
}
