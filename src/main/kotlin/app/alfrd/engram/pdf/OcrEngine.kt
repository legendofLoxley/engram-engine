package app.alfrd.engram.pdf

import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** A word the OCR engine read, with its box in image pixels. */
data class OcrWord(val text: String, val box: Box)

/** What an engine read from one page image: the words, and its own mean word confidence (0 to 100). */
data class OcrResult(val words: List<OcrWord>, val confidence: Double)

interface OcrEngine {
    val id: String
    fun recognize(image: BufferedImage, dpi: Int): OcrResult
}

/**
 * Tesseract through its command line (`tesseract <png> stdout tsv`), the one non-JVM piece of PDF reading. It is found at run time:
 * [detect] returns null when the binary is not there, and the extractor then records pages with no text layer as unread instead of
 * pretending. Nothing in the build or the deployment installs it.
 */
class TesseractOcr private constructor(private val binary: String, private val lang: String, override val id: String) : OcrEngine {

    override fun recognize(image: BufferedImage, dpi: Int): OcrResult {
        val png = File.createTempFile("engram-ocr-", ".png")
        try {
            ImageIO.write(image, "png", png)
            val proc = ProcessBuilder(binary, png.path, "stdout", "-l", lang, "--dpi", dpi.toString(), "--psm", "3", "tsv")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(120, TimeUnit.SECONDS)) { proc.destroyForcibly(); throw IllegalStateException("tesseract timed out") }
            check(proc.exitValue() == 0) { "tesseract exited with ${proc.exitValue()}" }
            return parseTsv(out)
        } finally {
            png.delete()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TesseractOcr::class.java)

        /** The `tesseract` named by ENGRAM_TESSERACT or found on the PATH, or null. Language from ENGRAM_TESSERACT_LANG (default eng). */
        fun detect(
            binary: String = System.getenv("ENGRAM_TESSERACT")?.takeIf { it.isNotBlank() } ?: "tesseract",
            lang: String = System.getenv("ENGRAM_TESSERACT_LANG")?.takeIf { it.isNotBlank() } ?: "eng",
        ): TesseractOcr? = try {
            val p = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) null
            else TesseractOcr(binary, lang, "tesseract-" + (text.lineSequence().firstOrNull()?.substringAfter("tesseract")?.trim().orEmpty().ifBlank { "unknown" }) + "-$lang")
        } catch (e: Exception) {
            logger.info("ocr: tesseract not available ({})", e.javaClass.simpleName)
            null
        }

        /** Level-5 rows of Tesseract's TSV are words; a confidence of -1 is not a word. */
        internal fun parseTsv(tsv: String): OcrResult {
            val words = ArrayList<OcrWord>(); val confs = ArrayList<Double>()
            for (line in tsv.lineSequence().drop(1)) {
                val f = line.split('\t')
                if (f.size < 12 || f[0] != "5") continue
                val conf = f[10].toDoubleOrNull() ?: continue
                val text = f[11].trim()
                if (conf < 0 || text.isEmpty()) continue
                val l = f[6].toDoubleOrNull() ?: continue; val t = f[7].toDoubleOrNull() ?: continue
                val w = f[8].toDoubleOrNull() ?: continue; val h = f[9].toDoubleOrNull() ?: continue
                words += OcrWord(text, Box(l, t, l + w, t + h)); confs += conf
            }
            return OcrResult(words, if (confs.isEmpty()) 0.0 else confs.average())
        }
    }
}
