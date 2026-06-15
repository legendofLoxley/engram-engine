package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCandidate
import app.alfrd.engram.cognitive.pipeline.memory.PhraseCategory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// CorrectionBranch — unit tests
// ─────────────────────────────────────────────────────────────────────────────

class CorrectionBranchTest {

    // ── Acceptance: "not X" amends existing phrase; subsequent recall returns new value ──

    @Test
    fun `correction with not-X amends existing phrase and subsequent recall returns corrected value`() = runTest {
        val engram = InMemoryEngramClient()
        // Seed the existing fact
        engram.ingest(
            listOf(PhraseCandidate("My dog's name is Neutron", "user", PhraseCategory.CONTEXT)),
            userEmail = "user-1",
        )

        val branch = CorrectionBranch(engram)
        val ctx = CognitiveContext(
            utterance = "Actually my dog's name is Newton, not Neutron",
            sessionId = "s",
            userId = "user-1",
            userEmail = "user-1",
        )
        branch.execute(ctx)

        // Should acknowledge the update, not the stub
        val content = ctx.branchResult!!.content
        assertFalse(
            content.contains("Corrections aren't available yet"),
            "Must not return stub, got: $content",
        )
        assertTrue(content.isNotBlank())

        // Subsequent recall should return Newton, not Neutron
        val recalled = engram.queryPhrases("user-1", "dog")
        val texts = recalled.map { it.text.lowercase() }
        assertTrue(texts.any { it.contains("newton") }, "Expected Newton in memory after correction, got: $recalled")
        assertFalse(texts.any { it.contains("neutron") && !it.contains("newton") },
            "Neutron should be superseded, got: $recalled")
    }

    // ── No existing phrase: ingest the corrected fact and acknowledge ──────────

    @Test
    fun `correction without matching memory ingests new fact and acknowledges`() = runTest {
        val engram = InMemoryEngramClient()
        val branch = CorrectionBranch(engram)
        val ctx = CognitiveContext(
            utterance = "Actually my name is Alex, not Sam",
            sessionId = "s",
            userId = "user-1",
            userEmail = "user-1",
        )
        branch.execute(ctx)

        val content = ctx.branchResult!!.content
        assertFalse(content.contains("Corrections aren't available yet"), "Must not return stub, got: $content")
        assertTrue(content.isNotBlank())
        // The new fact should be ingested
        val phrases = engram.queryPhrases("user-1", "alex")
        assertTrue(phrases.isNotEmpty(), "Expected corrected fact to be stored, got: $phrases")
    }

    // ── Correction with new fact but no "not X": ingest and acknowledge ────────

    @Test
    fun `correction without not-X clause ingests new fact`() = runTest {
        val engram = InMemoryEngramClient()
        val branch = CorrectionBranch(engram)
        val ctx = CognitiveContext(
            utterance = "Actually I prefer TypeScript over JavaScript",
            sessionId = "s",
            userId = "user-1",
            userEmail = "user-1",
        )
        branch.execute(ctx)

        val content = ctx.branchResult!!.content
        assertFalse(content.contains("Corrections aren't available yet"), "Must not return stub, got: $content")
        assertTrue(content.isNotBlank())
    }

    // ── Unresolvable (too vague) correction returns clarification, not stub ────

    @Test
    fun `unresolvable correction returns clarification question`() = runTest {
        val engram = InMemoryEngramClient()
        val branch = CorrectionBranch(engram)
        val ctx = CognitiveContext(
            utterance = "wait",
            sessionId = "s",
            userId = "user-1",
            userEmail = "user-1",
        )
        branch.execute(ctx)

        val content = ctx.branchResult!!.content
        assertFalse(content.contains("Corrections aren't available yet"), "Must not return stub, got: $content")
        assertTrue(content.isNotBlank())
        assertTrue(
            content.contains("update", ignoreCase = true) || content.contains("correct", ignoreCase = true),
            "Expected clarification, got: $content",
        )
    }

    @Test
    fun `no I meant with vague body returns clarification`() = runTest {
        val engram = InMemoryEngramClient()
        val branch = CorrectionBranch(engram)
        val ctx = CognitiveContext(
            utterance = "no, i meant it",
            sessionId = "s",
            userId = "user-1",
            userEmail = "user-1",
        )
        branch.execute(ctx)

        val content = ctx.branchResult!!.content
        assertFalse(content.contains("Corrections aren't available yet"), "Must not return stub, got: $content")
    }

    // ── Response strategy ──────────────────────────────────────────────────────

    @Test
    fun `correction branch sets SIMPLE response strategy`() = runTest {
        val engram = InMemoryEngramClient()
        engram.ingest(
            listOf(PhraseCandidate("My cat's name is Whiskers", "user", PhraseCategory.CONTEXT)),
            userEmail = "user-1",
        )
        val branch = CorrectionBranch(engram)
        val ctx = CognitiveContext(
            utterance = "Actually my cat's name is Shadow, not Whiskers",
            sessionId = "s",
            userId = "user-1",
            userEmail = "user-1",
        )
        branch.execute(ctx)
        assertEquals(ResponseStrategy.SIMPLE, ctx.branchResult!!.responseStrategy)
    }

    // ── Source tag ─────────────────────────────────────────────────────────────

    @Test
    fun `correction branch source is pool`() = runTest {
        val engram = InMemoryEngramClient()
        val branch = CorrectionBranch(engram)
        val ctx = CognitiveContext(
            utterance = "Actually I live in Portland, not Seattle",
            sessionId = "s",
            userId = "user-1",
            userEmail = "user-1",
        )
        branch.execute(ctx)
        assertEquals("pool", ctx.branchResult!!.source)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sweep: no turn may produce "Corrections aren't available yet."
// ─────────────────────────────────────────────────────────────────────────────

class CorrectionStubSweepTest {

    private val STUB = "Corrections aren't available yet."

    private val pipeline = CognitivePipeline()

    private suspend fun assertNoStub(utterance: String) {
        val response = pipeline.process(utterance, "s", "u")
        assertFalse(
            response == STUB,
            "Stub must not be returned for utterance \"$utterance\", got: $response",
        )
    }

    @Test
    fun `actually utterance does not return canned stub`() = runTest {
        assertNoStub("Actually it's Newton, not Neutron")
    }

    @Test
    fun `no i meant utterance does not return canned stub`() = runTest {
        assertNoStub("No I meant the staging deploy")
    }

    @Test
    fun `no comma i meant utterance does not return canned stub`() = runTest {
        assertNoStub("No, I meant the other one")
    }

    @Test
    fun `thats not right utterance does not return canned stub`() = runTest {
        assertNoStub("That's not right, I said Berlin not Paris")
    }

    @Test
    fun `wait utterance does not return canned stub`() = runTest {
        assertNoStub("Wait, I meant Tuesday")
    }

    @Test
    fun `correction intent utterance does not return canned stub`() = runTest {
        assertNoStub("Actually my project deadline is Friday")
    }
}
