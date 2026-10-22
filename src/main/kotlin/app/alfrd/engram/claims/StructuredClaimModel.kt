package app.alfrd.engram.claims

import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.SourceRecord
import app.alfrd.engram.vision.PageStructure
import app.alfrd.engram.vision.VisionReading

/**
 * Claims from a vision reading's checked entries, without a model call: each number, list item and table cell in a vision chunk is one claim,
 * quoted by its line of the chunk text, placed under its top heading (when it is short enough to be a place), its sub-headings (and a number's label) as the subject. Both passes are
 * the same reading, so they agree by construction; the independent check happened when the chunk was made (the vision model against the
 * page's text). Reads only vision chunks. The run's model name is the vision model that read the page, from the chunk.
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
            )
        }
        return PassResult(claims, "structure:" + (chunk.extractor ?: "vision"))
    }
}
