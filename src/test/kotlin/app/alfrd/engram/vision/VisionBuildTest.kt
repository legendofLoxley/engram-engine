package app.alfrd.engram.vision

import app.alfrd.engram.claims.ClaimAgreement
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

/** The check and the build on page text as the extractor actually reads it: wrapped table cells, card titles apart from their text. */
class VisionBuildTest {

    /** The text layer of a planning-floor table as the extractor orders it: the header row and the wrapped cells come out interleaved. */
    private val table = """
        Figure 6.1 Deriving Planning Floors

        School type

        Elementary

        Middle

        K–8

        QEM
        prototype

        Five-year Planning floor
        buffer

        range estimate

        360

        + about 85

        Approximately
        450+

        500

        + about
        100

        Approximately
        600+

        no QEM
        prototype estimate

        local Approximately

        650+

        What the floor is built to sustain

        6 grades, about 75 per grade.
    """.trimIndent()

    private val tableReading = listOf(
        PageSection(
            "Figure 6.1 Deriving Planning Floors",
            numbers = listOf(PageNumber("85", "+ about"), PageNumber("450+", "Approximately"), PageNumber("6", "grades, about")),
            tables = listOf(listOf(
                listOf("School type", "QEM prototype", "Five-year buffer", "Planning floor range estimate", "What the floor is built to sustain"),
                listOf("Elementary", "360", "+ about 85", "Approximately 450+", "6 grades, about 75 per grade."),
                listOf("Middle", "500", "+ about 100", "Approximately 600+", "3 grades"),
                listOf("K–8", "no QEM prototype", "local estimate", "Approximately 650+", "9 grades"),
            )),
        ),
    )

    @Test
    fun `column headers and cells the text layer interleaves are found by their words near each other`() {
        val checks = StructureCheck.check(tableReading, table)
        listOf("Five-year buffer", "Planning floor range estimate", "local estimate", "Approximately 450+", "+ about 100").forEach { v ->
            assertTrue(checks.first { it.value == v }.found, "$v: $checks")
        }
        val lines = VisionReading.build(tableReading, checks).text.lines()
        assertTrue("Figure 6.1 Deriving Planning Floors > Elementary > Five-year buffer: + about 85" in lines, lines.joinToString("\n"))
        assertTrue("Figure 6.1 Deriving Planning Floors > Elementary > Planning floor range estimate: Approximately 450+" in lines)
        assertTrue("Figure 6.1 Deriving Planning Floors > K–8 > Five-year buffer: local estimate" in lines)
    }

    @Test
    fun `numbers that repeat a cell of the section's table are left out, and the reason names the part that failed`() {
        val built = VisionReading.build(tableReading, StructureCheck.check(tableReading, table))
        assertFalse(built.entries.any { it.kind == "number" }, built.entries.toString())
        assertEquals(3, built.rejected.count { it.endsWith("repeats a table cell") }, built.rejected.toString())
        val noHeader = tableReading.map { it.copy(tables = listOf(listOf(listOf("School type", "Imaginary column"), listOf("Elementary", "360")))) }
        val r = VisionReading.build(noHeader, StructureCheck.check(noHeader, table)).rejected
        assertTrue(r.any { it.contains(":: 360") && it.endsWith("column header: not on the page") }, r.toString())
    }

    @Test
    fun `a chart's values are kept with labels made of its category and legend text printed apart from them`() {
        val chart = """
            Figure 2.2 Families with Middle School Students Rated Learning Environments Less Positively
            Than Elementary-Only Families

            High quality learning environment

            Absence of disrespect or bias

            45%

            61%

            48%

            66%

            0%

            20%

            Percentage of families agreeing

            Families with middle school students

            Families with only elementary students
        """.trimIndent()
        val heading = "Figure 2.2 Families with Middle School Students Rated Learning Environments Less Positively Than Elementary-Only Families"
        val reading = listOf(PageSection(heading, numbers = listOf(
            PageNumber("45%", "High quality learning environment, Families with middle school students"),
            PageNumber("61%", "High quality learning environment, Families with only elementary students"),
            PageNumber("66%", "Absence of disrespect or bias, Families with only elementary students, 2024-25"),
        )))
        val built = VisionReading.build(reading, StructureCheck.check(reading, chart))
        assertTrue("$heading: 45% High quality learning environment, Families with middle school students" in built.text.lines(), built.text)
        assertEquals(2, built.entries.size, built.rejected.toString())
        assertTrue(built.rejected.single().contains(":: 66% — label not on the page"), "a year the page does not print is not a label: ${built.rejected}")
    }

