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
)

data class FocusRecord(
    val targetUid: String,
    val targetKind: FocusTargetKind,
    val zoom: FocusZoom,
    val mode: FocusMode,
    val setCycleSeq: Long,
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
    ): ItemRecord?

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
    suspend fun setDue(userEmail: String, cycleSeq: Long, targetUid: String, dueAt: Long?): Boolean

    /**
     * Marks a task, event or question `resolved` (or `open` again when [resolved] is false), or a
     * project `done`/`active`. False for any other target or one the user doesn't own. Records
     * `resolvedAt` when resolving; a no-op if it is already in the requested state. For a task or
     * question the legacy `ASSERTS.status` the current Horizon reads is kept in step (see [createItem]).
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
     * the target's kind. False for a target the user doesn't own.
     */
    suspend fun setFocus(userEmail: String, cycleSeq: Long, targetUid: String, mode: FocusMode, zoom: FocusZoom? = null): Boolean

    /**
     * Removes focus edges: those to [targetUid] when given, otherwise every pinned focus — and
     * every nudge too when [includeNudges]. Returns how many were removed.
     */
    suspend fun releaseFocus(userEmail: String, targetUid: String? = null, includeNudges: Boolean = true): Int

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

    /** The items filed directly under project [projectUid] (never nested projects), oldest first. Empty for a project the user doesn't own. */
    suspend fun itemsIn(userEmail: String, projectUid: String): List<ItemRecord>

    /** The projects filed directly under domain [domainUid], oldest first. Empty for a domain the user doesn't own. */
    suspend fun projectsIn(userEmail: String, domainUid: String): List<ContainerRecord>

    /**
     * The user's most recently asserted conversational items, newest first, capped at [limit] —
     * or, when [openOnly], only those still open (a task or question not yet resolved). Used to
     * show the interpretation model what it can refer to; a bounded read, never the whole graph.
     */
    suspend fun listRecentItems(userEmail: String, limit: Int, openOnly: Boolean = false): List<ItemRecord>
}

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
    ): ItemRecord? {
        if (userEmail.isBlank() || text.isBlank()) return null
        return mutate("createItem", null) {
            val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: run {
                logger.warn("createItem: no User vertex for email=$userEmail — skipping")
                return@mutate null
            }
            val namespacedKey = opKey?.let { "$userEmail|$it" }
            if (namespacedKey != null) {
                findOwnedItemByOpKey(userEmail, namespacedKey)?.let { return@mutate it.toItemRecord() }
            }
            val project = if (projectUid != null) {
                findOwnedContainer(userEmail, projectUid, ContainerKind.PROJECT) ?: run {
                    logger.warn("createItem: projectUid=$projectUid is not a project owned by userEmail=$userEmail — rejecting")
                    return@mutate null
                }
            } else null
            val source = findOrCreateConversationSource(userVertex, userEmail)
            val now = System.currentTimeMillis()
            val vertex = db.newVertex(kind.typeName).apply {
                set("uid", UUID.randomUUID().toString())
                set("text", text.trim())
                set("hash", sha256(text))
                set("visibility", "private")
                set("createdAt", now)
                set("updatedAt", now)
                if (namespacedKey != null) set("opKey", namespacedKey)
                if (!sourceQuote.isNullOrBlank() && sourceQuote.trim() != text.trim()) set("sourceQuote", sourceQuote.trim())
                when (kind) {
                    ItemKind.TASK -> {
                        set("status", "open")
                        if (dueAt != null) set("dueAt", dueAt)
                    }
                    ItemKind.EVENT -> {
                        set("status", "open")
                        if (dueAt != null) set("occursAt", dueAt)
                    }
                    ItemKind.QUESTION -> {
                        set("status", "open")
                        set("askedBy", askedBy)
                    }
                    ItemKind.FACT -> Unit
                }
                save()
            }
            source.newEdge("ASSERTS", vertex, false).apply {
                set("context", DatabaseEngramClient.SOURCE_TYPE)
                set("timestamp", now)
                set("scores", """[{"type":"trust","perspective":"user","value":${DatabaseEngramClient.TRUST_SCORE}}]""")
                set("cycleSeq", cycleSeq)
                // Legacy dual-write, removed with the legacy Horizon pools: today's assembler decides
                // "open" from ASSERTS.status, so a task or question must carry it or it would vanish
                // from the Horizon until the selector replaces those pools.
                if (kind == ItemKind.TASK || kind == ItemKind.QUESTION) applyLegacyStatus(this, "open", cycleSeq, now)
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

    override suspend fun setDue(userEmail: String, cycleSeq: Long, targetUid: String, dueAt: Long?): Boolean =
        mutate("setDue", false) {
            val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
            val property = when (target.typeName) {
                "Task", "Project" -> "dueAt"
                "Event" -> "occursAt"
                else -> return@mutate false
            }
            val current = (target.get(property) as? Number)?.toLong()
            if (current == dueAt) return@mutate true
            target.modify().apply {
                if (dueAt == null) remove(property) else set(property, dueAt)
                set("updatedAt", System.currentTimeMillis())
                save()
            }
            true
        }

    override suspend fun resolve(userEmail: String, cycleSeq: Long, targetUid: String, resolved: Boolean): Boolean =
        mutate("resolve", false) {
            val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
            val (doneValue, undoneValue) = when (target.typeName) {
                "Task", "Event", "Question" -> "resolved" to "open"
                "Project" -> "done" to "active"
                else -> return@mutate false
            }
            val desired = if (resolved) doneValue else undoneValue
            if (target.get("status") == desired) return@mutate true
            val now = System.currentTimeMillis()
            target.modify().apply {
                set("status", desired)
                if (target.typeName != "Project") {
                    if (resolved) set("resolvedAt", now) else remove("resolvedAt")
                }
                set("updatedAt", now)
                save()
            }
            if (target.typeName == "Task" || target.typeName == "Question") {
                // Keep the legacy ASSERTS.status the current Horizon reads in step — see createItem.
                HorizonOwnership.ownedAssertsEdges(db, userEmail, targetUid).forEach { edge ->
                    val mutable = edge.modify()
                    applyLegacyStatus(mutable, if (resolved) "resolved" else "open", cycleSeq, now)
                    mutable.save()
                }
            }
            true
        }

    override suspend fun setAttention(userEmail: String, cycleSeq: Long, targetUid: String, attention: AttentionDirective?): Boolean =
        mutate("setAttention", false) {
            val target = resolveOwnedVertex(userEmail, targetUid) ?: return@mutate false
            val desired = attention?.name?.lowercase()
            if (target.get("attention") as? String == desired) return@mutate true
            target.modify().apply {
                if (desired == null) remove("attention") else set("attention", desired)
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

        if (existing != null) {
            existing.modify().apply {
                set("zoom", resolvedZoom.wire())
                set("mode", mode.wire())
                set("setCycleSeq", cycleSeq)
                set("updatedAt", now)
                save()
            }
        } else {
            userVertex.modify().newEdge("FOCUSES_ON", target, false).apply {
                set("zoom", resolvedZoom.wire())
                set("mode", mode.wire())
                set("setCycleSeq", cycleSeq)
                set("updatedAt", now)
                save()
            }
        }
        true
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
                .sortedBy { it.createdAt }
        }

    override suspend fun getContainer(userEmail: String, uid: String): ContainerRecord? = read("getContainer", null) {
        (findOwnedContainer(userEmail, uid, ContainerKind.PROJECT) ?: findOwnedContainer(userEmail, uid, ContainerKind.DOMAIN))
            ?.toContainerRecord()
    }

    override suspend fun getItem(userEmail: String, uid: String): ItemRecord? = read("getItem", null) {
        HorizonOwnership.findPhraseOwnedByUser(db, userEmail, uid)?.toItemRecord()
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

    override suspend fun listRecentItems(userEmail: String, limit: Int, openOnly: Boolean): List<ItemRecord> =
        read("listRecentItems", emptyList()) {
            if (limit <= 0) return@read emptyList()
            val userVertex = HorizonOwnership.findUserVertex(db, userEmail) ?: return@read emptyList()
            // Only the conversational Source: Actor and environment evidence are not things the user said.
            val sourceUids = userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS")
                .filter { it.get("type") == DatabaseEngramClient.SOURCE_TYPE }
                .mapNotNull { it.get("uid") as? String }
            if (sourceUids.isEmpty()) return@read emptyList()
            val sql = if (openOnly) {
                "SELECT @in.uid AS uid FROM ASSERTS WHERE @out.uid IN :sourceUids AND status = 'open' ORDER BY timestamp DESC LIMIT :limit"
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

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /**
     * Sets the legacy `status`/`statusHistory`/`statusCycleSeq` on an ASSERTS edge — the exact shape
     * [ArcadeHorizonGraphStore.markAssertionStatus] writes — so today's Horizon assembler, which
     * reads open/resolved from there, sees a task or question the way it saw an intention.
     */
    private fun applyLegacyStatus(edge: com.arcadedb.graph.MutableEdge, state: String, cycleSeq: Long, now: Long) {
        val history = legacyHistory(edge.get("statusHistory") as? String) + StatusHistoryEntry(state, now)
        edge.set("status", state)
        edge.set("statusHistory", legacyJson.encodeToString(history))
        edge.set("statusCycleSeq", cycleSeq)
    }

    private val legacyJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun legacyHistory(raw: String?): List<StatusHistoryEntry> = try {
        if (raw.isNullOrBlank()) emptyList() else legacyJson.decodeFromString(raw)
    } catch (_: Exception) {
        emptyList()
    }

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

    private fun findOrCreateConversationSource(userVertex: Vertex, userEmail: String): Vertex {
        val sourceName = "${DatabaseEngramClient.SOURCE_TYPE}:$userEmail"
        val existing = userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS")
            .firstOrNull { it.get("name") == sourceName && it.get("type") == DatabaseEngramClient.SOURCE_TYPE }
        if (existing != null) return existing.modify()
        val source = db.newVertex("Source").apply {
            set("uid", UUID.randomUUID().toString())
            set("name", sourceName)
            set("type", DatabaseEngramClient.SOURCE_TYPE)
            set("metadata", """{"userEmail":"$userEmail","createdAt":${System.currentTimeMillis()}}""")
            save()
        }
        userVertex.modify().newEdge("TRUSTS", source, false).apply {
            set("scores", "[]")
            save()
        }
        return source
    }

    private fun findOrCreateConcept(name: String, normalizedName: String): Vertex =
        db.query("sql", "SELECT FROM Concept WHERE normalizedName = :n", mapOf("n" to normalizedName))
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
            projectUid = getVertices(Vertex.DIRECTION.OUT, "PART_OF")
                .firstOrNull { it.typeName == ContainerKind.PROJECT.typeName }?.get("uid") as? String,
            createdAt = (get("createdAt") as? Number)?.toLong() ?: 0L,
            about = getVertices(Vertex.DIRECTION.OUT, "ABOUT").mapNotNull { it.get("name") as? String },
            attention = attentionOf(),
            sourceQuote = get("sourceQuote") as? String,
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
