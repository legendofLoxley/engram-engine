package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.cognitive.UserGraphService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Handles first-session onboarding for invited users in the closed beta.
 *
 * Integrates into [CognitivePipeline] as an early interceptor:
 *   - [CognitivePipeline.initSession] runs [detectFirstSession] + [handleTurn1].
 *
 * Identity is established via OAuth — email == seeded email — so no runtime quiz
 * or LLM answer-matching is needed. [handleTurn1] returns the warm provenance intro
 * drawn from the INVITED edge's [UserGraphService.InvitedEdgeRecord.openingContext],
 * calibrated by trustPhase and attributed to Jacob.
 *
 * Service-layer separation mirrors [app.alfrd.engram.api.OnboardingService]:
 * all graph I/O goes through [UserGraphService].
 */
class FirstSessionHandler(
    internal val userGraphService: UserGraphService,
    private val sessionManager: SessionManager,
) {

    private val logger = LoggerFactory.getLogger(FirstSessionHandler::class.java)

    companion object {
        // Owner account: always simulate a first-session invitee for repeatable flow testing.
        private const val OWNER_EMAIL = "jmac@primarykey.consulting"
        private val OWNER_SYNTHETIC_INVITE = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "Jacob is the app owner, testing the invite flow",
            trustPhase          = "Confidant",
            engagementIntent    = "testing",
            timestamp           = 0L,
            openingContext      = "Jacob here — you're running as the owner account, so you're seeing the first-session warm intro path.",
        )

        const val CLOSED_BETA_REJECTION =
            "alfrd is in closed beta right now. If you think you should have access, reach out to Jacob."
        const val SEEDING_ERROR =
            "Your invite isn't fully set up yet — something's missing on Jacob's end. Let him know and he can fix it."
    }

    data class DetectionResult(
        val isFirstSession: Boolean,
        val invitedEdge: UserGraphService.InvitedEdgeRecord? = null,
    )

    data class Turn1Result(
        val response: String,
        val rejected: Boolean = false,
        val seedingError: Boolean = false,
        val invitedEdge: UserGraphService.InvitedEdgeRecord? = null,
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
        if (userEmail == OWNER_EMAIL) {
            return DetectionResult(isFirstSession = true, invitedEdge = OWNER_SYNTHETIC_INVITE)
        }

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
     * Generates the Turn 1 system response: the warm provenance intro.
     *
     * - No INVITED edge → closed-beta rejection (caller falls through to normal greeting).
     * - INVITED edge with blank/missing openingContext → visible seeding error (NOT a silent fallback).
     * - INVITED edge with openingContext → warm intro calibrated by trustPhase, attributed to Jacob.
     */
    fun handleTurn1(detection: DetectionResult): Turn1Result {
        val edge = detection.invitedEdge
            ?: return Turn1Result(response = CLOSED_BETA_REJECTION, rejected = true)

        val openingContext = edge.openingContext?.takeIf { it.isNotBlank() }
            ?: return Turn1Result(response = SEEDING_ERROR, seedingError = true, invitedEdge = edge)

        val greeting = when (edge.trustPhase.trim().lowercase()) {
            "confidant" -> "Good to finally talk. $openingContext"
            "colleague" -> "Good to hear from you. $openingContext"
            else        -> "Good to meet you. $openingContext"  // Acquaintance / default
        }

        return Turn1Result(response = greeting, invitedEdge = edge)
    }
}
