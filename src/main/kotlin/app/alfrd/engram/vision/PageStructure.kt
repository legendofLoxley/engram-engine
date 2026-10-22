package app.alfrd.engram.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A number printed under a heading, and the words printed with it that say what it counts (empty when the heading says it all, as on a card). */
data class PageNumber(val value: String, val label: String = "")

/**
 * What a vision model says is on a page: headings, each with the numbers and items printed under it, its tables (rows of cells, header row
 * first) and its charts, and nested sub-headings. A proposal to be checked against the page's own text ([StructureCheck]), never a fact.
 */
data class PageSection(
    val heading: String,
    val numbers: List<PageNumber> = emptyList(),
    val items: List<String> = emptyList(),
    val tables: List<List<List<String>>> = emptyList(),
    val charts: List<PageChart> = emptyList(),
    val subsections: List<PageSection> = emptyList(),
)

/** A chart's title and the text printed in it (axis titles and ticks, legend entries, data labels). Never values read off the lines. */
data class PageChart(val title: String, val values: List<String>)

object PageStructure {

    /** The prompt that asks for [PageSection]s. Copy-exactly matters: what comes back is checked against the page's text. */
    const val PROMPT = "Return only JSON, no prose: {\"sections\": [{\"heading\": \"...\", \"numbers\": [{\"value\": \"...\", \"label\": \"...\"}], " +
        "\"items\": [\"...\"], \"tables\": [[[\"header cell\", \"...\"], [\"cell\", \"...\"]]], \"charts\": [{\"title\": \"...\", \"values\": [\"...\"]}], " +
        "\"subsections\": [ ...same shape... ]}]}. " +
        "List every heading on this page in reading order. Under each heading give every number printed under it, with its % or unit as printed " +
        "(value) and the words printed next to it that say what it counts (label, empty if the heading itself says it); every list item; every table " +
        "as rows of cells with the header row first; and every chart with its title and all the text printed in it (axis titles, ticks, legend, data " +
        "labels). Never estimate a value from a line or bar: only text that is printed. Nest a heading inside the heading it sits under. A number " +
        "belongs to the heading it sits under, never to one above or beside it. Copy all text exactly as printed; do not correct, translate or summarize."

    /** Versioned with the prompt: a reading made with another prompt is another reading. */
    const val VERSION = "structure-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Reads the model's answer: the first JSON object in it (code fences and surrounding prose are ignored). Tolerates the shapes models drift
     * to: `headings`/`text`/`title` for sections and headings, `children` for sub-headings, a single table given as rows, numbers given as
     * plain strings, chart text under any key. Unreadable → IllegalArgumentException.
     */
    fun parse(answer: String): List<PageSection> {
        val start = answer.indexOf('{'); val end = answer.lastIndexOf('}')
        require(start >= 0 && end > start) { "the answer holds no JSON object" }
        val root = try { json.parseToJsonElement(answer.substring(start, end + 1)) } catch (e: Exception) { throw IllegalArgumentException("the answer's JSON could not be read") }
        val o = root as? JsonObject ?: throw IllegalArgumentException("the answer's JSON is not an object")
        val sections = (o["sections"] ?: o["headings"]) as? JsonArray ?: throw IllegalArgumentException("the answer has no sections list")
        return sections.mapNotNull { section(it) }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { str(it) }.orEmpty()

    private fun numbers(e: JsonElement?): List<PageNumber> = (e as? JsonArray)?.mapNotNull { n ->
        when (n) {
            is JsonPrimitive -> str(n)?.let { PageNumber(it) }
            is JsonObject -> str(n["value"])?.let { PageNumber(it, str(n["label"]).orEmpty()) }
            else -> null
        }
    }.orEmpty()

    private fun tables(e: JsonElement?): List<List<List<String>>> {
        val list = e as? JsonArray ?: return emptyList()
        // One table given directly as rows: [[cell, ...], ...].
        if (list.isNotEmpty() && list.all { r -> r is JsonArray && r.all { it is JsonPrimitive } }) {
            return listOf(list.map { strings(it) }.filter { it.isNotEmpty() }).filter { it.isNotEmpty() }
        }
        return list.mapNotNull { t -> (t as? JsonArray)?.map { r -> strings(r) }?.filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
    }

    private fun chart(e: JsonElement): PageChart? {
        val o = e as? JsonObject ?: return null
        val title = str(o["title"]).orEmpty()
        val values = o.entries.filter { it.key != "title" }.flatMap { (_, v) ->
            when (v) {
                is JsonArray -> strings(v)
                is JsonPrimitive -> listOfNotNull(str(v))
                else -> emptyList()
            }
        }
        return PageChart(title, values)
    }

    private fun section(e: JsonElement): PageSection? {
        val o = e as? JsonObject ?: return null
        val heading = str(o["heading"]) ?: str(o["text"]) ?: str(o["title"]) ?: return null
        return PageSection(
            heading = heading,
            numbers = numbers(o["numbers"]),
            items = strings(o["items"]),
            tables = tables(o["tables"]),
            charts = (o["charts"] as? JsonArray)?.mapNotNull { chart(it) }.orEmpty(),
            subsections = ((o["subsections"] ?: o["children"] ?: o["sections"]) as? JsonArray)?.mapNotNull { section(it) }.orEmpty(),
        )
    }
}
