package app.alfrd.engram.notion

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [NotionApi] over Notion's REST API, version `2025-09-03` (queries and creates address a *data
 * source*, not a database). It carries no policy: pacing and retry are [RateLimitedNotionApi]'s job,
 * and confinement to an identity's tables is [AllowlistedNotionApi]'s.
 *
 * The token is fetched from [tokenSource] on every request, so a rotated secret takes effect without
 * a restart. It is only ever placed in the `Authorization` header: it appears in no log line, no
 * exception message, and no `toString`.
 */
class HttpNotionApi(
    private val tokenSource: () -> NotionToken,
    baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
    private val notionVersion: String = NOTION_VERSION,
) : NotionApi {

    private val base = baseUrl.trimEnd('/')

    override suspend fun query(dataSourceId: String, filter: NotionFilter?, sorts: List<NotionSort>, startCursor: String?, pageSize: Int): QueryPage {
        require(pageSize in 1..NotionApi.MAX_PAGE_SIZE) { "pageSize must be between 1 and ${NotionApi.MAX_PAGE_SIZE}" }
        val body = buildJsonObject {
            filter?.let { put("filter", it.toJson()) }
            if (sorts.isNotEmpty()) putJsonArray("sorts") { sorts.forEach { add(it.toJson()) } }
            startCursor?.let { put("start_cursor", it) }
            put("page_size", pageSize)
        }
        val json = send("POST", "/v1/data_sources/$dataSourceId/query", body)
        val results = (json["results"] as? JsonArray).orEmpty().map { parsePage(it.jsonObject) }
        return QueryPage(results, nextCursor(json))
    }

    override suspend fun createPage(dataSourceId: String, properties: Map<String, NotionValue>): NotionPage {
        val body = buildJsonObject {
            putJsonObject("parent") {
                put("type", "data_source_id")
                put("data_source_id", dataSourceId)
            }
            put("properties", properties.toJson())
        }
        return parsePage(send("POST", "/v1/pages", body))
    }

    override suspend fun updatePage(pageId: String, properties: Map<String, NotionValue>): NotionPage {
        val body = buildJsonObject { put("properties", properties.toJson()) }
        return parsePage(send("PATCH", "/v1/pages/$pageId", body))
    }

    override suspend fun retrievePage(pageId: String): NotionPage = parsePage(send("GET", "/v1/pages/$pageId", null))

    override suspend fun appendBlocks(parentBlockId: String, blocks: List<NotionBlock>) {
        require(blocks.size <= NotionApi.MAX_BLOCKS_PER_APPEND) { "at most ${NotionApi.MAX_BLOCKS_PER_APPEND} blocks per append" }
        if (blocks.isEmpty()) return
        val body = buildJsonObject {
            putJsonArray("children") { blocks.forEach { add(it.toJson()) } }
        }
        send("PATCH", "/v1/blocks/$parentBlockId/children", body)
    }

    override suspend fun listBlocks(parentBlockId: String, startCursor: String?, pageSize: Int): BlockPage {
        require(pageSize in 1..NotionApi.MAX_PAGE_SIZE) { "pageSize must be between 1 and ${NotionApi.MAX_PAGE_SIZE}" }
        val query = buildString {
            append("?page_size=").append(pageSize)
            startCursor?.let { append("&start_cursor=").append(java.net.URLEncoder.encode(it, Charsets.UTF_8)) }
        }
        val json = send("GET", "/v1/blocks/$parentBlockId/children$query", null)
        val blocks = (json["results"] as? JsonArray).orEmpty().map { parseBlock(it.jsonObject) }
        return BlockPage(blocks, nextCursor(json))
    }

    /**
     * The data sources this token can see, by id and title only (`POST /v1/search`, filtered to data
     * sources). For the live probe: "which of the tables I expect can the token reach, and what else
     * can it reach?". It is deliberately **not** on [NotionApi]: the guard stands between planner
     * code and the workspace, and a listing of everything shared with the integration is not
     * something planner code should be able to ask for. It returns metadata, no rows and no content.
     */
    suspend fun searchDataSources(maxPages: Int = 5): List<VisibleDataSource> {
        val found = ArrayList<VisibleDataSource>()
        var cursor: String? = null
        repeat(maxPages) {
            val body = buildJsonObject {
                putJsonObject("filter") { put("property", "object"); put("value", "data_source") }
                cursor?.let { put("start_cursor", it) }
                put("page_size", NotionApi.MAX_PAGE_SIZE)
            }
            val json = send("POST", "/v1/search", body)
            (json["results"] as? JsonArray).orEmpty().forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                if ((obj["object"] as? JsonPrimitive)?.contentOrNull != "data_source") return@forEach
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.let(::normalizeNotionId) ?: return@forEach
                val title = plainText(obj["title"])
                found += VisibleDataSource(id, title.trim())
            }
            cursor = nextCursor(json)
            if (cursor == null) return found
        }
        return found
    }

    // ── Transport ────────────────────────────────────────────────────────────

    private suspend fun send(method: String, path: String, body: JsonObject?): JsonObject {
        val url = "$base$path".toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${tokenSource().reveal()}")
            .header("Notion-Version", notionVersion)
            .header("Accept", "application/json")
            .method(method, body?.let { JSON.encodeToString(JsonObject.serializer(), it).toRequestBody(JSON_MEDIA_TYPE) })
            .build()

        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw NotionTransportException(requestMayHaveBeenProcessed = !e.isFailureBeforeSend(), cause = e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) {
                return try {
                    JSON.parseToJsonElement(text).jsonObject
                } catch (_: Exception) {
                    throw NotionApiException(it.code, "malformed_response", "the response was not a JSON object")
                }
            }
            if (it.code == 429) {
                throw NotionRateLimitedException(it.header("Retry-After")?.toDoubleOrNull()?.let { s -> (s * 1000).toLong() })
            }
            val error = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
            throw NotionApiException(
                status = it.code,
                code = (error?.get("code") as? JsonPrimitive)?.contentOrNull,
                detail = (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: "no detail",
            )
        }
    }

    /** Only failures that happen before any bytes of the request are sent. A handshake error is one; any other SSL error might be mid-stream, after the write landed. */
    private fun IOException.isFailureBeforeSend(): Boolean =
        this is ConnectException || this is UnknownHostException || this is NoRouteToHostException || this is SSLHandshakeException

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    // ── JSON out ─────────────────────────────────────────────────────────────

    private fun Map<String, NotionValue>.toJson(): JsonObject {
        val properties = this
        return buildJsonObject { for ((name, value) in properties) put(name, value.toJson(name)) }
    }

    private fun NotionValue.toJson(propertyName: String): JsonElement {
        val value = this
        return buildJsonObject {
            when (value) {
                is NotionValue.Title -> put("title", richTextJson(value.text))
                is NotionValue.RichText -> put("rich_text", richTextJson(value.text))
                is NotionValue.Select -> {
                    val option = value.name
                    if (option == null) put("select", JsonNull) else putJsonObject("select") { put("name", option) }
                }
                is NotionValue.Checkbox -> put("checkbox", value.checked)
                is NotionValue.DateValue -> {
                    val start = value.start
                    val end = value.end
                    if (start == null) put("date", JsonNull) else putJsonObject("date") {
                        put("start", start)
                        end?.let { put("end", it) }
                    }
                }
                is NotionValue.Relation -> putJsonArray("relation") { value.ids.forEach { id -> add(buildJsonObject { put("id", id) }) } }
                is NotionValue.NumberValue -> {
                    val number = value.value
                    if (number == null) put("number", JsonNull) else put("number", number)
                }
                is NotionValue.UrlValue -> {
                    val url = value.value
                    if (url == null) put("url", JsonNull) else put("url", url)
                }
                is NotionValue.CreatedTime, is NotionValue.Unsupported, is NotionValue.UniqueId, is NotionValue.Formula, is NotionValue.Rollup ->
                    throw IllegalArgumentException("property '$propertyName' is a ${value.kindName} value, which Notion computes: it is read-only and cannot be written")
            }
        }
    }

    private fun richTextJson(text: String): JsonArray = buildJsonArray {
        chunkText(text, NotionApi.MAX_TEXT_CHARS).forEach { chunk ->
            add(buildJsonObject {
                put("type", "text")
                putJsonObject("text") { put("content", chunk) }
            })
        }
    }

    private fun NotionBlock.toJson(): JsonElement = when (val block = this) {
        is NotionBlock.Paragraph -> buildJsonObject {
            put("object", "block")
            put("type", "paragraph")
            putJsonObject("paragraph") {
                putJsonArray("rich_text") {
                    block.segments.forEach { segment ->
                        require(segment.text.length <= NotionApi.MAX_TEXT_CHARS) { "a block segment may not exceed ${NotionApi.MAX_TEXT_CHARS} characters" }
                        add(buildJsonObject {
                            put("type", "text")
                            putJsonObject("text") { put("content", segment.text) }
                            if (segment.bold) putJsonObject("annotations") { put("bold", true) }
                        })
                    }
                }
            }
        }
    }

    private fun NotionFilter.toJson(): JsonObject = when (this) {
        is NotionFilter.SelectEquals -> propertyFilter(property, "select", "equals", JsonPrimitive(name))
        is NotionFilter.SelectDoesNotEqual -> propertyFilter(property, "select", "does_not_equal", JsonPrimitive(name))
        is NotionFilter.SelectIsEmpty -> propertyFilter(property, "select", "is_empty", JsonPrimitive(true))
        is NotionFilter.SelectIsNotEmpty -> propertyFilter(property, "select", "is_not_empty", JsonPrimitive(true))
        is NotionFilter.CheckboxEquals -> propertyFilter(property, "checkbox", "equals", JsonPrimitive(value))
        is NotionFilter.DateOnOrBefore -> propertyFilter(property, "date", "on_or_before", JsonPrimitive(iso))
        is NotionFilter.DateOnOrAfter -> propertyFilter(property, "date", "on_or_after", JsonPrimitive(iso))
        is NotionFilter.DateIsEmpty -> propertyFilter(property, "date", "is_empty", JsonPrimitive(true))
        is NotionFilter.DateIsNotEmpty -> propertyFilter(property, "date", "is_not_empty", JsonPrimitive(true))
        is NotionFilter.RelationContains -> propertyFilter(property, "relation", "contains", JsonPrimitive(pageId))
        is NotionFilter.RelationIsEmpty -> propertyFilter(property, "relation", "is_empty", JsonPrimitive(true))
        is NotionFilter.RelationIsNotEmpty -> propertyFilter(property, "relation", "is_not_empty", JsonPrimitive(true))
        is NotionFilter.TextEquals -> propertyFilter(property, kind.apiKey(), "equals", JsonPrimitive(value))
        is NotionFilter.TextContains -> propertyFilter(property, kind.apiKey(), "contains", JsonPrimitive(value))
        is NotionFilter.LastEditedOnOrAfter -> buildJsonObject {
            put("timestamp", "last_edited_time")
            putJsonObject("last_edited_time") { put("on_or_after", iso) }
        }
        is NotionFilter.And -> buildJsonObject { putJsonArray("and") { all.forEach { add(it.toJson()) } } }
        is NotionFilter.Or -> buildJsonObject { putJsonArray("or") { any.forEach { add(it.toJson()) } } }
    }

    private fun NotionFilter.TextKind.apiKey(): String = if (this == NotionFilter.TextKind.TITLE) "title" else "rich_text"

    private fun propertyFilter(property: String, type: String, operator: String, operand: JsonElement): JsonObject = buildJsonObject {
        put("property", property)
        putJsonObject(type) { put(operator, operand) }
    }

    private fun NotionSort.toJson(): JsonObject {
        val sort = this
        return buildJsonObject {
            when (sort) {
                is NotionSort.ByProperty -> put("property", sort.property)
                is NotionSort.ByTimestamp -> put("timestamp", if (sort.field == NotionSort.Timestamp.CREATED_TIME) "created_time" else "last_edited_time")
            }
            put("direction", if (sort.ascending) "ascending" else "descending")
        }
    }

    // ── JSON in ──────────────────────────────────────────────────────────────

    private fun nextCursor(json: JsonObject): String? =
        if ((json["has_more"] as? JsonPrimitive)?.booleanOrNull == true) (json["next_cursor"] as? JsonPrimitive)?.contentOrNull else null

    private fun parsePage(obj: JsonObject): NotionPage {
        val parent = obj["parent"] as? JsonObject
        val dataSource = (parent?.get("data_source_id") as? JsonPrimitive)?.contentOrNull?.let(::normalizeNotionId)
        val properties = (obj["properties"] as? JsonObject).orEmpty().mapValues { (_, value) -> parseValue(value.jsonObject) }
        return NotionPage(
            id = normalizeNotionId((obj["id"] as? JsonPrimitive)?.contentOrNull) ?: throw NotionApiException(200, "malformed_response", "a page had no usable id"),
            dataSourceId = dataSource,
            createdTime = instant(obj["created_time"]),
            lastEditedTime = instant(obj["last_edited_time"]),
            properties = properties,
            archived = (obj["archived"] as? JsonPrimitive)?.booleanOrNull == true || (obj["in_trash"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun parseValue(prop: JsonObject): NotionValue = when (val type = (prop["type"] as? JsonPrimitive)?.contentOrNull) {
        "title" -> NotionValue.Title(plainText(prop["title"]))
        "rich_text" -> NotionValue.RichText(plainText(prop["rich_text"]), hrefs(prop["rich_text"]))
        "number" -> NotionValue.NumberValue((prop["number"] as? JsonPrimitive)?.doubleOrNull)
        "url" -> NotionValue.UrlValue((prop["url"] as? JsonPrimitive)?.contentOrNull)
        "unique_id" -> (prop["unique_id"] as? JsonObject)?.let { id ->
            val number = (id["number"] as? JsonPrimitive)?.longOrNull
            if (number == null) NotionValue.Unsupported("unique_id")
            else NotionValue.UniqueId((id["prefix"] as? JsonPrimitive)?.contentOrNull, number)
        } ?: NotionValue.Unsupported("unique_id")
        "formula" -> parseFormula(prop["formula"] as? JsonObject)
        "rollup" -> parseRollup(prop["rollup"] as? JsonObject)
        "select" -> NotionValue.Select(((prop["select"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull)
        "checkbox" -> NotionValue.Checkbox((prop["checkbox"] as? JsonPrimitive)?.booleanOrNull ?: false)
        "date" -> (prop["date"] as? JsonObject)
            ?.let { NotionValue.DateValue((it["start"] as? JsonPrimitive)?.contentOrNull, (it["end"] as? JsonPrimitive)?.contentOrNull) }
            ?: NotionValue.DateValue(null)
        "relation" -> NotionValue.Relation(
            ids = (prop["relation"] as? JsonArray).orEmpty().mapNotNull { normalizeNotionId(((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull) },
            hasMore = (prop["has_more"] as? JsonPrimitive)?.booleanOrNull == true,
        )
        "created_time" -> NotionValue.CreatedTime(instant(prop["created_time"]))
        else -> NotionValue.Unsupported(type ?: "unknown")
    }

    private fun parseBlock(obj: JsonObject): NotionBlockRead {
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
        val segments = ((obj[type] as? JsonObject)?.get("rich_text") as? JsonArray).orEmpty().map { run ->
            val o = run.jsonObject
            RichSegment(
                text = (o["plain_text"] as? JsonPrimitive)?.contentOrNull ?: (((o["text"] as? JsonObject)?.get("content")) as? JsonPrimitive)?.contentOrNull.orEmpty(),
                bold = (((o["annotations"] as? JsonObject)?.get("bold")) as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        return NotionBlockRead(
            id = normalizeNotionId((obj["id"] as? JsonPrimitive)?.contentOrNull) ?: "",
            type = type,
            segments = segments,
            createdTime = instant(obj["created_time"]),
        )
    }

    private fun parseFormula(formula: JsonObject?): NotionValue {
        val type = (formula?.get("type") as? JsonPrimitive)?.contentOrNull ?: return NotionValue.Unsupported("formula")
        return NotionValue.Formula(
            type = type,
            string = (formula["string"] as? JsonPrimitive)?.contentOrNull,
            number = (formula["number"] as? JsonPrimitive)?.doubleOrNull,
            boolean = (formula["boolean"] as? JsonPrimitive)?.booleanOrNull,
            date = parseDate(formula["date"] as? JsonObject),
        )
    }

    private fun parseRollup(rollup: JsonObject?): NotionValue {
        val type = (rollup?.get("type") as? JsonPrimitive)?.contentOrNull ?: return NotionValue.Unsupported("rollup")
        return NotionValue.Rollup(
            type = type,
            function = (rollup["function"] as? JsonPrimitive)?.contentOrNull,
            number = (rollup["number"] as? JsonPrimitive)?.doubleOrNull,
            date = parseDate(rollup["date"] as? JsonObject),
            items = (rollup["array"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseValue) },
        )
    }

    private fun parseDate(date: JsonObject?): NotionValue.DateValue? =
        date?.let { NotionValue.DateValue((it["start"] as? JsonPrimitive)?.contentOrNull, (it["end"] as? JsonPrimitive)?.contentOrNull) }

    /**
     * The URLs attached to a rich-text array, in order and without repeats: a hyperlink's URL, a
     * mention's `href`, and, when a page or database mention carries no `href`, a URL built from the
     * mentioned id. A mention's `plain_text` is only its title, so this is the only way to learn where it points.
     */
    private fun hrefs(element: JsonElement?): List<String> = (element as? JsonArray).orEmpty().mapNotNull { run ->
        val o = run.jsonObject
        (o["href"] as? JsonPrimitive)?.contentOrNull
            ?: (((o["text"] as? JsonObject)?.get("link") as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
            ?: mentionedUrl(o["mention"] as? JsonObject)
    }.distinct()

    private fun mentionedUrl(mention: JsonObject?): String? {
        val kind = (mention?.get("type") as? JsonPrimitive)?.contentOrNull ?: return null
        if (kind != "page" && kind != "database") return null
        val id = (((mention[kind] as? JsonObject)?.get("id")) as? JsonPrimitive)?.contentOrNull?.let(::normalizeNotionId) ?: return null
        return "https://www.notion.so/${id.replace("-", "")}"
    }

    private fun plainText(element: JsonElement?): String = (element as? JsonArray).orEmpty().joinToString("") { run ->
        val o = run.jsonObject
        (o["plain_text"] as? JsonPrimitive)?.contentOrNull ?: (((o["text"] as? JsonObject)?.get("content")) as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private fun instant(element: JsonElement?): Instant =
        (element as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    companion object {
        const val DEFAULT_BASE_URL = "https://api.notion.com"
        const val NOTION_VERSION = "2025-09-03"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** A data source as `POST /v1/search` lists it: its id (normalized, dashed) and title. Nothing else about it is read. */
data class VisibleDataSource(val id: String, val title: String)
