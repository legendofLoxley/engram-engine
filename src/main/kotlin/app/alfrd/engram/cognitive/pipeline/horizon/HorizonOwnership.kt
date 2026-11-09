package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import com.arcadedb.graph.Edge
import com.arcadedb.graph.Vertex

/**
 * User-scope checks shared by [ArcadeHorizonGraphStore] (write-side rejection of cross-user
 * mutations) and [ArcadeHorizonAssembler] (read-side defense-in-depth against surfacing another
 * user's content even if a bad edge exists in the graph already). Kept standalone rather than
 * having the assembler depend on the write-side class, so the two interfaces stay independent.
 *
 * ## Edges created with `bidirectional = false`, and how to traverse them
 *
 * An edge made with `newEdge(type, to, false)` is recorded on its *out* vertex only. Following it outward
 * (`vertex.getEdges(OUT, type)`, `getVertices(OUT, type)`) works; following it inward
 * (`getEdges(IN, type)`, `getVertices(IN, type)`) is **silently empty**: no error, no rows. Read it backward with SQL on the
 * edge's endpoints, which does not depend on the flag:
 *
 * ```
 * SELECT FROM ASSERTS WHERE @in.uid = :phraseUid AND @out.uid IN :sourceUids      -- who asserts this item
 * SELECT @out.uid AS uid FROM ABOUT WHERE ownerEmail = :e AND @in.normalizedName = :n
 * ```
 *
 * | `bidirectional=false` (outward traversal only; read inward with `@in`/`@out` SQL) | `bidirectional=true` (both directions traverse) |
 * |---|---|
 * | `ASSERTS` Source -> item (and ResponsePhrase -> Source, the one reversed use, in `ResponsePhraseSeed`) | `RELATED_TO` (`relevant_to`, `supersedes`) |
 * | `TRUSTS` User -> Source | `PART_OF` item -> Project, Project -> Domain |
 * | `INVITED`, `VERIFIED`, `CONFIDENT_IN` (User -> ...) | `ABOUT` item -> Concept |
 * | `FOLLOWS` between Utterances | `FOLLOWS` with `relation = decomposition` (passage -> claim) |
 * | `SELECTED`, `OUTCOME` User -> Phrase | `POSSIBLY_SAME` Concept <-> Concept |
 * | `FOCUSES_ON` User -> target | `CORROBORATES`, `CONFLICTS_WITH` claim <-> claim |
 * | `CONTAINS` Source -> Chunk, `QUOTES` item -> Chunk | |
 * | `PROGRESS_OF` item -> item, `HAS_CHANGE` target -> ChangeRecord | |
 *
 * The list is read from every `newEdge` call in `src/main` (see `docs/graph-schema.md`). A new edge that must be read backward either passes `true`
 * (and then every deletion of it is visible from both ends) or is read with SQL as above; the existing graph already contains
 * `false` edges that cannot be changed in place, so a reader of `ASSERTS` or `TRUSTS` must always use SQL.
 */
internal object HorizonOwnership {

    fun findUserVertex(db: Database, userEmail: String): Vertex? = db.query(
        "sql", "SELECT FROM User WHERE email = :email", mapOf("email" to userEmail),
    ).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    /** The small set of Source uids [userEmail] trusts — bounded by that user's own out-degree, not graph size. */
    fun trustedSourceUids(db: Database, userEmail: String): List<String> {
        val userVertex = findUserVertex(db, userEmail) ?: return emptyList()
        return userVertex.getVertices(Vertex.DIRECTION.OUT, "TRUSTS").mapNotNull { it.get("uid") as? String }
    }

    /** Every `ASSERTS` edge asserting [phraseUid] whose Source is trusted by [userEmail] — empty if not owned or nonexistent. */
    fun ownedAssertsEdges(db: Database, userEmail: String, phraseUid: String): List<Edge> {
        val sourceUids = trustedSourceUids(db, userEmail)
        if (sourceUids.isEmpty()) return emptyList()
        return db.query(
            "sql",
            "SELECT FROM ASSERTS WHERE @in.uid = :phraseUid AND @out.uid IN :sourceUids",
            mapOf("phraseUid" to phraseUid, "sourceUids" to sourceUids),
        ).use { rs ->
            val out = mutableListOf<Edge>()
            while (rs.hasNext()) out += rs.next().toElement().asEdge()
            out
        }
    }

    /** Resolves [phraseUid] only if it is reachable from [userEmail]'s own `TRUSTS -> Source -> ASSERTS` traversal. */
    fun findPhraseOwnedByUser(db: Database, userEmail: String, phraseUid: String): Vertex? {
        if (ownedAssertsEdges(db, userEmail, phraseUid).isEmpty()) return null
        return db.query("sql", "SELECT FROM Phrase WHERE uid = :u", mapOf("u" to phraseUid))
            .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
    }
}
