package app.alfrd.engram.vision

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A thing a value describes, as the model named it: name as printed, kind (school, public body, ...) and the body it belongs to. */
@Serializable
data class PageThing(val name: String, val kind: String = "", val parent: String? = null)

/**
 * A value's context as the model reported it: the printed sentence, card or row it sits in, the things it describes and the period it refers
 * to. Checked against the page like everything else ([StructureCheck]); what the page does not print is dropped, never guessed.
 */
data class PageContext(val passage: String? = null, val about: List<PageThing> = emptyList(), val asOf: String? = null) {
    fun isEmpty() = passage == null && about.isEmpty() && asOf == null
}

/** A number printed under a heading, and the words printed with it that say what it counts (empty when the heading says it all, as on a card). */
data class PageNumber(val value: String, val label: String = "", val context: PageContext = PageContext())

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
    /** The context the model gave for list items, by item text. */
    val itemContext: Map<String, PageContext> = emptyMap(),
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
        "belongs to the heading it sits under, never to one above or beside it. For each number and each list item you may give it as an object " +
        "with \"passage\" (the whole sentence, card text or table row it is printed in, copied exactly), \"about\" (the things it describes, as " +
        "[{\"name\": \"...\", \"kind\": \"school|public body|organization|program|policy|scenario|place|person|other\", \"parent\": \"the body it " +
        "belongs to, if printed\"}], names copied as printed) and \"asOf\" (the school year, year or date the value refers to, as printed; leave it " +
        "out if none is printed). Never guess these. For a number in a chart, its label names the category, the series " +
        "(legend entry) and the year it belongs to, using the words printed for them. A figure's title covers only the figure: text after the figure " +
        "belongs under the section heading, not under the figure. Copy all text exactly as printed, including accents or their absence; do not " +
        "correct, translate or summarize."

    /**
     * Versioned with the prompt and with what [StructureCheck] and [VisionReading.build] keep: a reading made with another prompt or kept by
     * another check is another reading. v2: wrapped and interleaved text is matched, invented headings no longer drop what is under them.
     * v3: chart labels carry category, series and year and are checked word by word; prose after a figure is not the figure's.
     * v4: names misread by a letter or two are kept with the page's spelling; text is stored as the page prints it (case); long items with "a".
     * v5: each number and item may carry its passage, the things it is about and its period, checked like the rest (claims in context).
     */
    const val VERSION = "structure-v5"

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

    private fun things(e: JsonElement?): List<PageThing> = when (e) {
        is JsonPrimitive -> listOfNotNull(str(e)?.let { PageThing(it) })
        is JsonArray -> e.mapNotNull { t ->
            when (t) {
                is JsonPrimitive -> str(t)?.let { PageThing(it) }
                is JsonObject -> str(t["name"])?.let { PageThing(it, str(t["kind"]) ?: str(t["type"]).orEmpty(), str(t["parent"]) ?: str(t["part_of"])) }
                else -> null
            }
        }
        is JsonObject -> listOfNotNull(str(e["name"])?.let { PageThing(it, str(e["kind"]).orEmpty(), str(e["parent"])) })
        else -> emptyList()
    }

    private fun context(o: JsonObject) = PageContext(
        passage = str(o["passage"]) ?: str(o["sentence"]),
        about = things(o["about"]),
        asOf = str(o["asOf"]) ?: str(o["as_of"]) ?: str(o["period"]) ?: str(o["year"]),
    )

    private fun numbers(e: JsonElement?): List<PageNumber> = (e as? JsonArray)?.mapNotNull { n ->
        when (n) {
            is JsonPrimitive -> str(n)?.let { PageNumber(it) }
            is JsonObject -> str(n["value"])?.let { PageNumber(it, str(n["label"]).orEmpty(), context(n)) }
            else -> null
        }
    }.orEmpty()

    /** List items as text, with the context the model gave for any item written as an object. */
    private fun items(e: JsonElement?): Pair<List<String>, Map<String, PageContext>> {
        val texts = ArrayList<String>(); val ctx = LinkedHashMap<String, PageContext>()
        (e as? JsonArray)?.forEach { i ->
            when (i) {
                is JsonPrimitive -> str(i)?.let { texts += it }
                is JsonObject -> (str(i["text"]) ?: str(i["item"]) ?: str(i["value"]))?.let { t ->
                    texts += t
                    context(i).takeIf { !it.isEmpty() }?.let { ctx[t] = it }
                }
                else -> {}
            }
        }
        return texts to ctx
    }

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
        val (itemTexts, itemContext) = items(o["items"])
        return PageSection(
            heading = heading,
            numbers = numbers(o["numbers"]),
            items = itemTexts,
            itemContext = itemContext,
            tables = tables(o["tables"]),
            charts = (o["charts"] as? JsonArray)?.mapNotNull { chart(it) }.orEmpty(),
            subsections = ((o["subsections"] ?: o["children"] ?: o["sections"]) as? JsonArray)?.mapNotNull { section(it) }.orEmpty(),
        )
    }
}
