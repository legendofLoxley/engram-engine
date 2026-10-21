package app.alfrd.engram.claims

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Resolves the name a pass wrote for a claimant to one canonical name, so "PPS", "the District" and "Portland Public Schools" are one
 * claimant. [aliases] maps each canonical name to the other names it goes by. Names are compared without case, punctuation, a leading
 * "the" or extra spacing. A name that is not listed is its own canonical name, as first written (spacing tidied): nothing is guessed.
 * Two canonical names claiming the same alias is a mistake in the list and is refused.
 */
class ClaimantResolver(aliases: Map<String, List<String>> = emptyMap()) {
    private val byKey: Map<String, String>

    init {
        val map = HashMap<String, String>()
        aliases.forEach { (canonical, others) ->
            val name = tidy(canonical)
            require(name.isNotEmpty()) { "an alias group needs a canonical name" }
            (others + canonical).forEach { alias ->
                val key = key(alias)
                if (key.isEmpty()) return@forEach
                val existing = map[key]
                require(existing == null || existing == name) { "the name '$alias' is listed for both '$existing' and '$name'" }
                map[key] = name
            }
        }
        byKey = map
    }

    /** The canonical claimant for [raw], or null when no claimant was named. */
    fun canonical(raw: String?): String? {
        val t = tidy(raw ?: return null)
        if (t.isEmpty()) return null
        return byKey[key(t)] ?: t
    }

    companion object {
        /** The comparison form of a claimant: what two spellings of one name share. Null for no claimant. */
        fun key(raw: String?): String = raw.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().removePrefix("the ").trim()

        private fun tidy(raw: String) = raw.trim().replace(Regex("\\s+"), " ")

        /** `{"Portland Public Schools": ["PPS", "the District"], ...}` */
        fun parse(json: String): ClaimantResolver {
            val root = Json.parseToJsonElement(json).jsonObject
            return ClaimantResolver(root.mapValues { (name, v) ->
                require(v is JsonArray) { "aliases for '$name' must be a list of names" }
                v.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("aliases for '$name' must be strings") }
            })
        }

        fun parseObject(o: JsonObject): ClaimantResolver = parse(o.toString())
    }
}
