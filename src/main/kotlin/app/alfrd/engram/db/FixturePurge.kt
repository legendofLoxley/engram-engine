package app.alfrd.engram.db

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.api.ProtectedIdentities
import app.alfrd.engram.cognitive.pipeline.horizon.ChangeRecords
import com.arcadedb.database.Database
import com.arcadedb.database.Document
import com.arcadedb.database.RID
import com.arcadedb.graph.Edge
import com.arcadedb.graph.Vertex
import com.arcadedb.schema.EdgeType
import com.arcadedb.schema.VertexType

/**
 * Offline removal of fixture users and everything they own, from a graph directory the engine does NOT have open.
 *
 * Why it exists: `DELETE /debug/converse/purge` takes no list, deletes every `@test.alfrd.internal` user that is not protected (so it would
 * take the `debug+pps-*` users), and removes only the User vertex and a few small records, leaving the user's Sources, Chunks, Phrases and
 * Utterances behind with nobody who can reach them. This tool takes an explicit list, refuses anything that is not a plain fixture user, and
 * removes the data too. It needs no route and no unit change: it opens the directory itself (ArcadeDB holds a lock while the engine runs, so
 * running it against a live graph fails at open, by design).
 *
 * Two steps, in this order: [validate] (refuses the whole run on any bad entry) and [plan] (read-only: exactly what would go, per user).
 * [apply] takes a plan and deletes, one user per transaction.
 *
 * What counts as the listed user's data (the set `L` is the whole list; "outside" means a user, owner or Source not in `L`):
 *  - **Source**: trusted (`TRUSTS`) only by users in `L` (or trusted by nobody and `ownerEmail` in `L`). A Source also trusted by anyone
 *    outside `L` is kept and reported. So is a Source that asserts a shared seed Phrase (an ownerless Phrase asserted by a Source trusted by
 *    more than one user: the audit's A3i), and a Source whose `ownerEmail` is outside `L`.
 *  - **Chunk**: owned by a user in `L` (or ownerless) and contained only by deleted Sources; a Chunk owned by `L` that sits in no Source is
 *    deleted too. A Chunk owned by `L` that sits in a kept Source is kept and reported.
 *  - **Phrase family** (Phrase, Task, Event, Question): `ownerEmail` in `L`, or ownerless and asserted only through Sources trusted only by `L`;
 *    and in both cases asserted only by deleted Sources (or by none). Anything also asserted from outside is kept and reported. A shared seed
 *    Phrase is never deleted. A row whose `ownerEmail` is outside `L` is never touched.
 *  - **By owner key**: Utterance (`userId`), ProcessedRequest (`userEmail`), EntityAlias, ExtractionRun, ClaimDisagreement, Domain, Project,
 *    ChangeRecord (`ownerEmail`), UserScaffoldState (`userId`, email or uid), and any edge stamped `ownerEmail`/`userId` in `L` that would
 *    otherwise survive (both ends kept).
 *  - **User**: the listed users themselves. Edges at a deleted vertex go with it (ArcadeDB deletes a vertex's edges), so SELECTED, OUTCOME,
 *    TRUSTS, ASSERTS, ABOUT, QUOTES, CONTAINS, FOLLOWS and HAS_CHANGE leave with their endpoints and nothing dangles.
 *  - **Never deleted**: a Concept (shared, no owner); a ResponsePhrase; any row whose owner stamp is outside `L`.
 *
 * Safety nets: a deleted vertex that has an edge to a kept row owned outside `L` is a *blocking boundary* and [apply] refuses the whole run;
 * so is any row of a type this tool does not know that carries an owner key in `L`; and each record is re-checked just before it is deleted
 * (a stamp outside `L` aborts that user's transaction).
 *
 * ChangeRecord: the engine vetoes deleting one in-process (a listener registered at its start; it is not persisted, so a tool that opens the
 * directory by itself never registers it). This tool still goes through [ChangeRecords.withDeleteVetoLifted], which lifts only the delete veto
 * for the span of one user's deletions, so the same code works where the listener is present (the tests register it). It deletes a record
 * only after checking that its `ownerEmail` is in `L`.
 */
