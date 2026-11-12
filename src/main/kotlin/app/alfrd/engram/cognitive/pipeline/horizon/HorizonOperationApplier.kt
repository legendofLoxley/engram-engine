package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.LinkOutcome
import app.alfrd.engram.db.SourceChunker
import app.alfrd.engram.cognitive.pipeline.memory.DatabaseEngramClient
import app.alfrd.engram.db.SourceStore
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
    private val maxItems: Int = 14,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun build(
        userEmail: String,
        zone: ZoneId,
        pendingFilingItemUid: String? = null,
        pendingEmotionQuote: String? = null,
        /** Items the Horizon is showing (an also-due line, a stale question, a focused project's items): each is given a ref, after the pools, so it can be named. */
        include: Collection<String> = emptyList(),
    ): ViewSnapshot {
        val live = structure.listContainers(userEmail).filter { it.status == "active" }
        // Every domain and project gets a ref, however many there are: the cap is on items only. Domains first, then projects, each oldest
        // first, so refs stay stable as more are added. Each is one short line in the render.
        val ordered = live.filter { it.kind == ContainerKind.DOMAIN } + live.filter { it.kind == ContainerKind.PROJECT }
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
        // A suppressed item or a resolved one never takes a slot an open item needs: both are left out of the focus and open pools below.
        // Which projects count as "in focus" for a waiting item: a focused project, or any project of a focused domain.
        val focusedProjects = HashSet<String>()
        for (f in focus) when (f.targetKind) {
            FocusTargetKind.PROJECT -> focusedProjects += f.targetUid
            FocusTargetKind.DOMAIN -> structure.projectsIn(userEmail, f.targetUid).forEach { focusedProjects += it.uid }
            FocusTargetKind.ITEM -> Unit
        }
        val now = clock()
        // A waiting item surfaces only inside a focused project or once its check-in date has arrived (see [StateRules]).
        fun surfaces(item: ItemRecord) = StateRules.visible(item.workState, item.nudgeAt, item.projectUid != null && item.projectUid in focusedProjects, now)
        fun live(item: ItemRecord) = item.attention != AttentionDirective.SUPPRESSED && item.workState?.closed != true
        fun add(item: ItemRecord?, limit: Int) { if (item != null && item.uid !in candidates && candidates.size < limit) candidates[item.uid] = item }

        // Everything the pipeline needs the model to be able to name goes first, so the cap never squeezes it out.
        val hardCap = FOCUS_OPEN_CAP + maxItems + 1 // the hidden section is added after this and has its own cap
        pendingFilingItemUid?.let { add(structure.getItem(userEmail, it), hardCap) }
        focus.filter { it.targetKind == FocusTargetKind.ITEM }.forEach { f -> structure.getItem(userEmail, f.targetUid)?.takeIf(::live)?.let { add(it, hardCap) } }
        // What is in focus is listed in full (bounded by FOCUS_OPEN_CAP, well above maxItems): an item in view that has no ref cannot be
        // suppressed, resolved or corrected. Only open items; the newest if there are more than the cap.
        focus.filter { it.targetKind == FocusTargetKind.PROJECT }.forEach { f ->
            structure.itemsIn(userEmail, f.targetUid).filter(::live).takeLast(FOCUS_OPEN_CAP).forEach { add(it, hardCap) }
        }
        // Up to maxItems as before; a focus larger than that keeps its items and still leaves a few places for the rest.
        val room = if (candidates.size <= maxItems) maxItems else candidates.size + RECENT_ITEM_CAP
        structure.listRecentItems(userEmail, OPEN_ITEM_CAP * POOL_OVERFETCH, openOnly = true).filter { live(it) && surfaces(it) }.take(OPEN_ITEM_CAP).forEach { add(it, room) }
        // Recent items fill what is left, so a just-resolved item can still be named to reopen it, and a waiting one to move it on; a suppressed one never is.
        structure.listRecentItems(userEmail, RECENT_ITEM_CAP * POOL_OVERFETCH).filter { it.attention != AttentionDirective.SUPPRESSED && (it.workState?.closed == true || it.workState == WorkState.WAITING || surfaces(it)) }.take(RECENT_ITEM_CAP).forEach { add(it, room) }

        // Whatever the Horizon is showing can be named, however the pools above fell: these come after them, so no earlier ref moves.
        include.distinct().forEach { uid ->
            structure.getItem(userEmail, uid)?.takeIf { it.attention != AttentionDirective.SUPPRESSED && it.workState?.closed != true }
                ?.let { add(it, Int.MAX_VALUE) }
        }

        // The way back from suppression: with a project in focus, its suppressed open items are listed apart under "Hidden (suppressed)", with
        // refs, so set_attention normal can restore one. Outside focus they stay invisible and take no slot. They come last, so other refs do not move.
        val hidden = focus.filter { it.targetKind == FocusTargetKind.PROJECT }
            .flatMap { f -> structure.itemsIn(userEmail, f.targetUid) }
            .filter { it.attention == AttentionDirective.SUPPRESSED && it.workState?.closed != true && it.uid !in candidates }
            .distinctBy { it.uid }.takeLast(HIDDEN_CAP)
        val hiddenUids = hidden.map { it.uid }.toSet()
        hidden.forEach { candidates[it.uid] = it }

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
                hidden = it.uid in hiddenUids,
                state = it.workState,
                dueKind = it.effectiveDueKind,
                dueHasTime = it.dueHasTime,
                nudgeAt = it.nudgeAt,
                waitingOn = it.waitingOn,
                stateFromSource = it.stateFromSource,
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
        /** The most open items of a focused project that are listed with refs. */
        const val FOCUS_OPEN_CAP = 40
        /** The most suppressed items of a focused project listed (with refs) in the "Hidden" section. */
        const val HIDDEN_CAP = 20
        /** Pools are read this many times over so that suppressed items filtered out do not leave the pool short. */
        const val POOL_OVERFETCH = 4
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
    /** When a recorded task or event turned out to be one already on the list: that item. Nothing new was created ([resultUid] is null), but it was touched, so it is nudged. */
    val existingUid: String? = null,
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
    /** Needed only for a [RecordItem] that names a chunk; without it such an operation is refused. */
    private val sources: SourceStore? = null,
) {
    private val logger = LoggerFactory.getLogger(HorizonOperationApplier::class.java)

    suspend fun apply(
        userEmail: String,
        cycleSeq: Long,
        requestId: String,
        operations: List<HorizonOperation>,
        zone: ZoneId = ZoneId.systemDefault(),
        /** The `Source.type` that asserts the items this call creates: the user's conversation unless a caller (the owner's Claude session) says otherwise. */
        sourceType: String = DatabaseEngramClient.SOURCE_TYPE,
        /** The `Source.type` that asserts an item taken from a chunk. The Director's own commits keep the default; the owner route passes [CHANNEL_SOURCED_SOURCE_TYPE]. */
        chunkSourceType: String = DatabaseEngramClient.SOURCE_TYPE,
    ): List<AppliedOperation> {
        val createdContainerUids = mutableMapOf<String, String>()
        return operations.mapIndexed { index, op ->
            try {
                // The verb is the cause of any change this operation records (see ChangeRecords).
                kotlinx.coroutines.withContext(ChangeContext(op.verb)) {
                    applyOne(userEmail, cycleSeq, (op as? RecordItem)?.itemKey ?: "$requestId:$index", op, createdContainerUids, zone, sourceType, chunkSourceType)
                }
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
        zone: ZoneId,
        sourceType: String,
        chunkSourceType: String,
    ): AppliedOperation = when (op) {
        is CreateContainer -> {
            val record = structure.createContainer(userEmail, cycleSeq, op.kind, op.name, op.parentUid, tentative = !op.namedByUser)
            record?.let { createdContainerUids[OperationParser.normalizeName(op.name)] = it.uid }
            AppliedOperation(op, record != null, if (record != null) "Created ${op.kind.name.lowercase()} \"${record.name}\"" else "Could not create ${op.kind.name.lowercase()} \"${op.name}\"")
        }
        is RecordItem -> if (op.chunkUid != null) applyFromChunk(userEmail, cycleSeq, opKey, op, createdContainerUids, zone, chunkSourceType)
            else recordPlain(userEmail, cycleSeq, opKey, op, createdContainerUids, zone, sourceType)
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
        is SetState -> {
            val label = labelOf(userEmail, op.targetUid)
            val nudge = op.nudgeAt?.let { TimeValue(it, op.nudgeHasTime) }
            val done = structure.setState(userEmail, cycleSeq, op.targetUid, op.state, nudge, op.waitingOn)
            val note = if (op.state == WorkState.WAITING && op.waitingOn == null) " (nothing says who or what it waits on; say so to add it)" else ""
            val nudgeNote = nudge?.let { ", check in ${describeDue(it.at, zone)}" } ?: ""
            val phrase = when (op.state) {
                WorkState.DONE -> "Marked done"
                WorkState.DROPPED -> "Dropped"
                WorkState.OPEN -> "Reopened"
                WorkState.ACTIVE -> "Marked in progress"
                WorkState.WAITING -> "Marked waiting" + (op.waitingOn?.let { " on $it" } ?: "")
            }
            AppliedOperation(op, done, if (done) "$phrase: $label$nudgeNote$note" else "Could not change the state of $label")
        }
        is ConfirmItem -> {
            val label = labelOf(userEmail, op.targetUid)
            val done = structure.touchEvidence(userEmail, cycleSeq, op.targetUid)
            AppliedOperation(op, done, if (done) "Confirmed as still live: $label" else "Could not confirm $label")
        }
        is CorrectItem -> applyCorrection(userEmail, cycleSeq, opKey, op, sourceType, zone)
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

    /** A [RecordItem] as the user's own words: the duplicate check, then project inference, then the write. */
    private suspend fun recordPlain(
        userEmail: String, cycleSeq: Long, opKey: String, op: RecordItem, createdContainerUids: MutableMap<String, String>, zone: ZoneId,
        sourceType: String = DatabaseEngramClient.SOURCE_TYPE,
        /** For an item filed from a source: when the source says it was last edited, which is when the item's evidence starts (bounded by now at the write). */
        evidenceAt: Long? = null,
    ): AppliedOperation = duplicateOf(userEmail, opKey, op, scopedToSource = sourceType == CHANNEL_SOURCED_SOURCE_TYPE)?.let { existing -> applyDuplicate(userEmail, cycleSeq, op, existing, zone, scopedToSource = sourceType == CHANNEL_SOURCED_SOURCE_TYPE, editedAt = evidenceAt) } ?: run {
            val stated = op.projectUid ?: op.projectName?.let { createdContainerUids[OperationParser.normalizeName(it)] }
            // The model rarely files an item even when the message plainly names its project, and an unfiled
            // item is outside every focus. So when it didn't file it and wasn't unsure, file it where its own
            // words say it belongs — only when that is unambiguous. See [ProjectInference].
            val inferred = if (stated == null && !op.askWhichProject) {
                ProjectInference.infer("${op.quote} ${op.title ?: ""}", structure.listContainers(userEmail, ContainerKind.PROJECT))
            } else null
            val projectUid = stated ?: inferred?.uid
            val extras = ItemExtras(
                state = op.state, dueKind = op.dueKind, dueHasTime = op.dueHasTime,
                starts = op.startsAt?.let { TimeValue(it, op.startsHasTime) }, nudge = op.nudgeAt?.let { TimeValue(it, op.nudgeHasTime) },
                happened = op.happenedAt?.let { TimeValue(it, op.happenedHasTime) }, waitingOn = op.waitingOn,
                fromSource = sourceType == CHANNEL_SOURCED_SOURCE_TYPE,
                evidenceAt = evidenceAt.takeIf { sourceType == CHANNEL_SOURCED_SOURCE_TYPE },
            )
            val record = structure.createItem(userEmail, cycleSeq, op.kind, op.title ?: op.quote, op.dueAt, projectUid, askedBy = "user", opKey = opKey, sourceQuote = op.quote, sourceType = sourceType, extras = extras)
            if (record == null) {
                AppliedOperation(op, false, "Could not record ${op.kind.name.lowercase()} \"${op.quote}\"")
            } else {
                op.about.forEach { structure.linkAbout(userEmail, cycleSeq, record.uid, it) }
                val basis = if (op.chunkUid != null) "from the source's words" else "from what you said"
                val filed = inferred?.let { " (filed under \"${it.name}\", $basis)" } ?: ""
                val stateNote = when {
                    op.state == WorkState.WAITING && op.waitingOn == null -> " (waiting, but nothing says on whom or what; say so to add it)"
                    op.state != null && op.state != WorkState.OPEN -> " (${op.state.wire()}${op.waitingOn?.let { " on $it" } ?: ""})"
                    else -> ""
                }
                AppliedOperation(op, true, "Recorded ${op.kind.name.lowercase()}: \"${record.text}\"$filed$stateNote", record.uid, record.text)
            }
        
    }

    /**
     * An item taken from a read source: the quote must be a passage of the named chunk (checked here, in code), and the item
     * is then recorded exactly as any other and tied to the chunk. This also holds when it turns out to be an item already on
     * the list: that item gains this chunk as a further place it was found. Recording the same chunk twice adds nothing.
     */
    private suspend fun applyFromChunk(
        userEmail: String, cycleSeq: Long, opKey: String, op: RecordItem, createdContainerUids: MutableMap<String, String>, zone: ZoneId,
        sourceType: String,
    ): AppliedOperation {
        val store = sources ?: return AppliedOperation(op, false, "Could not record from a source: no source store")
        // The chunk and the quote are checked on every path, a replay included: a replay is answered "already there" only for a quote
        // that is really in the chunk.
        val chunk = store.chunk(userEmail, op.chunkUid!!) ?: return AppliedOperation(op, false, "Could not record from a source: that chunk is not yours")
        if (op.quote.isBlank() || !SourceChunker.normalize(chunk.text).contains(SourceChunker.normalize(op.quote))) {
            return AppliedOperation(op, false, "Could not record from a source: the quote is not in the chunk")
        }
        // What the source says about when it was last edited: where the evidence of an item filed from it starts, and what a repeat of it refreshes to.
        val editedAt = if (sourceType == CHANNEL_SOURCED_SOURCE_TYPE) store.lastEditedMillis(userEmail, chunk.sourceUid) else null
        // A key already applied is a replay: the item (created, or matched when it was first applied) is found by the key on
        // its chunk link, so nothing is created, matched again or stamped with a cycle, whatever has happened to it since.
        store.itemForOpKey(userEmail, opKey)?.let { uid ->
            // The same item again is idempotent, but from a channel a different date is said, not silently dropped or applied.
            // Said again by the source, it is confirmed by it.
            if (sourceType == CHANNEL_SOURCED_SOURCE_TYPE) structure.refreshEvidenceFromSource(userEmail, cycleSeq, uid, editedAt)
            val have = structure.getItem(userEmail, uid)?.dueAt
            val conflict = if (sourceType == CHANNEL_SOURCED_SOURCE_TYPE && op.dueAt != null && have != null && op.dueAt != have) {
                "; the source gives a different date (${describeDue(op.dueAt, zone)}, the item has ${describeDue(have, zone)}) — the date was not changed"
            } else ""
            return AppliedOperation(op, true, "Already recorded from this source (nothing new was added)$conflict", existingUid = uid)
        }
        val result = recordPlain(userEmail, cycleSeq, opKey, op, createdContainerUids, zone, sourceType, evidenceAt = editedAt)
        val uid = result.resultUid ?: result.existingUid ?: return result
        val linked = store.linkItem(userEmail, uid, chunk.uid, op.writer?.takeIf { it.isNotBlank() } ?: "director", op.claimant, opKey, draftQuote = op.quote, draftDueAt = op.dueAt, runId = op.runId)
        return if (linked == LinkOutcome.REFUSED) result.copy(applied = false, summary = "Recorded but could not be tied to its chunk") else result
    }

    /**
     * The open task or event already on the list that a new one is plainly the same as — or null. A message like
     * "I'll do the room booking tomorrow" after "I need to book the conference room" is about the first task, and the
     * model sometimes records it as a second one; two of the same thing on the list, with the date on the wrong copy,
     * is worse than one. Only tasks and events, only open ones, only when the words are alike enough that treating
     * them as separate would be the surprise: at least two distinctive words in common and three quarters of the
     * longer wording, comparing the label and the user's own words on each side. A retry of the same request finds
     * the item it made the first time (by its key) and is never mistaken for a duplicate of it.
     */
    private suspend fun duplicateOf(userEmail: String, opKey: String, op: RecordItem, scopedToSource: Boolean = false): ItemRecord? {
        if (op.kind != ItemKind.TASK && op.kind != ItemKind.EVENT) return null
        if (structure.itemByOpKey(userEmail, opKey) != null) return null
        val incoming = listOfNotNull(op.title, op.quote).map { SalientTokenPropagator.salientTokens(it) }.filter { it.size >= MIN_DUPLICATE_WORDS }
        if (incoming.isEmpty()) return null
        // An operation a channel files from a source (the owner's Horizon tool) may only match an item asserted from that same source (or another stored version of the same
        // document): never an item from another source, never something the owner said. Without a source store it matches nothing.
        val sameDocument: Set<String>? = op.chunkUid?.takeIf { scopedToSource }?.let { chunkUid ->
            val store = sources ?: return null
            val chunk = store.chunk(userEmail, chunkUid) ?: return null
            store.versionsOf(userEmail, chunk.sourceUid)
        }
        return structure.listRecentItems(userEmail, DUPLICATE_SCAN).firstOrNull { existing ->
            existing.kind == op.kind && existing.status == "open" &&
                // The owner saying the same thing as a document's item is their adoption, not a duplicate of it: it is recorded as theirs.
                (op.chunkUid != null || !existing.channelSourced) &&
                (sameDocument == null || sources!!.sourceUidsOfItem(userEmail, existing.uid).any { it in sameDocument }) &&
                listOfNotNull(existing.text, existing.sourceQuote).map { SalientTokenPropagator.salientTokens(it) }.any { theirs ->
                    incoming.any { ours -> similarEnough(ours, theirs) }
                }
        }
    }

    private fun similarEnough(a: Set<String>, b: Set<String>): Boolean {
        val shared = (a intersect b).size
        return shared >= MIN_DUPLICATE_WORDS && shared.toDouble() / maxOf(a.size, b.size) >= DUPLICATE_SIMILARITY
    }

    /** The new record was the same as [existing]: add nothing, but carry over a date the user just gave, and say so plainly. */
    private suspend fun applyDuplicate(userEmail: String, cycleSeq: Long, op: RecordItem, existing: ItemRecord, zone: ZoneId, scopedToSource: Boolean = false, editedAt: Long? = null): AppliedOperation {
        val newDue = op.dueAt?.takeIf { it != existing.dueAt }
        // A repeat from a source never moves a date: the item's own date stands and the difference is reported. (What the owner just said wins, as before.)
        val conflict = scopedToSource && newDue != null && existing.dueAt != null
        if (scopedToSource) structure.refreshEvidenceFromSource(userEmail, cycleSeq, existing.uid, editedAt)
        val dueSet = newDue != null && !conflict && structure.setDue(userEmail, cycleSeq, existing.uid, newDue, op.dueHasTime)
        val note = when {
            conflict -> "; the source gives a different date (${describeDue(newDue!!, zone)}, the item has ${describeDue(existing.dueAt!!, zone)}) — the date was not changed"
            dueSet -> ", and its date is now ${describeDue(newDue!!, zone)}"
            else -> ""
        }
        return AppliedOperation(op, true, "Already on the list: \"${existing.text}\"$note (nothing new was added)", existingUid = existing.uid)
    }

    private fun describeDue(dueAt: Long, zone: ZoneId): String {
        val moment = java.time.Instant.ofEpochMilli(dueAt).atZone(zone)
        val boundary = (moment.hour == 0 && moment.minute == 0) || (moment.hour == 23 && moment.minute == 59)
        return if (boundary) moment.toLocalDate().toString() else "${moment.toLocalDate()} ${"%02d:%02d".format(moment.hour, moment.minute)}"
    }

    /**
     * A correction never overwrites: the corrected statement becomes a new item of the same kind,
     * filed where the old one was and tied to the same entities (with the new date if it changed),
     * linked as superseding it; the old one is closed so it stops surfacing. Nothing is lost.
     */
    private suspend fun applyCorrection(userEmail: String, cycleSeq: Long, opKey: String, op: CorrectItem, sourceType: String = DatabaseEngramClient.SOURCE_TYPE, zone: ZoneId = ZoneId.systemDefault()): AppliedOperation {
        val old = structure.getItem(userEmail, op.targetUid)
            ?: return AppliedOperation(op, false, "Could not find the item to correct")
        // The corrected item keeps where the old one stood and what was said about its time, except what the correction changes.
        val dueChanged = op.dueAt != null
        val extras = if (old.kind == ItemKind.FACT) ItemExtras(happened = old.happenedAt?.let { TimeValue(it, old.happenedHasTime) }) else ItemExtras(
            state = old.workState?.takeIf { !it.closed },
            dueKind = op.dueKind ?: old.dueKind,
            dueHasTime = if (dueChanged) op.dueHasTime else old.dueHasTime,
            starts = old.startsAt?.let { TimeValue(it, old.startsHasTime) },
            nudge = op.nudgeAt?.let { TimeValue(it, op.nudgeHasTime) } ?: old.nudgeAt?.let { TimeValue(it, old.nudgeHasTime) },
            happened = old.happenedAt?.let { TimeValue(it, old.happenedHasTime) },
            waitingOn = old.waitingOn,
        )
        val created = structure.createItem(userEmail, cycleSeq, old.kind, op.title ?: op.quote, op.dueAt ?: old.dueAt, old.projectUid, askedBy = "user", opKey = opKey, sourceQuote = op.quote, sourceType = sourceType, extras = extras)
            ?: return AppliedOperation(op, false, "Could not record the correction of \"${old.text}\"")
        old.about.forEach { structure.linkAbout(userEmail, cycleSeq, created.uid, it) }
        val linked = graph.markSuperseded(userEmail, cycleSeq, created.uid, old.uid)
        if (old.kind != ItemKind.FACT) structure.resolve(userEmail, cycleSeq, old.uid, resolved = true)
        return AppliedOperation(
            op,
            applied = linked,
            summary = if (linked) "Corrected \"${old.text}\" to \"${created.text}\"" + listOfNotNull(
                op.dueKind?.let { "; ${it.wire()} deadline" }, op.nudgeAt?.let { "; check in ${describeDue(it, zone)}" },
            ).joinToString("") else "Recorded the correction but could not link it to \"${old.text}\"",
            resultUid = created.uid,
            resultText = created.text,
        )
    }

    private companion object {
        const val DUPLICATE_SCAN = 60
        const val MIN_DUPLICATE_WORDS = 2
        const val DUPLICATE_SIMILARITY = 0.75
    }

    private suspend fun labelOf(userEmail: String, uid: String): String {
        structure.getContainer(userEmail, uid)?.let { return "\"${it.name}\"" }
        structure.getItem(userEmail, uid)?.let { return "\"${it.text}\"" }
        return "that item"
    }
}


/**
 * Decides, from an item's own words alone, which of the user's projects it belongs to — or none.
 * Deliberately conservative: it files only when the answer is unambiguous, and never from the current
 * focus (working on the demo does not make "call the plumber" a demo task).
 * 1. A project whose full name appears in the words wins; if several appear, the longest, if it is
 *    strictly the longest ("Demo prep" over "Demo").
 * 2. Otherwise a project sharing distinctive words with the item — the most shared, if exactly one project
 *    has the most, and only projects that are still active.
 * Anything else is left unfiled.
 */
internal object ProjectInference {
    fun infer(words: String, projects: List<ContainerRecord>): ContainerRecord? {
        val live = projects.filter { it.status == "active" }
        if (live.isEmpty() || words.isBlank()) return null
        val padded = " ${normalize(words)} "

        val named = live.filter { it.normalizedName.length >= 3 && padded.contains(" ${normalize(it.name)} ") }
        if (named.isNotEmpty()) {
            val longest = named.maxOf { it.normalizedName.length }
            val top = named.filter { it.normalizedName.length == longest }
            return top.singleOrNull()
        }

        val said = SalientTokenPropagator.salientTokens(words)
        if (said.isEmpty()) return null
        val overlaps = live.associateWith { (said intersect SalientTokenPropagator.salientTokens(it.name)).size }.filterValues { it > 0 }
        if (overlaps.isEmpty()) return null
        val best = overlaps.values.max()
        return overlaps.filterValues { it == best }.keys.singleOrNull()
    }

    private fun normalize(text: String): String = text.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
}
