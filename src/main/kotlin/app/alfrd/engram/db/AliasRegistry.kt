package app.alfrd.engram.db

import app.alfrd.engram.claims.ClaimantResolver
import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import java.util.UUID

@kotlinx.serialization.Serializable
/** One recorded fact that two names are one thing, and where that came from. */
data class AliasEntry(
    val uid: String, val canonical: String, val alias: String,
    /** `document` (a page defined the short form) or `confirmed` (a person decided). */
    val origin: String,
    val sourceUid: String?, val location: String?, val active: Boolean, val createdAt: Long,
)

/**
 * The only thing that merges two names. Entries are kept in the graph, per owner, each with its origin: a page that defines a short form
 * ("Portland Public Schools (PPS)"), or a person's decision on a pair the box could not settle. A run reads the active entries, so the same
 * Source read twice gives the same Concepts; deactivating an entry splits the merge from the next run, and nothing else is lost. The model
 * never adds an entry.
 */
class AliasRegistry(private val db: Database) {

    /** Records [alias] as another name for [canonical]. Idempotent per owner, canonical and alias; an inactive entry stays inactive. */
    fun add(userEmail: String, canonical: String, alias: String, origin: String, sourceUid: String? = null, location: String? = null): AliasEntry? {
        val c = tidy(canonical); val a = tidy(alias)
        if (c.isEmpty() || a.isEmpty() || ClaimantResolver.key(c) == ClaimantResolver.key(a)) return null
        val key = ClaimantResolver.key(a)
        var out: AliasEntry? = null
        db.transaction {
            val existing = list(userEmail).firstOrNull { ClaimantResolver.key(it.alias) == key && ClaimantResolver.key(it.canonical) == ClaimantResolver.key(c) }
            if (existing != null) { out = existing; return@transaction }
            val v = db.newVertex("EntityAlias").apply {
                set("uid", UUID.randomUUID().toString()); set("ownerEmail", userEmail); set("canonical", c); set("alias", a); set("aliasKey", key)
                set("origin", origin); set("active", true)
                // Strictly later than every earlier entry of this owner, so "the older entry wins" is never a tie.
                set("createdAt", maxOf(System.currentTimeMillis(), (list(userEmail).maxOfOrNull { it.createdAt } ?: 0L) + 1))
                sourceUid?.let { set("sourceUid", it) }; location?.let { set("location", it) }
                save()
            }
            out = v.toEntry()
        }
        return out
    }

    fun list(userEmail: String): List<AliasEntry> =
        db.query("sql", "SELECT FROM EntityAlias WHERE ownerEmail = :o", mapOf("o" to userEmail)).use { rs ->
            val out = ArrayList<AliasEntry>(); while (rs.hasNext()) out += rs.next().toElement().asVertex().toEntry(); out
        }.sortedBy { it.createdAt }

    /** Turns an entry on or off. False when the owner has no such entry. */
    fun setActive(userEmail: String, uid: String, active: Boolean): Boolean {
        var done = false
        db.transaction {
            val v = db.query("sql", "SELECT FROM EntityAlias WHERE uid = :u AND ownerEmail = :o", mapOf("u" to uid, "o" to userEmail))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null } ?: return@transaction
            v.modify().set("active", active).save(); done = true
        }
        return done
    }

    /** [base] (the run's own list, if any) plus this owner's active entries. */
    fun resolverFor(userEmail: String, base: ClaimantResolver = ClaimantResolver()): ClaimantResolver {
        val extra = list(userEmail).filter { it.active }.distinctBy { ClaimantResolver.key(it.alias) }.groupBy({ it.canonical }, { it.alias })
        return if (extra.isEmpty()) base else base.plus(extra)
    }

    private fun tidy(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private fun Vertex.toEntry() = AliasEntry(
        get("uid") as String, get("canonical") as String, get("alias") as String, get("origin") as? String ?: "document",
        get("sourceUid") as? String, get("location") as? String, (get("active") as? Boolean) ?: true, (get("createdAt") as? Number)?.toLong() ?: 0L,
    )
}
