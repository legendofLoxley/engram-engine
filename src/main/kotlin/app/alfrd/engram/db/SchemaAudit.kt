package app.alfrd.engram.db

import com.arcadedb.database.Database
import com.arcadedb.database.Document
import com.arcadedb.database.RID
import com.arcadedb.graph.Edge
import com.arcadedb.graph.Vertex
import com.arcadedb.schema.DocumentType
import com.arcadedb.schema.EdgeType
import com.arcadedb.schema.VertexType
import java.time.Instant

/** One thing the audit counted: how many rows break the rule, and the first few identifiers (uid, or the record id where there is no uid). */
data class AuditFinding(
    val id: String,
    val title: String,
    val count: Long,
    val examples: List<String>,
    /** A breakdown of [count] (by type, by class of row), when that tells the reader more than the total. */
    val breakdown: Map<String, Long> = emptyMap(),
    /** Informational findings describe the data; they are not violations of a rule. */
    val informational: Boolean = false,
) {
    val clean: Boolean get() = count == 0L
}

data class AuditReport(val ranAt: Instant, val totals: Map<String, Long>, val findings: List<AuditFinding>) {
    fun finding(id: String): AuditFinding = findings.first { it.id == id }
    val violations: List<AuditFinding> get() = findings.filter { !it.informational && it.count > 0 }

    fun toMarkdown(): String = buildString {
        appendLine("# Graph integrity")
        appendLine()
        appendLine("ran: $ranAt")
        appendLine()
        appendLine("## Totals")
        totals.forEach { (k, v) -> appendLine("- $k: $v") }
        appendLine()
        appendLine("## Findings")
        findings.forEach { f ->
            val tag = when { f.informational -> "info"; f.count == 0L -> "clean"; else -> "VIOLATION" }
            appendLine("### ${f.id} ${f.title} — ${f.count} [$tag]")
            if (f.breakdown.isNotEmpty()) f.breakdown.forEach { (k, v) -> appendLine("- $k: $v") }
            if (f.examples.isNotEmpty()) appendLine("- e.g. " + f.examples.joinToString(", "))
        }
    }
}

/**
 * A read-only audit of the graph's structural rules: what the schema does not enforce but the code assumes. It reads the database and
 * writes nothing, so it can run against the live graph (the Director tick runs it daily) or a scratch restore.
 *
 * It scans each type non-polymorphically (`iterateType(name, false)`) so a row of a Phrase subtype is read once, as itself, and never again
 * as a Phrase: the polymorphic query returns inherited duplicates. Counts are by distinct row; duplicate uids are counted per uid family.
 * Memory use is one small record per Phrase-family row and per ASSERTS edge, which is fine for this graph's size.
 */
class SchemaAudit(private val db: Database, private val exampleLimit: Int = 5) {

    private class Acc(val id: String, val title: String, val informational: Boolean = false, val limit: Int) {
        var count = 0L
        val examples = ArrayList<String>()
        val breakdown = LinkedHashMap<String, Long>()
        fun hit(example: String, bucket: String? = null) {
            count++
            if (examples.size < limit) examples += example
            if (bucket != null) breakdown.merge(bucket, 1L, Long::plus)
        }
        fun done() = AuditFinding(id, title, count, examples.toList(), breakdown.toMap(), informational)
    }

    private class ItemRow(val rid: RID, val uid: String?, val type: String, val ownerEmail: String?, val cls: String)

