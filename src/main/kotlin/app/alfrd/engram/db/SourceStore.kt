package app.alfrd.engram.db

import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOwnership
import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Where a Source came from in Google Drive: the file's id (stable across renames), its name and link as of the read, the version Drive
 * reported (a number that grows with every change) and the content md5, when it was last modified, the Shared Drive it lives in
 * ([driveId]; null in My Drive), the head revision, and the configured folder it was found in. Kept on the Source so provenance survives
 * a rename or a move; the page of each chunk is on the chunk.
 */
@kotlinx.serialization.Serializable
data class DriveFacts(
    val fileId: String,
    val fileName: String? = null,
    val webViewLink: String? = null,
    val version: String? = null,
    val md5: String? = null,
    val modifiedTime: String? = null,
    val driveId: String? = null,
    val headRevisionId: String? = null,
    val folderId: String? = null,
)

/** What is read: one document, page or email message. [text] is the whole text as read; the chunker decides what becomes chunks. */
data class SourceInput(
    /** `notion_page`, `document` or `email_message`. */
    val type: String,
    /** Where it lives: a page URL or id, a file path or id, or an email Message-ID. With [type], the identity of the thing read. */
    val locator: String,
    val title: String,
    val text: String,
    /** How much was read: `full`, `partial` or `ocr`. */
    val readMode: String = "full",
    /** Email only: the thread this message belongs to. The message, not the thread, is the Source. */
    val threadId: String? = null,
    val readAt: Long = System.currentTimeMillis(),
    /** Chunks already cut by a reader that knows the file's layout (a PDF); when given, [text] is not chunked again. */
    val chunks: List<ChunkDraft>? = null,
    /**
     * SHA-256 of the original file's bytes. When given, it is the Source's identity (stored as `contentHash` too): the same file is the
     * same Source however it was read, and a changed file is a new one. [text] still has to be non-blank but is not hashed.
     */
    val fileSha256: String? = null,
    val pageCount: Int? = null,
    /** Pages read by OCR, and pages that yielded nothing (so the Source is not the whole file). */
    val ocrPages: Int? = null,
    val unreadPages: Int? = null,
    /** Set when the file was fetched from Google Drive. */
    val drive: DriveFacts? = null,
    /**
     * Set when the text was supplied by a channel rather than read by alfrd: who supplied it (the `Utterance.channel` value, e.g. `claude`).
     * The Source then records `readByDirector = false`, and [url] / [lastEditedAt] are what the channel said about the page.
     */
    val suppliedBy: String? = null,
    val url: String? = null,
    val lastEditedAt: String? = null,
    /** Email only: what the message says about itself (subject, sent time, Gmail ids, sender, recipients). The addresses become nodes and edges. */
    val email: EmailGraph.Facts? = null,
)

data class SourceRecord(
    val uid: String, val type: String, val locator: String, val title: String, val readMode: String,
    val threadId: String?, val contentHash: String, val readAt: Long, val chunkCount: Int,
    val fileSha256: String? = null, val pageCount: Int? = null, val ocrPages: Int? = null, val unreadPages: Int? = null,
    val drive: DriveFacts? = null,
    /** Who issued the document ("Portland Public Schools"): the parent a school it names takes unless the page names another. Set by a person, never read off the page. */
    val issuer: String? = null,
    /** For text a channel supplied: the channel, and whether the Director read it (it did not). Null for everything alfrd read itself. */
    val suppliedBy: String? = null,
    val readByDirector: Boolean? = null,
    val url: String? = null,
    val lastEditedAt: String? = null,
    /** The uid of the later Source that replaced this one (same locator, other text) — set only for channel-supplied text. */
    val supersededBy: String? = null,
)

data class ChunkRecord(
    val uid: String, val sourceUid: String, val ordinal: Int, val location: String, val text: String, val hash: String,
    val page: Int? = null, val printedPage: String? = null, val section: String? = null, val kind: String? = null,
    val extractor: String? = null, val ocrConfidence: Double? = null, val box: String? = null, val table: String? = null,
    /** For a chunk added by a later reading of its page: the reading's key and its checked entries (JSON). */
    val readingKey: String? = null, val reading: String? = null,
)

