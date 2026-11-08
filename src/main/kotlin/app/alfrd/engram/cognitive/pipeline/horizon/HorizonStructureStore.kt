package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.cognitive.pipeline.memory.DatabaseEngramClient
import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID

/** The two container levels above an item: Domain > Project > item. */
enum class ContainerKind(val typeName: String) { DOMAIN("Domain"), PROJECT("Project") }

/** What an item is. [FACT] is a plain `Phrase`; the rest are `Phrase` subtypes with their own properties. */
enum class ItemKind(val typeName: String) {
    FACT("Phrase"),
    TASK("Task"),
    EVENT("Event"),
    QUESTION("Question"),
}

/**
 * Where an item stands. Stored on the item as `state`; the older `status` (`open`/`resolved`) is kept as the coarse, indexed
 * projection of it ([status]) so existing readers and rollback keep working. An item with no stored `state` reads from `status`:
 * `open` is [OPEN], `resolved` is [DONE]. A source's or the owner's claim; retracted is a separate error marker, not a state here.
 */
enum class WorkState(val status: String) {
    OPEN("open"), ACTIVE("open"), WAITING("open"), DONE("resolved"), DROPPED("resolved");

    fun wire(): String = name.lowercase()

    /** Done or dropped: finished one way or the other. Never shown in the Horizon. */
    val closed: Boolean get() = this == DONE || this == DROPPED

    companion object {
        fun fromWire(value: String?): WorkState? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }

        /** The state of a stored item: its own `state`, else read from the coarse `status`. Null for anything with neither. */
        fun of(state: String?, status: String?): WorkState? = fromWire(state) ?: when (status) {
            "open" -> OPEN
            "resolved" -> DONE
            else -> null
        }
    }
}

/** Whether a due date is a promise (hard) or a hope (soft). Soft is the default when nothing is said. */
enum class DueKind {
    HARD, SOFT;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(value: String?): DueKind? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * What state does to visibility and urgency, in one place: done and dropped never show; a waiting item shows only inside a focused
 * project or once its check-in date has arrived; overdue applies to open and active items only. An item with no state reads as open.
 */
object StateRules {
    fun visible(state: WorkState?, nudgeAt: Long?, inFocusedProject: Boolean, now: Long): Boolean = when (state) {
        WorkState.DONE, WorkState.DROPPED -> false
        WorkState.WAITING -> inFocusedProject || (nudgeAt != null && nudgeAt <= now)
        else -> true
    }

    /** Open and active items can be overdue; waiting, done and dropped never are. */
    fun canBeOverdue(state: WorkState?): Boolean = state == null || state == WorkState.OPEN || state == WorkState.ACTIVE
}

/** A moment, with the precision it was given: [hasTime] false means only the date was given (the time is the day's boundary). */
data class TimeValue(val at: Long, val hasTime: Boolean = false)

/** Everything an item can carry beyond its words, state and one date. All optional; an unset field is simply absent on the item. */
data class ItemExtras(
    val state: WorkState? = null,
    val dueKind: DueKind? = null,
    /** Precision of the `dueAt` passed to [HorizonStructureStore.createItem]. */
    val dueHasTime: Boolean = false,
    val starts: TimeValue? = null,
    val nudge: TimeValue? = null,
    val happened: TimeValue? = null,
    val waitingOn: String? = null,
    /** True when [state] or [dueKind] is a claim made by the document the item was filed from (until anyone changes it). */
    val fromSource: Boolean = false,
)

/** Zoom levels a focus can carry: broad, one container, one item's neighborhood. */
enum class FocusZoom {
    OVERVIEW, BINOCULARS, TELESCOPE;

    /** The lowercase value stored on the `FOCUSES_ON` edge. */
    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(value: String?): FocusZoom = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OVERVIEW
    }
}

/** A pinned focus is an explicit instruction and persists until changed; a nudge is a casual mention and fades. */
enum class FocusMode {
    PINNED, NUDGE;

    /** The lowercase value stored on the `FOCUSES_ON` edge. */
    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(value: String?): FocusMode = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NUDGE
    }
}

enum class FocusTargetKind { DOMAIN, PROJECT, ITEM }

data class ContainerRecord(
    val uid: String,
    val kind: ContainerKind,
    val name: String,
    val normalizedName: String,
    val status: String,
    val dueAt: Long?,
    /** For a project, the domain it is filed under, if any. Always null for a domain. */
    val parentUid: String?,
    val createdAt: Long,
    /** The user's explicit "keep this up front" / "stop bringing this up"; null = normal. */
    val attention: AttentionDirective? = null,
    /** True when the name is the model's own label rather than something the user said. */
    val tentative: Boolean = false,
)

data class ItemRecord(
    val uid: String,
    val kind: ItemKind,
    val text: String,
    /** `open`/`resolved` for a task, event or question; null for a fact. */
    val status: String?,
    /** A task's `dueAt` or an event's `occursAt` — the time this item is bound to, if any. */
    val dueAt: Long?,
    val projectUid: String?,
    val createdAt: Long,
    /** Short names of the entities this item is tied to (its `ABOUT` links), e.g. "sister". */
    val about: List<String> = emptyList(),
    val attention: AttentionDirective? = null,
    /** The user's own words, when [text] is a label written for a task or event rather than their words. */
    val sourceQuote: String? = null,
    /** For a question: who is waiting on the answer (`alfrd` when Alfrd asked it). Null for anything else. */
    val askedBy: String? = null,
    /** True when a channel filed this from a document it supplied ([CHANNEL_SOURCED_SOURCE_TYPE]): not something the owner said. */
    val channelSourced: Boolean = false,
    /** The stored `state`; null when the item has none yet (read [workState], which falls back to [status]). */
    val state: WorkState? = null,
    /** As stored; null reads as soft (see [effectiveDueKind]). */
    val dueKind: DueKind? = null,
    /** True when [dueAt] carries a time of day as given; false when only a date was given. */
    val dueHasTime: Boolean = false,
    val startsAt: Long? = null,
    val startsHasTime: Boolean = false,
    /** For a waiting item: the date to check in. */
    val nudgeAt: Long? = null,
    val nudgeHasTime: Boolean = false,
    val happenedAt: Long? = null,
    val happenedHasTime: Boolean = false,
    /** For a waiting item: who or what it waits on, in a few words. */
    val waitingOn: String? = null,
    /** True while the state or due kind is still the document's own claim, never the owner's. */
    val stateFromSource: Boolean = false,
    /** When anything last confirmed the item (see [HorizonStructureStore.touchEvidence]); null when never stamped, in which case [evidenceAt] falls back. */
    val lastEvidenceAt: Long? = null,
    val updatedAt: Long = 0L,
) {
    /** When the item was last confirmed: its stamp, else when it was last changed, else when it was made. */
    val evidenceAt: Long get() = lastEvidenceAt ?: updatedAt.takeIf { it > 0L } ?: createdAt

    /** Where the item stands: its own state, else read from [status]. Null for a plain fact. */
    val workState: WorkState? get() = state ?: WorkState.of(null, status)
    val effectiveDueKind: DueKind get() = dueKind ?: DueKind.SOFT
}

