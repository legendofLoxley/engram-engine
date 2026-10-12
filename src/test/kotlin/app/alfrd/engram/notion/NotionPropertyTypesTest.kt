package app.alfrd.engram.notion

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Number and URL are ordinary values; auto-increment ids, created time, formulas, rollups and
 * unmodelled types are computed by Notion. This is where the fake models that, and where the guard
 * refuses a write that carries one.
 */
class NotionPropertyTypesTest {

    private lateinit var fake: FakeNotionApi
    private lateinit var table: String
    private lateinit var api: AllowlistedNotionApi

    @BeforeEach
    fun setUp() {
        fake = FakeNotionApi()
        table = fake.defineDataSource(
            mapOf(
                "Goal" to FakeProperty.Title,
                "Goal ID" to FakeProperty.UniqueId("GOAL"),
                "Budget" to FakeProperty.Number,
                "URL" to FakeProperty.Url,
                "Confidence" to FakeProperty.Formula,
                "Best" to FakeProperty.Rollup,
                "Notes" to FakeProperty.RichText,
            ),
        )
        api = AllowlistedNotionApi(fake, NotionTableSet("types", listOf(NotionTable("goals", table))))
    }

    private fun violation(block: suspend () -> Unit): NotionGuardViolation =
        assertThrows(NotionGuardViolation::class.java) { runBlocking { block() } }

    // ── Number and URL are plain values ──────────────────────────────────────

    @Test
    fun `numbers and urls are written, read back, and cleared`() = runBlocking {
        val row = api.createPage(
            table,
            mapOf("Goal" to NotionValue.Title("g"), "Budget" to NotionValue.NumberValue(10.0), "URL" to NotionValue.UrlValue("https://example.org/a")),
        )
        assertEquals(10.0, fake.page(row.id).number("Budget"))
        assertEquals("https://example.org/a", fake.page(row.id).url("URL"))

        api.updatePage(row.id, mapOf("Budget" to NotionValue.NumberValue(null), "URL" to NotionValue.UrlValue(null)))
        assertNull(fake.page(row.id).number("Budget"))
        assertNull(fake.page(row.id).url("URL"))
    }

    @Test
    fun `a number cannot be written to a url property or the reverse`() {
        val e = assertThrows(NotionApiException::class.java) {
            runBlocking { fake.createPage(table, mapOf("Goal" to NotionValue.Title("g"), "URL" to NotionValue.NumberValue(1.0))) }
        }
        assertEquals(400, e.status)
        assertThrows(NotionApiException::class.java) {
            runBlocking { fake.createPage(table, mapOf("Goal" to NotionValue.Title("g"), "Budget" to NotionValue.UrlValue("https://x.org"))) }
        }
    }

    @Test
    fun `rows can be sorted by a number property numerically, not as text`() = runBlocking {
        listOf(10.0, 2.0, 33.0, 1.0).forEach { fake.createPage(table, mapOf("Goal" to NotionValue.Title("g$it"), "Budget" to NotionValue.NumberValue(it))) }
        val sorted = fake.query(table, sorts = listOf(NotionSort.ByProperty("Budget"))).pages.map { it.number("Budget") }
        assertEquals(listOf(1.0, 2.0, 10.0, 33.0), sorted)
    }

    // ── What the fake models of Notion's computed values ─────────────────────

    @Test
    fun `auto-increment ids are assigned on create in order, and never repeat`() = runBlocking {
        val first = fake.createPage(table, mapOf("Goal" to NotionValue.Title("a")))
        val second = fake.createPage(table, mapOf("Goal" to NotionValue.Title("b")))
        assertEquals("GOAL-1", fake.page(first.id).uniqueId("Goal ID")!!.display)
        assertEquals("GOAL-2", fake.page(second.id).uniqueId("Goal ID")!!.display)
    }

    @Test
    fun `the fake rejects a direct write to a computed property, as Notion does`() {
        for ((name, value) in listOf<Pair<String, NotionValue>>(
            "Goal ID" to NotionValue.UniqueId("GOAL", 99),
            "Confidence" to NotionValue.Formula("number", number = 1.0),
            "Best" to NotionValue.Rollup("number", number = 1.0),
        )) {
            val e = assertThrows(NotionApiException::class.java) { runBlocking { fake.createPage(table, mapOf("Goal" to NotionValue.Title("g"), name to value)) } }
            assertEquals(400, e.status, name)
            assertTrue(e.message!!.contains("computed"), e.message)
        }
    }

