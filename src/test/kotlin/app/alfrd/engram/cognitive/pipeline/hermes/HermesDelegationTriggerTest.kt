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

    @Test
    fun `exactly two approved documents are committed — a scratch grounding-check document used for live verification must never be left registered`() {
        // The grounding-instruction increment temporarily registered a third, throwaway document
        // here to demonstrate the fix live through the native WebUI, then deliberately reverted
        // this list before committing — this pins that revert, not a permanent catalog size limit.
        // Raising this to 3 on purpose (a genuine new approved document) means updating this test
        // deliberately, not silently leaving a scratch entry behind.
        assertEquals(2, HermesDelegationTrigger.APPROVED_DOCUMENTS.size, HermesDelegationTrigger.APPROVED_DOCUMENTS.map { it.filename }.toString())
    }
}
