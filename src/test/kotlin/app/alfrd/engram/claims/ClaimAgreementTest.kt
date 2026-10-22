package app.alfrd.engram.claims

import app.alfrd.engram.db.ChunkRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The establishment rule on its own: no model, no graph. */
class ClaimAgreementTest {

    private val resolver = ClaimantResolver(mapOf("Portland Public Schools" to listOf("PPS", "the District", "District")))
    private fun chunk(text: String) = ChunkRecord("c1", "s1", 0, "page 2", text, "h")
    private fun claim(quote: String, claimant: String? = "PPS", place: String? = null, subject: String = "x", kind: String = "fact") =
        PassClaim(quote, claimant, subject, kind, place)

    private val text = "Scenario A closes 14 schools.\nThe Board voted 5 to 2 on Tuesday."

    @Test
    fun `a claim both passes report with the same quote and claimant is agreed`() {
        val r = ClaimAgreement.compare(chunk(text), listOf(claim("Scenario A closes 14 schools.")), listOf(claim("Scenario A closes 14 schools.")), resolver)
        assertEquals(1, r.outcome.agreed.size)
        assertTrue(r.outcome.disagreements.isEmpty())
        assertEquals("Portland Public Schools", r.outcome.agreed.single().claimant)
        assertEquals("PPS", r.outcome.agreed.single().claimantAsWritten)
    }

    @Test
    fun `aliases of one claimant agree, and the canonical name is stored`() {
        val r = ClaimAgreement.compare(
            chunk(text), listOf(claim("Scenario A closes 14 schools.", "PPS")), listOf(claim("Scenario A closes 14 schools.", "The District")), resolver,
        )
        assertEquals(1, r.outcome.agreed.size)
        assertEquals("Portland Public Schools", r.outcome.agreed.single().claimant)
    }

    @Test
    fun `different claimants are a disagreement that keeps both versions, and nothing is agreed`() {
        val r = ClaimAgreement.compare(
            chunk(text), listOf(claim("The Board voted 5 to 2 on Tuesday.", "The Board")), listOf(claim("The Board voted 5 to 2 on Tuesday.", "The District")), resolver,
        )
        assertTrue(r.outcome.agreed.isEmpty())
        val d = r.outcome.disagreements.single()
        assertEquals("passes differ on the claimant", d.reason)
        assertEquals("The Board", d.versionA!!.claimant)
        assertEquals("Portland Public Schools", d.versionB!!.claimant)
    }

    @Test
    fun `a claim only one pass reports is retained, not established`() {
        val r = ClaimAgreement.compare(chunk(text), listOf(claim("Scenario A closes 14 schools.")), emptyList(), resolver)
        assertTrue(r.outcome.agreed.isEmpty())
        assertEquals("only pass 1 reported it", r.outcome.disagreements.single().reason)
        val back = ClaimAgreement.compare(chunk(text), emptyList(), listOf(claim("Scenario A closes 14 schools.")), resolver)
        assertEquals("only pass 2 reported it", back.outcome.disagreements.single().reason)
        assertNull(back.outcome.disagreements.single().versionA); assertNotNull(back.outcome.disagreements.single().versionB)
    }

    @Test
    fun `a quote that is not in the chunk is rejected and retained, and cannot be agreed even when both passes invent it`() {
        val invented = claim("Scenario A closes 20 schools.")
        val r = ClaimAgreement.compare(chunk(text), listOf(invented), listOf(invented), resolver)
        assertTrue(r.outcome.agreed.isEmpty())
        assertEquals(2, r.rejected)
        assertEquals(setOf(ClaimAgreement.REJECTED), r.outcome.disagreements.map { it.reason }.toSet())
    }

    @Test
    fun `a quote that differs only in spacing or line breaks is stored as the chunk's own text`() {
        val chunkText = "Scenario A closes\n14 schools   in total."
        val r = ClaimAgreement.compare(chunk(chunkText), listOf(claim("Scenario A closes 14 schools in total.")), listOf(claim("Scenario A closes\n14 schools   in total.")), resolver)
        assertEquals(chunkText, r.outcome.agreed.single().quote)
        assertTrue(chunkText.contains(r.outcome.agreed.single().quote))
    }

    @Test
    fun `a quote that differs in a word is not the same quote`() {
        val r = ClaimAgreement.compare(chunk(text), listOf(claim("Scenario A closes 14 schools.")), listOf(claim("Scenario A closes 14 schools")), resolver)
        assertTrue(r.outcome.agreed.isEmpty(), "a shorter quote is a different quote")
        assertEquals(2, r.outcome.disagreements.size)
    }

