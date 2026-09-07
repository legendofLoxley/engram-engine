package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.graph.Edge
import com.arcadedb.graph.Vertex

/**
 * Centralizes edge creation for the two `RELATED_TO` relationTypes this package introduces, so the
 * required `bidirectional=true` can't regress to `false` at a scattered call site.
 *
 * Every pre-existing `RELATED_TO` relationType (is_a/part_of/synonym/antonym) passes `false`
 * because they're only ever queried outward. Both `relevant_to` and `supersedes` are queried
 * *backward* ("what points at X") to answer "is this reactivated" / "is this current" — `false`
 * leaves that incoming traversal silently empty (confirmed empirically by the preceding increment's
 * test). Both therefore use `true` here, despite the older relationTypes' convention.
 */
object RelatedToEdges {

    /** newer ([from]) -RELATED_TO(relevant_to)-> older ([to]). Read backward from [to] to find what's just made it relevant. */
    fun createRelevantTo(from: Vertex, to: Vertex, strength: Double, cycleSeq: Long, createdAt: Long): Edge =
        from.newEdge("RELATED_TO", to, true).apply {
            set("relationType", "relevant_to")
            set("strength", strength)
            set("cycleSeq", cycleSeq)
            set("createdAt", createdAt)
            save()
        }

    /**
     * newer ([newer]) -RELATED_TO(supersedes)-> older ([older]). "Current" = the phrase among a
     * candidate group with no *incoming* `supersedes` edge — see [resolveCurrent], which is why
     * this also needs `bidirectional=true`, not the `false` every other `RELATED_TO` relationType
     * uses.
     */
    fun createSupersedes(newer: Vertex, older: Vertex, cycleSeq: Long, createdAt: Long): Edge =
        newer.newEdge("RELATED_TO", older, true).apply {
            set("relationType", "supersedes")
            set("strength", 1.0)
            set("cycleSeq", cycleSeq)
            set("createdAt", createdAt)
            save()
        }

    /**
     * Among [candidates], returns those with zero incoming `supersedes` edges — normally exactly
     * one. Correct for a linear chain (tested to 2 hops: A ← B ← C, current = C). A branching or
     * competing correction (more than one member with no incoming edge) comes back as a list with
     * more than one entry rather than being silently resolved to a guess — that judgment is left to
     * the propagation task.
     */
    fun resolveCurrent(candidates: List<Vertex>): List<Vertex> =
        candidates.filter { vertex ->
            vertex.getEdges(Vertex.DIRECTION.IN, "RELATED_TO")
                .none { it.get("relationType") as? String == "supersedes" }
        }
}
