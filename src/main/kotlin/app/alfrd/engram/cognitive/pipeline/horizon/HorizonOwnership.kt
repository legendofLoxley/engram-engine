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
 * Ownership is resolved via SQL `@in.uid`/`@out.uid` filtering on `ASSERTS`, never via
 * `Vertex.getEdges(DIRECTION.IN, "ASSERTS")` object-graph traversal — every `ASSERTS` edge in this
 * codebase (including the untouched conversational `DatabaseEngramClient.ingest()` path) is created
 * with `bidirectional=false`, which leaves incoming-direction object-graph traversal silently empty
 * (the same footgun the preceding increment documented for `RELATED_TO`). SQL `@in`/`@out`
 * dot-path filtering is unaffected by the `bidirectional` flag and is the pattern
 * [ArcadeHorizonAssembler] already relies on.
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