data class IngestResult(
    val source: SourceRecord,
    /** False when this exact content was already there: nothing was written. */
    val created: Boolean,
    val chunksCreated: Int,
    /**
     * Email only: the Message-ID was already stored, but this text hashes differently from the stored one. Nothing was written (a Message-ID is one Email,
     * and the first text stays the evidence); the caller can see that the body it offered was not kept.
     */
    val contentChanged: Boolean = false,
)

/** An Email as the graph holds it: its facts and the addresses on its edges, as stored. */
data class EmailView(
    val subject: String?, val sentAt: Long?, val gmailMessageId: String?, val gmailThreadId: String?,
    val from: String?, val fromName: String?, val to: List<String>, val cc: List<String>, val bcc: List<String>,
)

/** The claimant, quote and date a linked source item was recorded with. [quote] is null for a link made before the quote was kept. */
data class LinkedDraft(val claimant: String?, val quote: String?, val dueAt: Long?)

enum class LinkOutcome { CREATED, ALREADY_LINKED, REFUSED }

/** Where an item came from: its chunk and location, the source, who recorded it and who claimed it. */
data class ItemProvenance(
    val chunkUid: String, val location: String, val sourceUid: String, val sourceType: String,
    val sourceLocator: String, val sourceTitle: String, val readMode: String, val writer: String?, val claimant: String?,
)

/**
 * Sources and their chunks in the graph, and the link from an item to the chunk it came from.
 *
 * ```
 * User -TRUSTS-> Source -CONTAINS(position = ordinal)-> Chunk <-QUOTES(writer, claimant)- Item
 * ```
 * **Idempotent by content hash**: a Source is identified by (owner, type, locator, content hash of the text as read).
 * Reading the same content again finds that Source and writes nothing; changed content under the same locator is a
 * new Source (the old one stays). A new Source and all its chunks are written in one transaction.
 *
 * Chunks are not items: they are never ASSERTed, so the Context Horizon cannot surface one. Items keep being
 * created by the existing Horizon operations; [linkItem] only adds the QUOTES edge to the chunk.
 * Nothing here computes a score or confidence, and nothing logs memory text: logs carry counts and ids only.
 */
/**
 * A channel's `last_edited` as epoch milliseconds, or null when it is absent or not a recognisable ISO date or time (then it is treated as not given).
 * Accepts an instant (`2026-11-07T10:00:00Z`), an offset time, a local date-time (read as UTC) and a plain date (the start of that day, UTC).
 */
object SourceEditTime {
    fun parse(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }
}

class SourceStore(private val db: Database) {

    private val logger = LoggerFactory.getLogger(SourceStore::class.java)

