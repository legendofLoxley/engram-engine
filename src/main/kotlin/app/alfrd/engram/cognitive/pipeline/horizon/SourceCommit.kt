package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.IngestResult
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceStore
import kotlinx.serialization.Serializable

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
 * [HorizonOperationApplier]), each tied to its chunk. Items are keyed by the source's content hash and their position, so
 * committing the same content again finds every item by its key and adds nothing: no Source, no chunk, no item, no edge.
 */
class SourceCommitService(
    private val store: SourceStore,
    private val structure: HorizonStructureStore,
    private val applier: HorizonOperationApplier,
    private val cycles: CycleSequencer,
) {
    suspend fun commit(userEmail: String, input: SourceInput, items: List<SourceItemDraft>, writer: String = "director"): CommitReport {
        val ingest: IngestResult = store.ingest(userEmail, input)
        val chunks = store.chunks(userEmail, ingest.source.uid)
        val requestId = "src:${ingest.source.contentHash.take(16)}"

        val ops = items.map { d ->
            val chunk = chunks.firstOrNull { it.ordinal == d.chunk }
            RecordItem(kind = d.kind, quote = d.quote, title = d.text, dueAt = d.dueAt, chunkUid = chunk?.uid ?: "no-such-chunk", claimant = d.claimant, writer = writer)
        }
        // A cycle number is only needed to stamp a new item; a repeat commit finds every item by its key and needs none.
        val fresh = ops.indices.count { structure.itemByOpKey(userEmail, "$requestId:$it") == null }
        val cycleSeq = if (fresh > 0) cycles.allocateCycle(userEmail) ?: 0L else 0L
        val before = ops.indices.map { structure.itemByOpKey(userEmail, "$requestId:$it") != null }

        val applied = applier.apply(userEmail, cycleSeq, requestId, ops)
        val out = applied.mapIndexed { i, a ->
            val uid = a.resultUid ?: a.existingUid
            CommittedItem(
                kind = items[i].kind.name.lowercase(), uid = uid, created = a.applied && !before[i] && a.resultUid != null,
                linked = uid != null && store.provenanceOf(userEmail, uid).any { it.chunkUid == ops[i].chunkUid }, applied = a.applied,
            )
        }
        return CommitReport(
            sourceUid = ingest.source.uid, sourceCreated = ingest.created, chunksTotal = chunks.size, chunksCreated = ingest.chunksCreated,
            items = out, itemsCreated = out.count { it.created }, itemsAlreadyThere = out.count { it.applied && !it.created },
        )
    }
}