data class FocusRecord(
    val targetUid: String,
    val targetKind: FocusTargetKind,
    val zoom: FocusZoom,
    val mode: FocusMode,
    val setCycleSeq: Long,
    /** A nudge's strength (0 to 1) when it was set, before it fades. 1.0 for a pin. */
    val weight: Double = 1.0,
)

/**
 * The zoom a focus on a target of this kind gets when the caller doesn't say: a domain is read
 * broadly, a project through binoculars, a single item through the telescope. Provisional — the
 * selector's policy decides what each level actually shows.
 */
fun defaultZoomFor(kind: FocusTargetKind): FocusZoom = when (kind) {
    FocusTargetKind.DOMAIN -> FocusZoom.OVERVIEW
    FocusTargetKind.PROJECT -> FocusZoom.BINOCULARS
    FocusTargetKind.ITEM -> FocusZoom.TELESCOPE
}

/**
 * Write and read operations for the Horizon's structure: containers (Domain, Project), typed
 * items (Task, Event, Question, plain facts), the edges that file items into containers and tie
 * them to entities, and the user's focus. Separate from [HorizonGraphStore] on purpose — that
 * interface has other implementers and fakes, and none of them should have to learn this.
 *
 * The rules are the same ones [HorizonGraphStore] holds itself to:
 * - Every mutator is owner-scoped. A uid that isn't the caller's — a container whose `ownerEmail`
 *   differs, an item not reachable through the caller's own `TRUSTS -> Source -> ASSERTS` chain —
 *   is a rejected, logged no-op that returns `null`/`false`; nothing is written and nothing throws.
 * - Every mutator takes [HorizonConsistencyLock]'s write lock for the duration of its transaction,
 *   bounded, so it is excluded from a concurrent [HorizonAssembler.assemble] read and from other
 *   writers, and is rejected (never run unguarded) if the lock can't be had in time.
 * - Every mutator is idempotent under retry: creating the same container twice returns the first;
 *   filing, linking, focusing, resolving and setting a time to the state already recorded is a
 *   successful no-op; [createItem] with the same `opKey` returns the item it made the first time.
 *   That is what lets a crash-resumed write plan re-apply without duplicating anything.
 *
 * Nothing here decides *what* to write or what is in view — that is the interpretation model's
 * verbs (applied through this store) and the selector's policy. This is only the durable substrate.
 */
interface HorizonStructureStore {

    /**
     * Creates a container named [name] for [userEmail], or returns the one that already exists
     * under the same (owner, kind, normalized name). When [parentDomainUid] is given for a
     * [ContainerKind.PROJECT] it is filed under that domain at creation; an already-existing
     * project is returned as-is, never re-filed (use [file]). Null on an unknown user, a blank
     * name, or a [parentDomainUid] that isn't one of the user's domains. [tentative] marks a name
     * the model chose rather than one the user said.
     */
    suspend fun createContainer(
        userEmail: String,
        cycleSeq: Long,
        kind: ContainerKind,
        name: String,
        parentDomainUid: String? = null,
        dueAt: Long? = null,
        tentative: Boolean = false,
    ): ContainerRecord?

    /**
     * Creates an item of [kind] for [userEmail], asserted from the user's conversational Source
     * exactly like an ordinarily-ingested claim (so it is owned, and queryable, the same way).
     * [dueAt] is a task's due time or an event's start; ignored for other kinds. [askedBy] applies
     * to [ItemKind.QUESTION] only. When [projectUid] is given the item is filed under that project
     * in the same transaction. [opKey], when given, makes the call idempotent: a second call with
     * the same key for the same user returns the item the first one created instead of a duplicate.
     * Null on an unknown user, blank text, or a [projectUid] that isn't one of the user's projects.
     * [sourceQuote] is the user's own words when [text] is a label written for them — kept so nothing
     * they said is lost behind a paraphrase.
     */
    suspend fun createItem(
        userEmail: String,
        cycleSeq: Long,
        kind: ItemKind,
        text: String,
        dueAt: Long? = null,
        projectUid: String? = null,
        askedBy: String = "alfrd",
        opKey: String? = null,
        sourceQuote: String? = null,
        sourceType: String = DatabaseEngramClient.SOURCE_TYPE,
        extras: ItemExtras = ItemExtras(),
    ): ItemRecord?

    /**
     * Moves a task, event or question to [state]. [nudge] (the date to check in) and [waitingOn] are set when given; leaving
     * a waiting state clears whichever of them is not given. Reaching a closed state records `resolvedAt`; leaving one clears it.
     * The coarse `status` follows. A no-op when nothing would change. False for a fact, a container, or a target the user doesn't own.
     */
    suspend fun setState(userEmail: String, cycleSeq: Long, targetUid: String, state: WorkState, nudge: TimeValue? = null, waitingOn: String? = null): Boolean

    /**
     * Records that the item was confirmed just now ("yes, still live"): sets `lastEvidenceAt` and nothing else. True for a task, event or
     * question the user owns; false for anything else. Attention changes (pin, suppress) are not evidence and never call this.
     */
    suspend fun touchEvidence(userEmail: String, cycleSeq: Long, targetUid: String): Boolean

    /**
     * Sets the hard/soft marking of an item's due date, and optionally its check-in date. False for a fact, container, or target the
     * user doesn't own. Idempotent.
     */
    suspend fun setTiming(userEmail: String, cycleSeq: Long, targetUid: String, dueKind: DueKind? = null, nudge: TimeValue? = null): Boolean

    /**
     * Files [childUid] under [parentUid]: an item under a project, or a project under a domain —
     * any other pairing is rejected. A child has at most one parent, so filing under a different
     * parent moves it; filing under the parent it already has is a successful no-op.
     */
    suspend fun file(userEmail: String, cycleSeq: Long, childUid: String, parentUid: String): Boolean

    /** Ties [itemUid] to the shared entity `Concept` named [conceptName] (found or created by normalized name). Idempotent per (item, concept). */
    suspend fun linkAbout(userEmail: String, cycleSeq: Long, itemUid: String, conceptName: String): Boolean