object FixturePurge {
    const val DOMAIN = DebugConverseService.SYNTHETIC_EMAIL_DOMAIN
    private val FIXTURE_ADDRESS = Regex("^debug\\+[A-Za-z0-9_.-]+@test\\.alfrd\\.internal$")
    private const val PPS_PREFIX = "debug+pps-"
    private val OWNER_KEYS = listOf("ownerEmail", "userEmail", "userId")

    /** The whole run is refused: nothing was read past the checks, nothing was changed. */
    class Refusal(val problems: List<String>) : RuntimeException("refused: " + problems.joinToString("; "))

    /** Columns of the per-user table, in delete order. Edges are counted under `edge:<Type>`. */
    val COLUMNS: List<String> = listOf("ChangeRecord") +
        app.alfrd.engram.cognitive.pipeline.horizon.ItemKinds.all.map { it.typeName } +   // Phrase, Task, Event, Question, from the registry
        listOf("Chunk", "Email", "EmailAddress", "Source", "Utterance", "ProcessedRequest", "EntityAlias", "ExtractionRun", "ClaimDisagreement", "Project", "Domain", "UserScaffoldState", "User")
    private val HANDLED_VERTEX_TYPES = COLUMNS.toSet()
    private val OWNERLESS_TYPES = setOf("Concept", "ResponsePhrase", "ScoreType", "Scope")

    data class Kept(val type: String, val id: String, val user: String, val reason: String)
    data class Boundary(val user: String, val deleted: String, val edge: String, val other: String, val otherOwner: String?, val blocking: Boolean)

    class UserPlan(val email: String, val exists: Boolean) {
        val rids = LinkedHashMap<String, MutableList<RID>>()
        /** Edges that go with their endpoints, by type (informational; the delete itself is the vertex delete). */
        val cascadeEdges = LinkedHashMap<String, Int>()
        /** The edge records at this user's deleted vertices, found by scanning every edge type (not by walking a vertex: an edge made with bidirectional=false is invisible from its target). */
        val edgeRids = ArrayList<RID>()
        fun add(key: String, rid: RID) { rids.getOrPut(key) { ArrayList() } += rid }
        fun count(key: String): Int = rids[key]?.size ?: 0
        val isEmpty: Boolean get() = rids.isEmpty() && edgeRids.isEmpty()
    }

    class Plan(
        val users: Map<String, UserPlan>,
        val kept: List<Kept>,
        val boundary: List<Boundary>,
        val unhandled: List<String>,
        /** Concepts reached by an edge from a deleted vertex (uid -> name). Never deleted. */
        val touchedConcepts: Map<String, String>,
    ) {
        val blocking: List<Boundary> get() = boundary.filter { it.blocking }
        val totals: Map<String, Int> get() = LinkedHashMap<String, Int>().also { t ->
            users.values.forEach { u -> u.rids.forEach { (k, v) -> t.merge(k, v.size, Int::plus) } }
        }
        val totalRows: Int get() = totals.values.sum()

        fun render(): String = buildString {
            val cols = COLUMNS.filter { c -> users.values.any { it.count(c) > 0 } }
            val extra = users.values.flatMap { it.rids.keys }.distinct().filter { it !in COLUMNS }
            val all = cols + extra
            appendLine("# Fixture purge plan")
            appendLine()
            appendLine("users listed: ${users.size} (${users.values.count { it.exists }} present, ${users.values.count { !it.exists }} not in the graph)")
            appendLine()
            appendLine("| user | " + all.joinToString(" | ") + " |")
            appendLine("|---|" + all.joinToString("|") { "---:" } + "|")
            users.values.forEach { u -> appendLine("| ${u.email.removeSuffix(DOMAIN)}${if (u.exists) "" else " (absent)"} | " + all.joinToString(" | ") { u.count(it).toString() } + " |") }
            appendLine("| **total** | " + all.joinToString(" | ") { (totals[it] ?: 0).toString() } + " |")
            appendLine()
            val cascade = LinkedHashMap<String, Int>().also { m -> users.values.forEach { u -> u.cascadeEdges.forEach { (k, v) -> m.merge(k, v, Int::plus) } } }
            appendLine("edges removed with their endpoints: " + if (cascade.isEmpty()) "none" else cascade.entries.joinToString(", ") { "${it.key}=${it.value}" })
            appendLine()
            appendLine("rows kept although a listed user is involved: ${kept.size}")
            kept.groupBy { "${it.type}: ${it.reason}" }.forEach { (k, v) -> appendLine("- $k — ${v.size} (e.g. ${v.take(3).joinToString(", ") { it.id }})") }
            appendLine()
            appendLine("boundary edges to kept rows: ${boundary.size} (blocking: ${blocking.size})")
            boundary.groupBy { Triple(it.deleted, it.edge, it.other) }.forEach { (k, v) ->
                appendLine("- ${k.first} -${k.second}- ${k.third}: ${v.size}${if (v.any { it.blocking }) "  BLOCKING (owner ${v.first { it.blocking }.otherOwner})" else ""}")
            }
            appendLine()
            appendLine("rows of types this tool does not handle that carry a listed owner key: ${unhandled.size}")
            unhandled.forEach { appendLine("- $it") }
            appendLine()
            appendLine("concepts touched (never deleted): ${touchedConcepts.size}")
        }
    }