    @Test
    fun `a name misread by a letter or two is kept with the page's spelling, and text is stored as the page prints it`() {
        val page = """
            Bridger Creative Science School SEGC Analysis

            SCENARIO A

            ATTENDANCE BOUNDARY CHANGES

            38

            Abernethy Arleta Duniway Sitton Woodstock Sitten-Park

            PROGRAM MOVES

            Spanish: Beach, James John, and Sitton to Cesar Chavez

            Educational options

            A strong network of neighborhood schools balanced with
            educational options.
        """.trimIndent()
        val reading = listOf(
            PageSection("Bridger Creative Science School SEGc Analysis"),
            PageSection("SCENARIO A", subsections = listOf(
                PageSection("ATTENDANCE BOUNDARY CHANGES", numbers = listOf(PageNumber("38")), items = listOf("Abernethy", "Dunaway", "Siton", "Woodstok", "Zzyzx")),
                PageSection("PROGRAM MOVES", items = listOf("Spanish: Beach, James John, and Sitten to Cesar Chavez")),
            )),
            PageSection("WHAT GUIDES THE WORK", items = listOf("Educational options: A strong network of neighborhood schools balanced with educational options.")),
        )
        val built = VisionReading.build(reading, StructureCheck.check(reading, page))
        val lines = built.text.lines()
        assertTrue("Bridger Creative Science School SEGC Analysis" in lines, "the page's case: ${built.text}")
        assertTrue("SCENARIO A > ATTENDANCE BOUNDARY CHANGES: Duniway" in lines, built.text)
        assertTrue("SCENARIO A > PROGRAM MOVES: Spanish: Beach, James John, and Sitton to Cesar Chavez" in lines, built.text)
        assertTrue(lines.any { it.startsWith("Educational options: A strong network") }, "a long item with an \"a\" in it: ${built.text}")
        assertTrue(built.corrected.any { it.endsWith(":: Dunaway → Duniway") }, built.corrected.toString())
        assertTrue(built.corrected.any { it.contains("Sitten to Cesar Chavez → Spanish: Beach, James John, and Sitton to Cesar Chavez") }, built.corrected.toString())
        assertFalse(built.corrected.any { it.contains("SEGc") }, "a change of case is not a correction")
        // "Siton" is one edit from Sitton and from no other name: kept. "Woodstok" is one from Woodstock: kept. "Zzyzx" is near nothing: rejected.
        assertTrue("SCENARIO A > ATTENDANCE BOUNDARY CHANGES: Sitton" in lines && "SCENARIO A > ATTENDANCE BOUNDARY CHANGES: Woodstock" in lines, built.text)
        assertTrue(built.rejected.any { it.contains(":: Zzyzx — not on the page") }, built.rejected.toString())
    }

    @Test
    fun `a name the OCR transcript also misreads is corrected to the text layer's spelling, inside a whole list`() {
        val page = "ATTENDANCE BOUNDARY CHANGES\n38\nAbernethy · Arleta · Dr. Martin Luther King Jr. · Duniway · Faubion · Sitton · Whitman"
        val ocr = "ATTENDANCE BOUNDARY CHANGES 38 Abernethy · Arleta · Dr. Martin Luther King Jr. · Dunlway · Faubion · Sitton · Whitman"
        val list = "Abernethy, Arleta, Dr. Martin Luther King Jr., Duniwary, Faubion, Sitton, Whitman"
        val reading = listOf(PageSection("ATTENDANCE BOUNDARY CHANGES", items = listOf(list)))
        val built = VisionReading.build(reading, StructureCheck.check(reading, page, ocr))
        assertEquals(listOf("ATTENDANCE BOUNDARY CHANGES: Abernethy, Arleta, Dr. Martin Luther King Jr., Duniway, Faubion, Sitton, Whitman"), built.entries.map { it.line }, built.toString())
        assertTrue(built.corrected.single().endsWith("→ Abernethy, Arleta, Dr. Martin Luther King Jr., Duniway, Faubion, Sitton, Whitman"))
    }

