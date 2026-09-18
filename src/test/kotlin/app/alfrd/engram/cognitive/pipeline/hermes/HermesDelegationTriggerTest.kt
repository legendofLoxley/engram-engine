package app.alfrd.engram.cognitive.pipeline.hermes

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

class HermesDelegationTriggerDocumentSummaryTest {

    @Test
    fun `matches when the approved document name and a summarize verb are both present`() {
        assertTrue(HermesDelegationTrigger.detectDocumentSummary("Can you summarize director-hermes-project-brief.md for me?"))
        assertTrue(HermesDelegationTrigger.detectDocumentSummary("please summarise DIRECTOR-HERMES-PROJECT-BRIEF.MD"))
        assertTrue(HermesDelegationTrigger.detectDocumentSummary("could you sum up director-hermes-project-brief.md"))
        assertTrue(HermesDelegationTrigger.detectDocumentSummary("recap director-hermes-project-brief.md for me"))
    }

    @Test
    fun `does not match the document name alone without a summarize verb`() {
        assertFalse(HermesDelegationTrigger.detectDocumentSummary("director-hermes-project-brief.md is a funny filename"))
    }

    @Test
    fun `does not match a summarize verb without the document name`() {
        assertFalse(HermesDelegationTrigger.detectDocumentSummary("can you summarize the weather for me?"))
    }

    @Test
    fun `does not match an inspection verb alone — that is the other trigger's job, not this one`() {
        assertFalse(HermesDelegationTrigger.detectDocumentSummary("please check director-hermes-project-brief.md"))
    }

    @Test
    fun `does not match unrelated conversation`() {
        assertFalse(HermesDelegationTrigger.detectDocumentSummary("What time does school start?"))
    }

    @Test
    fun `the fixture-marker trigger does not fire for the document summary filename`() {
        assertFalse(HermesDelegationTrigger.detect("please check director-hermes-project-brief.md"))
    }
}
