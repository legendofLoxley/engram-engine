package app.alfrd.engram.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StructureCheckTest {

    private val page = """
        STATUS QUO
        0 schools close 26% of schools above sustainability threshold
        SCENARIO A
        14 schools close 67%
        SCHOOLS CLOSING
        14
        Beach Buckman Creston
        ATTENDANCE BOUNDARY CHANGES
        38
        Abernethy Arleta
        SCENARIO B
        11 schools close 57%
        ATTENDANCE BOUNDARY CHANGES
        34
        Updated October 4, 2026 – 138 pages
    """.trimIndent()

    private fun s(h: String, numbers: List<String> = emptyList(), sub: List<PageSection> = emptyList(), items: List<String> = emptyList()) =
        PageSection(h, numbers = numbers, items = items, subsections = sub)

    private val right = listOf(
        s("STATUS QUO", listOf("0", "26%")),
        s("SCENARIO A", listOf("14", "67%"), listOf(s("SCHOOLS CLOSING", listOf("14"), items = listOf("Beach")), s("ATTENDANCE BOUNDARY CHANGES", listOf("38")))),
        s("SCENARIO B", listOf("11", "57%"), listOf(s("ATTENDANCE BOUNDARY CHANGES", listOf("34")))),
    )

    @Test
    fun `everything a correct reading reports is found, and under the heading it was given`() {
        val checks = StructureCheck.check(right, page)
        assertTrue(checks.all { it.found }, checks.filter { !it.found }.toString())
        val values = checks.filter { it.kind != "heading" }
        assertTrue(values.all { it.inSection == true }, values.filter { it.inSection != true }.toString())
        assertEquals(listOf("SCENARIO A", "ATTENDANCE BOUNDARY CHANGES"), values.single { it.value == "38" }.path)
    }

    @Test
    fun `a count filed under the wrong scenario is found on the page but not under that heading`() {
        val swapped = listOf(
            s("SCENARIO A", sub = listOf(s("ATTENDANCE BOUNDARY CHANGES", listOf("34")))),
            s("SCENARIO B", sub = listOf(s("ATTENDANCE BOUNDARY CHANGES", listOf("38")))),
        )
        val checks = StructureCheck.check(swapped, page).filter { it.kind == "number" }
        assertTrue(checks.all { it.found })
        assertTrue(checks.all { it.inSection == false }, checks.toString())
    }

    @Test
    fun `a number that is not on the page is not found, and a number inside a longer number does not count`() {
        val checks = StructureCheck.check(listOf(s("SCENARIO B", listOf("13", "1", "38"))), page).associateBy { it.value }
        assertFalse(checks.getValue("13").found, "13 only occurs inside 138")
        assertFalse(checks.getValue("1").found, "1 only occurs inside 11, 14, 138, 2026")
        assertTrue(checks.getValue("38").found)
        assertFalse(checks.getValue("38").inSection!!, "the only 38 is in A's section")
    }

    @Test
    fun `case, spacing and typographic dashes do not matter, and OCR text counts for presence only`() {
        val checks = StructureCheck.check(listOf(s("scenario  a", listOf("67 %")), s("Chart", listOf("82%"))), page.replace("67%", "67 %"), ocrText = "Chart 82%")
        val byValue = checks.associateBy { it.value }
        assertTrue(byValue.getValue("scenario  a").found)
        assertTrue(byValue.getValue("67 %").found)
        assertTrue(byValue.getValue("82%").found, "found in the OCR transcript")
        assertNull(byValue.getValue("82%").inSection, "its heading is not in the reading-order text, so its section cannot be judged")
        assertEquals(StructureCheck.normalize("Updated October 4, 2026 - 138"), StructureCheck.normalize("updated  October 4, 2026 – 138"))
    }

    @Test
    fun `a heading the page does not have is reported as not found`() {
        val c = StructureCheck.check(listOf(s("SCENARIO C", listOf("5"))), page)
        assertFalse(c.single { it.kind == "heading" }.found)
    }

    @Test
    fun `the answer is read from the first JSON object, fences and prose ignored, and an unreadable answer is refused`() {
        val answer = "Here you go:\n```json\n{\"sections\": [{\"heading\": \"SCENARIO A\", \"numbers\": [\"14\", 67], \"tables\": [[[\"Measure\", \"A\"], [\"Close\", \"14\"]]], " +
            "\"charts\": [{\"title\": \"Enrollment\", \"values\": [\"82%\"]}], \"subsections\": [{\"heading\": \"CARD\", \"numbers\": [\"38\"]}, {\"numbers\": [\"9\"]}]}]}\n```"
        val sections = PageStructure.parse(answer)
        val a = sections.single()
        assertEquals(listOf("14", "67"), a.numbers, "a bare JSON number is kept as its text")
        assertEquals(listOf(listOf("Measure", "A"), listOf("Close", "14")), a.tables.single())
        assertEquals("Enrollment", a.charts.single().title)
        assertEquals(listOf("CARD"), a.subsections.map { it.heading }, "a section with no heading is dropped")
        assertThrows<IllegalArgumentException> { PageStructure.parse("no json here") }
        assertThrows<IllegalArgumentException> { PageStructure.parse("{\"pages\": []}") }
        assertThrows<IllegalArgumentException> { PageStructure.parse("{not json}") }
    }
}