    fun ingest(userEmail: String, input: SourceInput): IngestResult {
        require(input.type in TYPES) { "unknown source type" }
        require(input.readMode in READ_MODES) { "readMode must be one of: ${READ_MODES.joinToString(", ")}" }
        require(input.locator.isNotBlank() && input.text.isNotBlank()) { "a source needs a locator and text" }
        require(input.email == null || input.type == EMAIL) { "email facts belong to an email_message source" }
        require((input.email?.recipients()?.size ?: 0) <= EmailGraph.MAX_RECIPIENTS) { "an email is stored with at most ${EmailGraph.MAX_RECIPIENTS} recipients" }
        val hash = input.fileSha256 ?: SourceChunker.contentHash(input.text)
        synchronized(LOCK) {
            if (input.type == EMAIL) {
                // A Message-ID is one Email whatever its text: the unique index is on (owner, type, locator), so the hash cannot make a second one. A row written
                // before the subtype existed (a plain Source of this type) is found by the hash path below.
                findEmail(userEmail, input.locator)?.let {
                    return IngestResult(it.toRecord(), created = false, chunksCreated = 0, contentChanged = (it.get("contentHash") as? String) != hash)
                }
            }
            findSource(userEmail, input.type, input.locator, hash)?.let { return IngestResult(it.toRecord(), created = false, chunksCreated = 0) }
            val user = HorizonOwnership.findUserVertex(db, userEmail) ?: throw IllegalArgumentException("unknown user")
            val drafts = input.chunks ?: if (input.type == EMAIL) SourceChunker.chunkEmail(input.text, "message") else SourceChunker.chunkDocument(input.text)
            require(drafts.isNotEmpty()) { "nothing to chunk" }
            var sourceUid = ""
            db.transaction {
                val source = db.newVertex(if (input.type == EMAIL) EmailGraph.EMAIL else "Source").apply {
                    sourceUid = UUID.randomUUID().toString()
                    set("uid", sourceUid)
                    set("name", input.title.ifBlank { input.locator })
                    set("type", input.type)
                    set("metadata", "{}")
                    set("locator", input.locator)
                    set("contentHash", hash)
                    set("readAt", input.readAt)
                    set("readMode", input.readMode)
                    set("ownerEmail", userEmail)
                    input.threadId?.let { set("threadId", it) }
                    input.email?.let { f ->
                        f.subject?.takeIf { it.isNotBlank() }?.let { set("subject", it.take(1000)) }
                        f.sentAt?.let { set("sentAt", it) }
                        f.gmailMessageId?.takeIf { it.isNotBlank() }?.let { set("gmailMessageId", it.take(200)) }
                        (f.gmailThreadId ?: input.threadId)?.takeIf { it.isNotBlank() }?.let { set("gmailThreadId", it.take(200)) }
                    }
                    input.suppliedBy?.let { set("suppliedBy", it); set("readByDirector", false) }
                    input.url?.let { set("url", it) }
                    input.lastEditedAt?.let { set("lastEditedAt", it) }
                    input.fileSha256?.let { set("fileSha256", it) }
                    input.pageCount?.let { set("pageCount", it) }
                    input.ocrPages?.let { set("ocrPages", it) }
                    input.unreadPages?.let { set("unreadPages", it) }
                    input.drive?.let { f ->
                        set("driveFileId", f.fileId)
                        f.fileName?.let { set("driveFileName", it) }; f.webViewLink?.let { set("driveWebViewLink", it) }
                        f.version?.let { set("driveVersion", it) }; f.md5?.let { set("driveMd5", it) }
                        f.modifiedTime?.let { set("driveModifiedTime", it) }; f.driveId?.let { set("driveId", it) }
                        f.headRevisionId?.let { set("driveHeadRevisionId", it) }; f.folderId?.let { set("driveFolderId", it) }
                    }
                    save()
                }
                user.modify().newEdge("TRUSTS", source, false).apply { set("scores", "[]"); save() }
                val now = System.currentTimeMillis()
                drafts.forEachIndexed { index, draft ->
                    val chunk = db.newVertex("Chunk").apply {
                        set("uid", UUID.randomUUID().toString())
                        set("ownerEmail", userEmail)
                        set("sourceUid", sourceUid)
                        set("ordinal", index)
                        set("location", draft.location)
                        set("text", draft.text)
                        set("hash", SourceChunker.contentHash(draft.text))
                        set("createdAt", now)
                        draft.page?.let { set("page", it) }
                        draft.printedPage?.let { set("printedPage", it) }
                        draft.section?.let { set("section", it) }
                        draft.kind?.let { set("kind", it) }
                        draft.extractor?.let { set("extractor", it) }
                        draft.ocrConfidence?.let { set("ocrConfidence", it) }
                        draft.box?.let { set("box", it) }
                        draft.table?.let { set("table", it) }
                        save()
                    }
                    source.newEdge("CONTAINS", chunk, false).apply { set("position", index); save() }
                }
                input.email?.let { writeAddresses(userEmail, source, it, now) }
            }
            logger.info("source: stored a {} with {} chunk(s)", input.type, drafts.size)
            return IngestResult(requireNotNull(sourceByUid(userEmail, sourceUid)), created = true, chunksCreated = drafts.size)
        }
    }

