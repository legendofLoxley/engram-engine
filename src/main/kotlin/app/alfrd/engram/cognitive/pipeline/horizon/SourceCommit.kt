package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.SourceChunker
import app.alfrd.engram.db.SourceInput
import app.alfrd.engram.db.SourceRecord
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
data class CommittedItem(val kind: String, val uid: String?, val created: Boolean, val linked: Boolean, val applied: Boolean, val healed: Boolean = false)

@Serializable
data class CommitReport(
    val sourceUid: String,
    val sourceCreated: Boolean,
    val chunksTotal: Int,
    val chunksCreated: Int,
    val items: List<CommittedItem>,
    val itemsCreated: Int,
    val itemsAlreadyThere: Int,
    val itemsHealed: Int = 0,
)

/**
 * Commits a read source and the items the Director took from it: the Source and its chunks through [SourceStore] (idempotent
 * by content hash), then the items through the existing closed Horizon operations ([RecordItem] applied by
 * [HorizonOperationApplier]), each tied to its chunk.
 *
 * **Keys come from content and from the source.** An item's operation key is the Source's own uid (which is its owner, type,
 * locator and content hash) plus a digest of the item's kind, its normalized text and its chunk: not its position in the list.
 * The same drafts in another order are the same items, a changed draft is a new item, and identical content read from a second
 * locator is a second Source whose items are tied to its own chunks. Each key is recorded on its own link from the item to the
 * chunk, so a replay finds every item by its key (also one that matched an item already on the list when it was first applied,
 * and whatever has happened to it since) and adds nothing: no source, chunk, item, edge or cycle number.
 *
 * **Drafts that are the same item.** Two drafts with the same kind, normalized text and chunk are one item. If they agree on
 * quote, claimant and date they collapse into one (the second is reported as already there); if they differ, the commit is
 * refused with [IllegalArgumentException] before anything is written, because one of them would otherwise be dropped silently.
 *
 * **Same discipline as the other cycle writers.** The whole commit runs under [PerUserCycleLock] for the user, and a cycle number
 * is allocated only when an item is new. If it cannot be allocated (lock timeout, database failure) the commit fails with
 * [CommitFailedException] and records no item; there is no fallback cycle number. The Source and its chunks, being idempotent
 * and not items, stay stored, so a retry picks up where this stopped. An item written by an earlier attempt whose link was lost
 * is linked now and reported as healed, not as created.
 */
