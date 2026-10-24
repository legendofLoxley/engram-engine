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
data class PossiblySame(val uid: String, val name: String, val kind: String, val parent: String? = null, val reason: String)

/** One answer to "which things are called this?": the Concept, how much of the owner's evidence is about it, and its possible twins. */
@Serializable
data class ConceptCandidate(
    val uid: String, val name: String, val kind: String, val parent: String? = null,
    /** The owner's established claims ABOUT it, and the Sources those claims come from. */
    val claims: Int, val sourceUids: List<String>,
    /** True for a Concept made in conversation (no kind or parent stated). */
    val fromChat: Boolean,
    val possiblySame: List<PossiblySame>,
)

/**
 * Things (Concepts) that document claims are ABOUT, identified by name + kind + parent and never merged on a guess.
 *
 * **Identity.** "Clark (school, part of PPS)" and "Clark (person)" are two Concepts: one is reused only when name, kind and parent all
 * match (names compared without case, punctuation or a leading "the"; known aliases resolved first). A document usually states the kind
 * and the parent (a school in a PPS report), so that is what it is filed under.
 *
 * **Possibly the same.** A new Concept whose name another Concept already has is linked to it by `POSSIBLY_SAME` with the reason they
 * were kept apart. That link is evidence, not a decision: a person or alfrd settles it later, and it can be undone. Concepts made in
 * conversation (kind `topic`, by the Horizon) are never reused for a document claim, and the Horizon never reuses a document Concept.
 *
 * **Read time.** [candidates] answers a name with every Concept that has it, each with its kind, parent, possible twins and how much of
 * the owner's evidence is about it; the caller picks. Concepts are shared, so every edge here carries its owner and only the owner's
 * edges are counted.
 */
class ConceptStore(private val db: Database, private val aliases: ClaimantResolver = ClaimantResolver()) {

    companion object {
        /** The kinds a document Concept may have. Anything else is filed as `other`. */
        val KINDS = setOf("school", "public body", "organization", "program", "policy", "scenario", "place", "person", "other")

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

    fun key(name: String, kind: String, parent: String?): String =
        "${ClaimantResolver.key(canonical(name))}|${kindOf(kind)}|${ClaimantResolver.key(parent?.let { canonical(it) })}"

    /** The Concept for this name, kind and parent, made if there is none. A new one is linked to any other Concept of the same name. */
    fun resolve(userEmail: String, name: String, kind: String, parent: String?): ConceptRef {
        val display = canonical(name).replace(Regex("\\s+"), " ")
        require(display.isNotBlank()) { "a thing needs a name" }
        val k = kindOf(kind)
        val p = parent?.let { canonical(it).replace(Regex("\\s+"), " ") }?.takeIf { it.isNotBlank() }
        val key = key(display, k, p)
        var out: ConceptRef? = null
        db.transaction {
            val found = db.query("sql", "SELECT FROM Concept WHERE conceptKey = :k", mapOf("k" to key)).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
            if (found != null) { out = found.toRef(); return@transaction }
            val v = db.newVertex("Concept").apply {
                set("uid", UUID.randomUUID().toString()); set("name", display); set("type", k)
                set("normalizedName", normalizedName(display)); set("conceptKey", key)
                p?.let { set("parentName", it) }
                save()
            }
            sameName(display).filter { it.identity != v.identity }.forEach { other ->
                v.newEdge("POSSIBLY_SAME", other, false).apply {
                    set("reason", reason(k, p, other)); set("ownerEmail", userEmail); set("createdAt", System.currentTimeMillis()); save()
                }
            }
            out = v.toRef()
        }
        return out!!
    }

    private fun reason(kind: String, parent: String?, other: Vertex): String {
        val otherKind = other.get("type") as? String ?: ""
        val otherParent = other.get("parentName") as? String
        return when {
            otherKind == CHAT_KIND || otherKind.isBlank() -> "same name; the other was named in conversation, with no kind or parent"
            otherKind != kind -> "same name; kinds differ ($kind, $otherKind)"
            else -> "same name; parents differ (${parent ?: "none stated"}, ${otherParent ?: "none stated"})"
        }
    }

    /** Every Concept whose name is [name] or, through the alias list, one of its aliases. */
    private fun sameName(name: String): List<Vertex> {
        val names = setOf(normalizedName(name), normalizedName(canonical(name)))
        return names.flatMap { n ->
            db.query("sql", "SELECT FROM Concept WHERE normalizedName = :n", mapOf("n" to n)).use { rs ->
                val out = ArrayList<Vertex>(); while (rs.hasNext()) out += rs.next().toElement().asVertex(); out
            }
        }.distinctBy { it.identity }
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
    fun candidates(userEmail: String, name: String): List<ConceptCandidate> = sameName(name).map { v ->
        val claims = v.getEdges(Vertex.DIRECTION.IN, "ABOUT").filter { it.get("ownerEmail") == userEmail }
            .map { it.outVertex.asVertex() }.filter { it.get("claimSourceUid") != null }
        val twins = (v.getEdges(Vertex.DIRECTION.OUT, "POSSIBLY_SAME").map { it to it.inVertex.asVertex() } +
            v.getEdges(Vertex.DIRECTION.IN, "POSSIBLY_SAME").map { it to it.outVertex.asVertex() })
            .map { (e, o) -> val r = o.toRef(); PossiblySame(r.uid, r.name, r.kind, r.parent, e.get("reason") as? String ?: "") }
            .distinctBy { it.uid }
        val r = v.toRef()
        ConceptCandidate(
            r.uid, r.name, r.kind, r.parent, claims.size, claims.mapNotNull { it.get("claimSourceUid") as? String }.distinct(),
            fromChat = (v.get("conceptKey") == null), possiblySame = twins,
        )
    }.sortedWith(compareByDescending<ConceptCandidate> { it.claims }.thenBy { it.name })

    private fun Vertex.toRef() = ConceptRef(get("uid") as String, get("name") as? String ?: "", get("type") as? String ?: "", get("parentName") as? String)
}
