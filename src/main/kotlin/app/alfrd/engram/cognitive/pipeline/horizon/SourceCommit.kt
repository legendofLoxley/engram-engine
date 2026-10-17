package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.IngestResult
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** One item the Director decided to keep from a source: what it is, a short label, the passage it rests on, and which chunk that passage is in. */
data class SourceItemDraft(
    val kind: ItemKind,
    /** A short label for the item (the item's text). */
    val text: String,
    /** A verbatim passage of the chunk. Checked in code before anything is written. */
    val quote: String,
    /** The chunk's ordinal within the source (0-based). */
    val chunk: Int,
    /** Who asserted the claim, when it is not the writer. */
    val claimant: String? = null,
    val dueAt: Long? = null,
)

/** A commit that could not be carried out, with nothing applied. The message never carries memory text. */
class CommitFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Serializable
data class CommittedItem(val kind: String, val uid: String?, val created: Boolean, val linked: Boolean, val applied: Boolean)

@Serializable
data class CommitReport(
    val sourceUid: String,
    val sourceCreated: Boolean,
    val chunksTotal: Int,
    val chunksCreated: Int,
    val items: List<CommittedItem>,
    val itemsCreated: Int,
    val itemsAlreadyThere: Int,
)

/**
 * Commits a read source and the items the Director took from it: the Source and its chunks through [SourceStore] (idempotent
 * by content hash), then the items through the existing closed Horizon operations ([RecordItem] applied by
 * [HorizonOperationApplier]), each tied to its chunk.
 *
 * **Keys come from content.** An item's operation key is the source's content hash plus a digest of the item's kind, its
 * normalized text and its chunk: not its position in the list. The same drafts in another order are the same items, a changed
 * draft is a new item, and the key is recorded on the item's chunk link, so a replay finds each item by its key (also one that
 * matched an item already on the list when it was first applied) and adds nothing: no source, chunk, item, edge or cycle number.
 *
 * **Same discipline as the other cycle writers.** The whole commit runs under [PerUserCycleLock] for the user, and a cycle number
 * is allocated only when an item is new. If it cannot be allocated (lock timeout, database failure) the commit fails with
 * [CommitFailedException] and applies no item; there is no fallback cycle number. The Source and its chunks, being idempotent
 * and not items, stay stored, so a retry picks up where this stopped.
 */
class SourceCommitService(
    private val store: SourceStore,
    private val structure: HorizonStructureStore,
    private val applier: HorizonOperationApplier,
    private val cycles: CycleSequencer,
) {
    suspend fun commit(userEmail: String, input: SourceInput, items: List<SourceItemDraft>, writer: String = "director"): CommitReport =
        PerUserCycleLock.withLock(userEmail) {
            val ingest: IngestResult = store.ingest(userEmail, input)
            val chunks = store.chunks(userEmail, ingest.source.uid)
            val requestId = "src:${ingest.source.contentHash.take(16)}"

            val ops = items.map { d ->
                val chunk = chunks.firstOrNull { it.ordinal == d.chunk }
                RecordItem(
                    kind = d.kind, quote = d.quote, title = d.text, dueAt = d.dueAt, chunkUid = chunk?.uid ?: "no-such-chunk",
                    claimant = d.claimant, writer = writer, itemKey = "$requestId:${itemDigest(d)}",
                )
            }
            val before = ops.map { store.itemForOpKey(userEmail, it.itemKey!!) != null }

            // A cycle number is only needed to stamp a new item; a replay finds every item by its key and needs none.
            val cycleSeq = if (before.any { !it }) {
                try {
                    cycles.allocateCycle(userEmail)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CommitFailedException("could not allocate a cycle for this commit; nothing was recorded", e)
                } ?: throw CommitFailedException("could not allocate a cycle for this commit; nothing was recorded")
            } else 0L

            val applied = applier.apply(userEmail, cycleSeq, requestId, ops)
            val out = applied.mapIndexed { i, a ->
                val uid = a.resultUid ?: a.existingUid
                CommittedItem(
                    kind = items[i].kind.name.lowercase(), uid = uid, created = a.applied && !before[i] && a.resultUid != null,
                    linked = uid != null && store.provenanceOf(userEmail, uid).any { it.chunkUid == ops[i].chunkUid }, applied = a.applied,
                )
            }
            CommitReport(
                sourceUid = ingest.source.uid, sourceCreated = ingest.created, chunksTotal = chunks.size, chunksCreated = ingest.chunksCreated,
                items = out, itemsCreated = out.count { it.created }, itemsAlreadyThere = out.count { it.applied && !it.created },
            )
        }

    /** A digest of what the item is: its kind, its text with case and spacing normalized, and the chunk it sits in. */
    private fun itemDigest(d: SourceItemDraft): String {
        val text = d.text.lowercase().replace(Regex("\\s+"), " ").trim()
        return MessageDigest.getInstance("SHA-256").digest("${d.kind.name}|$text|${d.chunk}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
    }
}
