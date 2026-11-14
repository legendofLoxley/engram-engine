package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.horizon.AppliedOperation
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeBoardSource
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeDirectorMessageStore
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorMessageDigest
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPolicy
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.CHANNEL_SOURCED_SOURCE_TYPE
import app.alfrd.engram.cognitive.pipeline.horizon.CLAUDE_SESSION_SOURCE_TYPE
import app.alfrd.engram.cognitive.pipeline.horizon.ItemWords
import app.alfrd.engram.cognitive.pipeline.horizon.RecordItem
import app.alfrd.engram.db.ChunkDraft
import app.alfrd.engram.db.EmailGraph
import app.alfrd.engram.db.SourceChunker
import app.alfrd.engram.db.SourceEditTime
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPropagator
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonSeeder
import app.alfrd.engram.cognitive.pipeline.horizon.OperationParser
import app.alfrd.engram.cognitive.pipeline.horizon.PerUserCycleLock
import app.alfrd.engram.cognitive.pipeline.horizon.SalientTokenPropagator
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshot
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshotBuilder
import com.arcadedb.database.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** What happened to one operation the caller sent. [applied] false means nothing was written for it; [detail] is the guard's reason or the change made. */
@Serializable
data class OwnerOperationReport(
    val verb: String, val applied: Boolean, val detail: String, val quote: String? = null,
    /** For an operation that cited a source: the document's title and the location of the passage it rests on. Null for a message-grounded one. */
    val source: String? = null, val location: String? = null,
)

/** What happened to one entry of the `sources` array. [stored] false means nothing was written for it. */
@Serializable
data class OwnerSourceReport(
    val id: String, val title: String, val stored: Boolean, val detail: String, val chunks: Int = 0,
    /** Email only: `new`, `repeat` (this Message-ID was already stored with this text) or `repeat_text_differed` (stored with other text: the first text is kept). */
    val outcome: String? = null,
    /** Email only, and only when true: the Message-ID was already stored with a different body. Nothing of the offered body was kept. */
    val sourceTextDiffered: Boolean = false,
)

/**
 * [horizon] is the standing prompt exactly as the Director tick renders it, after any writes. [refs] is what the operation verbs may name
 * (the short refs `c1`, `i2` that an operation's `item` / `target` / `into` fields take), built after the writes, so it is what the *next* call
 * resolves against. [cycleSeq] is the cycle this call allocated, or null for a pure read.
 */
@Serializable
data class OwnerHorizonResponse(
    val horizon: String, val refs: String, val cycleSeq: Long?, val operations: List<OwnerOperationReport>,
    val sources: List<OwnerSourceReport> = emptyList(),
)

class OwnerHorizonUnavailable(message: String) : RuntimeException(message)

/**
 * The owner's Horizon tool: `horizon(instruction?)`. One call may record a message to the ledger, apply operations through the existing
 * guards, and always returns the refreshed Horizon.
 *
 * - **No message and no operations**: a pure read. Nothing is written and no cycle is allocated.
 * - **Otherwise** the whole call runs under the owner's [PerUserCycleLock], as a conversational turn does, and allocates a cycle number the
 *   same way ([CycleSequencer]). The message is written to the ledger as an `Utterance` with `channel = "claude"` (no model call). Operations
 *   go through [OperationParser] (grounding, refs, negation, shape: the message is the text a `quote` must be found in) and then
 *   [HorizonOperationApplier], whose writes are asserted by a `claude_session` Source the owner trusts.
 * - Seeding and propagation run as they do after a turn, so what was just touched or said is in view. The silence check does not: it is the
 *   conversation's, and a Claude session is not a conversation with Alfrd.
 */