    // ── input ────────────────────────────────────────────────────────────────

    /** One id or email per line; blank lines and `#` comments are skipped. A bare id is resolved the way the debug routes resolve it. */
    fun parseList(lines: List<String>): List<String> =
        lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            .map { if (it.contains('@')) it.lowercase() else DebugConverseService.resolveUserId(it).lowercase() }
            .distinct().sorted()

    /** The protected set for the offline tool: `owner-jacob` always, plus whatever the environment configures (see [ProtectedIdentities]). */
    fun protectedFor(env: Map<String, String> = System.getenv()): ProtectedIdentities {
        val configured = listOfNotNull(ProtectedIdentities.DEFAULT_CONFIGURED, env[ProtectedIdentities.ENV_CONFIGURED]).joinToString(",")
        return ProtectedIdentities.fromEnvironment(env + (ProtectedIdentities.ENV_CONFIGURED to configured))
    }

    /** Refuses the whole run, naming every bad entry, or returns normally. Reads only the User table. */
    fun validate(db: Database, emails: List<String>, protectedIds: ProtectedIdentities = protectedFor()) {
        val problems = ArrayList<String>()
        if (emails.isEmpty()) problems += "the list is empty"
        val users = HashMap<String, Pair<String?, Any?>>()
        forEachVertex(db, "User") { v -> (v.get("email") as? String)?.let { users[it.lowercase()] = (v.get("username") as? String) to v.get("tier") } }
        for (e in emails) {
            if (protectedIds.isProtected(e)) problems += "$e is a protected identity"
            if (!FIXTURE_ADDRESS.matches(e)) problems += "$e is not a debug+...$DOMAIN address"
            if (e.startsWith(PPS_PREFIX)) problems += "$e matches debug+pps-*"
            val u = users[e]
            if (u != null) {
                val tier = (u.second as? Number)?.toInt()
                if (u.first != "debug-synthetic") problems += "$e has username '${u.first}', not debug-synthetic"
                if (tier != -1) problems += "$e has tier $tier, not -1"
            }
        }
        if (problems.isNotEmpty()) throw Refusal(problems.distinct())
    }

    // ── plan ─────────────────────────────────────────────────────────────────

    private class Src(val rid: RID, val uid: String, val owner: String?, val type: String = "Source")
    private class Itm(val rid: RID, val type: String, val uid: String, val owner: String?)
    private class Chk(val rid: RID, val uid: String, val owner: String?)