    /**
     * The Email's sender and recipients as nodes and edges, in the transaction that stores the message. One Email Address node per owner and address, found
     * or made; one SENT_BY edge for the sender; one SENT_TO edge per recipient and kind (the same address in two kinds gets two edges, a repeat within a kind
     * one). An address that is not `local@domain` is not recorded (the header said something that is not an address; the message is kept). Nothing here
     * says whose an address is.
     */
    private fun writeAddresses(userEmail: String, email: com.arcadedb.graph.MutableVertex, facts: EmailGraph.Facts, now: Long) {
        val nodes = HashMap<String, Vertex>()
        var skipped = 0
        fun node(address: String): Vertex = nodes.getOrPut(address) {
            db.query("sql", "SELECT FROM ${EmailGraph.ADDRESS} WHERE ownerEmail = :o AND address = :a", mapOf("o" to userEmail, "a" to address)).use { rs ->
                if (rs.hasNext()) rs.next().toElement().asVertex()
                else db.newVertex(EmailGraph.ADDRESS).apply {
                    set("uid", UUID.randomUUID().toString()); set("ownerEmail", userEmail); set("address", address)
                    set("domain", EmailGraph.domainOf(address)); set("createdAt", now); save()
                }
            }
        }
        fun edge(type: String, party: EmailGraph.Party, kind: String?) {
            val address = EmailGraph.normalize(party.address) ?: run { skipped++; return }
            email.newEdge(type, node(address), true).apply {
                set("ownerEmail", userEmail); set("createdAt", now)
                party.name?.trim()?.takeIf { it.isNotEmpty() }?.let { set("displayName", it.take(200)) }
                kind?.let { set("kind", it) }
                save()
            }
        }
        facts.from?.let { edge(EmailGraph.SENT_BY, it, null) }
        val done = HashSet<Pair<String, String>>()
        facts.recipients().forEach { (kind, party) ->
            val address = EmailGraph.normalize(party.address)
            if (address != null && !done.add(kind to address)) return@forEach
            edge(EmailGraph.SENT_TO, party, kind)
        }
        if (skipped > 0) logger.info("source: {} address(es) in an email's headers were not addresses and were not recorded", skipped)
    }

