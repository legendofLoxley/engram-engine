package app.alfrd.engram.cognitive.pipeline.horizon

import org.slf4j.LoggerFactory
import java.time.ZoneId

/**
 * Builds the [ViewSnapshot] the interpretation model is shown: the current focus, the user's live
 * containers, and a bounded handful of items — those in the pinned focus, those still open, and the
 * most recent — each under a short ref. A stand-in for the selector that will decide what is in
 * view; it exists so the model can refer to existing things by ref rather than by name or text.
 */
class ViewSnapshotBuilder(
    private val structure: HorizonStructureStore,
    private val maxContainers: Int = 15,
    private val maxItems: Int = 14,
) {

    suspend fun build(
        userEmail: String,
        zone: ZoneId,
        pendingFilingItemUid: String? = null,
        pendingEmotionQuote: String? = null,
    ): ViewSnapshot {
        val live = structure.listContainers(userEmail).filter { it.status == "active" }
        // Domains first, then projects, each oldest first — so refs stay stable as more are added — keeping the newest if capped.
        val ordered = (live.filter { it.kind == ContainerKind.DOMAIN } + live.filter { it.kind == ContainerKind.PROJECT }).takeLast(maxContainers)
        val refByContainerUid = ordered.withIndex().associate { (i, c) -> c.uid to "c${i + 1}" }
        val containers = ordered.map { c ->
            SnapshotContainer(
                ref = refByContainerUid.getValue(c.uid),
                uid = c.uid,
                kind = c.kind,
                name = c.name,
                parentRef = c.parentUid?.let { refByContainerUid[it] },
                attention = c.attention,
            )
        }

        val focus = structure.getFocus(userEmail).filter { it.mode == FocusMode.PINNED }
        val candidates = LinkedHashMap<String, ItemRecord>()
        fun add(item: ItemRecord?) { if (item != null && item.uid !in candidates && candidates.size < maxItems) candidates[item.uid] = item }

        // Everything the pipeline needs the model to be able to name goes first, so the cap never squeezes it out.
        pendingFilingItemUid?.let { add(structure.getItem(userEmail, it)) }
        focus.filter { it.targetKind == FocusTargetKind.ITEM }.forEach { add(structure.getItem(userEmail, it.targetUid)) }
        focus.filter { it.targetKind == FocusTargetKind.PROJECT }.forEach { f ->
            structure.itemsIn(userEmail, f.targetUid).takeLast(FOCUS_ITEM_CAP).forEach { add(it) }
        }
        structure.listRecentItems(userEmail, OPEN_ITEM_CAP, openOnly = true).forEach { add(it) }
        structure.listRecentItems(userEmail, RECENT_ITEM_CAP).forEach { add(it) }

        val items = candidates.values.withIndex().map { (i, it) ->
            SnapshotItem(
                ref = "i${i + 1}",
                uid = it.uid,
                kind = it.kind,
                text = it.text,
                status = it.status,
                dueAt = it.dueAt,
                projectRef = it.projectUid?.let { p -> refByContainerUid[p] },
                about = it.about,
                attention = it.attention,
            )
        }
        val refByItemUid = items.associate { it.uid to it.ref }

        val focusLines = focus.mapNotNull { f ->
            val ref = refByContainerUid[f.targetUid] ?: refByItemUid[f.targetUid] ?: return@mapNotNull null
            val label = containers.firstOrNull { it.ref == ref }?.let { "${it.name} (${it.kind.name.lowercase()})" } ?: "(item)"
            "pinned -> $ref $label"
        }
        return ViewSnapshot(
            focusLines = focusLines,
            containers = containers,
            items = items,
            pendingEmotionQuote = pendingEmotionQuote,
            pendingFilingRef = pendingFilingItemUid?.let { refByItemUid[it] },
            zone = zone,
        )
    }

    private companion object {
        const val FOCUS_ITEM_CAP = 8
        const val OPEN_ITEM_CAP = 8
        const val RECENT_ITEM_CAP = 6
    }
}

/** What happened to one operation. [applied] false means it was not written and must never be described to the user as done. */
data class AppliedOperation(
    val operation: HorizonOperation,
    val applied: Boolean,
    /** A short, factual description of the change — fed to the reply so it can say plainly what was done. */
    val summary: String,
    /** The Phrase this operation created (a new or corrected item), if any — what propagation is run over. */
    val resultUid: String? = null,
    val resultText: String? = null,
)

/**
 * The only thing that turns a validated [HorizonOperation] into a graph write, via
 * [HorizonStructureStore] (structure, focus, status) and [HorizonGraphStore] (supersession and
 * relevance links). Applies operations in order — a project created early in a response is visible
 * to the items filed under it later — and stops nothing on a failure: each operation succeeds or
 * fails on its own and says so, so one bad write never hides the ones that landed.
 *
 * Every write is idempotent under retry (see [HorizonStructureStore]): a created item is keyed by
 * `requestId:index`, and re-running a stored plan after a crash converges on the same graph rather
 * than duplicating anything. That is what lets [app.alfrd.engram.cognitive.pipeline.HorizonCycleCoordinator]
 * checkpoint the plan and replay it without calling the model a second time.
 */
