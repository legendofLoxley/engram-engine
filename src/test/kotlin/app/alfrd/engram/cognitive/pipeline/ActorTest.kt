package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.AssertionStatus
import app.alfrd.engram.cognitive.pipeline.horizon.AttentionDirective
import app.alfrd.engram.cognitive.pipeline.horizon.BoundedText
import app.alfrd.engram.cognitive.pipeline.horizon.ContextHorizon
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonBudget
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItem
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItemCategory
import app.alfrd.engram.cognitive.pipeline.horizon.ProvenanceKind
import app.alfrd.engram.cognitive.pipeline.horizon.PropagationOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.ReactivationInfo
import app.alfrd.engram.cognitive.pipeline.horizon.SurfacingReason
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [Actor.compose]'s prompt-budget ladder ([recentTurns] first, then [Conditioners.horizonItems]
 * tail-trimmed but never past the last essential item, then an explicit [PromptDebugInfo]-carrying
 * `Failed` floor) plus [HorizonItemsRenderer]'s pure render/caveat functions — see `Actor.kt`'s
 * `assemblePrompt` doc for the priority order this asserts.
 */
class ActorTest {

    private fun baseConditioners(
        recentTurns: String? = null,
        horizonItems: List<HorizonPromptItem> = emptyList(),
        integrityCaveat: String? = null,
    ) = Conditioners(
        modality = Modality.TEXT,
        responseStrategy = ResponseStrategy.SIMPLE,
        directive = "Respond warmly and concisely.",
        attunement = "Neutral turn, no strong signal.",
        persona = "You are Alfrd.",
        selfDescription = "A calm, attentive assistant.",
        topicConfidence = null,
        mood = "warm",
        recentTurns = recentTurns,
        horizonItems = horizonItems,
        integrityCaveat = integrityCaveat,
    )

    // ---- HorizonItemsRenderer.render ----

    private fun justAssertedItem(text: String, cycleSeq: Long) = HorizonItem(
        category = HorizonItemCategory.FACT,
        text = BoundedText(text, truncated = false),
        status = null,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT,
        surfacing = SurfacingReason.JustAsserted(cycleSeq),
        sourceRefs = emptyList(),
        sourceCount = 1,
    )

    private fun dormantOpenItem(text: String, lastChangeCycleSeq: Long) = HorizonItem(
        category = HorizonItemCategory.INTENTION,
        text = BoundedText(text, truncated = false),
        status = AssertionStatus.OPEN,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT,
        surfacing = SurfacingReason.DormantOpen(lastChangeCycleSeq),
        sourceRefs = emptyList(),
        sourceCount = 1,
    )

    private fun activeReactivationItem(text: String, triggeringText: String) = HorizonItem(
        category = HorizonItemCategory.INTENTION,
        text = BoundedText(text, truncated = false),
        status = AssertionStatus.OPEN,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT,
        surfacing = SurfacingReason.ActiveReactivation(
            ReactivationInfo(
                triggeringPhraseUid = "trigger-uid",
                triggeringPhraseText = BoundedText(triggeringText, truncated = false),
                strength = 0.5,
                edgeCycleSeq = 3,
                edgeCreatedAt = 0L,
            ),
        ),
        sourceRefs = emptyList(),
        sourceCount = 1,
    )

    @Test
    fun `render marks ActiveReactivation and JustAsserted essential, DormantOpen droppable, order preserved`() {
        val horizon = ContextHorizon(
            userEmail = "u@test.alfrd.internal",
            asOf = 0L,
            schemaVersion = 1,
            items = listOf(
                activeReactivationItem("Arx build is ready", "Arx developer build finished compiling"),
                justAssertedItem("Newton is my dog", cycleSeq = 5),
                dormantOpenItem("Weekly backup pending review", lastChangeCycleSeq = 1),
            ),
            budget = HorizonBudget(maxItems = 12, itemCount = 3, truncated = false),
            omittedSample = emptyList(),
            omittedAtLeast = 0,
            moreCandidatesAvailable = false,
        )

        val rendered = HorizonItemsRenderer.render(horizon)

        assertEquals(3, rendered.size)
        assertTrue(rendered[0].essential, "ActiveReactivation must be essential")
        assertTrue(rendered[0].renderedLine.contains("Arx developer build finished compiling"), "must surface the triggering evidence text")
        assertTrue(rendered[1].essential, "JustAsserted must be essential")
        assertTrue(!rendered[2].essential, "DormantOpen must be droppable")
        assertTrue(rendered[2].renderedLine.contains("noted earlier, still open"))
    }

