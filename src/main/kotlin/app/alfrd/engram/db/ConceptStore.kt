package app.alfrd.engram.db

import app.alfrd.engram.claims.ClaimantResolver
import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.serialization.Serializable
import java.util.UUID

/** A thing a claim is about, as stored: its name as first written, its kind and the body it belongs to (null when none was stated). */
@Serializable
data class ConceptRef(val uid: String, val name: String, val kind: String, val parent: String? = null)

/** Another Concept that may be the same thing, and why the two were not merged. */
@Serializable
data class PossiblySame(val uid: String, val name: String, val kind: String, val parent: String? = null, val reason: String, val decision: String? = null)

/** One answer to "which things are called this?": the Concept, how much of the owner's evidence is about it, and its possible twins. */
@Serializable
data class ConceptCandidate(
    val uid: String, val name: String, val kind: String, val parent: String? = null,
    /** The owner's established claims ABOUT it, and the Sources those claims come from. */
    val claims: Int, val sourceUids: List<String>,
    /** True for a Concept made in conversation (no kind or parent stated). */
    val fromChat: Boolean,
    val possiblySame: List<PossiblySame>,
    /** Where the parent came from: `stated` (the page names it) or `issuer` (the Source's issuer, by default); null when there is none. */
    val parentOrigin: String? = null,
    /** The alias entries that make this name another name's (or another's its), with where each came from. */
    val aliasEntries: List<AliasEntry> = emptyList(),
)

/**
 * Things (Concepts) that document claims are ABOUT, identified by name + kind + parent and never merged on a guess.
 *
 * **Identity.** "Clark (school, part of PPS)" and "Clark (person)" are two Concepts: one is reused only when name, kind and parent all
 * match (names compared without case, punctuation or a leading "the"). A document usually states the kind and the parent (a school in a
 * PPS report), so that is what it is filed under. Names are filed as printed: the alias list never changes what a claim is linked to.
 *
 * **Possibly the same.** A new Concept whose name another Concept already has is linked to it by `POSSIBLY_SAME` with the reason they
 * were kept apart. That link is evidence, not a decision: a person or alfrd settles it later, and it can be undone. Concepts made in
 * conversation (kind `topic`, by the Horizon) are never reused for a document claim, and the Horizon never reuses a document Concept.
 *
 * **Read time.** Aliases (the owner's [AliasRegistry], given as this store's resolver) apply only when reading: [candidates] answers a name
 * with every Concept that has it or one of its aliases, each with its kind, parent, possible twins and how much of the owner's evidence is
 * about it, and [entities] groups Concepts the alias list says are one. Turning an alias off splits the group at once, with nothing to
 * rewrite. The caller picks. Concepts are shared, so every edge here carries its owner and only the owner's
 * edges are counted.
 */
class ConceptStore(private val db: Database, private val aliases: ClaimantResolver = ClaimantResolver()) {