class HorizonOperationApplier(
    private val structure: HorizonStructureStore,
    private val graph: HorizonGraphStore,
) {
    private val logger = LoggerFactory.getLogger(HorizonOperationApplier::class.java)

    suspend fun apply(userEmail: String, cycleSeq: Long, requestId: String, operations: List<HorizonOperation>): List<AppliedOperation> {
        val createdContainerUids = mutableMapOf<String, String>()
        return operations.mapIndexed { index, op ->
            try {
                applyOne(userEmail, cycleSeq, "$requestId:$index", op, createdContainerUids)
            } catch (e: Exception) {
                logger.warn("apply ${op.verb} failed for userEmail=$userEmail: ${e.message}")
                AppliedOperation(op, applied = false, summary = "Could not apply ${op.verb}")
            }
        }
    }

    private suspend fun applyOne(
        userEmail: String,
        cycleSeq: Long,
        opKey: String,
        op: HorizonOperation,
        createdContainerUids: MutableMap<String, String>,
    ): AppliedOperation = when (op) {
        is CreateContainer -> {
            val record = structure.createContainer(userEmail, cycleSeq, op.kind, op.name, op.parentUid, tentative = !op.namedByUser)
            record?.let { createdContainerUids[OperationParser.normalizeName(op.name)] = it.uid }
            AppliedOperation(op, record != null, if (record != null) "Created ${op.kind.name.lowercase()} \"${record.name}\"" else "Could not create ${op.kind.name.lowercase()} \"${op.name}\"")
        }
        is RecordItem -> {
            val projectUid = op.projectUid
                ?: op.projectName?.let { createdContainerUids[OperationParser.normalizeName(it)] }
            val record = structure.createItem(userEmail, cycleSeq, op.kind, op.quote, op.dueAt, projectUid, askedBy = "user", opKey = opKey)
            if (record == null) {
                AppliedOperation(op, false, "Could not record ${op.kind.name.lowercase()} \"${op.quote}\"")
            } else {
                op.about.forEach { structure.linkAbout(userEmail, cycleSeq, record.uid, it) }
                AppliedOperation(op, true, "Recorded ${op.kind.name.lowercase()}: \"${record.text}\"", record.uid, record.text)
            }
        }
        is ResolveItem -> {
            val label = labelOf(userEmail, op.targetUid)
            val done = structure.resolve(userEmail, cycleSeq, op.targetUid, resolved = true)
            AppliedOperation(op, done, if (done) "Marked done: $label" else "Could not mark $label done")
        }
        is ReopenItem -> {
            val label = labelOf(userEmail, op.targetUid)
            val done = structure.resolve(userEmail, cycleSeq, op.targetUid, resolved = false)
            AppliedOperation(op, done, if (done) "Reopened: $label" else "Could not reopen $label")
        }
        is CorrectItem -> applyCorrection(userEmail, cycleSeq, opKey, op)
        is FileItem -> {
            val label = labelOf(userEmail, op.targetUid)
            val into = labelOf(userEmail, op.intoUid)
            val done = structure.file(userEmail, cycleSeq, op.targetUid, op.intoUid)
            AppliedOperation(op, done, if (done) "Filed $label under $into" else "Could not file $label under $into")
        }
        is SetFocus -> {
            val label = labelOf(userEmail, op.targetUid)
            val done = structure.setFocus(userEmail, cycleSeq, op.targetUid, FocusMode.PINNED)
            AppliedOperation(op, done, if (done) "Focus set on $label" else "Could not set focus on $label")
        }
        is ReleaseFocus -> {
            structure.releaseFocus(userEmail)
            AppliedOperation(op, true, "Focus cleared; back to the big picture")
        }
        is SetAttention -> {
            val label = labelOf(userEmail, op.targetUid)
            val done = structure.setAttention(userEmail, cycleSeq, op.targetUid, op.attention)
            val what = when (op.attention) {
                AttentionDirective.PINNED -> "Pinned $label"
                AttentionDirective.SUPPRESSED -> "Stopped bringing up $label"
                null -> "Restored normal attention for $label"
            }
            AppliedOperation(op, done, if (done) what else "Could not change attention for $label")
        }
        is LinkItems -> {
            val done = graph.markRelevant(userEmail, cycleSeq, op.fromUid, op.toUid)
            AppliedOperation(op, done, if (done) "Linked ${labelOf(userEmail, op.fromUid)} with ${labelOf(userEmail, op.toUid)}" else "Could not link those items")
        }
        is HoldForChoice -> AppliedOperation(op, true, "Held \"${op.quote}\" without saving it, pending the user's choice")
    }

    /**
     * A correction never overwrites: the corrected statement becomes a new item of the same kind,
     * filed where the old one was and tied to the same entities (with the new date if it changed),
     * linked as superseding it; the old one is closed so it stops surfacing. Nothing is lost.
     */
    private suspend fun applyCorrection(userEmail: String, cycleSeq: Long, opKey: String, op: CorrectItem): AppliedOperation {
        val old = structure.getItem(userEmail, op.targetUid)
            ?: return AppliedOperation(op, false, "Could not find the item to correct")
        val created = structure.createItem(userEmail, cycleSeq, old.kind, op.quote, op.dueAt ?: old.dueAt, old.projectUid, askedBy = "user", opKey = opKey)
            ?: return AppliedOperation(op, false, "Could not record the correction of \"${old.text}\"")
        old.about.forEach { structure.linkAbout(userEmail, cycleSeq, created.uid, it) }
        val linked = graph.markSuperseded(userEmail, cycleSeq, created.uid, old.uid)
        if (old.kind != ItemKind.FACT) structure.resolve(userEmail, cycleSeq, old.uid, resolved = true)
        return AppliedOperation(
            op,
            applied = linked,
            summary = if (linked) "Corrected \"${old.text}\" to \"${created.text}\"" else "Recorded the correction but could not link it to \"${old.text}\"",
            resultUid = created.uid,
            resultText = created.text,
        )
    }

    private suspend fun labelOf(userEmail: String, uid: String): String {
        structure.getContainer(userEmail, uid)?.let { return "\"${it.name}\"" }
        structure.getItem(userEmail, uid)?.let { return "\"${it.text}\"" }
        return "that item"
    }
}