    /**
     * Sets or clears (`null`) the time a target is bound to: a task's or project's `dueAt`, an
     * event's `occursAt`. False for a target with no such time (a fact, question or domain) or one
     * the user doesn't own.
     */
    suspend fun setDue(userEmail: String, cycleSeq: Long, targetUid: String, dueAt: Long?, hasTime: Boolean = false): Boolean

    /**
     * Marks a task, event or question `resolved` (or `open` again when [resolved] is false), or a
     * project `done`/`active`. False for any other target or one the user doesn't own. Records
     * `resolvedAt` when resolving; a no-op if it is already in the requested state. For a task or
     * question the `ASSERTS.status` copy is kept in step too (see [createItem]).
     */
    suspend fun resolve(userEmail: String, cycleSeq: Long, targetUid: String, resolved: Boolean = true): Boolean

    /**
     * Records the user's explicit attention instruction for an item, project or domain: [PINNED]
     * keeps it in front of them, [SUPPRESSED] stops it being brought up (without resolving it),
     * `null` returns it to normal. False for a target the user doesn't own. Persisted only — what
     * acts on it is the selector.
     */
    suspend fun setAttention(userEmail: String, cycleSeq: Long, targetUid: String, attention: AttentionDirective?): Boolean

    /**
     * Sets a focus on [targetUid]. A [FocusMode.PINNED] focus replaces any other pinned focus (at
     * most one per user) and leaves nudges alone. A [FocusMode.NUDGE] refreshes an existing edge
     * to the same target, but never downgrades a pinned focus. Null [zoom] uses [defaultZoomFor]
     * the target's kind. [weight] is a nudge's strength (0 to 1): refreshing keeps the stronger of the
     * old and new. False for a target the user doesn't own.
     */
    suspend fun setFocus(userEmail: String, cycleSeq: Long, targetUid: String, mode: FocusMode, zoom: FocusZoom? = null, weight: Double = 1.0): Boolean

    /**
     * Removes focus edges: those to [targetUid] when given, otherwise every pinned focus — and
     * every nudge too when [includeNudges]. Returns how many were removed.
     */
    suspend fun releaseFocus(userEmail: String, targetUid: String? = null, includeNudges: Boolean = true): Int

    /** Deletes this user's nudges set before [olderThanCycle], returning how many — nudges fade to nothing well before then, so this only keeps the graph from accumulating them. Pins are never touched. */
    suspend fun pruneNudges(userEmail: String, olderThanCycle: Long): Int

    /** Records the wall-clock time of the user's latest turn, only ever moving it forward. False for an unknown user. */
    suspend fun touchLastActive(userEmail: String, atMillis: Long): Boolean

    // ── Reads ────────────────────────────────────────────────────────────────

    suspend fun lastActiveAt(userEmail: String): Long?

    /** Every focus edge the user currently has, pinned first. */
    suspend fun getFocus(userEmail: String): List<FocusRecord>

    /** The user's containers, optionally of one [kind]; oldest first. */
    suspend fun listContainers(userEmail: String, kind: ContainerKind? = null): List<ContainerRecord>

    suspend fun getContainer(userEmail: String, uid: String): ContainerRecord?

    suspend fun getItem(userEmail: String, uid: String): ItemRecord?

    /** The item this user's request created under [opKey] (`requestId:index`), if it did — how a retry recognizes its own earlier write. */
    suspend fun itemByOpKey(userEmail: String, opKey: String): ItemRecord?

    /** The items filed directly under project [projectUid] (never nested projects), oldest first. Empty for a project the user doesn't own. */
    suspend fun itemsIn(userEmail: String, projectUid: String): List<ItemRecord>

    /** The projects filed directly under domain [domainUid], oldest first. Empty for a domain the user doesn't own. */
    suspend fun projectsIn(userEmail: String, domainUid: String): List<ContainerRecord>

    /** The user's items tied to the entity named [conceptName] (matched by normalized name), newest first, bounded by [limit]. Only the caller's own items, though the entity itself is shared. */
    suspend fun itemsAbout(userEmail: String, conceptName: String, limit: Int = 20): List<ItemRecord>

    /**
     * The user's most recently asserted conversational items, newest first, capped at [limit] —
     * or, when [openOnly], only those still open (a task or question not yet resolved). Used to
     * show the interpretation model what it can refer to; a bounded read, never the whole graph.
     */
    suspend fun listRecentItems(userEmail: String, limit: Int, openOnly: Boolean = false): List<ItemRecord>

    /**
     * Just the words of the most recent things the user's own Sources assert (their statements, and
     * reports made for them), newest first, capped at [limit] — one query, nothing else loaded. This is what "the message mentions something they
     * told me" is matched against, so it has to reach further back than [listRecentItems] is asked to
     * and stay cheap enough to run every turn.
     */
    suspend fun scanItemTexts(userEmail: String, limit: Int): List<ItemText>
}

private const val MAX_WAITING_ON = 120
private val ITEM_STATE_TYPES = setOf("Task", "Event", "Question")

/** An item's identity and words, nothing more — see [HorizonStructureStore.scanItemTexts]. */
data class ItemText(val uid: String, val text: String)

