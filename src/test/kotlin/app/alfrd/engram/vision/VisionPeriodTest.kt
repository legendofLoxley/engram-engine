package app.alfrd.engram.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which period a value carries: the page's own statement in code, a heading's, a figure's for what it says, and never a caption's for prose. */
class VisionPeriodTest {

    private val page1 = """
        RIGHTSIZING

        WHAT GUIDES THE WORK
        Well-resourced schools
        Consistent access to strong academic programming.
        PPS equity lens

        The three scenarios

        All figures are projections for the
        2031–32 school year unless noted.

        SCENARIO A
        Closures bring about two thirds of schools up to a sustainable size. About 80% of students will attend a sustainable school.
        STATUS QUO
        About 48% of students will attend a sustainable school. In 2023–24 about 5% left.

        District-wide, side by side
        Measure SCENARIO A SCENARIO B
        Schools or sites that would close 14 11
        Students who would change schools 27% 24%
    """.trimIndent()

    private fun built(json: String, page: String): VisionReading.Built {
        val sections = PageStructure.parse(json)
        return VisionReading.build(sections, StructureCheck.check(sections, page))
    }

    @Test
    fun `the period a page states about itself is applied in code to its figures, with no help from the model`() {
        // The model put no period anywhere.
        val b = built("""{"sections": [
            {"heading": "WHAT GUIDES THE WORK", "items": ["Well-resourced schools", "PPS equity lens"]},
            {"heading": "The three scenarios", "subsections": [
              {"heading": "SCENARIO A", "numbers": [{"value": "80%", "label": "of students will attend a sustainable school",
                  "passage": "About 80% of students will attend a sustainable school."}]}]},
            {"heading": "District-wide, side by side", "tables": [[["Measure", "SCENARIO A", "SCENARIO B"],
                ["Schools or sites that would close", "14", "11"], ["Students who would change schools", "27%", "24%"]]]}]}""", page1)
        val cells = b.entries.filter { it.kind == "cell" }
        assertEquals(4, cells.size)
        assertTrue(cells.all { it.asOf == "2031–32 school year" }, cells.toString())
        assertEquals("2031–32 school year", b.entries.single { it.kind == "number" }.asOf)
        assertTrue(b.entries.filter { it.kind == "item" }.all { it.asOf == null }, "list items under WHAT GUIDES THE WORK carry no period")
    }

    @Test
    fun `the model's hyphenated section period is the page's, printed as the page prints it`() {
        val b = built("""{"sections": [{"heading": "The three scenarios", "asOf": "2031-32 school year", "subsections": [
              {"heading": "SCENARIO A", "numbers": [{"value": "80%", "label": "of students will attend a sustainable school",
                  "passage": "About 80% of students will attend a sustainable school."}]}]}]}""", page1)
        assertEquals("2031–32 school year", b.entries.single { it.kind == "number" }.asOf)
    }

    @Test
    fun `a value whose own passage names another year keeps that year, not the page's`() {
        val b = built("""{"sections": [{"heading": "STATUS QUO", "numbers": [
            {"value": "5%", "label": "left", "passage": "In 2023–24 about 5% left.", "asOf": "2023–24"},
            {"value": "48%", "label": "of students will attend a sustainable school", "passage": "About 48% of students will attend a sustainable school."}]}]}""", page1)
        assertEquals("2023–24", b.entries.single { it.value == "5%" }.asOf)
        assertEquals("2031–32 school year", b.entries.single { it.value == "48%" }.asOf)
    }

    private val page66 = """
        Lewis Elementary School
        Anticipated SEGC Changes
        a boundary change (which made its catchment smaller)
        District documents identified Lewis as a school that had exceeded the district's 80% target utilization in recent years, with a 2025–26 forecast utilization of 80.6%.
        Figure 5.1. Lewis estimated, annual, and October 1 enrollment, 2020–21 through 2024–25.
        Note: Annual enrollment is based on assignment of students.
    """.trimIndent()

