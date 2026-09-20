package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import com.arcadedb.graph.Edge
import com.arcadedb.graph.Vertex
import org.slf4j.LoggerFactory
import java.util.PriorityQueue

/**
 * Decides, from the graph and the clock, which structured items deserve a place in view and why.
 * Nothing it computes is stored: it is re-derived every turn, so it is always current and every
 * result carries the components it came from ([ItemActivation.components]).
 *
 * Activation comes from independent sources, each spreading a bounded way along `PART_OF`,
 * `ABOUT` and `relevant_to` edges and losing strength at every step (see [HorizonPolicy]):
 * - **the pinned focus** — what the user told Alfrd to work on — reaching as far as its zoom allows;
 * - **nudges** — things merely mentioned or touched, which fade with the turns since;
 * - **what was just said**, spreading a little to its neighbors;
 * - **deadlines**, computed from the wall clock now (a stored score would already be stale), which
 *   break through whatever the focus is;
 * - **an explicit "keep this in front of me"**.
 * Contributions to one item are combined so each adds but none exceeds 1 ([HorizonPolicy.combine]).
 * Items the user resolved, told Alfrd to stop bringing up, or corrected away are excluded by
 * [loadStates] callers; nothing here surfaces them on its own.
 *
 * Runs inside [ArcadeHorizonAssembler.assemble]'s read lock and transaction — it never takes a lock
 * or opens a transaction itself. The number of nodes expanded, rows scanned and candidates returned
 * is bounded by [HorizonPolicy], never by graph size.
 */
