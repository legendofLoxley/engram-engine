package app.alfrd.engram.claims

import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOwnership
import app.alfrd.engram.db.ConceptRef
import app.alfrd.engram.db.EntityRules
import app.alfrd.engram.db.SourceStore
import app.alfrd.engram.vision.StructureCheck
import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.serialization.Serializable

/** What linking the two readers' claims of one Source did, counted. */
@Serializable
data class LinkReport(
    val sourceUid: String,
    /** Claims read from vision structure, and claims read from text, as the graph holds them. */
    val structureClaims: Int, val textClaims: Int,
    /** Pairs that state the same fact, and pairs that disagree, whether linked by this call or an earlier one; and how many links this call wrote. */
    val corroborated: Int, val conflicts: Int, val newLinks: Int,
    /** Claims no claim of the other reader matches: they stand on their own. */
    val structureOnly: Int, val textOnly: Int,
    val conflictReasons: List<String> = emptyList(),
)

/**
 * Connects the claims the two readers made of one Source: **one fact, one claim, however many readers saw it** (design: "Claims in context",
 * section 3). Nothing is overwritten, deleted or re-established, and the agreement rules of extraction are untouched.
 *
 * - **The same fact:** a structure claim and a text claim on the same page where the structure claim's value is printed in the text
 *   claim's quote, and they share a passage, a thing they are about, or a distinctive word of what the value counts.
 * - **Agree:** the pair is linked `CORROBORATES` (two independent reads). Both claims stay as they are.
 * - **Disagree:** the pair is linked `CONFLICTS_WITH` and both claims go to Re-checking, kept as they were. They disagree when both name
 *   the same kind of thing and no thing in common (two different schools), or both give a period and the periods differ. Things of
 *   different kinds (a scenario on one side, the district on the other) are two true descriptions, not a conflict; a missing thing or period
 *   is a gap, not a conflict.
 * - **One reader only:** the claim stands on its own.
 *
 * Idempotent: a pair is linked once, whichever reader finished last. Links carry the owner (a Phrase is the owner's).
 */
class ClaimLinker(private val db: Database, private val sources: SourceStore, private val claims: ClaimStore) {

    fun link(userEmail: String, sourceUid: String, runId: String): LinkReport {
        val promptByRun = claims.runs(userEmail, sourceUid).associate { it.runId to it.promptVersion }
        val pageOfChunk = sources.chunks(userEmail, sourceUid).associate { it.uid to it.page }
        val all = claims.claims(userEmail, sourceUid)
        val structure = all.filter { isStructure(promptByRun[it.runId]) }
        val text = all.filter { !isStructure(promptByRun[it.runId]) }
        fun pages(c: ClaimItem) = c.chunkUids.mapNotNull { pageOfChunk[it] }.toSet()

        var corroborated = 0; var newLinks = 0; var conflicts = 0
        val reasons = ArrayList<String>()
        val linkedS = HashSet<String>(); val linkedT = HashSet<String>()
        for (s in structure) {
            val sp = pages(s)
            for (t in text) {
                if (sp.isEmpty() || sp.intersect(pages(t)).isEmpty() || !sameFact(s, t)) continue
                linkedS += s.uid; linkedT += t.uid
                val why = conflict(s, t)
                val type = if (why == null) "CORROBORATES" else "CONFLICTS_WITH"
                val isNew = addLink(userEmail, s.uid, t.uid, type, why, runId)
                if (isNew) newLinks++
                if (why == null) corroborated++ else {
                    conflicts++; reasons += why
                    if (isNew) { claims.markRechecking(userEmail, s.uid, runId); claims.markRechecking(userEmail, t.uid, runId) }
                }
            }
        }
        return LinkReport(
            sourceUid, structure.size, text.size, corroborated, conflicts, newLinks,
            structure.count { it.uid !in linkedS }, text.count { it.uid !in linkedT }, reasons.distinct(),
        )
    }

    /** True when a new link was written; false when the pair already had one of this type. */
    private fun addLink(userEmail: String, aUid: String, bUid: String, type: String, reason: String?, runId: String): Boolean {
        var created = false
        db.transaction {
            val a = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, aUid) ?: return@transaction
            val b = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, bUid) ?: return@transaction
            val exists = a.getEdges(Vertex.DIRECTION.OUT, type).any { it.inVertex.identity == b.identity } ||
                a.getEdges(Vertex.DIRECTION.IN, type).any { it.outVertex.identity == b.identity }
            if (exists) return@transaction
            a.modify().newEdge(type, b, true).apply {
                set("ownerEmail", userEmail); set("runId", runId); set("createdAt", System.currentTimeMillis())
                reason?.let { set("reason", it) }
                save()
            }
            created = true
        }
        return created
    }

    companion object {
        /** The structure reader's runs are named for it (`claims-from-structure-v7`). */
        fun isStructure(promptVersion: String?): Boolean = promptVersion?.startsWith("claims-from-") == true

        private val STOP = setOf("with", "that", "this", "from", "than", "have", "were", "will", "their", "which", "under", "more", "about", "other", "each", "into", "also", "such", "only", "students", "student")

        private fun words(s: String): Set<String> =
            Regex("\\p{L}{4,}").findAll(StructureCheck.normalize(s)).map { it.value }.filter { it !in STOP }.toSet()

        /** What the structure claim counts or lists: the part of its line after the label (`heading > label: value`). */
        fun valueOf(c: ClaimItem): String = c.quote.substringAfterLast(": ", c.quote).trim()

        private fun printsValue(text: String, value: String): Boolean {
            val v = StructureCheck.normalize(value)
            if (v.isEmpty()) return false
            val t = StructureCheck.normalize(text)
            if (StructureCheck.occurrences(t, v).isNotEmpty() && v.any { it.isDigit() }) return true
            return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(v) + "(?![\\p{L}\\p{N}])").containsMatchIn(t)
        }

        private fun thingKey(t: ConceptRef) = EntityRules.looseName(t.name)

        fun sameFact(s: ClaimItem, t: ClaimItem): Boolean {
            val value = valueOf(s)
            if (value.isBlank() || !printsValue(t.quote, value)) return false
            val samePassage = s.passage != null && t.passage != null && ClaimAgreement.quoteKey(s.passage) == ClaimAgreement.quoteKey(t.passage)
            val sharedThing = s.about.map(::thingKey).toSet().intersect(t.about.map(::thingKey).toSet()).isNotEmpty()
            val sharedWord = words(s.subject).intersect(words(t.quote + " " + t.subject)).isNotEmpty()
            return samePassage || sharedThing || sharedWord
        }

        /** Why [s] and [t], which state the same fact, disagree; null when they agree (or differ only by a gap). */
        fun conflict(s: ClaimItem, t: ClaimItem): String? {
            for (kind in s.about.map { it.kind }.toSet().intersect(t.about.map { it.kind }.toSet())) {
                val a = s.about.filter { it.kind == kind }.map(::thingKey).toSet(); val b = t.about.filter { it.kind == kind }.map(::thingKey).toSet()
                if (a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isEmpty()) return "the readers name different ${kind}s: ${s.about.first { it.kind == kind }.name} / ${t.about.first { it.kind == kind }.name}"
            }
            val ps = s.asOf; val pt = t.asOf
            if (ps != null && pt != null && period(ps) != period(pt)) return "the readers give different periods: $ps / $pt"
            return null
        }

        private fun period(p: String) = StructureCheck.normalize(p).replace(Regex("\\s+"), " ").trim()
    }
}