    @Test
    fun `a range the model took from a figure caption does not become the period of prose`() {
        val b = built("""{"sections": [{"heading": "Lewis Elementary School", "subsections": [
            {"heading": "Anticipated SEGC Changes", "asOf": "2020–21 through 2024–25",
              "items": ["a boundary change (which made its catchment smaller)"],
              "numbers": [{"value": "80%", "label": "target utilization", "passage": "District documents identified Lewis as a school that had exceeded the district's 80% target utilization in recent years"},
                          {"value": "80.6%", "label": "forecast utilization", "passage": "with a 2025–26 forecast utilization of 80.6%", "asOf": "2025–26"}]}]}]}""", page66)
        assertNull(b.entries.single { it.kind == "item" }.asOf)
        assertNull(b.entries.single { it.value == "80%" }.asOf, "'in recent years' prints no period")
        assertEquals("2025–26", b.entries.single { it.value == "80.6%" }.asOf)
    }

    @Test
    fun `the same range still covers what the figure itself says`() {
        val page = "Figure 2.3 Changes in student composition at Clark, 2020–21 to 2024–25\nStudents of color 31% 33% 78%\nMeasure 2020–21 2021–22 2023–24"
        val b = built("""{"sections": [{"heading": "Figure 2.3 Changes in student composition at Clark, 2020–21 to 2024–25", "asOf": "2020–21 to 2024–25",
            "tables": [[["Measure", "2020–21", "2021–22", "2023–24"], ["Students of color", "31%", "33%", "78%"]]]}]}""", page)
        val cells = b.entries.filter { it.kind == "cell" }
        assertEquals(3, cells.size)
        assertTrue(cells.all { it.asOf == "2020–21 to 2024–25" }, cells.toString())
    }

    @Test
    fun `a period the model gives a value that its own words do not print is dropped and reported`() {
        val page = "In the transition year of 2022–23, staffing turned over sharply. About 73% of that year's staff did not return to Bridger the following year."
        val sections = PageStructure.parse("""{"sections": [{"heading": "Staffing", "numbers": [
            {"value": "73%", "label": "of that year's staff did not return", "passage": "About 73% of that year's staff did not return to Bridger the following year.", "asOf": "2022–23"}]}]}""")
        val b = VisionReading.build(sections, StructureCheck.check(sections, "Staffing\n$page"))
        assertNull(b.entries.single().asOf)
        assertTrue(b.rejected.any { it.contains("period '2022–23' not in its passage, label or heading") && it.endsWith("(value kept)") }, b.rejected.toString())
    }

    @Test
    fun `a period the page states survives to the claim, which keeps a period only when its chunk prints it`() = kotlinx.coroutines.runBlocking<Unit> {
        // The box gate: entries carried the period, but the chunk text did not print it, so every claim lost it in ClaimAgreement.context.
        val sections = PageStructure.parse("""{"sections": [{"heading": "The three scenarios", "subsections": [
              {"heading": "SCENARIO A", "numbers": [{"value": "80%", "label": "of students will attend a sustainable school",
                  "passage": "About 80% of students will attend a sustainable school."}]}]}]}""")
        val b = VisionReading.build(sections, StructureCheck.check(sections, page1))
        assertTrue(b.text.lines().contains("Period: 2031–32 school year"), b.text)
        val chunk = app.alfrd.engram.db.ChunkRecord("c", "s", 6, "pdf p. 1, vision reading", b.text, "h", kind = VisionReading.CHUNK_KIND, extractor = "vision:m",
            reading = kotlinx.serialization.json.Json.encodeToString(b.entries))
        val claim = app.alfrd.engram.claims.StructuredClaimModel().extract(
            app.alfrd.engram.db.SourceRecord("s", "pdf", "file:x.pdf", "x", "pdf", null, "h", 0, 10), chunk, 1).claims.single()
        assertEquals("2031–32 school year", claim.asOf)
        assertEquals("2031–32 school year", app.alfrd.engram.claims.ClaimAgreement.context(chunk, claim, claim).asOf)
    }

    @Test
    fun `the date a document was updated is not the period of its figures`() {
        val page = "What changes under each scenario\nSCENARIO A\n14 schools close\nUpdated October 4, 2026"
        val b = built("""{"sections": [{"heading": "What changes under each scenario", "asOf": "Updated October 4, 2026",
            "subsections": [{"heading": "SCENARIO A", "numbers": [{"value": "14", "label": "schools close"}]}]}]}""", page)
        assertTrue(b.entries.isNotEmpty() && b.entries.all { it.asOf == null }, b.entries.toString())
    }
}