    fun run(): AuditReport {
        val schema = db.schema
        val totals = LinkedHashMap<String, Long>()
        val vertexTypes = schema.types.filterIsInstance<VertexType>()
        val edgeTypes = schema.types.filterIsInstance<EdgeType>()
        val phraseFamily = vertexTypes.filter { it.name == "Phrase" || it.isSubTypeOf("Phrase") }.map { it.name }
        val registered = app.alfrd.engram.cognitive.pipeline.horizon.ItemKinds.phraseTypeNames()
        val stateKinds = app.alfrd.engram.cognitive.pipeline.horizon.ItemKinds.stateful().map { it.typeName }.toSet()

        // ── 1. uid presence and duplicates ───────────────────────────────────
        val noUid = Acc("A1", "vertices with no uid", limit = exampleLimit)
        val dupUid = Acc("A2", "duplicate uids (distinct rows sharing a uid, within a uid family)", limit = exampleLimit)
        // A subtype of Source (Email) inherits `uid` rather than declaring it, so it is listed by name; read by exact type, so each row is seen once, as itself.
        val uidTypes = vertexTypes.filter { hasUidProperty(it) || it.isSubTypeOf("Source") }
        val seen = HashMap<String, MutableMap<String, RID>>() // family -> uid -> first rid
        val dupReported = HashSet<String>()
        // Phrase and its subtypes share one uid index; every other type is its own family.
        // A Source and its subtype (Email) likewise share Source's uid index, so a uid used by both is a duplicate.
        val sourceFamily = vertexTypes.filter { it.name == "Source" || it.isSubTypeOf("Source") }.map { it.name }
        fun family(t: String) = if (t in phraseFamily) "Phrase" else if (t in sourceFamily) "Source" else t

        // ── 2. owner chain: User -TRUSTS-> Source -ASSERTS-> item ────────────
        val emailByUserRid = HashMap<RID, String>()
        for (t in vertexTypes.filter { it.name == "User" }) forEachVertex(t.name) { v -> (v.get("email") as? String)?.let { emailByUserRid[v.identity] = it } }
        val trustedBy = HashMap<RID, MutableSet<String>>() // source rid -> emails that trust it
        forEachEdge("TRUSTS") { e -> emailByUserRid[e.out]?.let { trustedBy.getOrPut(e.`in`) { HashSet() } += it } }
        val chainOwners = HashMap<RID, MutableSet<String>>() // item rid -> emails whose trusted Source asserts it
        val assertedByAnySource = HashSet<RID>()
        var assertsTotal = 0L
        forEachEdge("ASSERTS") { e ->
            assertsTotal++
            assertedByAnySource += e.`in`
            trustedBy[e.out]?.let { owners -> chainOwners.getOrPut(e.`in`) { HashSet() } += owners }
        }
        totals["User"] = emailByUserRid.size.toLong()
        totals["ASSERTS"] = assertsTotal

        // ── 3. item scans ────────────────────────────────────────────────────
        val noOwner = Acc("A3", "items (Phrase family) with no ownerEmail", limit = exampleLimit)
        val ownerDisagrees = Acc("A4", "items whose ownerEmail disagrees with the User -TRUSTS-> Source -ASSERTS-> item chain", limit = exampleLimit)
        val noTrustedAsserts = Acc("A5", "items with an ownerEmail but no ASSERTS from a Source in that owner's trusted set", limit = exampleLimit)
        val passagesNotAsserted = Acc("A5i", "of the rows with an ownerEmail and no trusted ASSERTS, passages (never ASSERTed by design, so left out of A5)", informational = true, limit = exampleLimit)
        val badState = Acc("A6", "Task/Event/Question rows with a null or unknown state (bucket: type:state/status)", limit = exampleLimit)
        val badType = Acc("A7", "Phrase-family rows whose type is not Phrase, Task, Event or Question", limit = exampleLimit)
        val sharedNoOwner = Acc("A3i", "of the A3 rows, those asserted only by a Source trusted by more than one user (shared seed content)", informational = true, limit = exampleLimit)
        val itemRows = HashMap<RID, ItemRow>()
        val validStates = setOf("open", "active", "waiting", "done", "dropped")
        val perType = LinkedHashMap<String, Long>()

        for (typeName in phraseFamily) {
            forEachVertex(typeName) { v ->
                perType.merge(typeName, 1L, Long::plus)
                val uid = v.get("uid") as? String
                val id = uid ?: v.identity.toString()
                val owner = v.get("ownerEmail") as? String
                val cls = when {
                    v.get("claimSourceUid") != null -> "claim"
                    v.get("role") == "passage" -> "passage"
                    typeName != "Phrase" || v.get("opKey") != null -> "horizon"
                    else -> "ingested"
                }
                itemRows[v.identity] = ItemRow(v.identity, uid, typeName, owner, cls)
                if (uid == null) noUid.hit(id, typeName) else {
                    val fam = seen.getOrPut("Phrase") { HashMap() }
                    val first = fam.putIfAbsent(uid, v.identity)
                    if (first != null && first != v.identity) { if (dupReported.add("Phrase|$uid")) dupUid.hit(uid, "Phrase") }
                }
                if (typeName !in registered) badType.hit(id, typeName)
                if (typeName in stateKinds) {
                    val s = v.get("state") as? String
                    if (s == null || s !in validStates) badState.hit(id, "$typeName:${s ?: "null"}/status=${v.get("status") ?: "null"}")
                }
                val chain = chainOwners[v.identity].orEmpty()
                if (owner == null) {
                    noOwner.hit(id, "$typeName/$cls/" + if (chain.isNotEmpty()) "asserted by ${chain.size} trusting user(s)" else if (v.identity in assertedByAnySource) "asserted by an untrusted Source only" else "no ASSERTS at all")
                } else {
                    if (owner !in chain) {
                        if (cls == "passage") passagesNotAsserted.hit(id) else {
                            noTrustedAsserts.hit(id, "$typeName/$cls/${if (v.identity in assertedByAnySource) "asserted by others only" else "no ASSERTS at all"}")
                        }
                        if (chain.isNotEmpty()) ownerDisagrees.hit(id, typeName)
                    }
                }
            }
        }
        // Items without an owner stamp that sit in a shared Source: legitimate seed content, so say how many.
        for (r in itemRows.values) {
            if (r.ownerEmail == null && (chainOwners[r.rid]?.size ?: 0) > 1) sharedNoOwner.hit(r.uid ?: r.rid.toString())
        }
        phraseFamily.forEach { totals["vertices:$it"] = perType[it] ?: 0L }

        // ── 4. the other vertex types: uid presence, duplicates, container stamps ─
        val containerNoOwner = Acc("A10", "Domain/Project rows with no ownerEmail", limit = exampleLimit)
        val containerNoCreated = Acc("A11", "Domain/Project rows with no createdAt", limit = exampleLimit)
        val itemNoCreated = Acc("A12", "items (Phrase family) with no createdAt", limit = exampleLimit)
        val itemNoText = Acc("A13", "Task/Event/Question rows with no text", limit = exampleLimit)
        for (t in uidTypes) {
            if (t.name in phraseFamily) continue
            forEachVertex(t.name) { v ->
                totals.merge("vertices:${t.name}", 1L, Long::plus)
                val uid = v.get("uid") as? String
                if (uid == null) { noUid.hit(v.identity.toString(), t.name); return@forEachVertex }
                val fam = seen.getOrPut(family(t.name)) { HashMap() }
                val first = fam.putIfAbsent(uid, v.identity)
                if (first != null && first != v.identity && dupReported.add("${family(t.name)}|$uid")) dupUid.hit(uid, t.name)
            }
        }
        for (name in listOf("Domain", "Project")) {
            if (!schema.existsType(name)) continue
            forEachVertex(name) { v ->
                val id = (v.get("uid") as? String) ?: v.identity.toString()
                if ((v.get("ownerEmail") as? String).isNullOrBlank()) containerNoOwner.hit(id, name)
                if (v.get("createdAt") == null) containerNoCreated.hit(id, name)
            }
        }
        for (typeName in phraseFamily) {
            forEachVertex(typeName) { v ->
                val id = (v.get("uid") as? String) ?: v.identity.toString()
                if (v.get("createdAt") == null) itemNoCreated.hit(id, typeName)
                if (typeName in stateKinds && (v.get("text") as? String).isNullOrBlank()) itemNoText.hit(id, typeName)
            }
        }

        // ── 5. dangling edges and filing ─────────────────────────────────────
        val dangling = Acc("A8", "dangling edges (an endpoint record no longer exists)", limit = exampleLimit)
        val missingProject = Acc("A9a", "items filed (PART_OF) under a missing Project, or under something that is not a Project", limit = exampleLimit)
        val missingDomain = Acc("A9b", "Projects filed (PART_OF) under a missing Domain, or under something that is not a Domain", limit = exampleLimit)
        val multiParent = Acc("A9c", "children with more than one PART_OF parent", limit = exampleLimit)
        val crossOwner = Acc("A9d", "PART_OF edges joining two different owners", limit = exampleLimit)
        val partOfCount = HashMap<RID, Int>()
        for (et in edgeTypes) {
            forEachEdge(et.name) { e ->
                totals.merge("edges:${et.name}", 1L, Long::plus)
                val out = loadOrNull(e.out)
                val inn = loadOrNull(e.`in`)
                if (out == null || inn == null) {
                    dangling.hit("${et.name}@${e.identity}", "${et.name}:${if (out == null && inn == null) "both" else if (out == null) "out" else "in"}")
                }
                if (et.name == "PART_OF") {
                    partOfCount.merge(e.out, 1, Int::plus)
                    if (out != null && inn != null) {
                        val childIsProject = out.typeName == "Project"
                        val wantParent = if (childIsProject) "Domain" else "Project"
                        if (inn.typeName != wantParent) (if (childIsProject) missingDomain else missingProject).hit(uidOf(out), "${out.typeName}->${inn.typeName}")
                        val a = (out as? Document)?.getString("ownerEmail"); val b = (inn as? Document)?.getString("ownerEmail")
                        if (a != null && b != null && a != b) crossOwner.hit("${uidOf(out)} -> ${uidOf(inn)}")
                    } else if (out != null) {
                        (if (out.typeName == "Project") missingDomain else missingProject).hit(uidOf(out), "${out.typeName}->missing")
                    }
                }
            }
        }
        partOfCount.forEach { (rid, n) -> if (n > 1) multiParent.hit(loadOrNull(rid)?.let(::uidOf) ?: rid.toString()) }

        val all = listOf(noUid, dupUid, noOwner, sharedNoOwner, ownerDisagrees, noTrustedAsserts, passagesNotAsserted, badState, badType, dangling, missingProject, missingDomain, multiParent, crossOwner,
            containerNoOwner, containerNoCreated, itemNoCreated, itemNoText)
        return AuditReport(Instant.now(), totals, all.map { it.done() })
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private fun hasUidProperty(t: VertexType): Boolean = try { t.getProperty("uid") != null } catch (_: Exception) { false }

    private fun forEachVertex(type: String, body: (Vertex) -> Unit) {
        val rows: Iterator<com.arcadedb.database.Record> = db.iterateType(type, false)
        while (rows.hasNext()) body(rows.next().asVertex())
    }

    private fun forEachEdge(type: String, body: (Edge) -> Unit) {
        if (!db.schema.existsType(type)) return
        val rows: Iterator<com.arcadedb.database.Record> = db.iterateType(type, false)
        while (rows.hasNext()) body(rows.next().asEdge())
    }

    private fun loadOrNull(rid: RID): Document? = try {
        if (db.existsRecord(rid)) db.lookupByRID(rid, true) as? Document else null
    } catch (_: Exception) { null }

    private fun uidOf(d: Document): String = d.getString("uid") ?: d.identity.toString()
}

/** Command line: `schemaAudit -PauditDb=<path to an ArcadeDB directory>` opens it read-only and prints the report. Never use it on a path the engine has open for writing. */
object SchemaAuditCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.firstOrNull() ?: run { System.err.println("usage: SchemaAuditCli <database directory> [exampleLimit]"); kotlin.system.exitProcess(2) }
        val limit = args.getOrNull(1)?.toIntOrNull() ?: 5
        val factory = com.arcadedb.database.DatabaseFactory(path)
        val db = factory.open(com.arcadedb.engine.ComponentFile.MODE.READ_ONLY)
        try {
            println(SchemaAudit(db, limit).run().toMarkdown())
        } finally {
            db.close()
            factory.close()
        }
    }
}