internal class HorizonSelector(
    private val db: Database,
    private val policy: HorizonPolicy,
) {
    private val logger = LoggerFactory.getLogger(HorizonSelector::class.java)

    /** One item's place in the selection: how strongly, why, and from what. */
    data class ItemActivation(
        val uid: String,
        val score: Double,
        /** Strongest first. */
        val components: List<ScoreComponent>,
        /** Steps from whatever first drew it in (0 for the thing itself). */
        val hops: Int,
        /** What drew it in, in words ("in Demo", "mentioned", "about sister"). */
        val via: String,
        val dueAt: Long?,
        val overdue: Boolean,
    )

    /** What a candidate item is, read from the graph — used to exclude the resolved, suppressed and superseded, and to describe the rest. */
    data class ItemState(
        val uid: String,
        val kind: String,
        val text: String,
        /** The item's own vertex status (`open`/`resolved`); null for a plain fact. */
        val status: String?,
        val dueAt: Long?,
        val attention: AttentionDirective?,
        val askedBy: String?,
        val container: HorizonContainerRef?,
        val containerStatus: String?,
        val containerAttention: AttentionDirective?,
        val superseded: Boolean,
    )

    data class Selection(
        val activations: Map<String, ItemActivation>,
        val focus: HorizonFocusRef?,
        val zoom: FocusZoom,
        /** Every live container of the user's, most relevant first, bounded; `itemsInView` is left 0 for the assembler to fill from what it keeps. */
        val containers: List<HorizonContainerView>,
        /** True if a bound (expansions, scan limit) was hit, so more may exist than was looked at. */
        val truncated: Boolean,
    )

    private class Reach(val vertex: Vertex, val uid: String, val activation: Double, val hops: Int, val via: String)

    private data class Seed(val vertex: Vertex, val activation: Double, val label: String)

    private data class Dated(val vertex: Vertex, val uid: String, val dueAt: Long, val urgency: Double, val overdue: Boolean)

    fun select(userEmail: String, currentCycleSeq: Long, nowMillis: Long, freshUids: Collection<String>): Selection {
        var truncated = false
        val userVertex = HorizonOwnership.findUserVertex(db, userEmail)
            ?: return Selection(emptyMap(), null, FocusZoom.OVERVIEW, emptyList(), false)

        val components = HashMap<String, MutableList<ScoreComponent>>()
        val hopsByUid = HashMap<String, Int>()
        val viaByUid = HashMap<String, String>()
        val itemUids = HashSet<String>()
        val containerReaches = HashMap<String, MutableList<Double>>()
        val containerVertices = HashMap<String, Vertex>()
        fun addItem(uid: String, kind: String, value: Double, detail: String?, hops: Int) {
            if (value <= 0.0) return
            components.getOrPut(uid) { mutableListOf() } += ScoreComponent(kind, value, detail)
            itemUids += uid
            if (hops < (hopsByUid[uid] ?: Int.MAX_VALUE)) {
                hopsByUid[uid] = hops
                detail?.let { viaByUid[uid] = it }
            }
        }
        fun addReaches(kindAtSeed: String, kindAway: String, reaches: Collection<Reach>) {
            for (r in reaches) {
                if (r.vertex.isContainer()) {
                    containerReaches.getOrPut(r.uid) { mutableListOf() } += r.activation
                    containerVertices[r.uid] = r.vertex
                } else if (r.vertex.isItem()) {
                    addItem(r.uid, if (r.hops == 0) kindAtSeed else kindAway, r.activation, r.via, r.hops)
                }
            }
        }

        // ── Focus and nudges ─────────────────────────────────────────────────
        var focusRef: HorizonFocusRef? = null
        var zoom = FocusZoom.OVERVIEW
        val nudgeSeeds = mutableListOf<Seed>()
        val focusSeeds = mutableListOf<Seed>()
        for (edge in userVertex.getEdges(Vertex.DIRECTION.OUT, "FOCUSES_ON")) {
            val target = edge.getVertex(Vertex.DIRECTION.IN) ?: continue
            if (!target.ownedBy(userEmail)) continue
            val mode = edge.get("mode") as? String
            val weight = (edge.get("weight") as? Number)?.toDouble() ?: 1.0
            if (mode == "pinned") {
                zoom = FocusZoom.fromWire(edge.get("zoom") as? String)
                focusSeeds += Seed(target, policy.pinnedFocusActivation, target.labelOf())
                focusRef = HorizonFocusRef(target.get("uid") as String, target.labelOf(), target.focusKind(), zoom)
            } else {
                val age = currentCycleSeq - ((edge.get("setCycleSeq") as? Number)?.toLong() ?: 0L)
                val strength = policy.nudgeStrength(weight, age)
                if (strength > 0.0) nudgeSeeds += Seed(target, strength, "mentioned")
            }
        }

        // Things the user asked to keep in front of them: seeds like a nudge that never fades.
        val attention = attentionSets(userEmail)
        for (uid in attention.pinned) phraseVertex(uid)?.let { v ->
            addItem(uid, "pinned", policy.pinnedItemActivation, "kept in view at your request", 0)
            nudgeSeeds += Seed(v, policy.pinnedItemActivation * policy.about, "pinned")
        }
        for (v in attention.pinnedContainers) nudgeSeeds += Seed(v, policy.pinnedItemActivation, "pinned")

        if (focusSeeds.isNotEmpty()) {
            val (reaches, cut) = spread(focusSeeds, policy.hopsFor(zoom), userEmail, currentCycleSeq)
            truncated = truncated || cut
            addReaches("focus", "focus", reaches.values)
        }
        if (nudgeSeeds.isNotEmpty()) {
            val (reaches, cut) = spread(nudgeSeeds, policy.hopsSeed, userEmail, currentCycleSeq)
            truncated = truncated || cut
            addReaches("nudge", "spread", reaches.values.filter { it.uid !in attention.pinned || it.hops > 0 })
        }

        // ── What was just said, and what sits next to it ─────────────────────
        val freshSeeds = freshUids.mapNotNull { uid -> phraseVertex(uid)?.let { Seed(it, policy.justAssertedActivation, "near what you just said") } }
        if (freshSeeds.isNotEmpty()) {
            val (reaches, cut) = spread(freshSeeds, policy.hopsSeed, userEmail, currentCycleSeq)
            truncated = truncated || cut
            // The fresh items themselves are scored by the caller's own just-asserted pool; only what they reach is new here.
            addReaches("spread", "spread", reaches.values.filter { it.hops > 0 })
        }

        // ── Deadlines, from the clock now ────────────────────────────────────
        val dated = datedThings(userEmail, nowMillis)
        truncated = truncated || dated.truncated
        val dueByUid = HashMap<String, Pair<Long, Boolean>>()
        // Zoomed out, anything with a deadline worth a slot is shown. Zoomed in, only what is genuinely pressing
        // breaks through — a task due in ten days does not interrupt work on something else.
        val minUrgency = if (focusRef != null) policy.breakthroughUrgency else policy.urgencyFloor
        for (d in dated.items) {
            if (d.urgency < minUrgency) continue
            if (d.vertex.isContainer()) {
                containerReaches.getOrPut(d.uid) { mutableListOf() } += d.urgency
                containerVertices[d.uid] = d.vertex
                // An urgent project lends its members some of its urgency.
                val lent = d.urgency * policy.projectUrgencyToMembers
                for (member in d.vertex.getVertices(Vertex.DIRECTION.IN, "PART_OF").take(MEMBER_SCAN_CAP)) {
                    if (!member.isItem()) continue
                    (member.get("uid") as? String)?.let { addItem(it, "urgency", lent, "${d.vertex.labelOf()} is due soon", 1) }
                }
            } else {
                dueByUid[d.uid] = d.dueAt to d.overdue
                addItem(d.uid, "urgency", d.urgency, if (d.overdue) "overdue" else "due soon", 0)
            }
        }

        // ── Combine ──────────────────────────────────────────────────────────
        val activations = LinkedHashMap<String, ItemActivation>()
        for (uid in itemUids) {
            val parts = components.getValue(uid).sortedByDescending { it.value }
            val due = dueByUid[uid]
            activations[uid] = ItemActivation(
                uid = uid,
                score = HorizonPolicy.combine(parts.map { it.value }),
                components = parts,
                hops = hopsByUid[uid] ?: 0,
                via = viaByUid[uid] ?: "",
                dueAt = due?.first,
                overdue = due?.second ?: false,
            )
        }

        val containers = containerViews(userEmail, containerReaches, containerVertices, focusRef?.uid, attention.suppressedContainerUids)
        return Selection(activations, focusRef, zoom, containers, truncated)
    }

    // ── Spreading ────────────────────────────────────────────────────────────

    /** Best-first spread from [seeds]: each node keeps the strongest activation that reached it. Returns the reaches and whether the expansion cap cut it short. */
    private fun spread(seeds: List<Seed>, maxHops: Int, userEmail: String, currentCycleSeq: Long): Pair<Map<String, Reach>, Boolean> {
        val best = HashMap<String, Reach>()
        val queue = PriorityQueue<Reach>(compareByDescending { it.activation })
        for (seed in seeds) {
            val uid = seed.vertex.get("uid") as? String ?: continue
            val existing = best[uid]
            if (existing == null || seed.activation > existing.activation) {
                val reach = Reach(seed.vertex, uid, seed.activation, 0, seed.label)
                best[uid] = reach
                queue.add(reach)
            }
        }
        var expanded = 0
        var cut = false
        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (best[current.uid] !== current) continue // superseded by a stronger path
            if (current.hops >= maxHops) continue
            if (expanded >= policy.maxExpansions) { cut = true; break }
            expanded++
            for ((next, transmission, via) in neighbors(current, userEmail, currentCycleSeq)) {
                val nextUid = next.get("uid") as? String ?: continue
                val activation = policy.step(current.activation, transmission, current.hops)
                if (activation < policy.spreadFloor) continue
                val known = best[nextUid]
                if (known != null && known.activation >= activation) continue
                val reach = Reach(next, nextUid, activation, current.hops + 1, via ?: current.via)
                best[nextUid] = reach
                queue.add(reach)
            }
        }
        return best to cut
    }

    private data class Step(val vertex: Vertex, val transmission: Double, val via: String?)

    /** The nodes one step from [from], with how strongly each edge carries activation. Everything is scoped to the user before it is followed. */
    private fun neighbors(from: Reach, userEmail: String, currentCycleSeq: Long): List<Step> {
        val v = from.vertex
        val out = mutableListOf<Step>()
        when {
            v.typeName == "Domain" -> for (project in v.getVertices(Vertex.DIRECTION.IN, "PART_OF").take(MEMBER_SCAN_CAP)) {
                if (project.ownedBy(userEmail)) out += Step(project, policy.partOfDown, "in ${v.labelOf()}")
            }
            v.typeName == "Project" -> {
                for (member in v.getVertices(Vertex.DIRECTION.IN, "PART_OF").take(MEMBER_SCAN_CAP)) out += Step(member, policy.partOfDown, "in ${v.labelOf()}")
                for (domain in v.getVertices(Vertex.DIRECTION.OUT, "PART_OF")) if (domain.ownedBy(userEmail)) out += Step(domain, policy.partOfUp, null)
            }
            v.typeName == "Concept" -> for (edge in v.getEdges(Vertex.DIRECTION.IN, "ABOUT")) {
                if (edge.get("ownerEmail") != userEmail) continue // Concepts are shared: never follow another user's edge
                edge.getVertex(Vertex.DIRECTION.OUT)?.let { out += Step(it, policy.about, "about ${v.get("name") ?: "the same thing"}") }
            }
            else -> {
                for (project in v.getVertices(Vertex.DIRECTION.OUT, "PART_OF")) if (project.ownedBy(userEmail)) out += Step(project, policy.partOfUp, null)
                for (edge in v.getEdges(Vertex.DIRECTION.OUT, "ABOUT")) {
                    if (edge.get("ownerEmail") != userEmail) continue
                    edge.getVertex(Vertex.DIRECTION.IN)?.let { out += Step(it, policy.about, null) }
                }
                for (edge in v.getEdges(Vertex.DIRECTION.OUT, "RELATED_TO")) relevantStep(edge, Vertex.DIRECTION.IN, userEmail, currentCycleSeq)?.let { out += it }
                for (edge in v.getEdges(Vertex.DIRECTION.IN, "RELATED_TO")) relevantStep(edge, Vertex.DIRECTION.OUT, userEmail, currentCycleSeq)?.let { out += it }
            }
        }
        return out
    }

    private fun relevantStep(edge: Edge, otherEnd: Vertex.DIRECTION, userEmail: String, currentCycleSeq: Long): Step? {
        if (edge.get("relationType") != "relevant_to" || edge.get("ownerEmail") != userEmail) return null
        val edgeCycle = (edge.get("cycleSeq") as? Number)?.toLong() ?: return null
        if (edgeCycle > currentCycleSeq || currentCycleSeq - edgeCycle > DEFAULT_LINK_WINDOW) return null
        val strength = (edge.get("strength") as? Number)?.toDouble() ?: 0.0
        return edge.getVertex(otherEnd)?.let { Step(it, strength * policy.relevantTo, "linked to something recent") }
    }

    // ── Deadlines ────────────────────────────────────────────────────────────

    private data class DatedScan(val items: List<Dated>, val truncated: Boolean)

    /**
     * One user's open tasks, events and projects with a deadline in range: the user's open rows by the
     * owner-scoped `(ownerEmail, status)` index, the deadline filtered here. Not an indexed range on the
     * deadline — that index shape returns duplicates for undated rows in this ArcadeDB version (see
     * `SchemaBootstrap`) — and never ORDER BY over a range, which has returned duplicates and gaps too.
     * Rows are de-duplicated by uid regardless, and hitting the scan limit is reported, never silent.
     */
    private fun datedThings(userEmail: String, nowMillis: Long): DatedScan {
        val end = nowMillis + policy.urgencyLookaheadDays * 86_400_000L
        val graceStart = nowMillis - (policy.eventGraceHours * 3_600_000L).toLong()
        val found = LinkedHashMap<String, Dated>()
        var truncated = false
        fun scan(type: String, openStatus: String, halfDays: Double, event: Boolean, property: String) {
            var rows = 0
            db.query("sql", "SELECT FROM $type WHERE ownerEmail = :e AND status = :s LIMIT ${policy.urgencyScanLimit}", mapOf("e" to userEmail, "s" to openStatus)).use { rs ->
                while (rs.hasNext()) {
                    rows++
                    val vertex = rs.next().toElement().asVertex()
                    val uid = vertex.get("uid") as? String ?: continue
                    val dueAt = (vertex.get(property) as? Number)?.toLong() ?: continue
                    if (dueAt > end || (event && dueAt < graceStart)) continue
                    val urgency = policy.urgency(dueAt, nowMillis, halfDays, event)
                    if (urgency < policy.urgencyFloor) continue
                    found[uid] = Dated(vertex, uid, dueAt, urgency, overdue = dueAt < nowMillis && !event)
                }
            }
            if (rows >= policy.urgencyScanLimit) truncated = true
        }
        scan("Task", "open", policy.taskUrgencyHalfDays, false, "dueAt")
        scan("Event", "open", policy.eventUrgencyHalfDays, true, "occursAt")
        scan("Project", "active", policy.projectUrgencyHalfDays, false, "dueAt")
        return DatedScan(found.values.toList(), truncated)
    }

    // ── Attention ────────────────────────────────────────────────────────────

    private data class AttentionSets(
        val pinned: Set<String>,
        val suppressed: Set<String>,
        val pinnedContainers: List<Vertex>,
        val suppressedContainerUids: Set<String>,
    )

    /** What the user explicitly asked to keep in front of them or to stop hearing about — rare rows, found by the (attention, ownerEmail) index. */
    private fun attentionSets(userEmail: String): AttentionSets {
        fun uidsWith(value: String): Set<String> = db.query(
            "sql", "SELECT uid FROM Phrase WHERE attention = :a AND ownerEmail = :e LIMIT $ATTENTION_SCAN_CAP", mapOf("a" to value, "e" to userEmail),
        ).use { rs ->
            val out = LinkedHashSet<String>()
            while (rs.hasNext()) (rs.next().toMap()["uid"] as? String)?.let { out += it }
            out
        }
        fun containersWith(type: String, value: String): List<Vertex> = db.query(
            "sql", "SELECT FROM $type WHERE ownerEmail = :e AND attention = :a LIMIT $ATTENTION_SCAN_CAP", mapOf("a" to value, "e" to userEmail),
        ).use { rs ->
            val out = mutableListOf<Vertex>()
            while (rs.hasNext()) out += rs.next().toElement().asVertex()
            out
        }
        val pinnedContainers = containersWith("Project", "pinned") + containersWith("Domain", "pinned")
        val suppressedContainers = containersWith("Project", "suppressed") + containersWith("Domain", "suppressed")
        return AttentionSets(uidsWith("pinned"), uidsWith("suppressed"), pinnedContainers, suppressedContainers.mapNotNull { it.get("uid") as? String }.toSet())
    }

    // ── Reading candidates ───────────────────────────────────────────────────

    /**
     * What each of [uids] is, read from its own vertex: resolved, suppressed and superseded items are
     * marked so the caller can leave them out, and each item's project is named for grouping. Items
     * that don't resolve to a vertex are simply absent.
     */
    fun loadStates(uids: Collection<String>): Map<String, ItemState> {
        val out = LinkedHashMap<String, ItemState>()
        for (uid in uids) {
            val v = phraseVertex(uid) ?: continue
            val kind = when (v.typeName) { "Task" -> "task"; "Event" -> "event"; "Question" -> "question"; else -> "fact" }
            val project = v.getVertices(Vertex.DIRECTION.OUT, "PART_OF").firstOrNull { it.typeName == "Project" }
            val dueAt = (v.get(if (kind == "event") "occursAt" else "dueAt") as? Number)?.toLong()
            val superseded = v.getEdges(Vertex.DIRECTION.IN, "RELATED_TO").any { it.get("relationType") == "supersedes" }
            out[uid] = ItemState(
                uid = uid,
                kind = kind,
                text = v.get("text") as? String ?: "",
                status = if (kind == "fact") null else v.get("status") as? String,
                dueAt = dueAt,
                attention = attentionOf(v),
                askedBy = if (kind == "question") v.get("askedBy") as? String else null,
                container = project?.let { HorizonContainerRef(it.get("uid") as String, it.labelOf(), "project") },
                containerStatus = project?.get("status") as? String,
                containerAttention = project?.let { attentionOf(it) },
                superseded = superseded,
            )
        }
        return out
    }

    // ── Containers ───────────────────────────────────────────────────────────

    private fun containerViews(
        userEmail: String,
        reaches: Map<String, List<Double>>,
        vertices: Map<String, Vertex>,
        focusUid: String?,
        suppressed: Set<String>,
    ): List<HorizonContainerView> {
        val all = LinkedHashMap<String, Vertex>()
        for (type in listOf("Domain", "Project")) {
            db.query("sql", "SELECT FROM $type WHERE ownerEmail = :e LIMIT $CONTAINER_SCAN_CAP", mapOf("e" to userEmail)).use { rs ->
                while (rs.hasNext()) {
                    val v = rs.next().toElement().asVertex()
                    val uid = v.get("uid") as? String ?: continue
                    all[uid] = v
                }
            }
        }
        vertices.forEach { (uid, v) -> if (v.ownedBy(userEmail)) all.putIfAbsent(uid, v) }
        return all.values
            .filter { (it.get("status") as? String ?: "active") in setOf("active") && (it.get("uid") as? String) !in suppressed }
            .map { v ->
                val uid = v.get("uid") as String
                val members = if (v.typeName == "Project") v.getVertices(Vertex.DIRECTION.IN, "PART_OF").filter { it.isItem() }.take(MEMBER_SCAN_CAP) else emptyList()
                val open = members.filter { it.get("status") == "open" }
                val next = open.mapNotNull { (it.get(if (it.typeName == "Event") "occursAt" else "dueAt") as? Number)?.toLong() }.minOrNull()
                val projectDue = (v.get("dueAt") as? Number)?.toLong()
                HorizonContainerView(
                    uid = uid,
                    name = v.labelOf(),
                    kind = v.typeName.lowercase(),
                    openItems = open.size,
                    itemsInView = 0,
                    nextDueAt = listOfNotNull(next, projectDue).minOrNull(),
                    score = HorizonPolicy.combine(reaches[uid] ?: emptyList()),
                    inFocus = uid == focusUid,
                )
            }
            .sortedWith(compareByDescending<HorizonContainerView> { it.inFocus }.thenByDescending { it.score }.thenByDescending { it.openItems })
            .take(policy.maxContainersInView)
    }

    // ── Vertex helpers ───────────────────────────────────────────────────────

    private fun phraseVertex(uid: String): Vertex? =
        db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to uid)).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    private fun Vertex.isContainer() = typeName == "Domain" || typeName == "Project"
    private fun Vertex.isItem() = !isContainer() && typeName != "Concept" && typeName != "User" && typeName != "Source"

    /** A container is the user's if it says so; an item's ownership is proved by the assembler through its own Sources before it is surfaced. */
    private fun Vertex.ownedBy(userEmail: String): Boolean = if (isContainer()) get("ownerEmail") == userEmail else true

    private fun Vertex.labelOf(): String = when {
        isContainer() -> (get("name") as? String ?: "").take(HorizonLimits.MAX_CONTAINER_NAME_LENGTH)
        else -> (get("text") as? String ?: "").take(HorizonLimits.MAX_CONTAINER_NAME_LENGTH)
    }

    private fun Vertex.focusKind(): String = when (typeName) { "Domain" -> "domain"; "Project" -> "project"; else -> "item" }

    private fun attentionOf(v: Vertex): AttentionDirective? =
        AttentionDirective.entries.firstOrNull { it.name.equals(v.get("attention") as? String, ignoreCase = true) }

    private companion object {
        const val MEMBER_SCAN_CAP = 100
        const val ATTENTION_SCAN_CAP = 100
        const val CONTAINER_SCAN_CAP = 60
        /** How many cycles back a `relevant_to` link still carries activation when spreading — the same window the assembler's reactivation uses. */
        const val DEFAULT_LINK_WINDOW = HorizonLimits.DEFAULT_RELEVANCE_CYCLES.toLong()
    }
}
