package app.alfrd.engram.notion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.DateTimeException
import java.time.ZoneId

/** The Notion token itself. Only [reveal] returns the value; printing one, in a log line or a stack trace, prints nothing useful. */
class NotionToken(private val value: String) {
    init { require(value.isNotBlank()) { "a Notion token must not be blank" } }

    fun reveal(): String = value

    override fun toString(): String = "NotionToken(***)"
}

/** Turns a token reference from configuration into the token. The reference may be logged; the token must not be. */
fun interface SecretResolver {
    fun resolve(ref: String): NotionToken
}

/**
 * `env:NAME` reads an environment variable; `file:/absolute/path` reads a file (a trailing newline
 * is dropped). Nothing else is accepted, so a token pasted into configuration where a reference
 * belongs is rejected, and the error does not repeat what was pasted.
 */
class EnvAndFileSecretResolver(
    private val env: Map<String, String> = System.getenv(),
    private val readFile: (String) -> String? = { path -> runCatching { File(path).readText() }.getOrNull() },
) : SecretResolver {
    override fun resolve(ref: String): NotionToken {
        val value = when {
            ref.startsWith(ENV_PREFIX) -> env[ref.removePrefix(ENV_PREFIX)]
            ref.startsWith(FILE_PREFIX) -> readFile(ref.removePrefix(FILE_PREFIX))
            else -> throw NotionConfigException("tokenRef must be 'env:NAME' or 'file:/path'")
        }?.trim()
        if (value.isNullOrEmpty()) throw NotionConfigException("the secret reference '${ref.take(MAX_REF_ECHO)}' did not resolve to a token")
        return NotionToken(value)
    }

    private companion object {
        const val ENV_PREFIX = "env:"
        const val FILE_PREFIX = "file:"
        const val MAX_REF_ECHO = 80
    }
}

/**
 * The five memory tables and the persona page of one person's alfrd teamspace. Every id is the
 * normalized dashed form (see [normalizeNotionId]). The persona is a page, not a table, and is only
 * ever read.
 */
data class NotionDataSources(
    val containers: String,
    val items: String,
    val entities: String,
    val connections: String,
    val sessions: String,
    val personaPageId: String,
) {
    /** The tables the adapter may read and write. The persona page is deliberately not among them. */
    val writableDataSourceIds: Set<String> get() = setOf(containers, items, entities, connections, sessions)

    /**
     * This memory template as a generic [NotionTableSet]: five read-write tables, blocks appendable
     * to Sessions pages only (the ledger), the persona page readable and never writable.
     *
     * [idempotentWrites] opts Items (`Op key`) and Sessions (`Key`) in to guard-enforced idempotency
     * keys. It is off by default, which keeps the memory behavior exactly as it was; the Notion-backed
     * stores turn it on.
     */
    fun toTableSet(name: String = "alfrd-memory", idempotentWrites: Boolean = false): NotionTableSet = NotionTableSet(
        name = name,
        tables = listOf(
            NotionTable("containers", containers),
            NotionTable("items", items, idempotencyKeyProperty = NotionSchema.Items.OP_KEY.takeIf { idempotentWrites }),
            NotionTable("entities", entities),
            NotionTable("connections", connections),
            NotionTable("sessions", sessions, pageBodyAppend = true, idempotencyKeyProperty = NotionSchema.Sessions.KEY.takeIf { idempotentWrites }),
        ),
        readablePages = listOf(NotionReadablePage("persona", personaPageId)),
    )
}

/**
 * Per-identity configuration for Notion storage: a token *reference* (never the token), the data
 * source ids, and the person's time zone (a date without a time is read as a day in this zone).
 *
 * ```json
 * {
 *   "identityId": "jacob",
 *   "storage": "notion",
 *   "tokenRef": "env:ALFRD_NOTION_TOKEN_JACOB",
 *   "timezone": "America/New_York",
 *   "dataSources": {
 *     "containers": "…", "items": "…", "entities": "…",
 *     "connections": "…", "sessions": "…", "persona": "…"
 *   }
 * }
 * ```
 * Ids may be given bare, as `collection://` references, or as Notion URLs. Unknown keys are
 * rejected so a misspelled one cannot silently leave a table unconfigured.
 */
data class NotionIdentityConfig(
    val identityId: String,
    override val tokenRef: String,
    val dataSources: NotionDataSources,
    val timezone: ZoneId = ZoneId.of("UTC"),
) : NotionSetBinding {

    /** This person's memory tables as a table set named `alfrd-memory.<identityId>`, so identities can be checked against each other and against any other set. */
    override val tableSet: NotionTableSet get() = dataSources.toTableSet(name = "alfrd-memory.$identityId")

    companion object {
        private val IDENTITY_ID = Regex("[A-Za-z0-9._-]{1,64}")
        private val TOP_LEVEL_KEYS = setOf("identityId", "storage", "tokenRef", "timezone", "dataSources")
        private val SOURCE_KEYS = setOf("containers", "items", "entities", "connections", "sessions", "persona")

        fun parse(json: String): NotionIdentityConfig {
            val root = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (_: Exception) {
                throw NotionConfigException("the Notion identity configuration is not a JSON object")
            }
            rejectUnknownKeys(root, TOP_LEVEL_KEYS, "configuration")

            root.optionalString("storage")?.let { if (it != "notion") throw NotionConfigException("storage must be \"notion\" for this configuration") }

            val identityId = root.requiredString("identityId")
            if (!IDENTITY_ID.matches(identityId)) throw NotionConfigException("identityId must be 1-64 characters of letters, digits, '.', '_' or '-'")

            val tokenRef = root.requiredString("tokenRef")
            if (!(tokenRef.startsWith("env:") && tokenRef.length > 4) && !(tokenRef.startsWith("file:") && tokenRef.length > 5)) {
                throw NotionConfigException("tokenRef must be 'env:NAME' or 'file:/path'")
            }

            val timezone = root.optionalString("timezone")?.let {
                try { ZoneId.of(it) } catch (_: DateTimeException) { throw NotionConfigException("timezone is not a valid time zone id") }
            } ?: ZoneId.of("UTC")

            val sources = (root["dataSources"] as? JsonObject) ?: throw NotionConfigException("dataSources is required and must be an object")
            rejectUnknownKeys(sources, SOURCE_KEYS, "dataSources")
            fun id(key: String): String =
                normalizeNotionId(sources.requiredString(key)) ?: throw NotionConfigException("dataSources.$key is not a Notion id")

            val parsed = NotionDataSources(
                containers = id("containers"),
                items = id("items"),
                entities = id("entities"),
                connections = id("connections"),
                sessions = id("sessions"),
                personaPageId = id("persona"),
            )
            val roles = listOf(
                "containers" to parsed.containers, "items" to parsed.items, "entities" to parsed.entities,
                "connections" to parsed.connections, "sessions" to parsed.sessions, "persona" to parsed.personaPageId,
            )
            roles.groupBy({ it.second }, { it.first }).values.firstOrNull { it.size > 1 }?.let {
                throw NotionConfigException("dataSources ${it.joinToString(" and ")} point at the same Notion id")
            }
            return NotionIdentityConfig(identityId, tokenRef, parsed, timezone)
        }
    }
}