    @Test
    fun `formulas and rollups are left out of a query and present in a page read`() = runBlocking {
        val row = fake.createPage(table, mapOf("Goal" to NotionValue.Title("g")))
        fake.setComputed(row.id, "Confidence", NotionValue.Formula("number", number = 0.6))
        fake.setComputed(row.id, "Best", NotionValue.Rollup("number", function = "max", number = 4.0))

        val queried = fake.query(table).pages.single()
        assertEquals(NotionValue.Unsupported("formula"), queried.properties["Confidence"])
        assertEquals(NotionValue.Unsupported("rollup"), queried.properties["Best"])

        val read = fake.retrievePage(row.id)
        assertEquals(0.6, read.formula("Confidence")!!.number)
        assertEquals(4.0, read.rollup("Best")!!.number)
    }

    @Test
    fun `setComputed only accepts the right kind of value for a property`() = runBlocking<Unit> {
        val row = fake.createPage(table, mapOf("Goal" to NotionValue.Title("g")))
        assertThrows(IllegalArgumentException::class.java) { fake.setComputed(row.id, "Confidence", NotionValue.Rollup("number")) }
        assertThrows(IllegalArgumentException::class.java) { fake.setComputed(row.id, "Budget", NotionValue.Formula("number")) }
    }

    // ── The guard refuses to carry a computed value ──────────────────────────

    private val computed = listOf<Pair<String, NotionValue>>(
        "Goal ID" to NotionValue.UniqueId("SECRETPREFIX", 4242),
        "Confidence" to NotionValue.Formula("string", string = "SECRETFORMULA"),
        "Best" to NotionValue.Rollup("number", number = 31337.0),
        "Created" to NotionValue.CreatedTime(Instant.EPOCH),
        "Mystery" to NotionValue.Unsupported("people"),
    )

    @Test
    fun `a create carrying a computed value is refused before any request is made`() {
        for ((name, value) in computed) {
            val e = violation { api.createPage(table, mapOf("Goal" to NotionValue.Title("g"), name to value)) }
            assertTrue(e.message!!.contains("'$name'") && e.message!!.contains(value.kindName), e.message)
        }
        assertEquals(0, fake.requests.size, "a request reached Notion for a write that cannot succeed")
        assertTrue(fake.rows(table).isEmpty())
    }

    @Test
    fun `an update carrying a computed value is refused and changes nothing`() = runBlocking {
        val row = api.createPage(table, mapOf("Goal" to NotionValue.Title("g")))
        val before = fake.requestCount()
        for ((name, value) in computed) {
            violation { api.updatePage(row.id, mapOf(name to value)) }
        }
        assertEquals(before, fake.requestCount(), "a refused update reached Notion")
        assertEquals("g", fake.page(row.id).title("Goal"))
    }

    @Test
    fun `a computed value hidden among ordinary ones still stops the whole write`() = runBlocking {
        val row = api.createPage(table, mapOf("Goal" to NotionValue.Title("g")))
        violation { api.updatePage(row.id, mapOf("Budget" to NotionValue.NumberValue(5.0), "Goal ID" to NotionValue.UniqueId("GOAL", 7))) }
        assertNull(fake.page(row.id).number("Budget"), "the ordinary half of a refused write was applied")
    }

    @Test
    fun `a refusal about a computed value names the property and kind, never the value`() {
        val e = violation { api.createPage(table, mapOf("Goal" to NotionValue.Title("g"), "Confidence" to NotionValue.Formula("string", string = "SECRETFORMULA"))) }
        assertFalse(e.message!!.contains("SECRETFORMULA"))
        assertTrue(e.message!!.contains("Confidence"))
        assertTrue(e.message!!.contains("Formula"))
    }

    @Test
    fun `values Notion assigns or computes say so, and ordinary values do not`() {
        assertTrue(listOf(NotionValue.UniqueId(null, 1), NotionValue.Formula("string"), NotionValue.Rollup("array"), NotionValue.CreatedTime(Instant.EPOCH), NotionValue.Unsupported("x")).all { it.isReadOnly })
        assertTrue(
            listOf(
                NotionValue.Title("t"), NotionValue.RichText("t"), NotionValue.NumberValue(1.0), NotionValue.UrlValue("u"), NotionValue.Select("s"),
                NotionValue.Checkbox(true), NotionValue.DateValue("2026-10-12"), NotionValue.Relation(emptyList()),
            ).none { it.isReadOnly },
        )
    }
}
