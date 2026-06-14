package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.posture.TurnShape
import app.alfrd.engram.cognitive.pipeline.posture.classifyTextPathTurnShape
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests — classifyTextPathTurnShape (pure heuristic, no LLM)
// ─────────────────────────────────────────────────────────────────────────────

class ClassifyTextPathTurnShapeTest {

    private fun shape(utterance: String) = classifyTextPathTurnShape(utterance)

    // ── Disclosure ────────────────────────────────────────────────────────────

    @Test
    fun `my dog's name is Newton classified as Disclosure`() {
        assertEquals(TurnShape.Disclosure, shape("My dog's name is Newton"))
    }

    @Test
    fun `I'm working on alfrd classified as Disclosure`() {
        assertEquals(TurnShape.Disclosure, shape("I'm working on alfrd"))
    }

    @Test
    fun `I was really stressed about the deadline classified as Disclosure`() {
        assertEquals(TurnShape.Disclosure, shape("I was really stressed about the deadline all week"))
    }

    @Test
    fun `I think we should reconsider classified as Disclosure`() {
        assertEquals(TurnShape.Disclosure, shape("I think we should reconsider the architecture approach"))
    }

    @Test
    fun `my team uses a different process classified as Disclosure`() {
        assertEquals(TurnShape.Disclosure, shape("My team uses a completely different release process"))
    }

    // ── FYI ───────────────────────────────────────────────────────────────────

    @Test
    fun `FYI I pushed the branch classified as FYI`() {
        assertEquals(TurnShape.FYI, shape("FYI I pushed the branch"))
    }

    @Test
    fun `just so you know the meeting moved classified as FYI`() {
        assertEquals(TurnShape.FYI, shape("Just so you know the standup is moving to 10am"))
    }

    @Test
    fun `heads up staging is down classified as FYI`() {
        assertEquals(TurnShape.FYI, shape("Heads up, the staging environment is going down for maintenance"))
    }

    // ── Question ─────────────────────────────────────────────────────────────

    @Test
    fun `what's the weather like classified as Question`() {
        assertEquals(TurnShape.Question, shape("What's the weather like?"))
    }

    @Test
    fun `how does the memory bridge work classified as Question`() {
        assertEquals(TurnShape.Question, shape("How does the memory bridge work exactly?"))
    }

    @Test
    fun `question with trailing question mark classified as Question`() {
        assertEquals(TurnShape.Question, shape("Can you remind me what we decided last time?"))
    }

    // ── TaskRequest ───────────────────────────────────────────────────────────

    @Test
    fun `can you update the task status classified as TaskRequest`() {
        assertEquals(TurnShape.TaskRequest, shape("Can you update the task status?"))
    }

    @Test
    fun `please schedule a call classified as TaskRequest`() {
        assertEquals(TurnShape.TaskRequest, shape("Please schedule a call with Maya for Friday"))
    }

    @Test
    fun `find the notes from Thursday classified as TaskRequest`() {
        assertEquals(TurnShape.TaskRequest, shape("Can you find the notes from Thursday's standup?"))
    }

    // ── Continuation ─────────────────────────────────────────────────────────

    @Test
    fun `heavy filler utterance classified as Continuation`() {
        assertEquals(TurnShape.Continuation, shape("um uh um like I was thinking about the architecture"))
    }

    // ── Correction ───────────────────────────────────────────────────────────

    @Test
    fun `word restart classified as Correction`() {
        assertEquals(TurnShape.Correction, shape("I I meant to say Tuesday not Wednesday afternoon"))
    }

    // ── Null (no explicit signal) ─────────────────────────────────────────────

    @Test
    fun `ambiguous gibberish returns null`() {
        assertNull(shape("Blah blorp zam"))
    }

    @Test
    fun `task imperative remind returns null (not in TASK_REQUEST_PATTERN)`() {
        // "remind" is an IntentType TASK imperative but not in TASK_REQUEST_PATTERN;
        // classifyTextPathTurnShape returns null and intent routing picks it up as TASK.
        assertNull(shape("Remind me to call the vet tomorrow"))
    }

    @Test
    fun `very short utterance returns null`() {
        assertNull(shape("yeah"))
    }

    @Test
    fun `two-word utterance returns null`() {
        assertNull(shape("not sure"))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Integration tests — Comprehension populates ctx.turnShape
// ─────────────────────────────────────────────────────────────────────────────

class ComprehensionTurnShapeTest {

    private val comprehension = Comprehension()

    private suspend fun turnShape(utterance: String): TurnShape? {
        val ctx = CognitiveContext(utterance = utterance, sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        return ctx.turnShape
    }

    @Test
    fun `my dog's name is Newton yields Disclosure from Comprehension`() = runTest {
        assertEquals(TurnShape.Disclosure, turnShape("My dog's name is Newton"))
    }

    @Test
    fun `FYI I pushed the branch yields FYI from Comprehension`() = runTest {
        assertEquals(TurnShape.FYI, turnShape("FYI I pushed the branch"))
    }

    @Test
    fun `I'm working on alfrd yields Disclosure from Comprehension`() = runTest {
        assertEquals(TurnShape.Disclosure, turnShape("I'm working on alfrd"))
    }

    @Test
    fun `what's the weather yields Question from Comprehension`() = runTest {
        assertEquals(TurnShape.Question, turnShape("What's the weather like?"))
    }

    @Test
    fun `can you update yields TaskRequest from Comprehension`() = runTest {
        assertEquals(TurnShape.TaskRequest, turnShape("Can you update the task status?"))
    }

    @Test
    fun `ambiguous utterance yields null turnShape from Comprehension`() = runTest {
        assertNull(turnShape("Blah blorp zam"))
    }

    @Test
    fun `intent classification is independent of turnShape`() = runTest {
        // turnShape is supplementary — intent is still set normally
        val ctx = CognitiveContext(utterance = "My dog's name is Newton", sessionId = "s", userId = "u")
        comprehension.evaluate(ctx)
        assertEquals(TurnShape.Disclosure, ctx.turnShape)
        // intent falls back to AMBIGUOUS since no Tier 1 rule fires for this input
        assertEquals(IntentType.AMBIGUOUS, ctx.intent)
    }
}