class OwnerHorizonService(
    private val email: String,
    private val db: Database,
    private val markers: ChangeMarkerReader,
    private val standing: StandingContext,
    private val sequencer: CycleSequencer,
    private val applier: HorizonOperationApplier,
    private val snapshots: ViewSnapshotBuilder,
    private val seeder: HorizonSeeder,
    private val propagator: HorizonPropagator,
    private val sourceStore: SourceStore = SourceStore(db),
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Stamps the evidence of items a re-sent document still says. */
    private val structure: HorizonStructureStore = ArcadeHorizonStructureStore(db),
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(OwnerHorizonService::class.java)

    /**
     * The items the last response's Horizon named, in order. They are listed with refs even when they fall outside the snapshot's own pools (a board line for
     * an old overdue item, say), so the next call must build its snapshot with them too, or the ref it was shown would be refused. Kept in memory; a restart
     * falls back to the snapshot's own pools.
     */
    @Volatile private var lastShownItems: List<String> = emptyList()

    suspend fun handle(message: String?, ops: JsonArray?, sources: JsonArray? = null): OwnerHorizonResponse {
        val text = message?.trim()?.takeIf { it.isNotEmpty() }
        val sent = ops ?: JsonArray(emptyList())
        val supplied = sources ?: JsonArray(emptyList())
        if (text == null && sent.isEmpty() && supplied.isEmpty()) return read()
        return PerUserCycleLock.withLock(email) { write(text, sent, supplied) }
    }

    private suspend fun read(): OwnerHorizonResponse {
        val marker = markerOrThrow()
        val (rendered, refsText) = renderStanding(marker, emptyList(), marker.lastCycleSeq + 1)
        return OwnerHorizonResponse(rendered.text, refsText, null, emptyList())
    }

    private suspend fun write(text: String?, sent: JsonArray, supplied: JsonArray): OwnerHorizonResponse {
        val before = markerOrThrow()
        // Sources first, so an operation in this same call can cite one. A source alone allocates no cycle and records no item.
        val sourceReports = if (supplied.isEmpty()) emptyList() else storeSources(supplied)
        if (text == null && sent.isEmpty()) {
            val after = markerOrThrow()
            val (rendered, refsText) = renderStanding(after, after.changesSince(before), after.lastCycleSeq + 1)
            logger.info("owner-horizon: sources={} stored={}", sourceReports.size, sourceReports.count { it.stored })
            return OwnerHorizonResponse(rendered.text, refsText, null, emptyList(), sourceReports)
        }
        val cycleSeq = sequencer.allocateCycle(email) ?: throw OwnerHorizonUnavailable("could not allocate a cycle")
        if (text != null) recordUtterance(text, cycleSeq)

        val reports = mutableListOf<OwnerOperationReport>()
        var applied: List<AppliedOperation> = emptyList()
        var sourcedApplied: List<AppliedOperation> = emptyList()
        if (sent.isNotEmpty()) {
            // hold_for_choice asks the user a question in a reply; there is no reply here, so it is refused before it can suppress the rest.
            // An operation beyond the cap is refused whichever kind it is; one that names a `source` rests on that document, not on the message.
            val allowed = mutableListOf<JsonElement>()
            val cited = mutableListOf<JsonObject>()
            val overflow = mutableListOf<OwnerOperationReport>()
            for ((index, element) in sent.withIndex()) {
                val obj = element as? JsonObject
                val verb = (obj?.get("verb") as? JsonPrimitive)?.takeIf { it.isString }?.content
                val quote = (obj?.get("quote") as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    index >= OperationParser.MAX_OPERATIONS -> overflow += OwnerOperationReport(verb ?: "?", false, "too_many_operations", quote)
                    verb == "hold_for_choice" -> reports += OwnerOperationReport(verb, false, "not_available_on_owner_route", quote)
                    obj != null && obj.containsKey("source") -> cited += obj
                    else -> allowed += element
                }
            }
            val snapshot = try {
                // The refs the last response showed were built with the items its Horizon named; building with the same ones keeps those refs working now.
                snapshots.build(email, zone, include = lastShownItems)
            } catch (e: Exception) {
                logger.warn("owner-horizon: view snapshot failed: {}", e::class.java.simpleName)
                ViewSnapshot(emptyList(), emptyList(), emptyList(), zone = zone)
            }
            val parsed = OperationParser.parse(JsonObject(mapOf("ops" to JsonArray(allowed))), text.orEmpty(), snapshot, zone)
            applied = applier.apply(email, cycleSeq, "claude-owner-${UUID.randomUUID()}", parsed.operations, zone, CLAUDE_SESSION_SOURCE_TYPE)
            applied.forEach { reports += OwnerOperationReport(it.operation.verb, it.applied, it.summary, it.operation.quote.takeIf { q -> q.isNotBlank() }) }
            parsed.rejections.forEach { reports += OwnerOperationReport(it.verb, false, it.reason, it.quote) }

            // Operations that cite a source: the quote is checked against that source's chunk text, not the message.
            val plans = cited.map { planSourced(it, snapshot) }
            reports += plans.filterIsInstance<SourcedPlan.Refused>().map { it.report }
            val ready = plans.filterIsInstance<SourcedPlan.Ready>()
            if (ready.isNotEmpty()) {
                sourcedApplied = applier.apply(
                    email, cycleSeq, "claude-owner-src-${UUID.randomUUID()}", ready.map { it.op }, zone, CLAUDE_SESSION_SOURCE_TYPE,
                    chunkSourceType = CHANNEL_SOURCED_SOURCE_TYPE,
                )
                sourcedApplied.forEachIndexed { i, a ->
                    val plan = ready[i]
                    val detail = if (a.applied) "${a.summary} — from the source \"${plan.title}\" (${plan.location}); not recorded as the owner's statement" else a.summary
                    reports += OwnerOperationReport(a.operation.verb, a.applied, detail, a.operation.quote.takeIf { q -> q.isNotBlank() }, plan.title, plan.location)
                }
            }
            reports += overflow
        }

        val allApplied = applied + sourcedApplied
        if (text != null || allApplied.isNotEmpty()) {
            try {
                seeder.seedTurn(email, cycleSeq, text.orEmpty(), allApplied)
                propagator.propagate(email, cycleSeq, allApplied.filter { it.applied && it.resultUid != null }.map { it.resultUid!! to (it.resultText ?: "") })
            } catch (e: Exception) {
                logger.warn("owner-horizon: seeding or propagation failed: {}", e::class.java.simpleName)
            }
        }

        val after = markerOrThrow()
        val (rendered, refsText) = renderStanding(after, after.changesSince(before), cycleSeq)
        logger.info(
            "owner-horizon: cycle={} message={} operations={} applied={} sources={} sourced={}",
            cycleSeq, text != null, reports.size, reports.count { it.applied }, sourceReports.size, sourcedApplied.count { it.applied },
        )
        return OwnerHorizonResponse(rendered.text, refsText, cycleSeq, reports, sourceReports)
    }

    // ── Sources a channel supplies ───────────────────────────────────────────

    /**
     * Stores each entry of `sources` as a Source with Chunks through the one [SourceStore] the Drive connector and the PDF reader use:
     * idempotent by content hash, so text already stored adds nothing; changed text under the same id is a new Source and the earlier one is
     * marked superseded (nothing is deleted). The Source records that the text was supplied by this channel and that the Director did not read it.
     */
    private suspend fun storeSources(entries: JsonArray): List<OwnerSourceReport> = withContext(Dispatchers.IO) {
        val seen = HashSet<String>()
        entries.withIndex().map { (index, element) ->
            val obj = element as? JsonObject
            val rawId = obj?.str("id")?.trim().orEmpty()
            val title = obj?.str("title")?.trim().orEmpty()
            fun refused(reason: String) = OwnerSourceReport(rawId.ifEmpty { "?" }, title, false, "refused: $reason")
            when {
                obj == null -> refused("malformed_source")
                index >= MAX_SOURCES -> refused("too_many_sources")
                rawId.isEmpty() -> refused("missing_id")
                title.isEmpty() && !(obj.str("kind") == KIND_EMAIL_MESSAGE && !obj.str("subject").isNullOrBlank()) -> refused("missing_title")
                obj.str("kind") == KIND_EMAIL_MESSAGE -> if (!seen.add("$KIND_EMAIL_MESSAGE|${EmailGraph.messageId(rawId)}")) refused("duplicate_source_id") else storeEmail(obj, rawId, title)
                obj.str("kind") != KIND_NOTION_PAGE -> refused("unsupported_source_kind")
                !seen.add(locatorOf(rawId)) -> refused("duplicate_source_id")
                else -> try {
                    val chunks = obj.chunkDrafts()
                    val body = obj.str("text")
                    val joined = body ?: chunks?.joinToString("\n\n") { it.text }
                    when {
                        (body != null) == (chunks != null) -> refused("send_either_text_or_chunks")
                        joined.isNullOrBlank() -> refused("empty_text")
                        else -> {
                            val result = sourceStore.ingest(
                                email,
                                SourceInput(
                                    type = SOURCE_TYPE_NOTION, locator = locatorOf(rawId), title = title, text = joined, chunks = chunks,
                                    suppliedBy = CHANNEL, url = obj.str("url")?.trim()?.takeIf { it.isNotEmpty() },
                                    lastEditedAt = obj.str("last_edited")?.trim()?.takeIf { it.isNotEmpty() },
                                ),
                            )
                            val changed = sourceStore.markCurrent(email, result.source.uid)
                            confirmItemsFrom(result.source.uid, joined, SourceEditTime.parse(obj.str("last_edited")))
                            OwnerSourceReport(
                                rawId, title, true,
                                when {
                                    result.created && changed > 0 -> "stored: the text changed, so the earlier text of this page is superseded (kept, not deleted)"
                                    result.created -> "stored: new source"
                                    changed > 0 -> "unchanged text matched an earlier version of this page, which is current again"
                                    else -> "unchanged: this text is already stored, nothing was added"
                                },
                                result.source.chunkCount,
                            )
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    refused("invalid_source")
                } catch (e: Exception) {
                    logger.warn("owner-horizon: storing a source failed: {}", e::class.java.simpleName)
                    refused("could_not_store")
                }
            }
        }
    }

    /**
     * One entry of kind `email_message`: the Message-ID (`id`), the body (`text`) and what the message says about itself (`subject`, `sent_at`, `from`, `to`,
     * `cc`, `bcc`, `gmail_message_id`, `gmail_thread_id`) go to [SourceStore.ingest] as an email, which makes the Email, its address nodes and its SENT_BY and SENT_TO
     * edges. Identity is the engine's: one Email per owner and Message-ID, the first text kept. Unlike a page there is no version to supersede, so nothing is
     * marked current and no item is re-confirmed; a repeat with another body is reported (`repeat_text_differed`) and writes nothing. The body is sent as `text`
     * (the engine strips quoted history when it chunks a message); cut `chunks` are not accepted.
     */
    private fun storeEmail(obj: JsonObject, rawId: String, rawTitle: String): OwnerSourceReport {
        val locator = EmailGraph.messageId(rawId)
        val subject = obj.str("subject")?.trim()?.takeIf { it.isNotEmpty() }
        val title = rawTitle.ifEmpty { subject.orEmpty() }
        fun refused(reason: String) = OwnerSourceReport(locator.ifEmpty { "?" }, title, false, "refused: $reason")
        return try {
            if (obj.containsKey("chunks")) return refused("send_text_for_email")
            val body = obj.str("text")
            if (body.isNullOrBlank()) return refused("empty_text")
            val sentRaw = obj.str("sent_at")?.trim()?.takeIf { it.isNotEmpty() }
            val sentAt = SourceEditTime.parse(sentRaw)
            if (sentRaw != null && sentAt == null) return refused("invalid_sent_at")
            val from = obj["from"]?.let { party(it) ?: return refused("invalid_sender") }
            val to = parties(obj, "to") ?: return refused("invalid_recipients")
            val cc = parties(obj, "cc") ?: return refused("invalid_recipients")
            val bcc = parties(obj, "bcc") ?: return refused("invalid_recipients")
            val facts = EmailGraph.Facts(
                subject = subject, sentAt = sentAt,
                gmailMessageId = obj.str("gmail_message_id")?.trim()?.takeIf { it.isNotEmpty() },
                gmailThreadId = obj.str("gmail_thread_id")?.trim()?.takeIf { it.isNotEmpty() },
                from = from, to = to, cc = cc, bcc = bcc,
            )
            if (facts.recipients().size > EmailGraph.MAX_RECIPIENTS) return refused("too_many_recipients")
            val result = sourceStore.ingest(
                email,
                SourceInput(
                    type = SOURCE_TYPE_EMAIL, locator = locator, title = title, text = body, threadId = facts.gmailThreadId,
                    suppliedBy = CHANNEL, url = obj.str("url")?.trim()?.takeIf { it.isNotEmpty() }, email = facts,
                ),
            )
            val (outcome, detail) = when {
                result.created -> "new" to "stored: new email"
                result.contentChanged -> "repeat_text_differed" to "this Message-ID is already stored with different text; the first text is kept as the evidence and the body sent now was not stored. Nothing was added"
                else -> "repeat" to "unchanged: this email is already stored, nothing was added"
            }
            OwnerSourceReport(locator, title, true, detail, result.source.chunkCount, outcome, sourceTextDiffered = result.contentChanged)
        } catch (e: IllegalArgumentException) {
            refused("invalid_source")
        } catch (e: Exception) {
            logger.warn("owner-horizon: storing an email failed: {}", e::class.java.simpleName)
            refused("could_not_store")
        }
    }

    /** A sender or recipient: `{"address": ..., "name": ...}` or a bare address string. Null when it is neither. */
    private fun party(e: JsonElement): EmailGraph.Party? = when (e) {
        is JsonPrimitive -> e.takeIf { it.isString }?.content?.trim()?.takeIf { EmailGraph.normalize(it) != null }?.let { EmailGraph.Party(it) }
        is JsonObject -> e.str("address")?.trim()?.takeIf { EmailGraph.normalize(it) != null }?.let { EmailGraph.Party(it, e.str("name")) }
        else -> null
    }

    /** The recipients under [key]: empty when absent, null when the field or any entry is malformed. */
    private fun parties(obj: JsonObject, key: String): List<EmailGraph.Party>? {
        val raw = obj[key] ?: return emptyList()
        val list = raw as? JsonArray ?: return null
        return list.map { party(it) ?: return null }
    }

    /**
     * A document sent again, unchanged or not, confirms what was filed from it: every item that quotes any stored version of this page, and whose
     * quoted passage is still in the text just sent, has its evidence refreshed. An item whose passage is gone is left as it was.
     */
    private suspend fun confirmItemsFrom(sourceUid: String, text: String, editedAt: Long?) {
        val wanted = SourceChunker.normalize(text)
        val items = sourceStore.itemsQuotedFrom(email, sourceStore.versionsOf(email, sourceUid))
            .filter { (_, quote) -> quote.isNullOrBlank() || wanted.contains(SourceChunker.normalize(quote)) }
            .map { it.first }.distinct()
        // With the page's last_edited, evidence follows it (raised when later; lowered only for an item no one but a source has touched). Without one, now, as before.
        for (uid in items) structure.refreshEvidenceFromSource(email, 0L, uid, editedAt)
    }

    private sealed interface SourcedPlan {
        data class Ready(val op: RecordItem, val title: String, val location: String) : SourcedPlan
        data class Refused(val report: OwnerOperationReport) : SourcedPlan
    }

    /**
     * One operation that cites a source. Only `record_item` may: the quote must be a passage of one chunk of the cited source (the same
     * grounding rule as a message, run against the chunk), and the item is written with writer `claude_session` and the source as its claimant.
     */
    private suspend fun planSourced(obj: JsonObject, snapshot: ViewSnapshot): SourcedPlan {
        val verb = obj.str("verb") ?: "?"
        val quote = obj.str("quote")?.trim().orEmpty()
        fun no(reason: String) = SourcedPlan.Refused(OwnerOperationReport(verb, false, reason, quote.takeIf { it.isNotBlank() }))
        if (verb != "record_item") return no("source_only_for_record_item")
        val id = obj.str("source")?.trim().orEmpty()
        if (id.isEmpty()) return no("missing_source")
        if (quote.isBlank()) return no("missing_quote")
        val (source, chunks) = withContext(Dispatchers.IO) {
            // A Notion page by its id; failing that, an email by its Message-ID (a Message-ID is never a page id, and the owner's graph is all this reads).
            val found = sourceStore.currentByLocator(email, SOURCE_TYPE_NOTION, locatorOf(id)) ?: sourceStore.currentByLocator(email, SOURCE_TYPE_EMAIL, EmailGraph.messageId(id))
            found to found?.let { sourceStore.chunks(email, it.uid) }.orEmpty()
        }
        if (source == null) return no("unknown_source")
        val chunk = chunks.firstOrNull { SourceChunker.normalize(it.text).contains(SourceChunker.normalize(quote)) } ?: return no("quote_not_in_source")
        // The ordinary parser does the rest (shape, kind, question check, project ref, date, label), with the chunk as the text a quote is found in.
        val parsed = OperationParser.parse(JsonObject(mapOf("ops" to JsonArray(listOf(obj)))), chunk.text, snapshot, zone)
        parsed.rejections.firstOrNull()?.let { return no(it.reason) }
        val op = parsed.operations.singleOrNull() as? RecordItem ?: return no("not_recordable")
        val author = obj.str("author")?.trim()?.takeIf { it.isNotEmpty() }
        // An email states its own sender; a page has no author unless the caller names one.
        val sender = if (source.type == SOURCE_TYPE_EMAIL) withContext(Dispatchers.IO) { sourceStore.emailView(email, source.uid) }?.let { it.fromName ?: it.from } else null
        val claimant = when {
            author != null -> "$author (in ${source.title})"
            sender != null -> "$sender (in ${source.title})"
            else -> "source: ${source.title} (author unknown)"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${op.kind.name}|${ItemWords.normalize(op.title ?: op.quote)}|${chunk.ordinal}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
        return SourcedPlan.Ready(
            // A document may claim a state and a hard or soft deadline; the other time fields are not accepted from a source.
            op.copy(
                chunkUid = chunk.uid, claimant = claimant, writer = CLAUDE_SESSION_SOURCE_TYPE, itemKey = "chsrc:${source.uid}:$digest",
                startsAt = null, startsHasTime = false, nudgeAt = null, nudgeHasTime = false, happenedAt = null, happenedHasTime = false, waitingOn = null,
            ),
            source.title, chunk.location,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The entry's `chunks` as drafts, or null when it has none. A malformed list throws [IllegalArgumentException]. */
    private fun JsonObject.chunkDrafts(): List<ChunkDraft>? {
        val raw = this["chunks"] ?: return null
        val list = raw as? JsonArray ?: throw IllegalArgumentException("chunks must be an array")
        require(list.size in 1..MAX_CHUNKS) { "chunks must hold 1 to $MAX_CHUNKS entries" }
        return list.mapIndexed { i, c ->
            val o = c as? JsonObject ?: throw IllegalArgumentException("a chunk must be an object")
            ChunkDraft(location = o.str("location")?.trim()?.takeIf { it.isNotEmpty() } ?: "part ${i + 1}", text = o.str("text") ?: throw IllegalArgumentException("a chunk needs text"))
        }
    }

    /** A Notion page id is the same page with or without dashes and in any case; other ids are kept as sent. */
    private fun locatorOf(id: String): String =
        id.trim().replace("-", "").takeIf { it.length == 32 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }?.lowercase() ?: id.trim()

    /**
     * The standing Horizon and the refs listing built together: the snapshot is made once the Horizon is assembled, with everything the Horizon shows
     * included, so a ref printed beside an also-due line or a stale question is the one the listing gives it.
     */
    private suspend fun renderStanding(marker: ChangeMarker, changes: List<ChangeMarker.Change>, cycleSeq: Long): Pair<StandingContext.Rendered, String> {
        var snapshot: ViewSnapshot? = null
        val rendered = standing.render(marker, changes, "Horizon tool", clock(), cycleSeq, forChannel = true, channel = CHANNEL) { outcome, board ->
            // Zoomed out, the text names the board's items; focused, it names what the Horizon lists.
            val shown = board?.itemUids ?: (outcome as? AssembleOutcome.Assembled)?.horizon?.let { h ->
                h.items.mapNotNull { it.sourceRefs.firstOrNull()?.phraseUid } + h.alsoDue.map { it.uid } + h.hidden.map { it.uid }
            }.orEmpty()
            snapshot = try { snapshots.build(email, zone, include = shown) } catch (e: Exception) { null }
            lastShownItems = shown
            snapshot?.refsByUid().orEmpty()
        }
        // Zoomed out, the listing is bounded like the board: every area, the projects on the board, the projects of the items listed. A project left out is reached
        // through its area. A focused view keeps the full listing (it is how an item is re-filed into any project).
        val bound = rendered.board?.let { it.projectUids.toSet() }
        return rendered to (snapshot?.render(bound) ?: "Refs unavailable: the graph read failed.")
    }

    private suspend fun refs(): String = try {
        snapshots.build(email, zone).render()
    } catch (e: Exception) {
        "Refs unavailable: the graph read failed."
    }

    private suspend fun markerOrThrow(): ChangeMarker =
        withContext(Dispatchers.IO) { markers.read(email) } ?: throw OwnerHorizonUnavailable("the owner identity has no User vertex")

    /** The message as the ledger keeps it: one `user` Utterance, `channel = "claude"`, chained after the last of this channel's with the same FOLLOWS edge a conversation uses. */
    private suspend fun recordUtterance(text: String, cycleSeq: Long) = withContext(Dispatchers.IO) {
        db.transaction {
            val tail = db.query("sql", "SELECT FROM Utterance WHERE sessionId = :s ORDER BY createdAt DESC LIMIT 1", mapOf("s" to LEDGER_SESSION))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
            val vertex = db.newVertex("Utterance").apply {
                set("uid", UUID.randomUUID().toString())
                set("sessionId", LEDGER_SESSION)
                set("userId", email)
                set("turnIndex", cycleSeq.toInt())
                set("role", "user")
                set("text", text)
                set("channel", CHANNEL)
                set("createdAt", System.currentTimeMillis())
                save()
            }
            tail?.newEdge("FOLLOWS", vertex, false)?.apply { set("attributions", "[]"); set("scores", "[]"); save() }
        }
    }

    companion object {
        const val CHANNEL = "claude"
        const val LEDGER_SESSION = "claude-owner"
        const val KIND_NOTION_PAGE = "notion_page"
        const val KIND_EMAIL_MESSAGE = "email_message"
        private const val SOURCE_TYPE_NOTION = "notion_page"
        private const val SOURCE_TYPE_EMAIL = "email_message"
        private const val MAX_SOURCES = 5
        private const val MAX_CHUNKS = 200
    }
}

/** The owner token file's outcome. The token itself is never logged or placed in a message. */
sealed interface OwnerTokenResult {
    data class Ready(val token: String, val created: Boolean) : OwnerTokenResult
    data class Refused(val reason: String) : OwnerTokenResult
}

object OwnerToken {
    fun defaultPath(): Path = Path.of(System.getProperty("user.home"), ".config", "engram", "owner-token")

    /**
     * Reads the token at [path], or creates it (32 random bytes, hex) with mode 600 when the file is missing. Refuses a file readable or
     * writable by group or others, and an empty one: a token other users could read is not a secret. The parent directory is created 700.
     */
    fun loadOrCreate(path: Path): OwnerTokenResult {
        try {
            if (!Files.exists(path)) {
                path.parent?.let { dir ->
                    if (!Files.exists(dir)) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                }
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val token = bytes.joinToString("") { "%02x".format(it) }
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                Files.writeString(path, token + "\n")
                return OwnerTokenResult.Ready(token, created = true)
            }
            val loose = Files.getPosixFilePermissions(path).filter {
                it in setOf(
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
                )
            }
            if (loose.isNotEmpty()) return OwnerTokenResult.Refused("the owner token file is accessible to group or others; it must be mode 600")
            val token = Files.readString(path).trim()
            if (token.length < MIN_LENGTH) return OwnerTokenResult.Refused("the owner token file is empty or too short")
            return OwnerTokenResult.Ready(token, created = false)
        } catch (e: Exception) {
            return OwnerTokenResult.Refused("the owner token file could not be read or created (${e::class.java.simpleName})")
        }
    }

    const val MIN_LENGTH = 16
}

/** Whether the owner's Horizon route is serving, and why not when it is not. */
sealed interface OwnerHorizonStartup {
    data class Running(val service: OwnerHorizonService, val token: String) : OwnerHorizonStartup
    data class Disabled(val reason: String) : OwnerHorizonStartup
}

private val ownerLog = LoggerFactory.getLogger("app.alfrd.engram.director.OwnerHorizonBootstrap")

const val ENV_OWNER_IDENTITY = "OWNER_IDENTITY"
const val ENV_OWNER_TOKEN_FILE = "OWNER_TOKEN_FILE"

/**
 * Builds the owner's Horizon tool from the environment. Off unless `OWNER_IDENTITY` is set; the identity is fixed by configuration, never
 * by the caller. Off, too, when the token file cannot be trusted (the route then answers 503 and says why, never 200).
 */
fun bootstrapOwnerHorizon(
    db: Database,
    notionStartup: NotionCycleStartup,
    hermesActive: HermesActiveAssignmentRegistry,
    tickStartup: DirectorTickStartup,
    env: Map<String, String> = System.getenv(),
    probe: SelfStateProbe = SystemProbe(),
): OwnerHorizonStartup {
    val raw = env[ENV_OWNER_IDENTITY]?.trim().orEmpty()
    if (raw.isEmpty()) return OwnerHorizonStartup.Disabled("$ENV_OWNER_IDENTITY is not set").also { ownerLog.info("owner-horizon: not serving: {}", it.reason) }
    val path = env[ENV_OWNER_TOKEN_FILE]?.trim()?.takeIf { it.isNotEmpty() }?.let(Path::of) ?: OwnerToken.defaultPath()
    val token = when (val loaded = OwnerToken.loadOrCreate(path)) {
        is OwnerTokenResult.Refused -> return OwnerHorizonStartup.Disabled(loaded.reason).also { ownerLog.error("owner-horizon: NOT SERVING: {}", it.reason) }
        is OwnerTokenResult.Ready -> loaded.also { if (it.created) ownerLog.info("owner-horizon: created the owner token file at {}", path) }.token
    }
    val email = DebugConverseService.resolveUserId(raw)
    val structure = ArcadeHorizonStructureStore(db)
    val sourceStore = SourceStore(db)
    val graph = ArcadeHorizonGraphStore(db)
    val assembler = ArcadeHorizonAssembler(db)
    val tick = (tickStartup as? DirectorTickStartup.Running)?.tick
    val policy = HorizonPolicy.fromEnvironment()
    val directorMessages = ArcadeDirectorMessageStore(db)
    val service = OwnerHorizonService(
        email = email,
        db = db,
        markers = ChangeMarkerReader(db),
        standing = StandingContext(
            email, tick != null, tick?.interval?.seconds ?: 0L, assembler, probe,
            notion = { notionState(notionStartup) },
            hermes = { hermesActive.activeByKind() },
            budgets = { budgetDefaults(notionStartup) },
            board = ArcadeBoardSource(db, policy),
            directorMessages = DirectorMessageDigest(directorMessages, structure, policy),
        ),
        sequencer = ArcadeCycleSequencer(db),
        applier = HorizonOperationApplier(structure, graph, sourceStore, directorMessages),
        snapshots = ViewSnapshotBuilder(structure),
        seeder = HorizonSeeder(structure),
        propagator = SalientTokenPropagator(graph, assembler),
        sourceStore = sourceStore,
    )
    ownerLog.info("owner-horizon: serving identity={}", raw)
    return OwnerHorizonStartup.Running(service, token)
}
