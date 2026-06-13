package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.cognitive.UserGraphService
import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.logging.Logger

/**
 * Handles identity verification for first-session users in the closed beta.
 *
 * Integrates into [CognitivePipeline] as an early interceptor:
 *   - [CognitivePipeline.initSession] runs [detectFirstSession] + [handleTurn1].
 *   - [CognitivePipeline.processInternal] checks [FirstSessionState.awaitingIdentityVerification]
 *     and delegates to [handleTurn2] when true.
 *
 * Service-layer separation mirrors [app.alfrd.engram.api.OnboardingService]:
 * all graph I/O goes through [UserGraphService], all LLM calls go through [llmClient].
 */
class FirstSessionHandler(
    internal val userGraphService: UserGraphService,
    private val sessionManager: SessionManager,
    private val llmClient: LlmClient?,
) {

    private val logger = Logger.getLogger(FirstSessionHandler::class.java.name)
    private val json   = Json { ignoreUnknownKeys = true }

    companion object {
        const val CLOSED_BETA_REJECTION =
            "alfrd is in closed beta right now. If you think you should have access, reach out to Jacob."
        const val REASK_PROMPT =
            "Hmm, I'm not sure I have the right context — just to make sure, how do you and Jacob know each other?"
        const val FLAG_MESSAGE =
            "Hmm, I'm not sure I have the right context. Let me flag this for Jacob to sort out. He'll be in touch."

        private const val MATCH_CONFIDENCE_THRESHOLD = 0.6
    }

    data class DetectionResult(
        val isFirstSession: Boolean,
        val invitedEdge: UserGraphService.InvitedEdgeRecord? = null,
    )

    data class Turn1Result(
        val response: String,
        val rejected: Boolean,
        val invitedEdge: UserGraphService.InvitedEdgeRecord? = null,
    )

    data class Turn2Result(
        val response: String,
        val newState: FirstSessionState,
    )

    // ── Detection ──────────────────────────────────────────────────────────────

    /**
     * Detects whether [userId]/[userEmail] is a first-session user.
     *
     * Both checks must pass:
     *   1. [SessionManager.isFirstKnownSession] — no prior session record for this userId.
     *   2. Graph: no outbound SELECTED edges from this User vertex.
     *
     * Marks the userId as seen in SessionManager atomically.
     * On graph failure, assumes not-first-session to avoid blocking returning users.
     */
    suspend fun detectFirstSession(userId: String, userEmail: String): DetectionResult {
        // Fast path: if SessionManager has seen this userId, not a first session.
        if (!sessionManager.isFirstKnownSession(userId)) {
            return DetectionResult(isFirstSession = false)
        }

        // Authoritative graph check: SELECTED edges mean prior interactions.
        val hasSelectedEdges = withContext(Dispatchers.IO) {
            userGraphService.hasSelectedEdges(userId)
        }

        if (hasSelectedEdges) {
            logger.info("detectFirstSession: userId=$userId has SELECTED edges — returning user")
            return DetectionResult(isFirstSession = false)
        }

        // Look up the user vertex and INVITED edge once (needed for VERIFIED check and Turn 1).
        val user = withContext(Dispatchers.IO) { userGraphService.findUserByEmail(userEmail) }
        val invitedEdge = if (user != null) {
            withContext(Dispatchers.IO) { userGraphService.findInvitedEdgeFromJacob(user.uid) }
        } else null

        // VERIFIED edge → identity verification was completed in a prior session.
        // Skip Turn 1 even if there are no SELECTED edges yet (e.g. user verified then left).
        val isVerified = withContext(Dispatchers.IO) {
            userGraphService.hasVerifiedEdge(userEmail)
        }
        if (isVerified) {
            logger.info(
                "detectFirstSession: userId=$userId userVertexId=${user?.uid} " +
                "is VERIFIED with no SELECTED edges — returning verified user, skipping Turn 1"
            )
            return DetectionResult(isFirstSession = false, invitedEdge = invitedEdge)
        }

        logger.info(
            "detectFirstSession: userId=$userId userVertexId=${user?.uid} — " +
            "first session (no SELECTED edges, not VERIFIED, invitedEdge=${invitedEdge != null})"
        )
        return DetectionResult(isFirstSession = true, invitedEdge = invitedEdge)
    }

    // ── Turn 1 ─────────────────────────────────────────────────────────────────

    /**
     * Generates the Turn 1 system response based on the detection result.
     *
     * - No INVITED edge → closed-beta rejection.
     * - INVITED edge exists → greeting + "How do you know Jacob?" calibrated by trustPhase:
     *     - Acquaintance (default): more formal
     *     - Colleague: warm but professional
     *     - Confidant: warmest
     */
    fun handleTurn1(detection: DetectionResult): Turn1Result {
        val edge = detection.invitedEdge
            ?: return Turn1Result(response = CLOSED_BETA_REJECTION, rejected = true)

        val greeting = when (edge.trustPhase.trim().lowercase()) {
            "confidant" -> "Good to finally talk. How do you know Jacob?"
            "colleague" -> "Good to hear from you. How do you know Jacob?"
            else        -> "Good to meet you. How do you know Jacob?"  // Acquaintance / default
        }

        return Turn1Result(response = greeting, rejected = false, invitedEdge = edge)
    }

    // ── Turn 2 ─────────────────────────────────────────────────────────────────

    /**
     * Processes the user's identity verification response.
     *
     * Returns a [Turn2Result] with the next response text and updated [FirstSessionState].
     *
     * Retry logic: one reask is allowed when confidence is low or the response is off-topic.
     * On the second failure, the user is flagged for Jacob's review and the flow ends.
     */
    suspend fun handleTurn2(
        userId: String,
        userResponse: String,
        currentState: FirstSessionState,
    ): Turn2Result {
        val relationshipContext = currentState.relationshipContext ?: ""

        // Detect off-topic / non-answer before spending an LLM call.
        if (isLikelyOffTopic(userResponse)) {
            return if (currentState.retryCount < 1) {
                Turn2Result(
                    response = REASK_PROMPT,
                    newState = currentState.copy(retryCount = currentState.retryCount + 1),
                )
            } else {
                flagUser(userId, userResponse, relationshipContext, 0.0,
                    "Off-topic on second attempt", currentState)
            }
        }

        val matchResult = runVerificationLlm(userResponse, relationshipContext)

        if (matchResult == null) {
            // LLM unavailable — be conservative: flag rather than silently verify.
            logger.warning("Verification LLM unavailable for userId=$userId; flagging for Jacob review")
            return flagUser(userId, userResponse, relationshipContext, 0.0,
                "LLM unavailable", currentState)
        }

        return if (matchResult.confidence >= MATCH_CONFIDENCE_THRESHOLD) {
            onVerified(userId, userResponse, currentState, matchResult)
        } else if (currentState.retryCount < 1) {
            Turn2Result(
                response = REASK_PROMPT,
                newState = currentState.copy(retryCount = currentState.retryCount + 1),
            )
        } else {
            flagUser(userId, userResponse, relationshipContext,
                matchResult.confidence, matchResult.reasoning, currentState)
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private suspend fun onVerified(
        userId: String,
        userResponse: String,
        currentState: FirstSessionState,
        matchResult: MatchResult,
    ): Turn2Result {
        // Write VERIFIED edge asynchronously — non-fatal if it fails.
        withContext(Dispatchers.IO) {
            userGraphService.writeVerifiedEdge(userId, System.currentTimeMillis())
        }

        val trustPhase = currentState.trustPhase ?: "Acquaintance"
        val opener = when (trustPhase.trim().lowercase()) {
            "confidant" -> "Good to finally talk. What's got your attention lately?"
            "colleague" -> "Jacob's told me a bit about you. What's on your plate this week?"
            else        -> "Good to meet you. What are you working on right now?"
        }

        logger.info(
            "Identity verified for userId=$userId confidence=${matchResult.confidence} " +
            "reasoning=\"${matchResult.reasoning}\""
        )

        return Turn2Result(
            response = opener,
            newState = currentState.copy(
                awaitingIdentityVerification = false,
                identityVerified             = true,
                trustPhase                   = trustPhase,
                engagementIntent             = currentState.engagementIntent,
            ),
        )
    }

    private fun flagUser(
        userId: String,
        userResponse: String,
        relationshipContext: String,
        confidence: Double,
        reasoning: String,
        currentState: FirstSessionState,
    ): Turn2Result {
        logger.warning(
            "Identity verification mismatch — userId=$userId " +
            "confidence=$confidence reasoning=\"$reasoning\" " +
            "userResponse=\"$userResponse\" relationshipContext=\"$relationshipContext\""
        )
        // TODO: notify Jacob (Notion API / email / in-app alert)
        return Turn2Result(
            response = FLAG_MESSAGE,
            newState = currentState.copy(
                awaitingIdentityVerification = false,
                identityFlagged              = true,
            ),
        )
    }

    private data class MatchResult(
        val match: Boolean,
        val confidence: Double,
        val reasoning: String,
    )

    private suspend fun runVerificationLlm(
        userResponse: String,
        relationshipContext: String,
    ): MatchResult? {
        val client = llmClient ?: return null
        return try {
            val response = client.complete(
                LlmRequest(
                    prompt = buildVerificationPrompt(userResponse, relationshipContext),
                    model  = LlmModel.CLAUDE_SONNET_4_5,
                    maxTokens  = 200,
                    timeoutMs  = 15_000,
                )
            )
            parseMatchResult(response.text)
        } catch (e: Exception) {
            logger.warning("Verification LLM call failed: ${e.message}")
            null
        }
    }

    private fun buildVerificationPrompt(userResponse: String, relationshipContext: String): String =
        """
        You are verifying a user's identity for a closed beta. Jacob described his relationship with this person as:

        "$relationshipContext"

        The user, when asked "How do you know Jacob?", responded:

        "$userResponse"

        Does the user's description plausibly match Jacob's? They don't need to use the same words — just describe a consistent relationship. Consider: same context (work, personal, school), compatible roles, no contradictions.

        Respond with JSON:
        {
          "match": true | false,
          "confidence": 0.0-1.0,
          "reasoning": "brief explanation"
        }
        """.trimIndent()

    private fun parseMatchResult(text: String): MatchResult? {
        return try {
            val jsonStart = text.indexOf('{')
            val jsonEnd   = text.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < 0) return null
            val obj = json.parseToJsonElement(text.substring(jsonStart, jsonEnd + 1)).jsonObject
            MatchResult(
                match      = obj["match"]?.jsonPrimitive?.boolean ?: false,
                confidence = obj["confidence"]?.jsonPrimitive?.double ?: 0.0,
                reasoning  = obj["reasoning"]?.jsonPrimitive?.content ?: "",
            )
        } catch (e: Exception) {
            logger.warning("Failed to parse verification LLM response: ${e.message}")
            null
        }
    }

    private fun isLikelyOffTopic(response: String): Boolean {
        val trimmed = response.trim().lowercase()
        if (trimmed.length < 5) return true
        val shortNonAnswers = setOf(
            "yes", "no", "ok", "okay", "sure", "fine", "maybe", "idk", "um", "uh", "hmm", "hm"
        )
        return trimmed in shortNonAnswers
    }
}