    @Test
    fun `a misread name with two names near it on the page is not guessed at`() {
        val page = "BOUNDARY CHANGES\nHarlow Harley Marlow"
        val reading = listOf(PageSection("BOUNDARY CHANGES", items = listOf("Harloy")))
        val built = VisionReading.build(reading, StructureCheck.check(reading, page))
        assertTrue(built.entries.isEmpty() && built.corrected.isEmpty(), built.toString())
        assertTrue(built.rejected.single().contains("Harloy — not on the page"))
    }

    @Test
    fun `words far apart, a one-letter word, or a single word are never matched apart`() {
        val text = StructureCheck.normalize("SCENARIO B closes 11 schools. Plan A is the status quo. Five-year plan for the district and its many neighborhood schools buffer")
        val words = StructureCheck.words(text)
        assertTrue(StructureCheck.scattered(words, "SCENARIO A").isEmpty(), "one-letter word")
        assertTrue(StructureCheck.scattered(words, "Five-year buffer").isEmpty(), "too far apart")
        assertTrue(StructureCheck.scattered(words, "closes").isEmpty(), "a single word is matched only as written")
        assertTrue(StructureCheck.scattered(words, "11 schools closes").isNotEmpty())
    }

    @Test
    fun `a card title read together with its text is found, and an invented heading keeps what is under it under the heading above`() {
        val page = """
            WHAT GUIDES THE WORK

            Well-resourced schools

            Consistent access to strong academic programming across PPS.

            PPS equity lens

            Centering students most impacted by inequities in every decision.
        """.trimIndent()
        val reading = listOf(
            PageSection("WHAT GUIDES THE WORK", subsections = listOf(
                PageSection("Guiding principles", items = listOf(
                    "Well-resourced schools: Consistent access to strong academic programming across PPS.",
                    "PPS equity lens – Centering students most impacted by inequities in every decision.",
                )),
            )),
            PageSection("Header", numbers = listOf(PageNumber("2031"))),
        )
        val built = VisionReading.build(reading, StructureCheck.check(reading, page))
        val lines = built.text.lines()
        assertTrue("WHAT GUIDES THE WORK: Well-resourced schools: Consistent access to strong academic programming across PPS." in lines, built.text)
        assertTrue(lines.any { it.startsWith("WHAT GUIDES THE WORK: PPS equity lens") }, built.text)
        assertTrue(built.entries.all { it.path == listOf("WHAT GUIDES THE WORK") }, built.entries.toString())
        assertTrue(built.rejected.any { it.contains("Guiding principles :: (heading) not on the page") }, built.rejected.toString())
        assertTrue(built.rejected.any { it.contains("2031") }, "a bare number with no heading or label says nothing")
    }

    @Test
    fun `a top heading too long to be a place stays in the subject, and a short one is the place`() = runBlocking<Unit> {
        val question = "RQ3: " + "To what extent do families report that the district's process was transparent and responsive to their concerns? ".repeat(2)
        assertTrue(question.length > ClaimAgreement.MAX_PLACE_CHARS)
        val entries = listOf(
            ReadingEntry("number", listOf(question, "Agree"), "41%", line = "$question > Agree: 41%"),
            ReadingEntry("number", listOf("SCENARIO A", "BOUNDARY CHANGES"), "38", line = "SCENARIO A > BOUNDARY CHANGES: 38"),
        )
        val chunk = ChunkRecord("c", "s", 9, "pdf p. 8, vision reading", entries.joinToString("\n") { it.line }, "h",
            kind = VisionReading.CHUNK_KIND, extractor = "vision:m", reading = Json.encodeToString(entries))
        val source = SourceRecord("s", "pdf", "file:x.pdf", "x", "pdf", null, "h", 0, 10)
        val claims = StructuredClaimModel().extract(source, chunk, 1).claims
        assertNull(claims[0].place)
        assertEquals("$question > Agree", claims[0].subject)
        assertEquals("SCENARIO A", claims[1].place)
        assertEquals("BOUNDARY CHANGES", claims[1].subject)
    }

