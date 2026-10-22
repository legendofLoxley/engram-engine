package app.alfrd.engram.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a vision model says is on a page: headings, each with the numbers and items printed under it, its tables (rows of cells, header row
 * first) and its charts, and nested sub-headings. A proposal to be checked against the page's own text ([StructureCheck]), never a fact.
 */
data class PageSection(
    val heading: String,
    val numbers: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    val tables: List<List<List<String>>> = emptyList(),
    val charts: List<PageChart> = emptyList(),
    val subsections: List<PageSection> = emptyList(),
)

data class PageChart(val title: String, val values: List<String>)

object PageStructure {

    /** The prompt that asks for [PageSection]s. Copy-exactly matters: what comes back is checked against the page's text. */
    const val PROMPT = "Return only JSON, no prose: {\"sections\": [{\"heading\": \"...\", \"numbers\": [\"...\"], \"items\": [\"...\"], " +
        "\"tables\": [[[\"header cell\", \"...\"], [\"cell\", \"...\"]]], \"charts\": [{\"title\": \"...\", \"values\": [\"...\"]}], \"subsections\": [ ...same shape... ]}]}. " +
        "List every heading on this page in reading order. Under each heading give every number printed under it (with its % or unit as printed), " +
        "every list item, every table as rows of cells with the header row first, and every chart with its title and every value printed in it. " +
        "Nest a heading inside the heading it sits under. A number belongs to the heading it sits under, never to one above or beside it. " +
        "Copy all text exactly as printed; do not correct, translate or summarize."

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Reads the model's answer: the first JSON object in it (code fences and surrounding prose are ignored). Unreadable → IllegalArgumentException. */
    fun parse(answer: String): List<PageSection> {
        val start = answer.indexOf('{'); val end = answer.lastIndexOf('}')
        require(start >= 0 && end > start) { "the answer holds no JSON object" }
        val root = try { json.parseToJsonElement(answer.substring(start, end + 1)) } catch (e: Exception) { throw IllegalArgumentException("the answer's JSON could not be read") }
        val o = root as? JsonObject ?: throw IllegalArgumentException("the answer's JSON is not an object")
        val sections = o["sections"] as? JsonArray ?: throw IllegalArgumentException("the answer has no sections list")
        return sections.mapNotNull { section(it) }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { str(it) }.orEmpty()

    private fun section(e: JsonElement): PageSection? {
        val o = e as? JsonObject ?: return null
        val heading = str(o["heading"]) ?: return null
        return PageSection(
            heading = heading,
            numbers = strings(o["numbers"]),
            items = strings(o["items"]),
            tables = (o["tables"] as? JsonArray)?.mapNotNull { t -> (t as? JsonArray)?.map { r -> strings(r) }?.filter { it.isNotEmpty() } }?.filter { it.isNotEmpty() }.orEmpty(),
            charts = (o["charts"] as? JsonArray)?.mapNotNull { c -> (c as? JsonObject)?.let { PageChart(str(it["title"]).orEmpty(), strings(it["values"])) } }.orEmpty(),
            subsections = (o["subsections"] as? JsonArray)?.mapNotNull { section(it) }.orEmpty(),
        )
    }
}