    fun plan(db: Database, listed: List<String>): Plan {
        val inL = listed.map { it.lowercase() }.toSet()
        fun isL(s: String?) = s != null && s.lowercase() in inL
        val schema = db.schema
        val vertexTypes = schema.types.filterIsInstance<VertexType>()
        val edgeTypes = schema.types.filterIsInstance<EdgeType>()
        val phraseFamily = vertexTypes.filter { it.name == "Phrase" || it.isSubTypeOf("Phrase") }.map { it.name }

        val plans = LinkedHashMap<String, UserPlan>()
        val userEmailByRid = HashMap<RID, String>()
        val userUidToEmail = HashMap<String, String>()
        forEachVertex(db, "User") { v ->
            val email = (v.get("email") as? String) ?: return@forEachVertex
            userEmailByRid[v.identity] = email.lowercase()
            (v.get("uid") as? String)?.let { userUidToEmail[it] = email.lowercase() }
        }
        for (e in inL.sorted()) plans[e] = UserPlan(e, userEmailByRid.containsValue(e))
        val kept = ArrayList<Kept>()

        // The edges every decision needs.
        val trusters = HashMap<RID, MutableSet<String>>()
        forEachEdge(db, "TRUSTS") { e -> userEmailByRid[e.out]?.let { trusters.getOrPut(e.`in`) { HashSet() } += it } }
        val assertedBy = HashMap<RID, MutableList<RID>>()
        forEachEdge(db, "ASSERTS") { e -> assertedBy.getOrPut(e.`in`) { ArrayList() } += e.out }
        val containedBy = HashMap<RID, MutableList<RID>>()
        forEachEdge(db, "CONTAINS") { e -> containedBy.getOrPut(e.`in`) { ArrayList() } += e.out }

        val sources = ArrayList<Src>()
        // Scanned by exact type, so a Source subtype is listed by name: an Email is a source and is purged with the user's other sources (under its own column).
        for (t in vertexTypes.filter { it.name == "Source" || it.isSubTypeOf("Source") }) {
            forEachVertex(db, t.name) { v -> sources += Src(v.identity, (v.get("uid") as? String) ?: v.identity.toString(), v.get("ownerEmail") as? String, t.name) }
        }
        val sourceRids = sources.map { it.rid }.toSet()
        val items = ArrayList<Itm>()
        for (t in phraseFamily) forEachVertex(db, t) { v -> items += Itm(v.identity, t, (v.get("uid") as? String) ?: v.identity.toString(), v.get("ownerEmail") as? String) }

        fun trustSize(s: RID) = trusters[s]?.size ?: 0
        // A3i: ownerless and asserted by a Source that more than one user trusts. Never deleted, and neither is a Source that asserts one.
        val sharedSeed = items.filter { it.owner == null && assertedBy[it.rid].orEmpty().any { s -> trustSize(s) > 1 } }.map { it.rid }.toSet()
        val seedSources = sharedSeed.flatMap { assertedBy[it].orEmpty() }.toSet()

        val delete = HashMap<RID, String>() // rid -> the listed user it is attributed to (a transaction deletes only its own user's rows)

        // Sources
        val firstListed = { s: Set<String> -> s.filter { isL(it) }.minOrNull() }
        val sourceOwnerOf = HashMap<RID, String>()
        for (s in sources) {
            val t = trusters[s.rid].orEmpty()
            val wholly = (t.isNotEmpty() && t.all { isL(it) }) || (t.isEmpty() && isL(s.owner))
            val touches = t.any { isL(it) } || isL(s.owner)
            val ownerElsewhere = s.owner != null && !isL(s.owner)
            when {
                wholly && s.rid in seedSources -> kept += Kept("Source", s.uid, firstListed(t) ?: s.owner!!, "asserts shared seed phrases")
                wholly && ownerElsewhere -> kept += Kept("Source", s.uid, firstListed(t) ?: "?", "ownerEmail ${s.owner} is outside the list")
                wholly -> { val u = (firstListed(t) ?: s.owner!!.lowercase()); delete[s.rid] = u; sourceOwnerOf[s.rid] = u; plans.getValue(u).add(s.type, s.rid) }
                touches -> kept += Kept("Source", s.uid, firstListed(t) ?: s.owner!!.lowercase(), "also trusted outside the list")
            }
        }

        // Phrase family
        for (i in items) {
            if (i.owner != null && !isL(i.owner)) continue
            val a = assertedBy[i.rid].orEmpty()
            val chain = a.flatMap { trusters[it].orEmpty() }.toSet()
            val candidate = isL(i.owner) || (i.owner == null && chain.isNotEmpty() && chain.all { isL(it) })
            if (!candidate) continue
            val user = if (isL(i.owner)) i.owner!!.lowercase() else firstListed(chain)!!
            when {
                i.rid in sharedSeed -> kept += Kept(i.type, i.uid, user, "shared seed phrase (A3i)")
                a.any { it !in sourceOwnerOf } -> kept += Kept(i.type, i.uid, user, "also asserted by a Source that is kept")
                else -> { delete[i.rid] = user; plans.getValue(user).add(i.type, i.rid) }
            }
        }

        // Chunks
        forEachVertex(db, "Chunk") { v ->
            val c = Chk(v.identity, (v.get("uid") as? String) ?: v.identity.toString(), v.get("ownerEmail") as? String)
            if (c.owner != null && !isL(c.owner)) return@forEachVertex
            val cont = containedBy[c.rid].orEmpty().filter { it in sourceRids }
            val allGone = cont.isNotEmpty() && cont.all { it in sourceOwnerOf }
            val orphanOfL = cont.isEmpty() && isL(c.owner)
            if (allGone || orphanOfL) {
                val user = if (isL(c.owner)) c.owner!!.lowercase() else sourceOwnerOf.getValue(cont.first())
                delete[c.rid] = user; plans.getValue(user).add("Chunk", c.rid)
            } else if (isL(c.owner)) {
                kept += Kept("Chunk", c.uid, c.owner!!.lowercase(), "sits in a Source that is kept")
            }
        }

        // Rows found by an owner key
        fun byKey(type: String, key: String, userOf: (String) -> String? = { if (isL(it)) it.lowercase() else null }) {
            if (!schema.existsType(type)) return
            forEachVertex(db, type) { v -> val k = v.get(key) as? String; val u = k?.let(userOf); if (u != null) { delete[v.identity] = u; plans.getValue(u).add(type, v.identity) } }
        }
        byKey("Utterance", "userId"); byKey("ProcessedRequest", "userEmail")
        byKey("EntityAlias", "ownerEmail"); byKey("ExtractionRun", "ownerEmail"); byKey("ClaimDisagreement", "ownerEmail")
        byKey("EmailAddress", "ownerEmail"); byKey("Domain", "ownerEmail"); byKey("Project", "ownerEmail"); byKey("ChangeRecord", "ownerEmail")
        byKey("UserScaffoldState", "userId") { k -> (if (isL(k)) k.lowercase() else userUidToEmail[k]?.takeIf { isL(it) }) }
        for ((rid, email) in userEmailByRid) if (isL(email)) { delete[rid] = email; plans.getValue(email).add("User", rid) }

        // Edges that would survive their endpoints but are stamped with a listed owner key (both ends kept).
        for (et in edgeTypes) {
            if (!et.propertyNames.any { it in OWNER_KEYS }) continue
            forEachEdge(db, et.name) { e ->
                if (e.out in delete || e.`in` in delete) return@forEachEdge
                val u = OWNER_KEYS.firstNotNullOfOrNull { (e.get(it) as? String)?.takeIf(::isL) }?.lowercase() ?: return@forEachEdge
                plans.getValue(u).add("edge:${et.name}", e.identity)
            }
        }

        // Edges at a deleted vertex go with it. Found by scanning every edge type once, so an edge created with bidirectional=false (invisible from
        // its target) is found too. An edge whose other end stays is a boundary edge; one to a row owned outside the list is blocking.
        val boundary = ArrayList<Boundary>()
        val touchedConcepts = LinkedHashMap<String, String>()
        val docCache = HashMap<RID, Document?>()
        fun docOf(r: RID): Document? = docCache.getOrPut(r) { if (db.existsRecord(r)) db.lookupByRID(r, true) as? Document else null }
        for (et in edgeTypes) {
            forEachEdge(db, et.name) { e ->
                val du = delete[e.out]; val di = delete[e.`in`]
                if (du == null && di == null) return@forEachEdge
                val user = du ?: di!!
                val up = plans.getValue(user)
                up.edgeRids += e.identity
                up.cascadeEdges.merge(et.name, 1, Int::plus)
                if (du != null && di != null) return@forEachEdge
                val keptEnd = if (du == null) e.out else e.`in`
                val deletedDoc = docOf(if (du != null) e.out else e.`in`)
                val od = docOf(keptEnd) ?: return@forEachEdge
                val ownerKey = if (od.typeName == "User") od.getString("email") else OWNER_KEYS.firstNotNullOfOrNull { od.getString(it) }
                if (od.typeName == "Concept") touchedConcepts[(od.getString("uid") ?: od.identity.toString())] = od.getString("name") ?: ""
                val blocking = deletedDoc?.typeName != "User" && ownerKey != null && !isL(ownerKey)
                boundary += Boundary(user.removeSuffix(DOMAIN), deletedDoc?.typeName ?: "?", et.name, od.typeName, ownerKey, blocking)
            }
        }

        // Rows of types this tool does not know that carry a listed owner key.
        val unhandled = ArrayList<String>()
        for (t in vertexTypes) {
            if (t.name in HANDLED_VERTEX_TYPES || t.name in phraseFamily || t.name in OWNERLESS_TYPES) continue
            val keys = OWNER_KEYS.filter { it in t.propertyNames }
            if (keys.isEmpty()) continue
            var n = 0
            forEachVertex(db, t.name) { v -> if (keys.any { isL(v.get(it) as? String) }) n++ }
            if (n > 0) unhandled += "${t.name}: $n"
        }
        return Plan(plans, kept, boundary, unhandled, touchedConcepts)
    }