    // ---- HorizonItemsRenderer.composeIntegrityCaveat ----

    @Test
    fun `composeIntegrityCaveat is null when result is null`() {
        assertNull(HorizonItemsRenderer.composeIntegrityCaveat(null))
    }

    @Test
    fun `composeIntegrityCaveat is null when everything applied and nothing failed`() {
        val result = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = listOf(MutationOutcome.Fact("p1", applied = true), MutationOutcome.Intention("p2", applied = true, quote = "priority")),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = null,
        )
        assertNull(HorizonItemsRenderer.composeIntegrityCaveat(result))
    }

    @Test
    fun `a failed intention write caveats only the intention, a successful fact is never gagged`() {
        val result = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = listOf(
                MutationOutcome.Fact("p1", applied = true),
                MutationOutcome.Intention(null, applied = false, quote = "getting Arx running is a priority"),
            ),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = null,
        )
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat)
        assertTrue(caveat!!.contains("getting Arx running is a priority"))
        assertTrue(caveat.contains("NOT confirmed"))
        assertTrue(!caveat.contains("Something from this turn"), "the successful fact must not be caveated")
    }

    @Test
    fun `a failed fact write produces a general non-recorded caveat`() {
        val result = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = listOf(MutationOutcome.Fact("p1", applied = false)),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = null,
        )
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat)
        assertTrue(caveat!!.contains("not confirmed recorded"))
    }

    @Test
    fun `propagation failure and non-Assembled outcomes each add an honest contextual-awareness caveat`() {
        val propagationFailedResult = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = emptyList(),
            propagationOutcome = PropagationOutcome.Failed("boom"),
            assembleOutcome = null,
        )
        val caveat1 = HorizonItemsRenderer.composeIntegrityCaveat(propagationFailedResult)
        assertNotNull(caveat1)
        assertTrue(caveat1!!.contains("missing some"))

        val assembleFailedResult = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = emptyList(),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = AssembleOutcome.QueryFailure("boom"),
        )
        val caveat2 = HorizonItemsRenderer.composeIntegrityCaveat(assembleFailedResult)
        assertNotNull(caveat2)
        assertTrue(caveat2!!.contains("missing some"))
    }

    // ---- Actor.compose budget ladder ----

    @Test
    fun `everything fits, prompt debug reports Fits with no omissions and all horizon items included`() = runBlocking {
        var receivedRequest: LlmRequest? = null
        val client = TestLlmClient { req ->
            receivedRequest = req
            LlmResponse(text = "The grocery app layout sounds solid.", latencyMs = 1L, retryCount = 0)
        }
        val actor = Actor(client)
        val items = listOf(HorizonPromptItem("\"Arx build is ready\" — new evidence just made this relevant again", essential = true))
        val conditioners = baseConditioners(recentTurns = "user: hi\nalfrd: hello!", horizonItems = items)

        val result = actor.compose("What do you think of this grocery app layout?", script = null, conditioners = conditioners)

        assertEquals("llm", result.source)
        assertEquals("The grocery app layout sounds solid.", result.text)
        assertNotNull(result.promptDebug)
        assertEquals("Fits", result.promptDebug!!.outcome)
        assertTrue(result.promptDebug!!.omissions.isEmpty())
        assertEquals(items, result.promptDebug!!.horizonItemsIncluded)
        assertNotNull(receivedRequest)
        assertTrue(receivedRequest!!.systemPrompt!!.contains("Arx build is ready"))
    }

    @Test
    fun `an oversized recentTurns is dropped first, everything else including horizon items survives`() = runBlocking {
        val client = TestLlmClient { LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val actor = Actor(client)
        // Alone this pushes the system prompt well past the 32,000-char budget; nothing else here is large.
        val hugeRecentTurns = "x".repeat(40_000)
        val items = listOf(HorizonPromptItem("a small horizon note", essential = false))
        val conditioners = baseConditioners(recentTurns = hugeRecentTurns, horizonItems = items)

        val result = actor.compose("short utterance", script = null, conditioners = conditioners)

        assertEquals("llm", result.source)
        assertEquals("Degraded", result.promptDebug!!.outcome)
        assertEquals(listOf("recentTurns"), result.promptDebug!!.omissions)
        assertEquals(items, result.promptDebug!!.horizonItemsIncluded, "horizon items are untouched when dropping recentTurns alone is enough")
        assertTrue(!result.promptDebug!!.finalSystemPrompt!!.contains(hugeRecentTurns))
    }

    @Test
    fun `dormant horizon items are trimmed from the tail one at a time, essential items are never dropped`() = runBlocking {
        val client = TestLlmClient { LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val actor = Actor(client)
        val essential = HorizonPromptItem("essential: new evidence just made this relevant again", essential = true)
        // Each dormant item alone, added to the small essential-only prompt, already exceeds the
        // 32,000-char budget — so the ladder cannot stop early with any dormant item still present;
        // it must keep dropping from the tail until only the essential item remains.
        val dormant = (1..5).map { HorizonPromptItem("dormant note #$it: ".plus("y".repeat(34_000)), essential = false) }
        val items = listOf(essential) + dormant
        val conditioners = baseConditioners(recentTurns = null, horizonItems = items)

        val result = actor.compose("short utterance", script = null, conditioners = conditioners)

        assertEquals("llm", result.source)
        assertEquals("Degraded", result.promptDebug!!.outcome)
        // recentTurns was already null, so the ladder's recentTurns-drop attempt is a documented
        // no-op step — it still records the attempt before moving on to horizon-item trimming.
        assertTrue(result.promptDebug!!.omissions.drop(1).all { it == "horizon item (dormant, lowest priority)" })
        assertTrue(result.promptDebug!!.omissions.count { it == "horizon item (dormant, lowest priority)" } == dormant.size)
        assertEquals(listOf(essential), result.promptDebug!!.horizonItemsIncluded, "only the essential item should survive full dormant trimming")
        assertTrue(result.promptDebug!!.finalSystemPrompt!!.contains("essential: new evidence"))
    }

    @Test
    fun `when even essential-only content does not fit, assembly fails explicitly rather than truncating silently`() = runBlocking {
        var llmCalled = false
        val client = TestLlmClient { llmCalled = true; LlmResponse(text = "should never be reached", latencyMs = 1L, retryCount = 0) }
        val actor = Actor(client)
        // The essential item alone is larger than the whole budget — dropping every dormant item cannot help.
        val hugeEssential = HorizonPromptItem("essential note: ".plus("z".repeat(40_000)), essential = true)
        val conditioners = baseConditioners(recentTurns = null, horizonItems = listOf(hugeEssential))

        val result = actor.compose("short utterance", script = null, conditioners = conditioners)

        assertEquals("degraded", result.source)
        assertEquals(Actor.DEGRADED_TEXT, result.text)
        assertNotNull(result.promptDebug)
        assertTrue(result.promptDebug!!.outcome.startsWith("Failed"), "expected a Failed outcome, got ${result.promptDebug!!.outcome}")
        assertNull(result.promptDebug!!.finalSystemPrompt)
        assertNull(result.promptDebug!!.finalUserPrompt)
        assertTrue(!llmCalled, "the LLM must never be called once assembly has already failed the essential-only floor")
    }

    @Test
    fun `no LLM client configured degrades without ever assembling a prompt`() = runBlocking {
        val actor = Actor(null)
        val result = actor.compose("hi", script = null, conditioners = baseConditioners())
        assertEquals("degraded", result.source)
        assertEquals(Actor.DEGRADED_TEXT, result.text)
        assertNull(result.promptDebug, "no prompt was ever assembled — nothing to report")
    }
}
