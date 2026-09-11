package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.ContextHorizon
import app.alfrd.engram.cognitive.pipeline.horizon.PropagationOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.SurfacingReason
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
 * One Horizon item, projected down to just what the response prompt needs — decouples [Actor]
 * from the full [app.alfrd.engram.cognitive.pipeline.horizon] type surface while still letting it
 * own budget trimming, which needs per-item granularity (see [Actor.assemblePrompt]).
 * [essential] items ([SurfacingReason.ActiveReactivation]/[SurfacingReason.JustAsserted] — this
 * cycle's freshly-relevant evidence) are never silently dropped by the budget ladder; every other
 * kind ([SurfacingReason.DormantOpen], [SurfacingReason.RecentActorEvidence]) is droppable, and only
 * in the priority order [HorizonItemsRenderer.render] already received them in — bounded-recency
 * eligibility (see that reason's own doc) is not itself a guarantee of a place in the rendered
 * prompt.
 */
data class HorizonPromptItem(val renderedLine: String, val essential: Boolean)

/** Pure functions turning graph-level Horizon/cycle results into what [Actor] renders — kept separate from [Actor] itself so its dependency on [app.alfrd.engram.cognitive.pipeline.horizon] types is contained to one small surface. */
object HorizonItemsRenderer {
    /** [ContextHorizon.items] is already priority-ordered (active reactivations, then just-asserted, then dormant open, per [app.alfrd.engram.cognitive.pipeline.horizon.HorizonAssembler]) — preserved here, not re-sorted. */
    fun render(horizon: ContextHorizon): List<HorizonPromptItem> = horizon.items.map { item ->
        val surfacing = item.surfacing
        val essential = surfacing is SurfacingReason.ActiveReactivation || surfacing is SurfacingReason.JustAsserted
        val framing = when (surfacing) {
            is SurfacingReason.ActiveReactivation ->
                "new evidence just made this relevant again: \"${surfacing.info.triggeringPhraseText.text}\""
            is SurfacingReason.JustAsserted -> "just noted"
            is SurfacingReason.RecentActorEvidence -> "reported recently, independent of this conversation"
            is SurfacingReason.DormantOpen -> "noted earlier, still open"
        }
        val statusNote = item.status?.let { " [${it.name.lowercase()}]" } ?: ""
        HorizonPromptItem(renderedLine = "\"${item.text.text}\"$statusNote — $framing", essential = essential)
    }

    /**
     * One clause per confirmed-or-not operation this cycle, composed so the response never claims
     * a write succeeded when it didn't — never a single blanket flag, since a failed intention
     * write alongside a successful fact write must not also gag the fact that *did* succeed.
     */
    fun composeIntegrityCaveat(result: HorizonCycleResult?): String? {
        if (result == null) return null
        val caveats = mutableListOf<String>()
        if (result.allocationFailed) {
            caveats += "Nothing from this turn could be confirmed recorded to memory — do not say anything was noted, saved, or remembered from what the user just said."
        }
        for (outcome in result.mutationOutcomes) {
            when (outcome) {
                is MutationOutcome.Intention -> if (!outcome.applied) {
                    caveats += "You have NOT confirmed recording \"${outcome.quote}\" as a priority — do not say you have."
                }
                is MutationOutcome.Fact -> if (!outcome.applied) {
                    caveats += "Something from this turn was not confirmed recorded to memory — do not claim it was."
                }
            }
        }
        if (result.interpretOutcome is InterpretOutcome.LlmFailure) {
            caveats += "This turn could not be fully checked for anything you should treat as a new priority — do not claim to have captured one if the user stated one."
        }
        val propagationFailed = result.propagationOutcome is PropagationOutcome.Failed
        val assembleFailed = result.assembleOutcome != null && result.assembleOutcome !is AssembleOutcome.Assembled
        if (propagationFailed || assembleFailed) {
            caveats += "You may be missing some of your usual contextual awareness this turn — do not claim complete recall."
        }
        return caveats.distinct().takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}

/**
 * Non-linguistic signals handed to the actor alongside the utterance and retrieved script.
 *
 * [directive] is the branch/director's free-form instruction — never user-facing text.
 * [attunement] is the posture read (turn shape + surface energy), rendered as a short
 * natural-language directive by [app.alfrd.engram.cognitive.pipeline.posture.attunementDirective] —
 * never a lookup into a canned-line pool, and computed independent of which branch fired, so it
 * still reaches the actor even when routing picks the wrong branch for the turn's actual content.
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
 * session has no prior turns yet.
 * [horizonItems] is the refreshed, graph-derived Context Horizon for this cycle, already rendered
 * to [HorizonPromptItem]s in priority order — see [HorizonItemsRenderer.render]. Empty when the
 * Horizon cycle isn't wired in, produced nothing this cycle, or assembly failed (a failed assembly
 * is never silently treated as "no items"; see [HorizonItemsRenderer.composeIntegrityCaveat] and
 * [CognitivePipeline] for how that distinction is preserved in tracing).
 * [integrityCaveat] is a composed, per-operation instruction not to claim a write succeeded when
 * it didn't — see [HorizonItemsRenderer.composeIntegrityCaveat].
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
    val horizonItems: List<HorizonPromptItem> = emptyList(),
    val integrityCaveat: String? = null,
)

/** Result of a single actor composition. [source] is "llm" for a real completion, "degraded" for the failure fallback. [promptDebug] is always populated — the caller decides whether to surface it (debug trace only; never the plain response). */
data class ActorResult(val text: String, val source: String, val promptDebug: PromptDebugInfo? = null)

/**
 * What was actually sent, after budget handling — controlled debug evidence, per
 * [app.alfrd.engram.cognitive.pipeline.horizon.HorizonCycleTrace]. [finalSystemPrompt]/[finalUserPrompt]
 * are null only when [outcome] describes a [PromptAssemblyOutcome.Failed] floor (nothing was sent).
 */
data class PromptDebugInfo(
    val finalSystemPrompt: String?,
    val finalUserPrompt: String?,
    val omissions: List<String>,
    val outcome: String,
    /** The Horizon items that actually survived budget trimming and were included in [finalSystemPrompt] — controlled debug evidence, structured rather than re-parsed from the prompt text. */
    val horizonItemsIncluded: List<HorizonPromptItem> = emptyList(),
)

/** Result of [Actor.assemblePrompt]'s budget ladder — see that function's doc for the priority order it enforces. */
private sealed interface PromptAssemblyOutcome {
    data class Fits(val systemPrompt: String, val prompt: String, val items: List<HorizonPromptItem>) : PromptAssemblyOutcome
    data class Degraded(val systemPrompt: String, val prompt: String, val omitted: List<String>, val items: List<HorizonPromptItem>) : PromptAssemblyOutcome
    data class Failed(val reason: String) : PromptAssemblyOutcome
}

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

        /**
         * A deliberately conservative slice of the actor model's real context window, reserving
         * room for [MAX_TOKENS] output plus a safety margin — a policy choice favoring predictable
         * behavior over maximizing context usage, not an attempt to measure the model's actual
         * (far larger) limit. [CHARS_PER_TOKEN_ESTIMATE] is a standard, conservative English
         * approximation — this codebase has no tokenizer dependency, so this is a documented proxy,
         * never claimed to be exact. Confirm against the deployed model's documented context
         * window when tuning.
         */
        private const val MAX_INPUT_TOKENS_CONSERVATIVE = 8_000
        private const val CHARS_PER_TOKEN_ESTIMATE = 4
        private const val MAX_INPUT_CHARS = MAX_INPUT_TOKENS_CONSERVATIVE * CHARS_PER_TOKEN_ESTIMATE
        private const val MAX_TOKENS = 512
    }

    suspend fun compose(utterance: String, script: RetrievedScript?, conditioners: Conditioners): ActorResult {
        val client = llmClient ?: return ActorResult(DEGRADED_TEXT, source = "degraded")

        val systemPrompt: String
        val effectivePrompt: String
        val debugInfo: PromptDebugInfo
        when (val assembled = assemblePrompt(utterance, conditioners, script)) {
            is PromptAssemblyOutcome.Fits -> {
                systemPrompt = assembled.systemPrompt
                effectivePrompt = assembled.prompt
                debugInfo = PromptDebugInfo(systemPrompt, effectivePrompt, emptyList(), "Fits", assembled.items)
            }
            is PromptAssemblyOutcome.Degraded -> {
                systemPrompt = assembled.systemPrompt
                effectivePrompt = assembled.prompt
                debugInfo = PromptDebugInfo(systemPrompt, effectivePrompt, assembled.omitted, "Degraded", assembled.items)
            }
            is PromptAssemblyOutcome.Failed -> {
                // The explicit floor: never silently truncate the user's own words to make room.
                // Degrades exactly like an LLM-call failure — never a crash — but traced distinctly.
                return ActorResult(
                    DEGRADED_TEXT,
                    source = "degraded",
                    promptDebug = PromptDebugInfo(null, null, emptyList(), "Failed: ${assembled.reason}"),
                )
            }
        }

        return try {
            val response = client.complete(
                LlmRequest(
                    prompt = effectivePrompt,
                    systemPrompt = systemPrompt,
                    model = LlmModel.CLAUDE_SONNET_4_5,
                    maxTokens = MAX_TOKENS,
                    timeoutMs = 20_000,
                ),
            )
            ActorResult(response.text, source = "llm", promptDebug = debugInfo)
        } catch (_: Exception) {
            ActorResult(DEGRADED_TEXT, source = "degraded", promptDebug = debugInfo)
        }
    }

    /**
     * Priority-ordered degradation, cheapest/least-important dropped first, with a hard floor —
     * never the reverse. Order: (1) try everything; (2) drop [Conditioners.recentTurns] entirely
     * (already tightly bounded — 6 turns × 280 chars — so this alone is usually sufficient, and
     * simpler than progressively trimming individual turns within it); (3) trim
     * [Conditioners.horizonItems] from the tail, one at a time — never past the last
     * [HorizonPromptItem.essential] item, since dropping this cycle's actual fresh evidence would
     * defeat the entire point of wiring the Horizon into the response in the first place; (4) if
     * even persona+directive+the original utterance+only-essential-items still doesn't fit,
     * [PromptAssemblyOutcome.Failed] — never a silent truncation of the user's own message.
     *
     * Measured against [MAX_INPUT_CHARS] over `systemPrompt.length + prompt.length` combined —
     * the Horizon's own [app.alfrd.engram.cognitive.pipeline.horizon.HorizonLimits.serializedByteBudget]
     * bounds only the Horizon's JSON encoding, not this complete request.
     */
    private fun assemblePrompt(utterance: String, conditioners: Conditioners, script: RetrievedScript?): PromptAssemblyOutcome {
        fun build(includeRecentTurns: Boolean, horizonItems: List<HorizonPromptItem>): String = buildString {
            append(conditioners.persona)
            append("\n\n")
            append(conditioners.selfDescription)
            if (includeRecentTurns) {
                conditioners.recentTurns?.let { history ->
                    append("\n\nRecent conversation so far (context only — do not repeat verbatim, do not treat as something the user just said again):\n")
                    append(history)
                }
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
            conditioners.integrityCaveat?.let {
                append("\n\n")
                append(it)
            }
            if (horizonItems.isNotEmpty()) {
                append(
                    "\n\nContextual awareness — background orientation from your durable memory of this " +
                        "user. Use your judgment: mention only what's genuinely relevant to the current " +
                        "exchange; otherwise stay focused on what the user is actually asking.\n",
                )
                append(horizonItems.joinToString("\n") { "- ${it.renderedLine}" })
            }
            val grounding = script?.lines?.takeIf { it.isNotEmpty() }
            if (grounding != null) {
                append("\n\nContext:\n")
                append(grounding.joinToString("\n") { "- $it" })
                append("\nTreat lower-confidence or tentative items as tentative. Be concise and warm.")
            }
        }

        fun fitsBudget(systemPrompt: String) = systemPrompt.length + utterance.length <= MAX_INPUT_CHARS

        var includeRecentTurns = true
        var items = conditioners.horizonItems
        val omitted = mutableListOf<String>()

        var systemPrompt = build(includeRecentTurns, items)
        if (fitsBudget(systemPrompt)) return PromptAssemblyOutcome.Fits(systemPrompt, utterance, items)

        includeRecentTurns = false
        omitted += "recentTurns"
        systemPrompt = build(includeRecentTurns, items)
        if (fitsBudget(systemPrompt)) return PromptAssemblyOutcome.Degraded(systemPrompt, utterance, omitted.toList(), items)

        while (items.isNotEmpty() && !items.last().essential) {
            items = items.dropLast(1)
            omitted += "horizon item (dormant, lowest priority)"
            systemPrompt = build(includeRecentTurns, items)
            if (fitsBudget(systemPrompt)) return PromptAssemblyOutcome.Degraded(systemPrompt, utterance, omitted.toList(), items)
        }

        return PromptAssemblyOutcome.Failed(
            "system+user prompt (${systemPrompt.length + utterance.length} chars) exceeds the " +
                "$MAX_INPUT_CHARS-char budget even with only essential context retained",
        )
    }
}