class SourceCommitService(
    private val store: SourceStore,
    private val structure: HorizonStructureStore,
    private val applier: HorizonOperationApplier,
    private val cycles: CycleSequencer,
) {
    suspend fun commit(userEmail: String, input: SourceInput, items: List<SourceItemDraft>, writer: String = "director", runId: String? = null): CommitReport =
        commitWith(userEmail, items, writer, runId) { store.ingest(userEmail, input).let { Triple(it.source, it.created, it.chunksCreated) } }

    /**
     * Commits [items] to a Source that is already stored and owned by [userEmail], with the same checks, keys and replay behaviour
     * as [commit]. For items made later from chunks already read, such as a claim extraction. [runId] is kept on each item's link.
     */
    suspend fun commitStored(userEmail: String, sourceUid: String, items: List<SourceItemDraft>, writer: String = "director", runId: String? = null): CommitReport =
        commitWith(userEmail, items, writer, runId) {
            Triple(store.sourceByUid(userEmail, sourceUid) ?: throw IllegalArgumentException("unknown source"), false, 0)
        }

    private suspend fun commitWith(
        userEmail: String, items: List<SourceItemDraft>, writer: String, runId: String?, load: () -> Triple<SourceRecord, Boolean, Int>,
    ): CommitReport =
        PerUserCycleLock.withLock(userEmail) {
            // Refuse drafts that are one item but disagree, before anything is written.
            val digests = items.map { itemDigest(it) }
            val firstOf = digests.indices.groupBy { digests[it] }.mapValues { it.value.first() }
            digests.indices.forEach { i ->
                val first = firstOf.getValue(digests[i])
                require(i == first || sameDetails(items[first], items[i])) {
                    "two drafts describe the same item (same kind, text and chunk) but differ in quote, claimant or date; make them identical or word them differently"
                }
            }

            val (source, sourceCreated, chunksCreated) = load()
            val chunks = store.chunks(userEmail, source.uid)
            val requestId = "src:${source.uid}"

            val unique = digests.indices.filter { firstOf.getValue(digests[it]) == it }
            val ops = unique.map { i ->
                val d = items[i]
                RecordItem(
                    kind = d.kind, quote = d.quote, title = d.text, dueAt = d.dueAt,
                    chunkUid = chunks.firstOrNull { it.ordinal == d.chunk }?.uid ?: "no-such-chunk",
                    claimant = d.claimant, writer = writer, itemKey = "$requestId:${digests[i]}", runId = runId,
                )
            }

            // A replay must be the same item: what the earlier call recorded under this key (its quote, claimant and date) is compared
            // with this draft, and a difference is refused before anything is written. A link made before the quote was kept has
            // nothing to compare and is accepted as it always was.
            ops.forEachIndexed { k, op ->
                val before = store.draftOfOpKey(userEmail, op.itemKey!!)?.takeIf { it.quote != null } ?: return@forEachIndexed
                val d = items[unique[k]]
                val differs = buildList {
                    if (SourceChunker.normalize(before.quote!!) != SourceChunker.normalize(d.quote)) add("quote")
                    if ((before.claimant?.trim()?.takeIf { it.isNotEmpty() }) != (d.claimant?.trim()?.takeIf { it.isNotEmpty() })) add("claimant")
                    if (before.dueAt != d.dueAt) add("date")
                }
                require(differs.isEmpty()) {
                    "this item was already recorded from this source with a different ${differs.joinToString(" and ")}; " +
                        "a replay must repeat what was recorded (nothing was written)"
                }
            }
            val linkedBefore = ops.map { store.itemForOpKey(userEmail, it.itemKey!!) != null }
            val itemBefore = ops.map { structure.itemByOpKey(userEmail, it.itemKey!!) != null }

            // A cycle number is only needed to stamp a new item; a replay, or a healing, needs none.
            val cycleSeq = if (ops.indices.any { !linkedBefore[it] && !itemBefore[it] }) {
                try {
                    cycles.allocateCycle(userEmail)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CommitFailedException(ALLOCATION_FAILED, e)
                } ?: throw CommitFailedException(ALLOCATION_FAILED)
            } else 0L

            val applied = applier.apply(userEmail, cycleSeq, requestId, ops)
            val byDigest = HashMap<String, CommittedItem>()
            applied.forEachIndexed { k, a ->
                val i = unique[k]
                val uid = a.resultUid ?: a.existingUid
                byDigest[digests[i]] = CommittedItem(
                    kind = items[i].kind.name.lowercase(), uid = uid,
                    created = a.applied && !linkedBefore[k] && !itemBefore[k] && a.resultUid != null,
                    linked = uid != null && store.provenanceOf(userEmail, uid).any { it.chunkUid == ops[k].chunkUid },
                    applied = a.applied,
                    healed = a.applied && !linkedBefore[k] && itemBefore[k],
                )
            }
            val out = items.indices.map { i ->
                val r = byDigest.getValue(digests[i])
                if (firstOf.getValue(digests[i]) == i) r else r.copy(created = false, healed = false)
            }
            CommitReport(
                sourceUid = source.uid, sourceCreated = sourceCreated, chunksTotal = chunks.size, chunksCreated = chunksCreated,
                items = out, itemsCreated = out.count { it.created }, itemsAlreadyThere = out.count { it.applied && !it.created },
                itemsHealed = out.count { it.healed },
            )
        }

    private fun sameDetails(a: SourceItemDraft, b: SourceItemDraft): Boolean =
        SourceChunker.normalize(a.quote) == SourceChunker.normalize(b.quote) &&
            (a.claimant?.trim()?.takeIf { it.isNotEmpty() }) == (b.claimant?.trim()?.takeIf { it.isNotEmpty() }) && a.dueAt == b.dueAt

    /** A digest of what the item is: its kind, its text with case and spacing normalized, and the chunk it sits in. */
    private fun itemDigest(d: SourceItemDraft): String =
        MessageDigest.getInstance("SHA-256").digest("${d.kind.name}|${ItemWords.normalize(d.text)}|${d.chunk}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)

    private companion object {
        const val ALLOCATION_FAILED =
            "could not allocate a cycle for this commit; no item was recorded (the source and its chunks are stored, and a retry will continue from there)"
    }
}
