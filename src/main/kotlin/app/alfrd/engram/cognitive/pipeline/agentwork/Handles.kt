package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.normalizeNotionId

/**
 * Short names for what a goal's Scope puts in reach, so a model never has to copy a 32-character id.
 *
 * `P1`, `P2`... are mentioned pages, `D1`... are mentioned databases and `R1`, `R2`... are the rows of those
 * databases, numbered in the order Scope lists them. Every model-facing prompt shows the handle next to the
 * title; the model writes handles in `target_links` and `pages_used`; **code maps them back to the real URLs**.
 * One map serves a whole cycle, so a handle means the same thing in the plan, a task and QC.
 *
 * Resolving is a convenience and never a permission: a resolved link is checked against [AllowedScope] exactly
 * like one typed out in full, and a handle that names nothing is left as it was, so it is rejected exactly as
 * an unknown link is. An exact real URL still resolves to itself, so nothing that worked before breaks.
 */
class Handles private constructor(private val urlByHandle: Map<String, String>, private val handleById: Map<String, String>) {

    /** The URL a handle names, or null. Accepts `R3`, `r3`, `[R3]`. */
    fun urlOf(handle: String): String? = parse(handle)?.let(urlByHandle::get)

    /** The handle of a page, row or database given by id or URL, or null when it has none. */
    fun handleFor(idOrUrl: String): String? =
        (notionPageIdInPath(idOrUrl) ?: normalizeNotionId(idOrUrl))?.let(handleById::get)

    /** [link] as a URL when it is a known handle; otherwise unchanged. */
    fun resolve(link: String): String = urlOf(link.trim()) ?: link

    /** `[R3]` for something that has a handle, otherwise its [fallback]. */
    fun label(idOrUrl: String, fallback: String = ""): String = handleFor(idOrUrl)?.let { "[$it]" } ?: fallback

    val size: Int get() = urlByHandle.size

    companion object {
        val NONE = Handles(emptyMap(), emptyMap())
        private val SHAPE = Regex("""^\[?\s*([PDRpdr])\s*(\d{1,4})\s*\]?$""")

        private fun parse(text: String): String? = SHAPE.matchEntire(text.trim())?.let { "${it.groupValues[1].uppercase()}${it.groupValues[2].toInt()}" }

        fun isHandle(text: String): Boolean = SHAPE.matches(text.trim())

        fun of(scope: ScopeContext): Handles {
            val url = LinkedHashMap<String, String>()
            val byId = HashMap<String, String>()
            fun add(prefix: String, n: Int, id: String) {
                val key = normalizeNotionId(id) ?: return
                if (key in byId) return
                url["$prefix$n"] = notionPageUrl(id)
                byId[key] = "$prefix$n"
            }
            val pageIds = scope.outcomes.filterIsInstance<ScopeOutcome.PageRead>().map { it.pageId }
            pageIds.forEachIndexed { i, id -> add("P", i + 1, id) }
            scope.outcomes.filterIsInstance<ScopeOutcome.TableListed>().forEachIndexed { i, o -> add("D", i + 1, o.mention.id) }
            val pageKeys = pageIds.mapNotNull { normalizeNotionId(it) }.toSet()
            scope.rows.filter { normalizeNotionId(it.pageId) !in pageKeys }.forEachIndexed { i, row -> add("R", i + 1, row.pageId) }
            return Handles(url, byId)
        }
    }
}
