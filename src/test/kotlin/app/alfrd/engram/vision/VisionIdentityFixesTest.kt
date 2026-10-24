package app.alfrd.engram.vision

import app.alfrd.engram.claims.StructuredClaimModel
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.SourceRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fixes found on the box reading the PPS PDFs (ad49041): a wrong correction, lost list items, periods, and things named by headings. */
class VisionIdentityFixesTest {

    @Test
    fun `a legend label an image holds is not corrected to an ordinary word of the running text`() {
        // School by School p.86: "White" is printed only inside the chart image; the text layer has "while" in a sentence.
        val page = """
            Figure 6.2. Harrison Park composition, 2020–21 to 2024–25

            Note: Enrollment is measured in the building. The school managed multiple forms of change at once, while also maintaining stability.
            While these transitions were short, staff stayed.
        """.trimIndent()
        val sections = PageStructure.parse("""{"sections": [{"heading": "Figure 6.2. Harrison Park composition, 2020–21 to 2024–25",
            "charts": [{"title": "Figure 6.2. Harrison Park composition, 2020–21 to 2024–25", "values": ["White", "Students of color"]}]}]}""")
        val checks = StructureCheck.check(sections, page)
        val white = checks.single { it.kind == "chart" && it.value == "White" }
        assertFalse(white.found, "White is not on the page's text, and must not become 'while' or 'While'")
        assertNull(white.printedAs)
        val built = VisionReading.build(sections, checks)
        assertTrue(built.corrected.isEmpty(), built.corrected.toString())
        assertFalse(built.text.contains("while", ignoreCase = true) && built.text.lines().any { it.contains("; while") }, built.text)
    }

    @Test
    fun `a misread name is still corrected to the page's capitalized spelling`() {
        val page = "SCENARIO A\n\nATTENDANCE BOUNDARY CHANGES\n\nAbernethy Arleta Duniway Sitton"
        val sections = PageStructure.parse("""{"sections": [{"heading": "SCENARIO A", "subsections": [{"heading": "ATTENDANCE BOUNDARY CHANGES",
            "items": ["Abernethy Arleta Dunaway Sitton"]}]}]}""")
        val built = VisionReading.build(sections, StructureCheck.check(sections, page))
        assertEquals(listOf("Abernethy Arleta Duniway Sitton"), built.entries.filter { it.kind == "item" }.map { it.value })
        assertEquals(1, built.corrected.size); assertTrue(built.corrected[0].endsWith("Abernethy Arleta Duniway Sitton"))
    }

    @Test
    fun `list items given as objects are read under any usual key`() {
        val sections = PageStructure.parse("""{"sections": [{"heading": "WHAT GUIDES THE WORK", "items": [
            {"text": "Well-resourced schools", "passage": "Well-resourced schools Consistent access"},
            {"title": "Safe and healthy learning environments"},
            {"name": "Educational options"},
            {"heading": "PPS equity lens", "description": "Centering students"},
            "Plain string"]}]}""")
        assertEquals(listOf("Well-resourced schools", "Safe and healthy learning environments", "Educational options", "PPS equity lens", "Plain string"), sections.single().items)
        assertEquals("Well-resourced schools Consistent access", sections.single().itemContext["Well-resourced schools"]?.passage)
    }

    @Test
    fun `the prompt names the key an item's text goes under, the section period, and the earlier figure`() {
        assertTrue("\"text\"" in PageStructure.PROMPT)
        assertTrue("asOf" in PageStructure.PROMPT && "2031–32" in PageStructure.PROMPT)
        assertTrue("618" in PageStructure.PROMPT)
        assertEquals("structure-v6", PageStructure.VERSION)
    }

    @Test
    fun `a period the page states for a whole page covers the values under it, unless one gives its own`() {
        val page = """
            The three scenarios

            All figures are projections for the 2031–32 school year unless noted.

            SCENARIO A

            14 schools close
            About 80% of students will attend a sustainable school in 2029–30.
        """.trimIndent()
        val sections = PageStructure.parse("""{"sections": [{"heading": "The three scenarios", "asOf": "2031–32 school year", "subsections": [
            {"heading": "SCENARIO A", "numbers": [{"value": "14", "label": "schools close"}, {"value": "80%", "label": "students will attend a sustainable school",
              "passage": "About 80% of students will attend a sustainable school in 2029–30.", "asOf": "2029–30"}]}]}]}""")
        val built = VisionReading.build(sections, StructureCheck.check(sections, page))
        assertEquals("2031–32 school year", built.entries.single { it.value == "14" }.asOf)
        assertEquals("2029–30", built.entries.single { it.value == "80%" }.asOf)
    }

    @Test
    fun `a period the page does not print is not inherited, and the earlier figure of a from-to sentence gets none`() {
        val page = """
            Harrison Park

            In 2023–24, Harrison Park experienced the enrollment inflection from 618 students to 355 students, the shift in racial composition.
        """.trimIndent()
        val passage = "In 2023–24, Harrison Park experienced the enrollment inflection from 618 students to 355 students, the shift in racial composition."
        val sections = PageStructure.parse("""{"sections": [{"heading": "Harrison Park", "asOf": "2019–20", "numbers": [
            {"value": "618 students", "passage": "$passage", "asOf": "2023–24"},
            {"value": "355 students", "passage": "$passage", "asOf": "2023–24"}]}]}""")
        val built = VisionReading.build(sections, StructureCheck.check(sections, page))
        assertNull(built.entries.single { it.value == "618 students" }.asOf, "618 is the earlier figure: the sentence's year is not its year")
        assertEquals("2023–24", built.entries.single { it.value == "355 students" }.asOf)
        assertTrue(built.entries.none { it.asOf == "2019–20" }, "a period the page does not print is dropped, not kept")
    }

    @Test
    fun `a claim under a heading that names a scenario or a school is about it`() = runBlocking<Unit> {
        val entries = listOf(
            ReadingEntry("number", listOf("What changes", "SCENARIO A", "SCHOOLS CLOSING"), "14", "", "What changes > SCENARIO A > SCHOOLS CLOSING: 14"),
            ReadingEntry("number", listOf("Beaumont MS"), "88", "students", "Beaumont MS: 88 students", about = listOf(PageThing("Beaumont MS", "school"))),
            ReadingEntry("number", listOf("Enrollment"), "5", "", "Enrollment: 5"),
        )
        val chunk = ChunkRecord("c", "s", 1, "pdf p. 2, vision reading", entries.joinToString("\n") { it.line }, "h",
            kind = VisionReading.CHUNK_KIND, extractor = "vision:m", reading = Json.encodeToString(entries))
        val claims = StructuredClaimModel().extract(SourceRecord("s", "pdf", "file:x.pdf", "x", "pdf", null, "h", 0, 3), chunk, 1).claims
        assertEquals(listOf("SCENARIO A"), claims[0].about.map { it.name }); assertEquals("scenario", claims[0].about.single().kind)
        assertEquals(listOf("Beaumont MS"), claims[1].about.map { it.name }, "a thing already given is not added twice")
        assertTrue(claims[2].about.isEmpty(), "a heading that is not a thing names none")
    }
}
