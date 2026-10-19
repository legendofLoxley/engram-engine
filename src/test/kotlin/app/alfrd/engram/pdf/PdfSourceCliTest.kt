package app.alfrd.engram.pdf

import app.alfrd.engram.scripts.PdfSourceCli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

class PdfSourceCliTest {
    private fun run(vararg args: String): Triple<Int, String, String> {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val code = PdfSourceCli.run(args.toList(), PrintStream(o), PrintStream(e))
        return Triple(code, o.toString(), e.toString())
    }

    private fun pdf(): java.io.File = Files.createTempFile("board-", ".pdf").toFile().also { it.writeBytes(PdfFixtures.board()); it.deleteOnExit() }

    @Test
    fun `a dry run prints the pages, the table as rows and the blocks in reading order, and renders the table page`() {
        val dir = Files.createTempDirectory("cli-render-").toFile()
        val (code, out, _) = run(pdf().path, "--dry-run", "--render-dir", dir.path)
        assertEquals(0, code)
        assertTrue(out.contains("pages=3") && out.contains("readMode=full"), out)
        assertTrue(out.contains("TABLE pdf p.3 (printed 2) ruled, 4 rows x 4 cols"), out)
        assertTrue(out.contains("| Students in sustainable schools | 48% | 80% | 73%"), out)
        assertTrue(out.indexOf("Scenario A closes") in 0 until out.indexOf("Scenario B closes"), out)
        assertTrue(dir.listFiles()!!.any { it.name.endsWith("-page-3.png") && it.length() > 1000 })
        dir.deleteRecursively()
    }

    @Test
    fun `usage errors, owner-jacob and a non-loopback url are refused before anything is sent`() {
        assertEquals(64, run().first)
        assertEquals(64, run(pdf().path).first)
        assertEquals(64, run(pdf().path, "--user", "owner-jacob").first)
        assertEquals(64, run(pdf().path, "--user", "x", "--url", "http://example.com:8080").first)
    }
}