    // ── apply ────────────────────────────────────────────────────────────────

    data class UserResult(val email: String, val deleted: Map<String, Int>, val error: String?)
    data class ApplyResult(val users: List<UserResult>) { val failed: List<UserResult> get() = users.filter { it.error != null } }

    /** Deletes the plan, one user per transaction. Refuses first if the plan has a blocking boundary or an unhandled type. */
    fun apply(db: Database, plan: Plan, listed: List<String>, log: (String) -> Unit = {}): ApplyResult {
        if (plan.blocking.isNotEmpty() || plan.unhandled.isNotEmpty()) {
            throw Refusal(plan.blocking.map { "${it.user}: ${it.deleted} -${it.edge}- ${it.other} owned by ${it.otherOwner}" } + plan.unhandled.map { "unhandled: $it" })
        }
        val inL = listed.map { it.lowercase() }.toSet()
        val order = COLUMNS + plan.users.values.flatMap { it.rids.keys }.distinct().filter { it !in COLUMNS }
        val results = ArrayList<UserResult>()
        for (up in plan.users.values.sortedBy { it.email }) {
            if (up.isEmpty) { log("purge: ${up.email} nothing to delete"); results += UserResult(up.email, emptyMap(), null); continue }
            val counts = LinkedHashMap<String, Int>()
            try {
                ChangeRecords.withDeleteVetoLifted(db.schema) {
                    db.transaction {
                        // The edges at this user's vertices first, then the rows, the User last. Each record is checked against the list just before it goes.
                        for (rid in up.edgeRids) {
                            if (!db.existsRecord(rid)) continue
                            val rec = db.lookupByRID(rid, true)
                            guard(rec as Document, "edge", inL)
                            rec.delete()
                            counts.merge("edge", 1, Int::plus)
                        }
                        for (key in order) for (rid in up.rids[key].orEmpty()) {
                            if (!db.existsRecord(rid)) continue // already gone with a neighbour
                            val rec = db.lookupByRID(rid, true)
                            guard(rec as Document, key, inL)
                            rec.delete()
                            counts.merge(key, 1, Int::plus)
                        }
                    }
                }
                log("purge: ${up.email} deleted " + counts.entries.joinToString(", ") { "${it.key}=${it.value}" })
                results += UserResult(up.email, counts, null)
            } catch (e: Exception) {
                log("purge: ${up.email} FAILED, nothing of this user was deleted: ${e.message}")
                results += UserResult(up.email, emptyMap(), e.message ?: e::class.java.simpleName)
            }
        }
        return ApplyResult(results)
    }

