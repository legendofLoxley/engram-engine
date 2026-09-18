package app.alfrd.engram.cognitive.pipeline.hermes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HermesDelegationTriggerTest {

    @Test
    fun `matches when the fixture name and an inspection verb are both present`() {
        assertTrue(HermesDelegationTrigger.detect("Can you check director-hermes-fixture.txt for me?"))
        assertTrue(HermesDelegationTrigger.detect("please inspect DIRECTOR-HERMES-FIXTURE.TXT"))
        assertTrue(HermesDelegationTrigger.detect("read director-hermes-fixture.txt"))
        assertTrue(HermesDelegationTrigger.detect("could you look at director-hermes-fixture.txt"))
        assertTrue(HermesDelegationTrigger.detect("open director-hermes-fixture.txt and tell me what's inside"))
    }

    @Test
    fun `does not match the filename alone without an inspection verb`() {
        assertFalse(HermesDelegationTrigger.detect("director-hermes-fixture.txt is a funny filename"))
    }

    @Test
    fun `does not match an inspection verb without the filename`() {
        assertFalse(HermesDelegationTrigger.detect("can you check the weather for me?"))
    }

    @Test
    fun `does not match unrelated conversation`() {
        assertFalse(HermesDelegationTrigger.detect("What time does school start?"))
    }
}

class HermesDelegationTriggerApprovedDocumentsTest {

    @Test
    fun `the fixture-marker trigger does not fire for an approved document's filename`() {
        HermesDelegationTrigger.APPROVED_DOCUMENTS.forEach { doc ->
            assertFalse(HermesDelegationTrigger.detect("please check ${doc.filename}"), "matched on ${doc.filename}")
        }
    }

    @Test
    fun `at least two approved documents exist, with distinct filenames — required for a real clarify case`() {
        val filenames = HermesDelegationTrigger.APPROVED_DOCUMENTS.map { it.filename }
        assertTrue(filenames.size >= 2, "need at least two approved documents to exercise ambiguity/clarification")
        assertEquals(filenames.size, filenames.distinct().size, "approved document filenames must be unique")
    }
}
