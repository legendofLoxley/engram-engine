package app.alfrd.engram.vision

import app.alfrd.engram.pdf.PdfFixtures
import app.alfrd.engram.pdf.poster
import app.alfrd.engram.scripts.VisionPageCli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/** The page tool on the generated poster fixture (cards with counts at their edge), with a scripted vision model. */
class VisionPageCliTest {

    private fun answer(a38: String, b38: String) = """
        {"sections": [
          {"heading": "STATUS QUO", "numbers": ["26%"]},
          {"heading": "SCENARIO A", "numbers": ["14"], "subsections": [
            {"heading": "SCHOOLS CLOSING", "numbers": ["14"]}, {"heading": "BOUNDARY CHANGES", "numbers": ["$a38"]}]},
          {"heading": "SCENARIO B", "numbers": ["11"], "subsections": [
            {"heading": "SCHOOLS CLOSING", "numbers": ["11"]}, {"heading": "BOUNDARY CHANGES", "numbers": ["$b38"]}]}
        ]}
    """.trimIndent()

    private fun run(answerText: String, vararg extra: String): Triple<Int, String, List<String>> {
        val dir = Files.createTempDirectory("vision-cli-").toFile()
        try {
            val pdf = File(dir, "poster.pdf").apply { writeBytes(PdfFixtures.poster()) }
            val seen = ArrayList<String>()
            val o = ByteArrayOutputStream()
            val code = VisionPageCli.run(listOf(pdf.path, "--page", "1") + extra, PrintStream(o), PrintStream(ByteArrayOutputStream())) { url, _ ->
                VisionModel { png, prompt ->
                    seen += "$url $prompt ${png.size > 1000}"
                    VisionAnswer(if (prompt == "OCR:") "SCENARIO A 14" else answerText, "Qwen3.8-27B", 5, "stop", 10)
                }
            }
            return Triple(code, o.toString(), seen)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `a correct reading of the poster is found in full and every count sits under its own scenario`() {
        val (code, out, seen) = run(answer("38", "34"))
        assertEquals(0, code, out)
        assertTrue(out.contains("values found 7/7"), out)
        assertTrue(out.contains("under their heading 7, elsewhere 0"), out)
        assertTrue(seen.single().startsWith("http://127.0.0.1:8083/v1 Return only JSON") && seen.single().endsWith("true"), "the rendered page went to 8083")
    }

    @Test
    fun `swapped card counts are flagged as found but not under that heading`() {
        val (code, out, _) = run(answer("34", "38"))
        assertEquals(0, code)
        assertTrue(out.contains("FOUND, BUT NOT UNDER THAT HEADING"), out)
        assertTrue(out.contains("SCENARIO A > BOUNDARY CHANGES :: 34") && out.contains("SCENARIO B > BOUNDARY CHANGES :: 38"), out)
    }

    @Test
    fun `the ocr server is asked only when named, and an unreadable answer or a bad page is refused`() {
        val (_, _, seen) = run(answer("38", "34"), "--ocr", "http://127.0.0.1:8084/v1")
        assertEquals(listOf("http://127.0.0.1:8084/v1 OCR: true"), seen.filter { it.contains("8084") })
        assertEquals(2, run("I cannot read this page").first)
        val dir = Files.createTempDirectory("vision-cli-page-").toFile()
        try {
            val pdf = File(dir, "poster.pdf").apply { writeBytes(PdfFixtures.poster()) }
            assertEquals(64, VisionPageCli.run(listOf(pdf.path, "--page", "9"), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())) { _, _ -> VisionModel { _, _ -> error("not called") } })
        } finally { dir.deleteRecursively() }
        assertEquals(64, VisionPageCli.run(listOf("nope.pdf", "--page", "1"), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
    }
}
