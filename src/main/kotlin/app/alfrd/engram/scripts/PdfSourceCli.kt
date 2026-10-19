package app.alfrd.engram.scripts

import app.alfrd.engram.pdf.PdfExtractor
import app.alfrd.engram.pdf.PdfPreparationCache
import app.alfrd.engram.pdf.PreparedPdf
import app.alfrd.engram.pdf.TesseractOcr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Turns a local PDF into a Source with page-located chunks.
 *
 * ```
 * PdfSourceCli <file.pdf> --user <label> [--url http://127.0.0.1:8080] [--locator L] [--title T]
 *     Commits through the running engine's debug route (POST /debug/sources/pdf) under the synthetic identity debug+<label>@...;
 *     needs DEBUG_CONVERSE_TOKEN. Loopback URLs only. Never owner-jacob. The engine does the reading, on the box.
 * PdfSourceCli <file.pdf> --dry-run [--render-dir DIR]
 *     Reads the file here (through the cache), prints what each page gave (path, printed page, tables, OCR confidence) and the chunks,
 *     and writes a PNG of every page that has a table into DIR. Touches no graph and no network.
 * ```
 * It goes through the running engine rather than opening the graph itself because the embedded graph has one owner at a time.
 * Exit status: 0 done, 2 the engine refused or could not be reached, 64 usage.
 */
object PdfSourceCli {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun run(args: List<String>, out: java.io.PrintStream = System.out, err: java.io.PrintStream = System.err): Int {
        val file = args.firstOrNull()?.takeIf { !it.startsWith("--") }?.let(::File)
        fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val dry = "--dry-run" in args
        if (file == null || !file.isFile || (!dry && opt("--user").isNullOrBlank())) {
            err.println("usage: PdfSourceCli <file.pdf> --user <label> [--url URL] [--locator L] [--title T]\n       PdfSourceCli <file.pdf> --dry-run [--render-dir DIR]")
            return 64
        }
        val bytes = file.readBytes()
        if (dry) {
            val prepared = try {
                PdfPreparationCache.fromEnvironment().getOrPrepare(bytes, PdfExtractor(TesseractOcr.detect())).prepared
            } catch (e: IllegalArgumentException) { err.println("refused: ${e.message}"); return 2 }
            out.println(report(file.name, prepared))
            opt("--render-dir")?.let { render(bytes, prepared, File(it), file.nameWithoutExtension, out) }
            return 0
        }
        val label = opt("--user")!!
        if (label.contains("owner-jacob")) { err.println("refused: that identity is real data and is never used by the debug routes"); return 64 }
        val token = System.getenv("DEBUG_CONVERSE_TOKEN").orEmpty()
        if (token.isBlank()) { err.println("DEBUG_CONVERSE_TOKEN is not set"); return 64 }
        val url = URI.create((opt("--url") ?: "http://127.0.0.1:8080").trimEnd('/') + "/debug/sources/pdf")
        if (url.host !in setOf("127.0.0.1", "localhost", "::1", "[::1]")) { err.println("refused: the debug route is loopback only"); return 64 }
        val body = JsonObject(
            buildMap {
                put("syntheticUserId", JsonPrimitive(label))
                put("contentBase64", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
                put("locator", JsonPrimitive(opt("--locator") ?: "file:${file.name}"))
                opt("--title")?.let { put("title", JsonPrimitive(it)) }
            },
        )
        val response = try {
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(url).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: java.io.IOException) { err.println("could not reach the engine at ${url.host}:${url.port}"); return 2 }
        if (response.statusCode() != 200) { err.println("engine answered ${response.statusCode()}: ${response.body().take(300)}"); return 2 }
        out.println(response.body().let { runCatching { json.encodeToString(JsonObject.serializer(), json.parseToJsonElement(it) as JsonObject) }.getOrDefault(it) })
        return 0
    }

    fun report(name: String, p: PreparedPdf): String = buildString {
        appendLine("$name  sha256=${p.sha256}")
        appendLine("title=\"${p.title}\" pages=${p.pageCount} readMode=${p.readMode} ocrEngine=${p.ocrEngine} chunks=${p.chunks.size} (text ${p.chunks.count { it.kind == "text" }}, table ${p.chunks.count { it.kind == "table" }})")
        p.pages.forEach {
            appendLine(
                "  pdf p.${it.page}  printed=${it.printedPage ?: "-"}  path=${it.path}  tables=${it.tables}  chars=${it.chars}" +
                    (it.ocrConfidence?.let { c -> "  ocrConfidence=%.1f".format(c) } ?: "") + (it.note?.let { n -> "  note=\"$n\"" } ?: ""),
            )
        }
        p.chunks.filter { it.kind == "table" }.forEach { c ->
            appendLine("  TABLE pdf p.${c.page} (printed ${c.printedPage ?: "-"}) ${c.table!!.method}, ${c.table.rows.size} rows x ${c.table.rows.maxOf { r -> r.size }} cols, box=${c.box}")
            c.table.rows.forEach { appendLine("    | " + it.joinToString(" | ")) }
        }
        p.chunks.filter { it.kind == "text" && it.blocks.size > 1 }.forEach { c ->
            appendLine("  TEXT pdf p.${c.page} blocks in reading order:")
            c.blocks.forEach { b -> appendLine("    [x ${b.box.x0.toInt()}-${b.box.x1.toInt()}, y ${b.box.y0.toInt()}-${b.box.y1.toInt()}] ${b.text.replace('\n', ' ').take(100)}") }
        }
    }

    private fun render(bytes: ByteArray, p: PreparedPdf, dir: File, base: String, out: java.io.PrintStream) {
        dir.mkdirs()
        Loader.loadPDF(bytes).use { doc ->
            val r = PDFRenderer(doc)
            p.chunks.filter { it.kind == "table" }.map { it.page }.distinct().forEach { n ->
                val png = File(dir, "$base-page-$n.png")
                ImageIO.write(r.renderImageWithDPI(n - 1, 110f), "png", png)
                out.println("rendered ${png.path}")
            }
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(PdfSourceCli.run(args.toList()))
}
