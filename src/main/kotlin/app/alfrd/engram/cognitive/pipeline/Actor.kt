package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.PostureMoveType
import app.alfrd.engram.model.ResponseCategory

/** Communication modality for the current turn — drives identity-prompt and modality-filter selection. */
enum class Modality { TEXT, VOICE }

/**
 * Describes what, if anything, the [Script] stage should fetch as grounding material for the
 * actor. Produced by a [Branch] (the director); branches never perform the fetch themselves.
 */
sealed interface RetrievalIntent {
    /** No grounding material needed. */
    data object None : RetrievalIntent

    /** Query the scored ResponsePhrase pool — either by [moveType] (first-response, branch-agnostic) or [branch]+[category]. */
    data class PhrasePool(
        val branch: BranchType? = null,
        val moveType: PostureMoveType? = null,
        val category: ResponseCategory? = null,
        val expressionPhase: ExpressionPhase = ExpressionPhase.FIRST_RESPONSE,
    ) : RetrievalIntent

    /** Query the memory graph for phrases relevant to [hint]. */
    data class MemoryQuery(val hint: String, val limit: Int = 5) : RetrievalIntent

    /** Resolve a correction: amend the phrase matching [supersededValue], or ingest [newFact] fresh. */
    data class Correction(val supersededValue: String?, val newFact: String) : RetrievalIntent
}

/** Grounding material handed to the actor. Never shown to the user verbatim — it's material, not copy. */
data class RetrievedScript(
    val lines: List<String> = emptyList(),
    val label: String? = null,
)

/**
 * Non-linguistic signals handed to the actor alongside the utterance and retrieved script.
 *
 * [directive] is the branch/director's free-form instruction — never user-facing text.
 * [attunement] is the posture read (turn shape + surface energy), rendered as a short
 * natural-language directive by [app.alfrd.engram.cognitive.pipeline.posture.attunementDirective] —
 * never a lookup into a canned-line pool, and computed independent of which branch fired, so it
 * still reaches the actor even when routing picks the wrong branch for the turn's content.
 * [persona] and [selfDescription] come from a [PersonaSource] (via [Script]), not a literal
 * baked into this file.
 * [topicConfidence] is an epistemic-confidence-only note for the *current turn's* resolved
 * topic — see [app.alfrd.engram.cognitive.pipeline.confidence.TopicConfidenceService] and
 * [app.alfrd.engram.cognitive.pipeline.confidence.topicConfidenceDirective]. It deliberately
 * never carries tone/warmth wording — confidence must never govern tone. [mood] is the separate,
 * session-level tone layer that does that job — see [app.alfrd.engram.cognitive.pipeline.affect.Mood].
 * Neither reads from nor writes to the other.
 * [recentTurns] is short-term conversational continuity — the last few turns of *this session*
 * (both the user's utterance and alfrd's own response), assembled by [CognitivePipeline] and
 * handed in as plain context, never as canned text the graph speaks on its own. Null when the
 * session has no prior turns yet. Without this, the actor has no way to know it already spoke —
 * see [CognitivePipeline.recentTurns] for why that matters.
 */
data class Conditioners(
    val modality: Modality,
    val responseStrategy: ResponseStrategy,
    val directive: String,
    val attunement: String,
    val persona: String,
    val selfDescription: String,
    val topicConfidence: String?,
    val mood: String,
    val recentTurns: String?,
)

/** Result of a single actor composition. [source] is "llm" for a real completion, "degraded" for the failure fallback. */
data class ActorResult(val text: String, val source: String)

/**
 * The mouth. The only component in the pipeline that writes user-facing language.
 *
 * Stateless by contract: [compose] accepts only the utterance, the retrieved script, and
 * conditioners — no [app.alfrd.engram.cognitive.pipeline.memory.EngramClient], no
 * [app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService], no [CognitiveContext].
 * On LLM absence, timeout, or any failure it returns the single centralized [DEGRADED_TEXT] —
 * never a per-branch phrase-pool string dressed up as alfrd speaking.
 */
class Actor(private val llmClient: LlmClient?) {

    companion object {
        const val DEGRADED_TEXT = "System notice: I'm temporarily unable to generate a response. Please try again in a moment."
    }

    suspend fun compose(utterance: String, script: RetrievedScript?, conditioners: Conditioners): ActorResult {
        val client = llmClient ?: return ActorResult(DEGRADED_TEXT, source = "degraded")

        return try {
            val response = client.complete(
                LlmRequest(
                    prompt = utterance,
                    systemPrompt = buildSystemPrompt(conditioners, script),
                    model = LlmModel.CLAUDE_SONNET_4_5,
                    maxTokens = 512,
                    timeoutMs = 20_000,
                )
            )
            ActorResult(response.text, source = "llm")
        } catch (_: Exception) {
            ActorResult(DEGRADED_TEXT, source = "degraded")
        }
    }

    private fun buildSystemPrompt(conditioners: Conditioners, script: RetrievedScript?): String = buildString {
        append(conditioners.persona)
        append("\n\n")
        append(conditioners.selfDescription)
        conditioners.recentTurns?.let { history ->
            append("\n\nRecent conversation so far (context only — do not repeat verbatim, do not treat as something the user just said again):\n")
            append(history)
        }
        conditioners.topicConfidence?.let { note ->
            append("\n\nTopic confidence: ")
            append(note)
        }
        append("\n\n")
        append("Tone: ")
        append(conditioners.mood)
        append("\n\n")
        append(conditioners.attunement)
        append("\n\n")
        append(conditioners.directive)
        val grounding = script?.lines?.takeIf { it.isNotEmpty() }
        if (grounding != null) {
            append("\n\nContext:\n")
            append(grounding.joinToString("\n") { "- $it" })
            append("\nTreat lower-confidence or tentative items as tentative. Be concise and warm.")
        }
    }
}
