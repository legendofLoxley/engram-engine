package app.alfrd.engram.notion

import kotlinx.coroutines.delay

/** How [IdempotentCreator] came by the row it returns. */
enum class CreateOutcome {
    /** A new row was made by this call. */
    CREATED,

    /** [IdempotentCreator.ensure] found a row with this key already there and made nothing. */
    ALREADY_EXISTED,

    /** The create's outcome was unknown, and a lookup by key found the row it had made. */
    RECOVERED,
}

data class IdempotentCreateResult(val page: NotionPage, val outcome: CreateOutcome)

/**
 * Creates rows in one table so that a write whose outcome is unknown can never become two rows.
 * Any table opts in by naming a text property in [NotionTable.idempotencyKeyProperty]; nothing here
 * is specific to alfrd's memory tables.
 *
 * Notion creates are not idempotent, so the layers below never retry one after an ambiguous failure
 * (a 5xx, or a connection that dropped after the request left). They raise
 * [NotionAmbiguousWriteException] instead. This class is what handles it: it looks the row up by its
 * key, a few times with short waits (a query can lag a fresh write), and returns it if it is there.
 * If it still cannot be found it rethrows the ambiguous failure and **does not create again**; the
 * caller defers, and the next [ensure] of the same key settles it.
 *
 * **One writer per key.** Notion cannot enforce uniqueness, so [ensure] (find, then create) is not
 * atomic. Two callers using the same key at the same time (two workers, or a worker that lost its
 * lease but is still running) can both find nothing and both create. A query can also lag a fresh
 * create, so an [ensure] issued immediately after a successful create may not see it. The guarantee
 * here is only that *one* call whose outcome is unknown never becomes two rows. Callers must keep a
 * key to a single writer: one worker per table set, and a lease that is checked before each write.
 * An in-process lock would not cover a second process, so none is offered.
 *
 * - [find] returns the oldest row with the key, or null.
 * - [create] writes the key itself, alongside the given properties.
 * - [ensure] is find-then-create, for callers that may be repeating work (a restart, a reclaimed lease).
 *   An existing row is returned as it is; its other properties are not updated.
 *
 * A key is 1-200 characters, trimmed, without line breaks or control characters. Generate keys from
 * stable inputs (goal, cycle, plan version, task order) so a repeat of the same work gets the same key.
 * [api] should be the guarded, rate-limited stack, so lookups are paced and confined like any other call.
 */
class IdempotentCreator(
    private val api: NotionApi,
    private val table: NotionTable,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val lookupDelaysMs: List<Long> = listOf(0L, 1_000L, 3_000L),
) {
    private val keyProperty: String = table.idempotencyKeyProperty
        ?: throw NotionConfigException("table '${table.name}' has not opted in to idempotent writes: it has no idempotencyKeyProperty")

    init {
        require(lookupDelaysMs.isNotEmpty() && lookupDelaysMs.all { it >= 0 }) { "lookupDelaysMs must be a non-empty list of non-negative waits" }
    }

    suspend fun find(key: String): NotionPage? {
        requireValidKey(key)
        val filter = NotionFilter.TextEquals(keyProperty, key, NotionFilter.TextKind.RICH_TEXT)
        val oldestFirst = listOf(NotionSort.ByTimestamp(NotionSort.Timestamp.CREATED_TIME, ascending = true))
        return api.query(table.dataSourceId, filter, oldestFirst, pageSize = 1).pages.firstOrNull()
    }

    suspend fun create(key: String, properties: Map<String, NotionValue>): IdempotentCreateResult {
        requireValidKey(key)
        val keyValue = NotionValue.RichText(key)
        require(properties[keyProperty].let { it == null || it == keyValue }) { "the properties already carry a different '$keyProperty'" }
        try {
            return IdempotentCreateResult(api.createPage(table.dataSourceId, properties + (keyProperty to keyValue)), CreateOutcome.CREATED)
        } catch (ambiguous: NotionAmbiguousWriteException) {
            for (wait in lookupDelaysMs) {
                if (wait > 0) sleep(wait)
                val found = try {
                    find(key)
                } catch (violation: NotionGuardViolation) {
                    throw violation
                } catch (_: NotionException) {
                    null
                }
                if (found != null) return IdempotentCreateResult(found, CreateOutcome.RECOVERED)
            }
            throw ambiguous
        }
    }

    suspend fun ensure(key: String, properties: Map<String, NotionValue>): IdempotentCreateResult {
        find(key)?.let { return IdempotentCreateResult(it, CreateOutcome.ALREADY_EXISTED) }
        return create(key, properties)
    }

    private fun requireValidKey(key: String) {
        require(key.isNotEmpty() && key.length <= MAX_KEY_CHARS) { "an idempotency key is 1-$MAX_KEY_CHARS characters" }
        require(key == key.trim()) { "an idempotency key must not start or end with whitespace" }
        require(key.none { it.isISOControl() }) { "an idempotency key must not contain line breaks or control characters" }
    }

    private companion object { const val MAX_KEY_CHARS = 200 }
}
