package app.alfrd.engram.claims

import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.EntityRules
import app.alfrd.engram.db.SourceRecord
import app.alfrd.engram.vision.PageStructure
import app.alfrd.engram.vision.VisionReading

/**
 * Claims from a vision reading's checked entries, without a model call: each number, list item and table cell in a vision chunk is one claim,
 * quoted by its line of the chunk text, placed under its top heading (when it is short enough to be a place), its sub-headings (and a number's label) as the subject. Both passes are
 * the same reading, so they agree by construction; the independent check happened when the chunk was made (the vision model against the
 * page's text). Reads only vision chunks. The context the check kept (passage, things, period) goes with each claim. The run's model name is the vision model that read the page, from the chunk.
 */
class StructuredClaimModel : ClaimPassModel {
    override val endpoint: String? = null
    override val promptVersion: String = "claims-from-" + PageStructure.VERSION

    override fun reads(chunk: ChunkRecord): Boolean = chunk.kind == VisionReading.CHUNK_KIND

    override suspend fun extract(source: SourceRecord, chunk: ChunkRecord, pass: Int): PassResult {
        val claims = VisionReading.entries(chunk).map { e ->
            // A top heading longer than a place can be (a survey question set as a title) stays in the quote and the subject, not the place.
            val place = e.path.firstOrNull()?.takeIf { it.length <= ClaimAgreement.MAX_PLACE_CHARS }
            val under = if (place == null) e.path else e.path.drop(1)
            PassClaim(
                quote = e.line, claimant = null,
                subject = (under + listOf(e.label).filter { it.isNotBlank() }).joinToString(" > ").ifEmpty { place ?: e.value },
                kind = if (e.kind == "number") ClaimKind.NUMBER.name else ClaimKind.FACT.name,
                place = place,
                passage = e.passage, about = withHeadingThings(e.path, e.about.map { ThingRef(it.name, it.kind, it.parent) }), asOf = e.asOf,
            )
        }
        return PassResult(claims, "structure:" + (chunk.extractor ?: "vision"))
    }

    /**
     * A claim under a heading that names a thing (SCENARIO A, a school's section) is about that thing, whatever the reader said: the
     * headings are the page's own, checked when the chunk was made. A heading is a thing only when its own shape says so ([EntityRules]), and only when the reader named no thing of that kind for the value.
     */
    private fun withHeadingThings(path: List<String>, given: List<ThingRef>): List<ThingRef> {
        val named = path.filter { h -> EntityRules.kindByName(h).let { it == "scenario" || it == "school" } }
            .map { ThingRef(it, EntityRules.kindByName(it).orEmpty()) }
        // What the reader already named stands: a heading adds a thing only of a kind the reader gave none of for this value, so one school
        // called "Clark" by the reader and "Clark Elementary School" by the heading is not two.
        val have = given.map { ClaimantResolver.key(it.name) }.toSet()
        val haveKinds = given.map { EntityRules.kindFor(it.name, it.kind) }.toSet()
        return given + named.filter { ClaimantResolver.key(it.name) !in have && it.kind !in haveKinds }
    }
}