    companion object {
        /** The kinds a document Concept may have. Anything else is filed as `other`. */
        val KINDS = setOf("school", "public body", "organization", "program", "policy", "scenario", "grade band", "place", "person", "other")

        /** The kind the Horizon gives the Concepts it makes in conversation. */
        const val CHAT_KIND = "topic"

        fun kindOf(raw: String?): String {
            val k = raw.orEmpty().trim().lowercase().replace(Regex("[_\\s]+"), " ")
            return when {
                k in KINDS -> k
                k in setOf("district", "board", "school board", "government", "agency", "committee") -> "public body"
                k in setOf("program or policy", "programme") -> "program"
                k in setOf("org", "company", "nonprofit", "group") -> "organization"
                else -> "other"
            }
        }

        /** How the Horizon matches names; kept the same so a name lookup finds chat Concepts too. */
        fun normalizedName(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private fun canonical(name: String) = aliases.canonical(name) ?: name.trim()

    private fun tidy(name: String) = name.trim().replace(Regex("\\s+"), " ")

    /** The identity of a thing as printed: no alias is applied, so a merge is never written into the graph. */
    fun key(name: String, kind: String, parent: String?): String =
        "${ClaimantResolver.key(name)}|${kindOf(kind)}|${ClaimantResolver.key(parent)}"

    /**
     * The Concept for this name, kind and parent, made if there is none. The kind is the name's own shape when it has one ([EntityRules]),
     * else the model's label. A school, program or policy with no parent printed takes [issuer] (who issued the document) as its parent, and
     * the Concept says so. A new Concept is linked to every other that has its name, or its name without designators.
     */
    fun resolve(userEmail: String, name: String, kind: String, parent: String?, issuer: String? = null): ConceptRef {
        val display = tidy(name)
        require(display.isNotBlank()) { "a thing needs a name" }
        val k = EntityRules.kindFor(display, kind)
        val stated = parent?.let { tidy(it) }?.takeIf { it.isNotBlank() }
        val fromIssuer = if (stated == null && k in EntityRules.KINDS_WITH_ISSUER_PARENT) {
            issuer?.let { tidy(it) }?.takeIf { it.isNotBlank() && ClaimantResolver.key(it) != ClaimantResolver.key(display) }
        } else null
        val p = stated ?: fromIssuer
        val key = key(display, k, p)
        var out: ConceptRef? = null
        db.transaction {
            val found = db.query("sql", "SELECT FROM Concept WHERE conceptKey = :k", mapOf("k" to key)).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
            if (found != null) { out = found.toRef(); return@transaction }
            val v = db.newVertex("Concept").apply {
                set("uid", UUID.randomUUID().toString()); set("name", display); set("type", k)
                set("normalizedName", normalizedName(display)); set("conceptKey", key); set("looseName", EntityRules.looseName(display))
                p?.let { set("parentName", it); set("parentOrigin", if (stated != null) "stated" else "issuer") }
                save()
            }
            sameName(userEmail, display, viaAliases = false).filter { it.identity != v.identity }.forEach { other ->
                v.newEdge("POSSIBLY_SAME", other, true).apply {
                    set("reason", reason(display, k, p, other)); set("ownerEmail", userEmail); set("createdAt", System.currentTimeMillis()); save()
                }
            }
            out = v.toRef()
        }
        return out!!
    }

    private fun reason(name: String, kind: String, parent: String?, other: Vertex): String {
        val otherKind = other.get("type") as? String ?: ""
        val otherParent = other.get("parentName") as? String
        val otherName = other.get("name") as? String ?: ""
        return when {
            normalizedName(otherName) != normalizedName(name) -> "same name apart from a designator ($name, $otherName)"
            otherKind == CHAT_KIND || otherKind.isBlank() -> "same name; the other was named in conversation, with no kind or parent"
            otherKind != kind -> "same name; kinds differ ($kind, $otherKind)"
            else -> "same name; parents differ (${parent ?: "none stated"}, ${otherParent ?: "none stated"})"
        }
    }

    /**
     * Every Concept whose name is [name], or one that differs only by a designator; with [viaAliases], also those named by one of its aliases
     * (read time only: a new Concept is linked as possibly the same only on its own name). Only Concepts [userEmail] has evidence about.
     */
    private fun sameName(userEmail: String, name: String, viaAliases: Boolean = true): List<Vertex> {
        val forms = if (viaAliases) aliasForms(name) else setOf(name)
        val names = forms.flatMap { listOf(normalizedName(it), EntityRules.looseName(it)) }.toSet()
        return names.flatMap { n -> listOf("normalizedName", "looseName").flatMap { field ->
            db.query("sql", "SELECT FROM Concept WHERE $field = :n", mapOf("n" to n)).use { rs ->
                val out = ArrayList<Vertex>(); while (rs.hasNext()) out += rs.next().toElement().asVertex(); out
            }
        } }.distinctBy { it.identity }.filter { ownerHasEvidenceAbout(userEmail, it) }
    }

    /**
     * Concepts are shared between owners, but only the owner's own ABOUT links make one visible to them: another owner's Concepts are never
     * listed, offered as a possible twin, or counted, and a name only they have used is not revealed.
     */
    private fun ownerHasEvidenceAbout(userEmail: String, concept: Vertex): Boolean =
        concept.getEdges(Vertex.DIRECTION.IN, "ABOUT").any { it.get("ownerEmail") == userEmail }

    /** [name], its canonical name, and every other name the alias list gives that canonical name. */
    private fun aliasForms(name: String): Set<String> {
        val c = canonical(name)
        return setOf(name, c) + (aliases.groups.entries.firstOrNull { ClaimantResolver.key(it.key) == ClaimantResolver.key(c) }?.value.orEmpty())
    }

    /** Links a claim (or its passage) to a thing it is about. Idempotent per phrase, Concept and owner. */
    fun linkAbout(userEmail: String, phraseUid: String, conceptUid: String, runId: String?): Boolean {
        var created = false
        db.transaction {
            val phrase = db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseUid)).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null } ?: return@transaction
            val concept = db.query("sql", "SELECT FROM Concept WHERE uid = :u", mapOf("u" to conceptUid)).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null } ?: return@transaction
            val already = phrase.getEdges(Vertex.DIRECTION.OUT, "ABOUT").any { it.get("ownerEmail") == userEmail && it.inVertex.identity == concept.identity }
            if (already) return@transaction
            phrase.modify().newEdge("ABOUT", concept, true).apply {
                set("createdAt", System.currentTimeMillis()); set("ownerEmail", userEmail); set("writer", "claims")
                runId?.let { set("runId", it) }
                save()
            }
            created = true
        }
        return created
    }

    /** The things [phraseUid] is about, as the owner linked them. */
    fun about(userEmail: String, phraseUid: String): List<ConceptRef> =
        db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseUid)).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
            ?.getEdges(Vertex.DIRECTION.OUT, "ABOUT")?.filter { it.get("ownerEmail") == userEmail }?.map { it.inVertex.asVertex().toRef() }.orEmpty()

    /** Every Concept called [name], with what the owner's evidence says about each and its possible twins. The caller picks. */
    fun candidates(userEmail: String, name: String, registry: AliasRegistry? = null): List<ConceptCandidate> = sameName(userEmail, name).map { v ->
        val claims = v.getEdges(Vertex.DIRECTION.IN, "ABOUT").filter { it.get("ownerEmail") == userEmail }
            .map { it.outVertex.asVertex() }.filter { it.get("claimSourceUid") != null }
        val twins = (v.getEdges(Vertex.DIRECTION.OUT, "POSSIBLY_SAME").map { it to it.inVertex.asVertex() } +
            v.getEdges(Vertex.DIRECTION.IN, "POSSIBLY_SAME").map { it to it.outVertex.asVertex() })
            .map { (e, o) -> val r = o.toRef(); PossiblySame(r.uid, r.name, r.kind, r.parent, e.get("reason") as? String ?: "", e.get("decision") as? String) }
            .distinctBy { it.uid }
        val r = v.toRef()
        ConceptCandidate(
            r.uid, r.name, r.kind, r.parent, claims.size, claims.mapNotNull { it.get("claimSourceUid") as? String }.distinct(),
            fromChat = (v.get("conceptKey") == null), possiblySame = twins, parentOrigin = v.get("parentOrigin") as? String,
            aliasEntries = registry?.list(userEmail)?.filter { e -> ClaimantResolver.key(e.alias) == ClaimantResolver.key(r.name) || ClaimantResolver.key(e.canonical) == ClaimantResolver.key(r.name) }.orEmpty(),
        )
    }.sortedWith(compareByDescending<ConceptCandidate> { it.claims }.thenBy { it.name })

    /** A pair of Concepts that may be one thing, waiting for a person's answer. */
    @Serializable
    data class NameCheck(val a: ConceptRef, val b: ConceptRef, val reason: String, val aClaims: Int, val bClaims: Int)

    private fun claimCount(userEmail: String, v: Vertex) = v.getEdges(Vertex.DIRECTION.IN, "ABOUT").count { it.get("ownerEmail") == userEmail }

    /** Every possibly-same pair among the owner's evidence that nobody has decided. */
    fun nameChecks(userEmail: String): List<NameCheck> {
        val edges = db.query("sql", "SELECT FROM POSSIBLY_SAME WHERE ownerEmail = :o", mapOf("o" to userEmail)).use { rs ->
            val out = ArrayList<com.arcadedb.graph.Edge>(); while (rs.hasNext()) out += rs.next().toElement().asEdge(); out
        }
        return edges.filter { it.get("decision") == null }.mapNotNull { e ->
            val a = e.outVertex.asVertex(); val b = e.inVertex.asVertex()
            val ac = claimCount(userEmail, a); val bc = claimCount(userEmail, b)
            if (ac == 0 && bc == 0) null else NameCheck(a.toRef(), b.toRef(), e.get("reason") as? String ?: "", ac, bc)
        }.sortedWith(compareByDescending<NameCheck> { it.aClaims + it.bClaims }.thenBy { it.a.name })
    }

    /**
     * Records a person's answer for a pair. `same` also records the shorter-lived name as an alias of the other in [registry] (the one with more
     * claims is the canonical name unless [canonicalUid] says otherwise); `different` keeps the pair apart and stops it being asked again.
     * False when the pair is not a possibly-same pair of this owner.
     */
    fun decide(userEmail: String, aUid: String, bUid: String, same: Boolean, registry: AliasRegistry, canonicalUid: String? = null): Boolean {
        var done = false
        db.transaction {
            val edge = db.query("sql", "SELECT FROM POSSIBLY_SAME WHERE ownerEmail = :o", mapOf("o" to userEmail)).use { rs ->
                var found: com.arcadedb.graph.Edge? = null
                while (rs.hasNext() && found == null) {
                    val e = rs.next().toElement().asEdge()
                    val ends = setOf(e.outVertex.asVertex().get("uid"), e.inVertex.asVertex().get("uid"))
                    if (ends == setOf(aUid, bUid) && e.get("decision") == null) found = e
                }
                found
            } ?: return@transaction
            edge.modify().set("decision", if (same) "same" else "different").set("decidedAt", System.currentTimeMillis()).save()
            if (same) {
                val a = edge.outVertex.asVertex(); val b = edge.inVertex.asVertex()
                val keep = when (canonicalUid) { a.get("uid") -> a; b.get("uid") -> b; else -> if (claimCount(userEmail, a) >= claimCount(userEmail, b)) a else b }
                val other = if (keep.identity == a.identity) b else a
                registry.add(userEmail, keep.get("name") as String, other.get("name") as String, "confirmed")
            }
            done = true
        }
        return done
    }

    /** One real thing as the owner's evidence has it: Concepts the alias list says are one, with every name they go by. */
    @Serializable
    data class Entity(
        val name: String, val kind: String, val parent: String?, val parentOrigin: String?, val aliases: List<String>, val conceptUids: List<String>,
        val claims: Int, val sourceUids: List<String>, val mayBeSameAs: List<PossiblySame>,
    )

    /** The owner's things, merged through the alias list at read time: nothing is merged in the graph, so removing an alias splits the group. */
    fun entities(userEmail: String, registry: AliasRegistry): List<Entity> {
        val resolver = registry.resolverFor(userEmail, aliases)
        val about = db.query("sql", "SELECT FROM ABOUT WHERE ownerEmail = :o", mapOf("o" to userEmail)).use { rs ->
            val out = ArrayList<com.arcadedb.graph.Edge>(); while (rs.hasNext()) out += rs.next().toElement().asEdge(); out
        }
        val concepts = about.map { it.inVertex.asVertex() }.filter { it.get("conceptKey") != null }.distinctBy { it.identity }
        fun group(v: Vertex): String {
            val name = v.get("name") as? String ?: ""
            return listOf(ClaimantResolver.key(resolver.canonical(name)), v.get("type") as? String ?: "", ClaimantResolver.key((v.get("parentName") as? String)?.let { resolver.canonical(it) })).joinToString("|")
        }
        return concepts.groupBy(::group).values.map { members ->
            val claimsOf = members.associateWith { v -> v.getEdges(Vertex.DIRECTION.IN, "ABOUT").filter { it.get("ownerEmail") == userEmail }.map { it.outVertex.asVertex() }.filter { it.get("claimSourceUid") != null } }
            val lead = members.maxByOrNull { claimsOf[it]!!.size }!!
            val names = members.map { it.get("name") as String }
            val canonicalName = resolver.canonical(lead.get("name") as String) ?: (lead.get("name") as String)
            val memberIds = members.map { it.identity }.toSet()
            val twins = members.flatMap { v ->
                (v.getEdges(Vertex.DIRECTION.OUT, "POSSIBLY_SAME").map { it to it.inVertex.asVertex() } + v.getEdges(Vertex.DIRECTION.IN, "POSSIBLY_SAME").map { it to it.outVertex.asVertex() })
                    .filter { (e, o) -> e.get("decision") == null && e.get("ownerEmail") == userEmail && o.identity !in memberIds }
                    .map { (e, o) -> val r = o.toRef(); PossiblySame(r.uid, r.name, r.kind, r.parent, e.get("reason") as? String ?: "") }
            }.distinctBy { it.uid }
            Entity(
                canonicalName, lead.get("type") as? String ?: "", lead.get("parentName") as? String, lead.get("parentOrigin") as? String,
                names.filter { ClaimantResolver.key(it) != ClaimantResolver.key(canonicalName) }.distinct(), members.map { it.get("uid") as String },
                claimsOf.values.flatten().distinctBy { it.identity }.size, claimsOf.values.flatten().mapNotNull { it.get("claimSourceUid") as? String }.distinct(), twins,
            )
        }.sortedWith(compareByDescending<Entity> { it.claims }.thenBy { it.name })
    }

    private fun Vertex.toRef() = ConceptRef(get("uid") as String, get("name") as? String ?: "", get("type") as? String ?: "", get("parentName") as? String)
}