    /** The Email for [locator] (its Message-ID), found by the unique (owner, type, locator) index; null when there is none. */
    private fun findEmail(userEmail: String, locator: String): Vertex? =
        db.query("sql", "SELECT FROM ${EmailGraph.EMAIL} WHERE ownerEmail = :o AND type = :t AND locator = :l", mapOf("o" to userEmail, "t" to EMAIL, "l" to locator))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    /** What the graph holds for an Email: its facts and the addresses on its edges. Null when [sourceUid] is not an Email of this user. */
    fun emailView(userEmail: String, sourceUid: String): EmailView? {
        val v = db.query("sql", "SELECT FROM ${EmailGraph.EMAIL} WHERE uid = :u AND ownerEmail = :o", mapOf("u" to sourceUid, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null } ?: return null
        val by = v.getEdges(Vertex.DIRECTION.OUT, EmailGraph.SENT_BY).firstOrNull()
        val sentTo = v.getEdges(Vertex.DIRECTION.OUT, EmailGraph.SENT_TO).map { (it.get("kind") as? String ?: "to") to (it.inVertex.get("address") as? String ?: "") }
        fun of(kind: String) = sentTo.filter { it.first == kind }.map { it.second }.sorted()
        return EmailView(
            v.get("subject") as? String, (v.get("sentAt") as? Number)?.toLong(), v.get("gmailMessageId") as? String, v.get("gmailThreadId") as? String,
            by?.inVertex?.get("address") as? String, by?.get("displayName") as? String, of("to"), of("cc"), of("bcc"),
        )
    }

    /**
     * Adds one chunk to a Source already stored, after the chunks it was read with: a later reading of one of its pages (a vision reading).
     * The Source's identity and its existing chunks are untouched. Idempotent by [readingKey]: a chunk already added under that key is
     * returned and nothing is written. The new chunk takes the next ordinal. Null when the source is not this user's.
     */
    fun appendChunk(userEmail: String, sourceUid: String, draft: ChunkDraft, readingKey: String, reading: String?): Pair<ChunkRecord, Boolean>? = synchronized(LOCK) {
        db.query("sql", "SELECT FROM Chunk WHERE readingKey = :k AND ownerEmail = :o AND sourceUid = :s", mapOf("k" to readingKey, "o" to userEmail, "s" to sourceUid))
            .use { rs -> if (rs.hasNext()) return@synchronized rs.next().toElement().asVertex().toChunk() to false }
        val source = db.query("sql", "SELECT FROM Source WHERE uid = :u AND ownerEmail = :o", mapOf("u" to sourceUid, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null } ?: return@synchronized null
        val next = chunks(userEmail, sourceUid).maxOfOrNull { it.ordinal + 1 } ?: 0
        var uid = ""
        db.transaction {
            val chunk = db.newVertex("Chunk").apply {
                uid = UUID.randomUUID().toString()
                set("uid", uid); set("ownerEmail", userEmail); set("sourceUid", sourceUid); set("ordinal", next)
                set("location", draft.location); set("text", draft.text); set("hash", SourceChunker.contentHash(draft.text)); set("createdAt", System.currentTimeMillis())
                draft.page?.let { set("page", it) }; draft.printedPage?.let { set("printedPage", it) }; draft.section?.let { set("section", it) }
                draft.kind?.let { set("kind", it) }; draft.extractor?.let { set("extractor", it) }; draft.box?.let { set("box", it) }
                set("readingKey", readingKey); reading?.let { set("reading", it) }
                save()
            }
            source.modify().newEdge("CONTAINS", chunk, false).apply { set("position", next); save() }
        }
        logger.info("source: added a {} chunk at ordinal {}", draft.kind ?: "chunk", next)
        requireNotNull(chunk(userEmail, uid)) to true
    }

    fun sources(userEmail: String): List<SourceRecord> =
        db.query("sql", "SELECT FROM Source WHERE ownerEmail = :o AND contentHash IS NOT NULL ORDER BY readAt", mapOf("o" to userEmail))
            .use { rs -> val out = ArrayList<SourceRecord>(); while (rs.hasNext()) out += rs.next().toElement().asVertex().toRecord(); out }

    /** The Source for [locator] that is not superseded (the newest text a channel supplied), or null. */
    fun currentByLocator(userEmail: String, type: String, locator: String): SourceRecord? =
        db.query("sql", "SELECT FROM Source WHERE ownerEmail = :o AND type = :t AND locator = :l AND contentHash IS NOT NULL ORDER BY readAt DESC", mapOf("o" to userEmail, "t" to type, "l" to locator))
            .use { rs ->
                var found: SourceRecord? = null
                while (found == null && rs.hasNext()) rs.next().toElement().asVertex().takeIf { it.get("supersededBy") == null }?.let { found = it.toRecord() }
                found
            }

    /**
     * Makes [uid] the current text for its locator: every other Source of that owner, type and locator is marked `supersededBy = uid`, and [uid]
     * itself is cleared (a document returned to earlier text becomes current again). Nothing is deleted. Returns how many Sources changed, so a
     * re-send of unchanged text, which finds the current Source already current, writes nothing and returns 0.
     */
    fun markCurrent(userEmail: String, uid: String): Int = synchronized(LOCK) {
        val target = db.query("sql", "SELECT FROM Source WHERE uid = :u AND ownerEmail = :o", mapOf("u" to uid, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null } ?: return 0
        val others = db.query(
            "sql", "SELECT FROM Source WHERE ownerEmail = :o AND type = :t AND locator = :l AND uid <> :u AND contentHash IS NOT NULL",
            mapOf("o" to userEmail, "t" to (target.get("type") as? String ?: ""), "l" to (target.get("locator") as? String ?: ""), "u" to uid),
        ).use { rs -> val out = ArrayList<Vertex>(); while (rs.hasNext()) out += rs.next().toElement().asVertex(); out }
        var changed = 0
        db.transaction {
            if (target.get("supersededBy") != null) { target.modify().set("supersededBy", null).save(); changed++ }
            others.filter { it.get("supersededBy") != uid }.forEach { it.modify().set("supersededBy", uid).save(); changed++ }
        }
        changed
    }

    /** [sourceUid] and every other stored version of the same document (same owner, type and locator). Just [sourceUid] for one that is not found. */
    fun versionsOf(userEmail: String, sourceUid: String): Set<String> {
        val s = sourceByUid(userEmail, sourceUid) ?: return setOf(sourceUid)
        return db.query(
            "sql", "SELECT uid FROM Source WHERE ownerEmail = :o AND type = :t AND locator = :l", mapOf("o" to userEmail, "t" to s.type, "l" to s.locator),
        ).use { rs -> val out = HashSet<String>(); while (rs.hasNext()) rs.next().getProperty<String>("uid")?.let(out::add); out + sourceUid }
    }

    /** Every item that quotes a chunk of one of [sourceUids], with the passage it quoted (null when none was kept). Items repeat once per chunk link. */
    fun itemsQuotedFrom(userEmail: String, sourceUids: Set<String>): List<Pair<String, String?>> {
        if (sourceUids.isEmpty()) return emptyList()
        return db.query(
            "sql", "SELECT @out.uid AS i, draftQuote AS q FROM QUOTES WHERE @in.sourceUid IN :s AND ownerEmail = :o", mapOf("s" to sourceUids.toList(), "o" to userEmail),
        ).use { rs ->
            val out = ArrayList<Pair<String, String?>>()
            while (rs.hasNext()) { val m = rs.next().toMap(); (m["i"] as? String)?.let { out += it to (m["q"] as? String) } }
            out
        }
    }

    /** The Sources whose chunks [itemUid] quotes: where the item was found. Empty for an item that came from no source. */
    fun sourceUidsOfItem(userEmail: String, itemUid: String): Set<String> =
        db.query("sql", "SELECT @in.sourceUid AS s FROM QUOTES WHERE @out.uid = :u AND ownerEmail = :o", mapOf("u" to itemUid, "o" to userEmail))
            .use { rs -> val out = HashSet<String>(); while (rs.hasNext()) (rs.next().toMap()["s"] as? String)?.let(out::add); out }

    /** Records who issued a Source. False when the owner has no such Source. A blank issuer clears it. */
    fun setIssuer(userEmail: String, uid: String, issuer: String?): Boolean {
        var done = false
        db.transaction {
            val v = db.query("sql", "SELECT FROM Source WHERE uid = :u AND ownerEmail = :o", mapOf("u" to uid, "o" to userEmail))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null } ?: return@transaction
            val clean = issuer?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
            v.modify().set("issuer", clean).save(); done = true
        }
        return done
    }

    fun sourceByUid(userEmail: String, uid: String): SourceRecord? =
        db.query("sql", "SELECT FROM Source WHERE uid = :u AND ownerEmail = :o", mapOf("u" to uid, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().toRecord() else null }

    /** The chunks of one source, in reading order. Empty for a source this user does not own. */
    fun chunks(userEmail: String, sourceUid: String): List<ChunkRecord> =
        db.query("sql", "SELECT FROM Chunk WHERE sourceUid = :s AND ownerEmail = :o ORDER BY ordinal", mapOf("s" to sourceUid, "o" to userEmail))
            .use { rs -> val out = ArrayList<ChunkRecord>(); while (rs.hasNext()) out += rs.next().toElement().asVertex().toChunk(); out }

    /** What a channel said about when the source version was last edited, in epoch milliseconds, or null when it said nothing usable. */
    fun lastEditedMillis(userEmail: String, sourceUid: String): Long? =
        db.query("sql", "SELECT lastEditedAt FROM Source WHERE uid = :u AND ownerEmail = :o", mapOf("u" to sourceUid, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) SourceEditTime.parse(rs.next().getProperty<String>("lastEditedAt")) else null }

    fun chunk(userEmail: String, chunkUid: String): ChunkRecord? =
        db.query("sql", "SELECT FROM Chunk WHERE uid = :u AND ownerEmail = :o", mapOf("u" to chunkUid, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().toChunk() else null }

    /**
     * Ties [itemUid] (an item this user owns) to [chunkUid] (a chunk this user owns) with `QUOTES`. Refused when either is
     * not the user's. [writer] is who recorded the item; [claimant], when given, is who asserted the claim, kept apart from it.
     *
     * **One link per operation key.** Several drafts can lead to the same item from the same chunk (two wordings of one task,
     * both matching the task already on the list); each draft's key has its own edge, so each is found again on a replay. A
     * second call with the same key writes nothing. An older edge for the pair that carries no key is adopted by the first key
     * that arrives. Without a key, one link per pair.
     */
    fun linkItem(
        userEmail: String, itemUid: String, chunkUid: String, writer: String, claimant: String?, opKey: String? = null,
        draftQuote: String? = null, draftDueAt: Long? = null, runId: String? = null,
    ): LinkOutcome = synchronized(LOCK) {
        val item = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, itemUid) ?: return LinkOutcome.REFUSED
        val chunk = chunkVertex(userEmail, chunkUid) ?: return LinkOutcome.REFUSED
        val key = opKey?.let { "$userEmail|$it" }
        val pair = item.getEdges(Vertex.DIRECTION.OUT, "QUOTES").filter { it.inVertex.identity == chunk.identity }
        if (key == null) {
            if (pair.isNotEmpty()) return LinkOutcome.ALREADY_LINKED
        } else {
            if (pair.any { it.get("opKey") == key }) return LinkOutcome.ALREADY_LINKED
            pair.firstOrNull { it.get("opKey") == null }?.let { keyless ->
                db.transaction { keyless.modify().set("opKey", key).save() }
                return LinkOutcome.ALREADY_LINKED
            }
        }
        db.transaction {
            item.modify().newEdge("QUOTES", chunk, false).apply {
                set("writer", writer)
                claimant?.takeIf { it.isNotBlank() }?.let { set("claimant", it) }
                set("ownerEmail", userEmail)
                set("createdAt", System.currentTimeMillis())
                key?.let { set("opKey", it) }
                draftQuote?.let { set("draftQuote", it) }
                draftDueAt?.let { set("draftDueAt", it) }
                runId?.let { set("runId", it) }
                save()
            }
        }
        LinkOutcome.CREATED
    }

    /**
     * The item a source operation [opKey] was applied to, or null if none was. The key is on the QUOTES edge, so this finds the
     * item whether the operation created it or matched one already on the list, and whatever has happened to that item since
     * (resolved, or pushed out of the recent-items window the duplicate check scans).
     */
    fun itemForOpKey(userEmail: String, opKey: String): String? =
        db.query("sql", "SELECT @out.uid AS uid FROM QUOTES WHERE opKey = :k AND ownerEmail = :o LIMIT 1", mapOf("k" to "$userEmail|$opKey", "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toMap()["uid"] as? String else null }

    /** What the draft that made the link under [opKey] said (null where there is no such link). */
    fun draftOfOpKey(userEmail: String, opKey: String): LinkedDraft? =
        db.query("sql", "SELECT claimant, draftQuote, draftDueAt FROM QUOTES WHERE opKey = :k AND ownerEmail = :o LIMIT 1", mapOf("k" to "$userEmail|$opKey", "o" to userEmail))
            .use { rs ->
                if (!rs.hasNext()) null else rs.next().toMap().let { m ->
                    LinkedDraft(m["claimant"] as? String, m["draftQuote"] as? String, (m["draftDueAt"] as? Number)?.toLong())
                }
            }

    /** Every chunk [itemUid] quotes, with its location, its source and the writer and claimant. Empty for an item this user does not own. */
    fun provenanceOf(userEmail: String, itemUid: String): List<ItemProvenance> {
        val item = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, itemUid) ?: return emptyList()
        // An item can have several links to one chunk (one per draft that led to it); it is shown once.
        return item.getEdges(Vertex.DIRECTION.OUT, "QUOTES").mapNotNull { edge ->
            val chunk = edge.inVertex.asVertex()
            val source = sourceByUid(userEmail, chunk.get("sourceUid") as? String ?: return@mapNotNull null) ?: return@mapNotNull null
            ItemProvenance(
                chunkUid = chunk.get("uid") as String, location = chunk.get("location") as? String ?: "", sourceUid = source.uid,
                sourceType = source.type, sourceLocator = source.locator, sourceTitle = source.title, readMode = source.readMode,
                writer = edge.get("writer") as? String, claimant = edge.get("claimant") as? String,
            )
        }.distinct()
    }

    private fun chunkVertex(userEmail: String, uid: String): Vertex? =
        db.query("sql", "SELECT FROM Chunk WHERE uid = :u AND ownerEmail = :o", mapOf("u" to uid, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    private fun findSource(userEmail: String, type: String, locator: String, hash: String): Vertex? =
        db.query(
            "sql", "SELECT FROM Source WHERE contentHash = :h AND ownerEmail = :o AND type = :t AND locator = :l",
            mapOf("h" to hash, "o" to userEmail, "t" to type, "l" to locator),
        ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    private fun Vertex.toRecord() = SourceRecord(
        uid = get("uid") as String, type = get("type") as? String ?: "", locator = get("locator") as? String ?: "",
        title = get("name") as? String ?: "", readMode = get("readMode") as? String ?: "full", threadId = get("threadId") as? String,
        contentHash = get("contentHash") as? String ?: "", readAt = (get("readAt") as? Number)?.toLong() ?: 0L,
        chunkCount = countEdges(Vertex.DIRECTION.OUT, "CONTAINS").toInt(),
        fileSha256 = get("fileSha256") as? String, pageCount = (get("pageCount") as? Number)?.toInt(),
        ocrPages = (get("ocrPages") as? Number)?.toInt(), unreadPages = (get("unreadPages") as? Number)?.toInt(),
        issuer = get("issuer") as? String,
        suppliedBy = get("suppliedBy") as? String, readByDirector = get("readByDirector") as? Boolean,
        url = get("url") as? String, lastEditedAt = get("lastEditedAt") as? String, supersededBy = get("supersededBy") as? String,
        drive = (get("driveFileId") as? String)?.let { id ->
            DriveFacts(
                fileId = id, fileName = get("driveFileName") as? String, webViewLink = get("driveWebViewLink") as? String,
                version = get("driveVersion") as? String, md5 = get("driveMd5") as? String, modifiedTime = get("driveModifiedTime") as? String,
                driveId = get("driveId") as? String, headRevisionId = get("driveHeadRevisionId") as? String, folderId = get("driveFolderId") as? String,
            )
        },
    )

    private fun Vertex.toChunk() = ChunkRecord(
        uid = get("uid") as String, sourceUid = get("sourceUid") as? String ?: "", ordinal = (get("ordinal") as? Number)?.toInt() ?: 0,
        location = get("location") as? String ?: "", text = get("text") as? String ?: "", hash = get("hash") as? String ?: "",
        page = (get("page") as? Number)?.toInt(), printedPage = get("printedPage") as? String, section = get("section") as? String,
        kind = get("kind") as? String, extractor = get("extractor") as? String, ocrConfidence = (get("ocrConfidence") as? Number)?.toDouble(),
        box = get("box") as? String, table = get("table") as? String,
        readingKey = get("readingKey") as? String, reading = get("reading") as? String,
    )

    companion object {
        const val EMAIL = "email_message"
        val TYPES = setOf("notion_page", "document", EMAIL)
        val READ_MODES = listOf("full", "partial", "ocr")
        /** One lock for the process: a find-then-create must not interleave with another. */
        private val LOCK = Any()
    }
}
