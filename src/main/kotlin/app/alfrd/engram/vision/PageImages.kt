package app.alfrd.engram.vision

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Renders one page of a PDF to PNG bytes. [page] is 1-based. */
object PageImages {
    fun render(pdf: ByteArray, page: Int, dpi: Float = 150f): ByteArray = Loader.loadPDF(pdf).use { doc ->
        require(page in 1..doc.numberOfPages) { "page $page is outside 1..${doc.numberOfPages}" }
        val image = PDFRenderer(doc).renderImageWithDPI(page - 1, dpi)
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
