package app.alfrd.engram.scripts

import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.TesseractOcr
import app.alfrd.engram.vision.CheckedValue
import app.alfrd.engram.vision.LocalVisionClient
import app.alfrd.engram.vision.PageImages
import app.alfrd.engram.vision.PageStructure
import app.alfrd.engram.vision.StructureCheck
import app.alfrd.engram.vision.VisionModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.system.exitProcess

/**
 * Reads one page of a local PDF with the vision servers and checks what they report against the page's own text. Touches no graph; the page
 * image goes only to loopback servers.
 *
 * ```
 * VisionPageCli <file.pdf> --page N [--vision http://127.0.0.1:8083/v1] [--ocr http://127.0.0.1:8084/v1] [--no-thinking] [--dpi 150] [--out report.json]
 * ```
 * The vision model is asked for the page's headings and, under each, its numbers, list items, tables and charts ([PageStructure.PROMPT]). Every
 * value is then looked for in the page's text layer, in the PDF reader's reading order, and (with `--ocr`) in an OCR transcript of the image.
 * The report says, per value, whether it was found and whether it sits under the heading the model put it under. `--out` also writes the raw
 * answers. Exit status: 0 read and checked, 2 a server failed or its answer could not be read, 64 usage.
 */
object VisionPageCli {

    fun run(
        args: List<String>, out: java.io.PrintStream = System.out, err: java.io.PrintStream = System.err,
        client: (url: String, thinking: Boolean?) -> VisionModel = { u, t -> LocalVisionClient(u, thinking = t) },
    ): Int {
        fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val file = args.firstOrNull()?.takeIf { !it.startsWith("--") }?.let(::File)
        val page = opt("--page")?.toIntOrNull()
        if (file == null || !file.isFile || page == null) {
            err.println("usage: VisionPageCli <file.pdf> --page N [--vision URL] [--ocr URL] [--no-thinking] [--dpi 150] [--out report.json]")
            return 64
        }
        val bytes = file.readBytes()
        val dpi = opt("--dpi")?.toFloatOrNull() ?: 150f
        val png = try { PageImages.render(bytes, page, dpi) } catch (e: IllegalArgumentException) { err.println(e.message); return 64 }
        val readingText = PdfExtractor(TesseractOcr.detect()).prepare(bytes).chunks.filter { it.page == page }.joinToString("\n") { it.text }

        val visionUrl = opt("--vision") ?: "http://127.0.0.1:8083/v1"
        val vision = try { client(visionUrl, if ("--no-thinking" in args) false else null).read(png, PageStructure.PROMPT) } catch (e: IllegalArgumentException) {
            err.println(e.message); return 64
        } catch (e: Exception) { err.println("vision server failed: ${e::class.java.simpleName}: ${e.message}"); return 2 }
        val sections = try { PageStructure.parse(vision.text) } catch (e: IllegalArgumentException) {
            err.println("vision answer could not be read: ${e.message}"); opt("--out")?.let { File(it).writeText(vision.text) }; return 2
        }
        val ocr = opt("--ocr")?.let { u ->
            try { client(u, null).read(png, "OCR:") } catch (e: Exception) { err.println("ocr server failed: ${e::class.java.simpleName}: ${e.message}"); return 2 }
        }
        val checks = StructureCheck.check(sections, readingText, ocr?.text)

        out.println("${file.name} page $page  ${png.size} bytes PNG at ${dpi.toInt()} dpi; text layer ${readingText.length} chars")
        out.println("vision: model=${vision.model} ${vision.ms} ms finish=${vision.finishReason} tokens=${vision.completionTokens}; ${sections.size} top-level heading(s)")
        ocr?.let { out.println("ocr: model=${it.model} ${it.ms} ms; ${it.text.length} chars") }
        val values = checks.filter { it.kind != "heading" }
        out.println(
            "checked ${checks.size}: headings found ${checks.count { it.kind == "heading" && it.found }}/${checks.count { it.kind == "heading" }}; " +
                "values found ${values.count { it.found }}/${values.size}; under their heading ${values.count { it.inSection == true }}, " +
                "elsewhere ${values.count { it.inSection == false }}, cannot tell ${values.count { it.inSection == null }}",
        )
        fun line(c: CheckedValue) = "  [${c.kind}] ${(c.path).joinToString(" > ").ifEmpty { "(top)" }} :: ${c.value}"
        checks.filter { !it.found }.takeIf { it.isNotEmpty() }?.let { out.println("NOT IN THE PAGE TEXT:"); it.forEach { c -> out.println(line(c)) } }
        values.filter { it.found && it.inSection == false }.takeIf { it.isNotEmpty() }?.let { out.println("FOUND, BUT NOT UNDER THAT HEADING IN READING ORDER:"); it.forEach { c -> out.println(line(c)) } }
        out.println("STRUCTURE:")
        checks.forEach { c -> out.println(line(c) + "  " + (if (c.found) "found" else "NOT FOUND") + (c.inSection?.let { if (it) ", in section" else ", elsewhere" } ?: "")) }

        opt("--out")?.let { path ->
            val report = JsonObject(buildMap {
                put("file", JsonPrimitive(file.name)); put("page", JsonPrimitive(page)); put("dpi", JsonPrimitive(dpi))
                put("visionModel", JsonPrimitive(vision.model)); put("visionMs", JsonPrimitive(vision.ms)); put("visionAnswer", JsonPrimitive(vision.text))
                ocr?.let { put("ocrModel", JsonPrimitive(it.model)); put("ocrMs", JsonPrimitive(it.ms)); put("ocrText", JsonPrimitive(it.text)) }
                put("readingText", JsonPrimitive(readingText))
                put("checks", JsonArray(checks.map { c ->
                    JsonObject(mapOf("path" to JsonArray(c.path.map { JsonPrimitive(it) }), "kind" to JsonPrimitive(c.kind), "value" to JsonPrimitive(c.value),
                        "found" to JsonPrimitive(c.found), "inSection" to JsonPrimitive(c.inSection)))
                }))
            })
            File(path).writeText(report.toString())
            out.println("report written to $path")
        }
        return 0
    }
}

fun main(args: Array<String>) {
    exitProcess(VisionPageCli.run(args.toList()))
}
