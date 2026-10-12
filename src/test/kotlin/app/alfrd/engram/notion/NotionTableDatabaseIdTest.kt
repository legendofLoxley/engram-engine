package app.alfrd.engram.notion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A table may record the id of its database, so a link written as a database page can be recognized. It is never sent to Notion. */
class NotionTableDatabaseIdTest {

    private fun id(n: Int) = "%08x-abcd-4abc-8abc-%012x".format(n, n)

    private fun rejected(block: () -> Unit): NotionConfigException = assertThrows(NotionConfigException::class.java, block)

    private fun parse(tables: String) = NotionTableSetConfig.parse("""{"setName":"s","tokenRef":"env:X","tables":$tables}""")

    private fun table(extra: String) = """[{"name":"sources","dataSourceId":"${id(1)}","access":"read-only"$extra}]"""

    @Test
    fun `a table records its database id, and a set finds the table from it in any spelling`() {
        val set = NotionTableSet("s", listOf(NotionTable("sources", id(1), TableAccess.READ_ONLY, databaseId = id(2))))
        assertEquals("sources", set.tableForDatabase(id(2))?.name)
        assertEquals("sources", set.tableForDatabase(id(2).replace("-", "").uppercase())?.name)
        assertNull(set.tableForDatabase(id(1)), "a data source id is not a database id")
        assertNull(set.tableForDatabase(id(9)))
        assertNull(set.tableForDatabase("not-an-id"))
    }

    @Test
    fun `the database id must be a normalized id of its own`() {
        rejected { NotionTable("t", id(1), databaseId = id(2).uppercase()) }
        rejected { NotionTable("t", id(1), databaseId = id(2).replace("-", "")) }
        rejected { NotionTable("t", id(1), databaseId = "not-an-id") }
        val same = rejected { NotionTable("t", id(1), databaseId = id(1)) }
        assertTrue(same.message!!.contains("same id"))
    }

    @Test
    fun `two tables of a set cannot have the same database id`() {
        val e = rejected { NotionTableSet("s", listOf(NotionTable("a", id(1), databaseId = id(5)), NotionTable("b", id(2), databaseId = id(5)))) }
        assertTrue(e.message!!.contains("same database id"))
    }

    @Test
    fun `making a table read-only keeps its database id`() {
        assertEquals(id(2), NotionTable("t", id(1), databaseId = id(2)).asReadOnly().databaseId)
    }

    @Test
    fun `the configuration accepts a bare database id in any spelling and normalizes it`() {
        for (spelling in listOf(id(2), id(2).replace("-", ""), id(2).uppercase())) {
            val set = parse(table(""","databaseId":"$spelling"""")).tableSet
            assertEquals(id(2), set.table("sources").databaseId, spelling)
        }
        assertNull(parse(table("")).tableSet.table("sources").databaseId)
    }

    @Test
    fun `the configuration refuses a database id that is a url, a collection reference, blank or not a string`() {
        for (bad in listOf("\"https://www.notion.so/${id(2).replace("-", "")}\"", "\"collection://${id(2)}\"", "\"x${id(2)}\"", "\"\"", "123", "null", "true")) {
            val e = rejected { parse(table(""","databaseId":$bad""")) }
            assertTrue(e.message!!.contains("tables[0].databaseId"), "'$bad' accepted or mis-reported: ${e.message}")
        }
    }

    @Test
    fun `a database id shared with another set is caught by the registry`() {
        fun binding(token: String, dataSource: Int, database: Int) = object : NotionSetBinding {
            override val tokenRef = token
            override val tableSet = NotionTableSet("set-$token", listOf(NotionTable("t", id(dataSource), databaseId = id(database))))
        }
        NotionSetRegistry(listOf(binding("env:A", 1, 2), binding("env:B", 3, 4)))
        val e = rejected { NotionSetRegistry(listOf(binding("env:A", 1, 2), binding("env:B", 3, 2))) }
        assertTrue(e.message!!.contains("belongs to one set"))
    }
}