    /** The last check before a record goes: a row stamped with an owner outside the list is never deleted. */
    private fun guard(d: Document, key: String, inL: Set<String>) {
        if (key == "User") { check((d.getString("email") ?: "").lowercase() in inL) { "User ${d.getString("email")} is not on the list" }; return }
        for (k in OWNER_KEYS) {
            val s = d.getString(k) ?: continue
            check(s.lowercase() in inL) { "${d.typeName} ${d.getString("uid") ?: d.identity} has $k=$s, which is not on the list" }
        }
    }

    // ── afterwards ───────────────────────────────────────────────────────────

    /** Concepts that never lose their row but may lose their last ABOUT edge. Call after [apply], or on a plan to see what the touched Concepts look like now. */
    data class ConceptReport(val noAboutAll: Int, val touchedNoAbout: List<String>, val touchedShared: List<String>)

    fun conceptReport(db: Database, plan: Plan, listed: List<String>): ConceptReport {
        val inL = listed.map { it.lowercase() }.toSet()
        // ABOUT edges are scanned, not walked from the Concept: an edge made with bidirectional=false is invisible from its target.
        val aboutOwners = HashMap<RID, MutableList<String?>>()
        forEachEdge(db, "ABOUT") { e -> aboutOwners.getOrPut(e.`in`) { ArrayList() } += (e.get("ownerEmail") as? String)?.lowercase() }
        var noAbout = 0
        val touchedNo = ArrayList<String>(); val touchedShared = ArrayList<String>()
        forEachVertex(db, "Concept") { v ->
            val uid = (v.get("uid") as? String) ?: v.identity.toString()
            val about = aboutOwners[v.identity].orEmpty()
            if (about.isEmpty()) { noAbout++; if (uid in plan.touchedConcepts) touchedNo += uid }
            else if (uid in plan.touchedConcepts && about.any { it !in inL }) touchedShared += uid
        }
        return ConceptReport(noAbout, touchedNo, touchedShared)
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun forEachVertex(db: Database, type: String, body: (Vertex) -> Unit) {
        if (!db.schema.existsType(type)) return
        val rows: Iterator<com.arcadedb.database.Record> = db.iterateType(type, false)
        while (rows.hasNext()) body(rows.next().asVertex())
    }

    private fun forEachEdge(db: Database, type: String, body: (Edge) -> Unit) {
        if (!db.schema.existsType(type)) return
        val rows: Iterator<com.arcadedb.database.Record> = db.iterateType(type, false)
        while (rows.hasNext()) body(rows.next().asEdge())
    }
}

/**
 * Command line. `plan` is the default and changes nothing (the database is opened read-only); `--apply` opens it for writing.
 *
 * `FixturePurgeCli <database directory> <list file> [--apply]`; exit 0 ok, 2 usage, 3 refused, 4 database could not be opened (the engine holds
 * it), 5 a user's deletion failed.
 */
object FixturePurgeCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val flags = args.filter { it.startsWith("--") }
        val pos = args.filter { !it.startsWith("--") }
        if (pos.size != 2 || flags.any { it != "--apply" }) { System.err.println("usage: FixturePurgeCli <database directory> <list file> [--apply]"); kotlin.system.exitProcess(2) }
        val apply = "--apply" in flags
        val emails = FixturePurge.parseList(java.io.File(pos[1]).readLines())
        val factory = com.arcadedb.database.DatabaseFactory(pos[0])
        val db = try {
            factory.open(if (apply) com.arcadedb.engine.ComponentFile.MODE.READ_WRITE else com.arcadedb.engine.ComponentFile.MODE.READ_ONLY)
        } catch (e: Exception) {
            System.err.println("cannot open ${pos[0]}: ${e.message}")
            System.err.println("If engram-dev is running it holds the database: stop it first (sudo systemctl stop engram-dev). This tool never runs against an open database.")
            kotlin.system.exitProcess(4)
        }
        var code = 0
        try {
            try {
                FixturePurge.validate(db, emails)
                val plan = FixturePurge.plan(db, emails)
                println(plan.render())
                if (!apply) {
                    println("plan only: nothing changed. Re-run with --apply to delete.")
                } else {
                    val started = System.currentTimeMillis()
                    val result = FixturePurge.apply(db, plan, emails) { println(it) }
                    println("apply finished in ${System.currentTimeMillis() - started} ms; users with nothing left to delete or deleted: ${result.users.size - result.failed.size}, failed: ${result.failed.size}")
                    val cr = FixturePurge.conceptReport(db, plan, emails)
                    println("concepts with no ABOUT edge now: ${cr.noAboutAll}; of the ${plan.touchedConcepts.size} touched: ${cr.touchedNoAbout.size} have none left, ${cr.touchedShared.size} are shared with an owner outside the list")
                    if (result.failed.isNotEmpty()) code = 5
                }
            } catch (r: FixturePurge.Refusal) {
                System.err.println("REFUSED, nothing was changed:")
                r.problems.forEach { System.err.println("- $it") }
                code = 3
            }
        } finally {
            db.close()
            factory.close()
        }
        if (code != 0) kotlin.system.exitProcess(code)
    }
}