    @Test
    fun `a value keeps the passage, things and period the page prints, and loses only what it does not print`() {
        val page = """
            Clark Elementary School

            Student demographics

            In 2023–24, 78% of Clark's students were students of color and 39% were multilingual learners.
        """.trimIndent()
        val passage = "In 2023–24, 78% of Clark's students were students of color and 39% were multilingual learners."
        val answer = """{"sections": [{"heading": "Clark Elementary School", "subsections": [{"heading": "Student demographics",
            "numbers": [
              {"value": "78%", "label": "students of color", "passage": "${passage.replace("’", "'")}",
               "about": [{"name": "Clark", "kind": "school", "parent": "PPS"}], "asOf": "2023–24"},
              {"value": "39%", "label": "multilingual learners", "passage": "In 2023-24, 39% were multilingual learners.", "about": ["Lincoln High"], "asOf": "2022–23"}
            ],
            "items": [{"text": "students of color", "about": [{"name": "Clark Elementary School", "kind": "school"}]}]}]}]}"""
        val sections = PageStructure.parse(answer)
        val built = VisionReading.build(sections, StructureCheck.check(sections, page))
        val first = built.entries.single { it.value == "78%" }
        assertEquals(passage, first.passage)
        assertEquals("2023–24", first.asOf)
        assertEquals(listOf(PageThing("Clark", "school", null)), first.about, "the parent PPS is not printed, so it is left off")
        val second = built.entries.single { it.value == "39%" }
        assertNull(second.passage); assertNull(second.asOf); assertTrue(second.about.isEmpty(), "nothing it was given is printed as given")
        assertTrue(built.rejected.any { it.contains(":: 39% — about 'Lincoln High' not on the page (value kept)") }, built.rejected.toString())
        assertTrue(built.rejected.any { it.contains("period '2022–23' not on the page") })
        assertTrue(built.rejected.any { it.contains("parent 'PPS' not on the page") })
        assertEquals(listOf(PageThing("Clark Elementary School", "school", null)), built.entries.single { it.kind == "item" }.about)
        assertTrue(passage in built.text.lines(), "the passage is a line of the chunk, so a claim's passage is a passage of its chunk")
    }

    @Test
    fun `claims from structure carry the context the check kept`() = runBlocking<Unit> {
        val passage = "In 2023–24, 78% of Clark's students were students of color."
        val entries = listOf(ReadingEntry("number", listOf("Clark Elementary School"), "78%", "students of color", "Clark Elementary School: 78% students of color",
            passage, listOf(PageThing("Clark", "school")), "2023–24"))
        val chunk = ChunkRecord("c", "s", 9, "pdf p. 30, vision reading", entries.joinToString("\n") { it.line } + "\n" + passage, "h",
            kind = VisionReading.CHUNK_KIND, extractor = "vision:m", reading = Json.encodeToString(entries))
        val claim = StructuredClaimModel().extract(SourceRecord("s", "pdf", "file:x.pdf", "x", "pdf", null, "h", 0, 10), chunk, 1).claims.single()
        assertEquals(passage, claim.passage); assertEquals("2023–24", claim.asOf); assertEquals("Clark", claim.about.single().name)
        val ctx = app.alfrd.engram.claims.ClaimAgreement.context(chunk, claim, claim)
        assertEquals(passage, ctx.passage, "both passes are the same reading, so its context is agreed")
    }
}