    @Test
    fun `two passes naming different places in the chunk do not agree, and one naming none does not block agreement`() {
        val q = "Scenario A closes 14 schools."
        val differ = ClaimAgreement.compare(chunk(text), listOf(claim(q, place = "card A")), listOf(claim(q, place = "card B")), resolver)
        assertTrue(differ.outcome.agreed.isEmpty())
        assertEquals("passes differ on the place", differ.outcome.disagreements.single().reason)
        val one = ClaimAgreement.compare(chunk(text), listOf(claim(q, place = "card A")), listOf(claim(q)), resolver)
        assertEquals("card A", one.outcome.agreed.single().place)
        val same = ClaimAgreement.compare(chunk(text), listOf(claim(q, place = "Card  A")), listOf(claim(q, place = "card a")), resolver)
        assertEquals(1, same.outcome.agreed.size)
    }

    @Test
    fun `the same claim reported twice by one pass is one claim`() {
        val q = claim("Scenario A closes 14 schools.")
        val r = ClaimAgreement.compare(chunk(text), listOf(q, q.copy(subject = "again")), listOf(q), resolver)
        assertEquals(1, r.outcome.agreed.size)
        assertTrue(r.outcome.disagreements.isEmpty())
    }

    @Test
    fun `a passage both passes attribute to two claimants is retained whole and establishes neither`() {
        val q = "The Board voted 5 to 2 on Tuesday."
        val both = listOf(claim(q, "The Board"), claim(q, "The District"))
        val r = ClaimAgreement.compare(chunk(text), both, both, resolver)
        assertTrue(r.outcome.agreed.isEmpty())
        assertEquals(setOf(ClaimAgreement.SPLIT_CLAIMANT), r.outcome.disagreements.map { it.reason }.toSet())
    }

    @Test
    fun `an unknown kind is read as a fact and a claim with no claimant keeps none`() {
        val r = ClaimAgreement.compare(chunk(text), listOf(claim("Scenario A closes 14 schools.", null, kind = "banana")), listOf(claim("Scenario A closes 14 schools.", "  ")), resolver)
        val a = r.outcome.agreed.single()
        assertNull(a.claimant)
        assertEquals(ClaimKind.FACT, a.kind)
    }

    @Test
    fun `an alias list that gives one name to two claimants is refused`() {
        assertThrows<IllegalArgumentException> { ClaimantResolver(mapOf("A" to listOf("Board"), "B" to listOf("the board"))) }
    }

    @Test
    fun `claimants compare without case, punctuation or a leading the, and unlisted names stand for themselves`() {
        assertEquals("Portland Public Schools", resolver.canonical("  pps. "))
        assertEquals("Portland Public Schools", resolver.canonical("The   District"))
        assertEquals("Superintendent Smith", resolver.canonical(" Superintendent   Smith "))
        assertNull(resolver.canonical(null)); assertNull(resolver.canonical("   "))
        assertEquals(ClaimantResolver.key("The School Board"), ClaimantResolver.key("school board."))
    }

    @Test
    fun `an alias list reads from json`() {
        val r = ClaimantResolver.parse("""{"Portland Public Schools": ["PPS", "the District"]}""")
        assertEquals("Portland Public Schools", r.canonical("PPS"))
        assertThrows<IllegalArgumentException> { ClaimantResolver.parse("""{"X": "PPS"}""") }
    }

    @Test
    fun `a place that is a stretch of copied text is rejected and retained, not used as a place`() {
        val q = "Scenario A closes 14 schools."
        val long = "x ".repeat(ClaimAgreement.MAX_PLACE_CHARS)
        val r = ClaimAgreement.compare(chunk(text), listOf(claim(q, place = long)), listOf(claim(q, place = long)), resolver)
        assertTrue(r.outcome.agreed.isEmpty())
        assertEquals(2, r.rejected)
        assertEquals(setOf(ClaimAgreement.PLACE_TOO_LONG), r.outcome.disagreements.map { it.reason }.toSet())
        assertTrue(r.outcome.disagreements.all { (it.versionA ?: it.versionB)!!.place!!.length <= ClaimAgreement.MAX_PLACE_CHARS + 1 }, "the retained version is shortened")
        val ok = ClaimAgreement.compare(chunk(text), listOf(claim(q, place = "SCENARIO A")), listOf(claim(q, place = "SCENARIO A")), resolver)
        assertEquals(1, ok.outcome.agreed.size)
    }
}
