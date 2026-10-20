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
)

data class SourceRecord(
    val uid: String, val type: String, val locator: String, val title: String, val readMode: String,
    val threadId: String?, val contentHash: String, val readAt: Long, val chunkCount: Int,
    val fileSha256: String? = null, val pageCount: Int? = null, val ocrPages: Int? = null, val unreadPages: Int? = null,
    val drive: DriveFacts? = null,
)

data class ChunkRecord(
    val uid: String, val sourceUid: String, val ordinal: Int, val location: String, val text: String, val hash: String,
    val page: Int? = null, val printedPage: String? = null, val section: String? = null, val kind: String? = null,
    val extractor: String? = null, val ocrConfidence: Double? = null, val box: String? = null, val table: String? = null,
)

data class IngestResult(
    val source: SourceRecord,
    /** False when this exact content was already there: nothing was written. */
    val created: Boolean,
    val chunksCreated: Int,
)

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
class SourceStore(private val db: Database) {

    private val logger = LoggerFactory.getLogger(SourceStore::class.java)

    fun ingest(userEmail: String, input: SourceInput): IngestResult {
        require(input.type in TYPES) { "unknown source type" }
        require(input.readMode in READ_MODES) { "readMode must be one of: ${READ_MODES.joinToString(", ")}" }
        require(input.locator.isNotBlank() && input.text.isNotBlank()) { "a source needs a locator and text" }
        val hash = input.fileSha256 ?: SourceChunker.contentHash(input.text)
        synchronized(LOCK) {
            findSource(userEmail, input.type, input.locator, hash)?.let { return IngestResult(it.toRecord(), created = false, chunksCreated = 0) }
            val user = HorizonOwnership.findUserVertex(db, userEmail) ?: throw IllegalArgumentException("unknown user")
            val drafts = input.chunks ?: if (input.type == EMAIL) SourceChunker.chunkEmail(input.text, "message") else SourceChunker.chunkDocument(input.text)
            require(drafts.isNotEmpty()) { "nothing to chunk" }
            var sourceUid = ""
            db.transaction {
                val source = db.newVertex("Source").apply {
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
            }
            logger.info("source: stored a {} with {} chunk(s)", input.type, drafts.size)
            return IngestResult(requireNotNull(sourceByUid(userEmail, sourceUid)), created = true, chunksCreated = drafts.size)
        }
    }

    fun sources(userEmail: String): List<SourceRecord> =
        db.query("sql", "SELECT FROM Source WHERE ownerEmail = :o AND contentHash IS NOT NULL ORDER BY readAt", mapOf("o" to userEmail))
            .use { rs -> val out = ArrayList<SourceRecord>(); while (rs.hasNext()) out += rs.next().toElement().asVertex().toRecord(); out }

    fun sourceByUid(userEmail: String, uid: String): SourceRecord? =
        db.query("sql", "SELECT FROM Source WHERE uid = :u AND ownerEmail = :o", mapOf("u" to uid, "o" to userEmail))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex().toRecord() else null }

    /** The chunks of one source, in reading order. Empty for a source this user does not own. */
    fun chunks(userEmail: String, sourceUid: String): List<ChunkRecord> =
        db.query("sql", "SELECT FROM Chunk WHERE sourceUid = :s AND ownerEmail = :o ORDER BY ordinal", mapOf("s" to sourceUid, "o" to userEmail))
            .use { rs -> val out = ArrayList<ChunkRecord>(); while (rs.hasNext()) out += rs.next().toElement().asVertex().toChunk(); out }

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
    fun linkItem(userEmail: String, itemUid: String, chunkUid: String, writer: String, claimant: String?, opKey: String? = null): LinkOutcome = synchronized(LOCK) {
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
    )

    companion object {
        const val EMAIL = "email_message"
        val TYPES = setOf("notion_page", "document", EMAIL)
        val READ_MODES = listOf("full", "partial", "ocr")
        /** One lock for the process: a find-then-create must not interleave with another. */
        private val LOCK = Any()
    }
}