class ArcadeHorizonStructureStore(
    private val db: Database,
    private val lockTimeoutMs: Long = HorizonConsistencyLock.DEFAULT_TIMEOUT_MS,
) : HorizonStructureStore {

    private val logger = LoggerFactory.getLogger(ArcadeHorizonStructureStore::class.java)

    // ── Writes ───────────────────────────────────────────────────────────────

    override suspend fun createContainer(
        userEmail: String,
        cycleSeq: Long,
        kind: ContainerKind,
        name: String,
        parentDomainUid: String?,
        dueAt: Long?,
        tentative: Boolean,
    ): ContainerRecord? {
        if (userEmail.isBlank() || name.isBlank()) return null
        return mutate("createContainer", null) {
            HorizonOwnership.findUserVertex(db, userEmail) ?: return@mutate null
            val normalized = normalizeName(name)
            findContainerByName(userEmail, kind, normalized)?.let { return@mutate it.toContainerRecord() }
            val parent = if (kind == ContainerKind.PROJECT && parentDomainUid != null) {
                findOwnedContainer(userEmail, parentDomainUid, ContainerKind.DOMAIN) ?: run {
                    logger.warn("createContainer: parentDomainUid=$parentDomainUid is not a domain owned by userEmail=$userEmail — rejecting")
                    return@mutate null
                }
            } else null
            val now = System.currentTimeMillis()
            val vertex = db.newVertex(kind.typeName).apply {
                set("uid", UUID.randomUUID().toString())
                set("name", name.trim())
                set("normalizedName", normalized)
                set("ownerEmail", userEmail)
                set("status", "active")
                if (tentative) set("tentative", true)
                if (kind == ContainerKind.PROJECT && dueAt != null) set("dueAt", dueAt)
                set("createdAt", now)
                set("updatedAt", now)
                save()
            }
            if (parent != null) newPartOf(vertex, parent, cycleSeq, userEmail)
            vertex.toContainerRecord()
        }
    }

    override suspend fun createItem(
        userEmail: String,
        cycleSeq: Long,
        kind: ItemKind,
        text: String,
        dueAt: Long?,
        projectUid: String?,
        askedBy: String,
        opKey: String?,
        sourceQuote: String?,
        sourceType: String,
        extras: ItemExtras,
    ): ItemRecord? {
        if (userEmail.isBlank() || text.isBlank()) return null
        return mutate("createItem", null) {
            val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: run {
                logger.warn("createItem: no User vertex for email=$userEmail — skipping")
                return@mutate null
            }
            val namespacedKey = opKey?.let { "$userEmail|$it" }
            if (namespacedKey != null) {
                findOwnedItemByOpKey(userEmail, namespacedKey)?.let { existing ->
                    // A key already known is a retry of the same write only if it is the same item. A different kind or text under
                    // the same key is a different item, and is refused rather than silently answered with the old one.
                    if (existing.typeName == kind.typeName && ItemWords.normalize(existing.get("text") as? String ?: "") == ItemWords.normalize(text)) return@mutate existing.toItemRecord()
                    logger.warn("createItem: operation key already used by a different item — refusing")
                    return@mutate null
                }
            }
            val project = if (projectUid != null) {
                findOwnedContainer(userEmail, projectUid, ContainerKind.PROJECT) ?: run {
                    logger.warn("createItem: projectUid=$projectUid is not a project owned by userEmail=$userEmail — rejecting")
                    return@mutate null
                }
            } else null
            val source = findOrCreateConversationSource(userVertex, userEmail, sourceType)
            val now = System.currentTimeMillis()
            val vertex = db.newVertex(kind.typeName).apply {
                set("uid", UUID.randomUUID().toString())
                set("text", text.trim())
                set("hash", sha256(text))
                set("visibility", "private")
                set("createdAt", now)
                set("updatedAt", now)
                set("ownerEmail", userEmail)
                if (namespacedKey != null) set("opKey", namespacedKey)
                if (!sourceQuote.isNullOrBlank() && sourceQuote.trim() != text.trim()) set("sourceQuote", sourceQuote.trim())
                when (kind) {
                    ItemKind.TASK -> {
                        if (dueAt != null) { set("dueAt", dueAt); if (extras.dueHasTime) set("dueHasTime", true) }
                    }
                    ItemKind.EVENT -> {
                        if (dueAt != null) { set("occursAt", dueAt); if (extras.dueHasTime) set("dueHasTime", true) }
                    }
                    ItemKind.QUESTION -> set("askedBy", askedBy)
                    ItemKind.FACT -> Unit
                }
                if (kind != ItemKind.FACT) {
                    // The state is the item's own; `status` is its coarse open/resolved projection (indexed, and what older readers use).
                    val state = extras.state ?: WorkState.OPEN
                    set("state", state.wire())
                    set("status", state.status)
                    set("lastEvidenceAt", now)
                    if (state.closed) set("resolvedAt", now)
                    if (extras.dueKind != null && dueAt != null) set("dueKind", extras.dueKind.wire())
                    if (state == WorkState.WAITING || extras.nudge != null) extras.nudge?.let { set("nudgeAt", it.at); if (it.hasTime) set("nudgeHasTime", true) }
                    extras.waitingOn?.trim()?.takeIf { it.isNotBlank() }?.let { set("waitingOn", it.take(MAX_WAITING_ON)) }
                    extras.starts?.let { set("startsAt", it.at); if (it.hasTime) set("startsHasTime", true) }
                    if (extras.fromSource && (extras.state != null || extras.dueKind != null)) set("stateFromSource", true)
                }
                // Facts happened too: the time something took place, when it is known.
                extras.happened?.let { set("happenedAt", it.at); if (it.hasTime) set("happenedHasTime", true) }
                save()
            }
            source.newEdge("ASSERTS", vertex, false).apply {
                set("context", sourceType)
                set("timestamp", now)
                // A document's words filed by a channel carry no trust from the owner: only their own adoption would.
                set("scores", if (sourceType == CHANNEL_SOURCED_SOURCE_TYPE) "[]" else """[{"type":"trust","perspective":"user","value":${DatabaseEngramClient.TRUST_SCORE}}]""")
                set("cycleSeq", cycleSeq)
                save()
            }
            if (project != null) newPartOf(vertex, project, cycleSeq, userEmail)
            vertex.toItemRecord()
        }
    }

    override suspend fun file(userEmail: String, cycleSeq: Long, childUid: String, parentUid: String): Boolean =
        mutate("file", false) {
            val child = resolveFilable(userEmail, childUid) ?: return@mutate false
            // An item files under a project; a project files under a domain. Nothing else nests.
            val parentKind = if (child.typeName == ContainerKind.PROJECT.typeName) ContainerKind.DOMAIN else ContainerKind.PROJECT
            val parent = findOwnedContainer(userEmail, parentUid, parentKind) ?: run {
                logger.warn("file: parentUid=$parentUid is not a ${parentKind.typeName} owned by userEmail=$userEmail for child=$childUid — rejecting")
                return@mutate false
            }
            val existing = child.getEdges(Vertex.DIRECTION.OUT, "PART_OF").toList()
            if (existing.any { it.getVertex(Vertex.DIRECTION.IN)?.get("uid") == parentUid }) return@mutate true
            existing.forEach { it.delete() }
            newPartOf(child, parent, cycleSeq, userEmail)
            if (child.typeName in ITEM_STATE_TYPES) child.modify().apply { set("lastEvidenceAt", System.currentTimeMillis()); save() }
            true
        }

    override suspend fun linkAbout(userEmail: String, cycleSeq: Long, itemUid: String, conceptName: String): Boolean {
        if (conceptName.isBlank()) return false
        return mutate("linkAbout", false) {
            val item = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, itemUid) ?: return@mutate false
            val normalized = normalizeName(conceptName)
            val concept = findOrCreateConcept(conceptName.trim(), normalized)
            val conceptUid = concept.get("uid")
            val already = item.getEdges(Vertex.DIRECTION.OUT, "ABOUT")
                .any { it.getVertex(Vertex.DIRECTION.IN)?.get("uid") == conceptUid }
            if (already) return@mutate true
            item.modify().newEdge("ABOUT", concept, true).apply {
                set("createdAt", System.currentTimeMillis())
                set("cycleSeq", cycleSeq)
                set("ownerEmail", userEmail)
                set("strength", 1.0)
                save()
            }
            true
        }
    }

    override suspend fun setDue(userEmail: String, cycleSeq: Long, targetUid: String, dueAt: Long?, hasTime: Boolean): Boolean =
        mutate("setDue", false) {
            val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
            val property = when (target.typeName) {
                "Task", "Project" -> "dueAt"
                "Event" -> "occursAt"
                else -> return@mutate false
            }
            val current = (target.get(property) as? Number)?.toLong()
            if (current == dueAt && (dueAt == null || (target.get("dueHasTime") == true) == hasTime)) return@mutate true
            target.modify().apply {
                if (dueAt == null) { remove(property); remove("dueHasTime"); remove("dueKind") } else { set(property, dueAt); if (hasTime) set("dueHasTime", true) else remove("dueHasTime") }
                set("updatedAt", System.currentTimeMillis())
                if (target.typeName in ITEM_STATE_TYPES) set("lastEvidenceAt", System.currentTimeMillis())
                save()
            }
            true
        }

    override suspend fun resolve(userEmail: String, cycleSeq: Long, targetUid: String, resolved: Boolean): Boolean =
        mutate("resolve", false) {
            val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
            if (target.typeName == "Project") {
                val desired = if (resolved) "done" else "active"
                if (target.get("status") == desired) return@mutate true
                val now = System.currentTimeMillis()
                target.modify().apply { set("status", desired); set("updatedAt", now); save() }
                return@mutate true
            }
            if (target.typeName !in ITEM_STATE_TYPES) return@mutate false
            // Resolve is set_state done; reopen is set_state open. The item's own state is the only record of it.
            applyState(target, if (resolved) WorkState.DONE else WorkState.OPEN, null, null)
            true
        }

    override suspend fun setState(userEmail: String, cycleSeq: Long, targetUid: String, state: WorkState, nudge: TimeValue?, waitingOn: String?): Boolean =
        mutate("setState", false) {
            val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
            if (target.typeName !in ITEM_STATE_TYPES) return@mutate false
            applyState(target, state, nudge, waitingOn)
            true
        }

    override suspend fun setTiming(userEmail: String, cycleSeq: Long, targetUid: String, dueKind: DueKind?, nudge: TimeValue?): Boolean =
        mutate("setTiming", false) {
            val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
            if (target.typeName !in ITEM_STATE_TYPES) return@mutate false
            val kindSame = dueKind == null || DueKind.fromWire(target.get("dueKind") as? String) == dueKind || (dueKind == DueKind.SOFT && target.get("dueKind") == null)
            val nudgeSame = nudge == null || ((target.get("nudgeAt") as? Number)?.toLong() == nudge.at && (target.get("nudgeHasTime") == true) == nudge.hasTime)
            if (kindSame && nudgeSame) return@mutate true
            target.modify().apply {
                if (dueKind != null) { set("dueKind", dueKind.wire()); remove("stateFromSource") }
                if (nudge != null) { set("nudgeAt", nudge.at); if (nudge.hasTime) set("nudgeHasTime", true) else remove("nudgeHasTime") }
                set("updatedAt", System.currentTimeMillis())
                set("lastEvidenceAt", System.currentTimeMillis())
                save()
            }
            true
        }

    override suspend fun touchEvidence(userEmail: String, cycleSeq: Long, targetUid: String): Boolean =
        mutate("touchEvidence", false) {
            val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
            if (target.typeName !in ITEM_STATE_TYPES) return@mutate false
            // Only the stamp moves: updatedAt is left alone so a confirmation is not mistaken for a change.
            target.modify().apply { set("lastEvidenceAt", System.currentTimeMillis()); save() }
            true
        }

    /** Writes [state] and its coarse `status`; see [HorizonStructureStore.setState]. Caller holds the lock. */
    private fun applyState(target: Vertex, state: WorkState, nudge: TimeValue?, waitingOn: String?) {
        val current = WorkState.of(target.get("state") as? String, target.get("status") as? String)
        val currentNudge = (target.get("nudgeAt") as? Number)?.toLong()
        val newWaitingOn = waitingOn?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_WAITING_ON)
        val leavingWaiting = state != WorkState.WAITING
        val nudgeUnchanged = if (nudge != null) currentNudge == nudge.at && (target.get("nudgeHasTime") == true) == nudge.hasTime else (!leavingWaiting || currentNudge == null)
        val waitingUnchanged = if (newWaitingOn != null) target.get("waitingOn") == newWaitingOn else (!leavingWaiting || target.get("waitingOn") == null)
        if (current == state && target.get("state") == state.wire() && nudgeUnchanged && waitingUnchanged) return
        val now = System.currentTimeMillis()
        target.modify().apply {
            set("state", state.wire())
            set("status", state.status)
            set("lastEvidenceAt", now)
            remove("stateFromSource")
            if (state.closed) { if (current?.closed != true) set("resolvedAt", now) } else remove("resolvedAt")
            if (nudge != null) { set("nudgeAt", nudge.at); if (nudge.hasTime) set("nudgeHasTime", true) else remove("nudgeHasTime") }
            else if (leavingWaiting) { remove("nudgeAt"); remove("nudgeHasTime") }
            if (newWaitingOn != null) set("waitingOn", newWaitingOn) else if (leavingWaiting) remove("waitingOn")
            set("updatedAt", now)
            save()
        }
    }

    override suspend fun setAttention(userEmail: String, cycleSeq: Long, targetUid: String, attention: AttentionDirective?): Boolean =
        mutate("setAttention", false) {
            val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
            val desired = attention?.name?.lowercase()
            if (target.get("attention") as? String == desired) return@mutate true
            target.modify().apply {
                if (desired == null) remove("attention") else set("attention", desired)
                // A pin or a suppression is not evidence the item is live. updatedAt moves below, so an item that was never stamped keeps
                // the time it would have read as (its old updatedAt) rather than inheriting this change.
                if (target.typeName in ITEM_STATE_TYPES && target.get("lastEvidenceAt") == null) {
                    set("lastEvidenceAt", (target.get("updatedAt") as? Number)?.toLong() ?: (target.get("createdAt") as? Number)?.toLong() ?: System.currentTimeMillis())
                }
                set("updatedAt", System.currentTimeMillis())
                save()
            }
            true
        }

    override suspend fun setFocus(
        userEmail: String,
        cycleSeq: Long,
        targetUid: String,
        mode: FocusMode,
        zoom: FocusZoom?,
        weight: Double,
    ): Boolean = mutate("setFocus", false) {
        val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@mutate false
        val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
        val resolvedZoom = zoom ?: defaultZoomFor(target.focusTargetKind())
        val edges = userVertex.getEdges(Vertex.DIRECTION.OUT, "FOCUSES_ON").toList()
        val existing = edges.firstOrNull { it.getVertex(Vertex.DIRECTION.IN)?.get("uid") == targetUid }
        val now = System.currentTimeMillis()

        if (mode == FocusMode.PINNED) {
            // At most one pinned focus: every other pinned edge goes.
            edges.filter { it.identity != existing?.identity && it.get("mode") == FocusMode.PINNED.wire() }.forEach { it.delete() }
        } else if (existing != null && existing.get("mode") == FocusMode.PINNED.wire()) {
            // A casual mention of the thing already pinned never weakens the pin.
            return@mutate true
        }

        val clamped = weight.coerceIn(0.0, 1.0)
        if (existing != null) {
            // A refreshed nudge keeps the stronger of what it had and what it is now; a pin is always full weight.
            val kept = if (mode == FocusMode.PINNED) 1.0 else maxOf((existing.get("weight") as? Number)?.toDouble() ?: 0.0, clamped)
            existing.modify().apply {
                set("zoom", resolvedZoom.wire())
                set("mode", mode.wire())
                set("setCycleSeq", cycleSeq)
                set("weight", kept)
                set("updatedAt", now)
                save()
            }
        } else {
            userVertex.modify().newEdge("FOCUSES_ON", target, false).apply {
                set("zoom", resolvedZoom.wire())
                set("mode", mode.wire())
                set("setCycleSeq", cycleSeq)
                set("weight", if (mode == FocusMode.PINNED) 1.0 else clamped)
                set("updatedAt", now)
                save()
            }
        }
        true
    }

    override suspend fun pruneNudges(userEmail: String, olderThanCycle: Long): Int = mutate("pruneNudges", 0) {
        val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@mutate 0
        val stale = userVertex.getEdges(Vertex.DIRECTION.OUT, "FOCUSES_ON").filter { edge ->
            edge.get("mode") == FocusMode.NUDGE.wire() && ((edge.get("setCycleSeq") as? Number)?.toLong() ?: 0L) < olderThanCycle
        }
        stale.forEach { it.delete() }
        stale.size
    }

    override suspend fun releaseFocus(userEmail: String, targetUid: String?, includeNudges: Boolean): Int =
        mutate("releaseFocus", 0) {
            val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@mutate 0
            val doomed = userVertex.getEdges(Vertex.DIRECTION.OUT, "FOCUSES_ON").filter { edge ->
                if (targetUid != null) {
                    edge.getVertex(Vertex.DIRECTION.IN)?.get("uid") == targetUid
                } else {
                    edge.get("mode") == FocusMode.PINNED.wire() || includeNudges
                }
            }
            doomed.forEach { it.delete() }
            doomed.size
        }

    override suspend fun touchLastActive(userEmail: String, atMillis: Long): Boolean =
        mutate("touchLastActive", false) {
            val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@mutate false
            val current = (userVertex.get("lastActiveAt") as? Number)?.toLong()
            if (current == null || atMillis > current) {
                userVertex.modify().apply { set("lastActiveAt", atMillis); save() }
            }
            true
        }

    // ── Reads ────────────────────────────────────────────────────────────────

    override suspend fun lastActiveAt(userEmail: String): Long? = read("lastActiveAt", null) {
        (HorizonOwnership.findUserVertex(db, userEmail)?.get("lastActiveAt") as? Number)?.toLong()
    }

    override suspend fun getFocus(userEmail: String): List<FocusRecord> = read("getFocus", emptyList()) {
        val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@read emptyList()
        userVertex.getEdges(Vertex.DIRECTION.OUT, "FOCUSES_ON").mapNotNull { edge ->
            val target = edge.getVertex(Vertex.DIRECTION.IN) ?: return@mapNotNull null
            FocusRecord(
                targetUid = target.get("uid") as? String ?: return@mapNotNull null,
                targetKind = target.focusTargetKind(),
                zoom = FocusZoom.fromWire(edge.get("zoom") as? String),
                mode = FocusMode.fromWire(edge.get("mode") as? String),
                setCycleSeq = (edge.get("setCycleSeq") as? Number)?.toLong() ?: 0L,
                weight = (edge.get("weight") as? Number)?.toDouble() ?: 1.0,
            )
        }.sortedWith(compareBy<FocusRecord> { it.mode != FocusMode.PINNED }.thenBy { it.setCycleSeq })
    }

    override suspend fun listContainers(userEmail: String, kind: ContainerKind?): List<ContainerRecord> =
        read("listContainers", emptyList()) {
            (kind?.let { listOf(it) } ?: ContainerKind.entries)
                .flatMap { k ->
                    db.query("sql", "SELECT FROM ${k.typeName} WHERE ownerEmail = :e", mapOf("e" to userEmail)).use { rs ->
                        val out = mutableListOf<Vertex>()
                        while (rs.hasNext()) out += rs.next().toElement().asVertex()
                        out
                    }
                }
                .map { it.toContainerRecord() }
                .distinctBy { it.uid } // belt and braces: an index lookup has returned one row several times in this ArcadeDB version
                .sortedBy { it.createdAt }
        }

    override suspend fun getContainer(userEmail: String, uid: String): ContainerRecord? = read("getContainer", null) {
        (findOwnedContainer(userEmail, uid, ContainerKind.PROJECT) ?: findOwnedContainer(userEmail, uid, ContainerKind.DOMAIN))
            ?.toContainerRecord()
    }

    override suspend fun getItem(userEmail: String, uid: String): ItemRecord? = read("getItem", null) {
        HorizonOwnership.findPhraseOwnedByUser(db, userEmail, uid)?.toItemRecord()
    }

    override suspend fun itemByOpKey(userEmail: String, opKey: String): ItemRecord? = read("itemByOpKey", null) {
        findOwnedItemByOpKey(userEmail, "$userEmail|$opKey")?.toItemRecord()
    }

    override suspend fun itemsIn(userEmail: String, projectUid: String): List<ItemRecord> = read("itemsIn", emptyList()) {
        val project = findOwnedContainer(userEmail, projectUid, ContainerKind.PROJECT) ?: return@read emptyList()
        project.getVertices(Vertex.DIRECTION.IN, "PART_OF")
            .filter { it.typeName != ContainerKind.PROJECT.typeName }
            // Belt and braces: the PART_OF edge was ownership-checked when written, but a member
            // is only ever surfaced if it is still reachable through the caller's own Sources.
            .filter { member ->
                val uid = member.get("uid") as? String
                uid != null && HorizonOwnership.ownedAssertsEdges(db, userEmail, uid).isNotEmpty()
            }
            .map { it.toItemRecord() }
            .sortedBy { it.createdAt }
    }

    override suspend fun projectsIn(userEmail: String, domainUid: String): List<ContainerRecord> =
        read("projectsIn", emptyList()) {
            val domain = findOwnedContainer(userEmail, domainUid, ContainerKind.DOMAIN) ?: return@read emptyList()
            domain.getVertices(Vertex.DIRECTION.IN, "PART_OF")
                .filter { it.typeName == ContainerKind.PROJECT.typeName && it.get("ownerEmail") == userEmail }
                .map { it.toContainerRecord() }
                .sortedBy { it.createdAt }
        }

    override suspend fun itemsAbout(userEmail: String, conceptName: String, limit: Int): List<ItemRecord> =
        read("itemsAbout", emptyList()) {
            if (limit <= 0 || conceptName.isBlank()) return@read emptyList()
            // Concepts are shared between users, so the edge's own ownerEmail scopes this to the caller before any LIMIT,
            // and each item is still checked to be reachable through the caller's own Sources before it is returned.
            val uids = db.query(
                "sql", "SELECT @out.uid AS uid FROM ABOUT WHERE ownerEmail = :e AND @in.normalizedName = :n LIMIT :l",
                mapOf("e" to userEmail, "n" to normalizeName(conceptName), "l" to limit * 2),
            ).use { rs ->
                val out = mutableListOf<String>()
                while (rs.hasNext()) (rs.next().toMap()["uid"] as? String)?.let { out += it }
                out
            }
            // Established document claims stay out of conversation until the Horizon can present them as the source's, not the user's.
            uids.distinct().mapNotNull { uid -> HorizonOwnership.findPhraseOwnedByUser(db, userEmail, uid)?.takeIf { it.get("claimSourceUid") == null }?.toItemRecord() }
                .sortedByDescending { it.createdAt }.take(limit)
        }

    override suspend fun listRecentItems(userEmail: String, limit: Int, openOnly: Boolean): List<ItemRecord> =
        read("listRecentItems", emptyList()) {
            if (limit <= 0) return@read emptyList()
            val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@read emptyList()
            // Only the conversational Sources: Actor and environment evidence are not things the user said. The owner's Claude session
            // writes as the owner, so what it recorded is as visible here as what they typed. What a channel filed from a document is
            // listed too (so it can be named by a ref and adopted or corrected), but it stays marked [ItemRecord.channelSourced].
            val sourceUids = userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS")
                .filter { it.get("type") == DatabaseEngramClient.SOURCE_TYPE || it.get("type") == CLAUDE_SESSION_SOURCE_TYPE || it.get("type") == CHANNEL_SOURCED_SOURCE_TYPE }
                .mapNotNull { it.get("uid") as? String }
            if (sourceUids.isEmpty()) return@read emptyList()
            val sql = if (openOnly) {
                // Open means the item's own coarse status, not a copy on the assertion.
                "SELECT @in.uid AS uid FROM ASSERTS WHERE @out.uid IN :sourceUids AND @in.@type IN ['Task', 'Question'] AND @in.status = 'open' ORDER BY timestamp DESC LIMIT :limit"
            } else {
                "SELECT @in.uid AS uid FROM ASSERTS WHERE @out.uid IN :sourceUids ORDER BY timestamp DESC LIMIT :limit"
            }
            val uids = db.query("sql", sql, mapOf("sourceUids" to sourceUids, "limit" to limit)).use { rs ->
                val out = mutableListOf<String>()
                while (rs.hasNext()) (rs.next().toMap()["uid"] as? String)?.let { out += it }
                out
            }
            uids.distinct().mapNotNull { uid ->
                db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to uid))
                    .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
                    ?.toItemRecord()
            }
        }

    override suspend fun scanItemTexts(userEmail: String, limit: Int): List<ItemText> =
        read("scanItemTexts", emptyList()) {
            if (limit <= 0) return@read emptyList()
            val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@read emptyList()
            // Everything the user's own Sources assert — what they said, and also what Hermes or the environment
            // reported on their behalf, since a question about a report should find it (the assembler frames those
            // by provenance when they surface). Ownership is the TRUSTS edge, as everywhere else.
            val sourceUids = userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS")
                .mapNotNull { it.get("uid") as? String }
            if (sourceUids.isEmpty()) return@read emptyList()
            db.query(
                "sql",
                "SELECT @in.uid AS uid, @in.text AS text FROM ASSERTS WHERE @out.uid IN :sourceUids ORDER BY timestamp DESC LIMIT :limit",
                mapOf("sourceUids" to sourceUids, "limit" to limit),
            ).use { rs ->
                val out = LinkedHashMap<String, String>()
                while (rs.hasNext()) {
                    val row = rs.next().toMap()
                    val uid = row["uid"] as? String ?: continue
                    val text = row["text"] as? String ?: continue
                    out.putIfAbsent(uid, text)
                }
                out.map { (uid, text) -> ItemText(uid, text) }
            }
        }

    // ── Plumbing ─────────────────────────────────────────────────────────────


    /** Runs [body] in one transaction holding the Horizon write lock; [failure] if the lock can't be had or anything throws. */
    private suspend fun <T> mutate(op: String, failure: T, body: () -> T): T = withContext(Dispatchers.IO) {
        try {
            withHorizonWriteLock(db, lockTimeoutMs) {
                var result: T = failure
                db.transaction { result = body() }
                result
            } ?: run {
                logger.warn("$op: could not acquire the Horizon write lock within ${lockTimeoutMs}ms — rejecting")
                failure
            }
        } catch (e: Exception) {
            logger.warn("$op failed: ${e.message}")
            failure
        }
    }

    private suspend fun <T> read(op: String, failure: T, body: () -> T): T = withContext(Dispatchers.IO) {
        try {
            body()
        } catch (e: Exception) {
            logger.warn("$op failed: ${e.message}")
            failure
        }
    }

    private fun newPartOf(child: Vertex, parent: Vertex, cycleSeq: Long, ownerEmail: String) {
        child.modify().newEdge("PART_OF", parent, true).apply {
            set("createdAt", System.currentTimeMillis())
            set("cycleSeq", cycleSeq)
            set("ownerEmail", ownerEmail)
            save()
        }
    }

    private fun findContainerByName(userEmail: String, kind: ContainerKind, normalizedName: String): Vertex? = db.query(
        "sql",
        "SELECT FROM ${kind.typeName} WHERE ownerEmail = :e AND normalizedName = :n",
        mapOf("e" to userEmail, "n" to normalizedName),
    ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    private fun findOwnedContainer(userEmail: String, uid: String, kind: ContainerKind): Vertex? = db.query(
        "sql",
        "SELECT FROM ${kind.typeName} WHERE uid = :u AND ownerEmail = :e",
        mapOf("u" to uid, "e" to userEmail),
    ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    private fun findOwnedItemByOpKey(userEmail: String, namespacedKey: String): Vertex? {
        val uid = db.query("sql", "SELECT uid FROM Phrase WHERE opKey = :k", mapOf("k" to namespacedKey))
            .use { rs -> if (rs.hasNext()) rs.next().toMap()["uid"] as? String else null } ?: return null
        return HorizonOwnership.findPhraseOwnedByUser(db, userEmail, uid)
    }

    /** A domain, a project or an item — whichever [uid] names, provided [userEmail] owns it. */
    private fun resolveOwnedVertex(userEmail: String, uid: String): Vertex? =
        findOwnedContainer(userEmail, uid, ContainerKind.PROJECT)
            ?: findOwnedContainer(userEmail, uid, ContainerKind.DOMAIN)
            ?: HorizonOwnership.findPhraseOwnedByUser(db, userEmail, uid)

    /** Something that can be filed under a parent: an owned item or an owned project (never a domain). */
    private fun resolveFilable(userEmail: String, uid: String): Vertex? =
        findOwnedContainer(userEmail, uid, ContainerKind.PROJECT)
            ?: HorizonOwnership.findPhraseOwnedByUser(db, userEmail, uid)

    private fun findOrCreateConversationSource(userVertex: Vertex, userEmail: String, sourceType: String = DatabaseEngramClient.SOURCE_TYPE): Vertex {
        val sourceName = "$sourceType:$userEmail"
        val existing = userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS")
            .firstOrNull { it.get("name") == sourceName && it.get("type") == sourceType }
        if (existing != null) return existing.modify()
        val source = db.newVertex("Source").apply {
            set("uid", UUID.randomUUID().toString())
            set("name", sourceName)
            set("type", sourceType)
            set("metadata", """{"userEmail":"$userEmail","createdAt":${System.currentTimeMillis()}}""")
            save()
        }
        userVertex.modify().newEdge("TRUSTS", source, false).apply {
            set("scores", "[]")
            save()
        }
        return source
    }

    // Only a Concept made in conversation: a document's Concepts (with a conceptKey: name, kind and parent) are kept apart and met at read
    // time (db/ConceptStore.kt), never merged into by a chat mention of the same name.
    private fun findOrCreateConcept(name: String, normalizedName: String): Vertex =
        db.query("sql", "SELECT FROM Concept WHERE normalizedName = :n AND conceptKey IS NULL", mapOf("n" to normalizedName))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
            ?: db.newVertex("Concept").apply {
                set("uid", UUID.randomUUID().toString())
                set("name", name)
                set("type", "topic")
                set("normalizedName", normalizedName)
                save()
            }

    private fun Vertex.toContainerRecord(): ContainerRecord {
        val kind = ContainerKind.entries.first { it.typeName == typeName }
        val parentUid = if (kind == ContainerKind.PROJECT) {
            getVertices(Vertex.DIRECTION.OUT, "PART_OF")
                .firstOrNull { it.typeName == ContainerKind.DOMAIN.typeName }?.get("uid") as? String
        } else null
        return ContainerRecord(
            uid = get("uid") as String,
            kind = kind,
            name = get("name") as? String ?: "",
            normalizedName = get("normalizedName") as? String ?: "",
            status = get("status") as? String ?: "active",
            dueAt = (get("dueAt") as? Number)?.toLong(),
            parentUid = parentUid,
            createdAt = (get("createdAt") as? Number)?.toLong() ?: 0L,
            attention = attentionOf(),
            tentative = get("tentative") == true,
        )
    }

    private fun Vertex.toItemRecord(): ItemRecord {
        val kind = ItemKind.entries.firstOrNull { it.typeName == typeName } ?: ItemKind.FACT
        return ItemRecord(
            uid = get("uid") as String,
            kind = kind,
            text = get("text") as? String ?: "",
            status = if (kind == ItemKind.FACT) null else get("status") as? String,
            dueAt = (get(if (kind == ItemKind.EVENT) "occursAt" else "dueAt") as? Number)?.toLong(),
            state = if (kind == ItemKind.FACT) null else WorkState.fromWire(get("state") as? String),
            dueKind = DueKind.fromWire(get("dueKind") as? String),
            dueHasTime = get("dueHasTime") == true,
            startsAt = (get("startsAt") as? Number)?.toLong(),
            startsHasTime = get("startsHasTime") == true,
            nudgeAt = (get("nudgeAt") as? Number)?.toLong(),
            nudgeHasTime = get("nudgeHasTime") == true,
            happenedAt = (get("happenedAt") as? Number)?.toLong(),
            happenedHasTime = get("happenedHasTime") == true,
            waitingOn = get("waitingOn") as? String,
            stateFromSource = get("stateFromSource") == true,
            lastEvidenceAt = (get("lastEvidenceAt") as? Number)?.toLong(),
            updatedAt = (get("updatedAt") as? Number)?.toLong() ?: 0L,
            projectUid = getVertices(Vertex.DIRECTION.OUT, "PART_OF")
                .firstOrNull { it.typeName == ContainerKind.PROJECT.typeName }?.get("uid") as? String,
            createdAt = (get("createdAt") as? Number)?.toLong() ?: 0L,
            about = getVertices(Vertex.DIRECTION.OUT, "ABOUT").mapNotNull { it.get("name") as? String },
            attention = attentionOf(),
            sourceQuote = get("sourceQuote") as? String,
            askedBy = if (kind == ItemKind.QUESTION) get("askedBy") as? String else null,
            // SQL, not getVertices(IN, "ASSERTS"): ASSERTS edges are unidirectional, so incoming traversal is silently empty (see HorizonOwnership).
            channelSourced = db.query("sql", "SELECT count(*) AS n FROM ASSERTS WHERE @in.uid = :u AND @out.type = :t", mapOf("u" to (get("uid") as String), "t" to CHANNEL_SOURCED_SOURCE_TYPE))
                .use { rs -> rs.hasNext() && (rs.next().getProperty<Number>("n")?.toLong() ?: 0L) > 0 },
        )
    }

    private fun Vertex.attentionOf(): AttentionDirective? =
        AttentionDirective.entries.firstOrNull { it.name.equals(get("attention") as? String, ignoreCase = true) }

    private fun Vertex.focusTargetKind(): FocusTargetKind = when (typeName) {
        ContainerKind.DOMAIN.typeName -> FocusTargetKind.DOMAIN
        ContainerKind.PROJECT.typeName -> FocusTargetKind.PROJECT
        else -> FocusTargetKind.ITEM
    }

    private fun normalizeName(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.trim().lowercase().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
