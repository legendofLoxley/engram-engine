package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.cognitive.UserGraphService
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// FirstSessionHandler — unit tests for detection and warm provenance intro flow
// ─────────────────────────────────────────────────────────────────────────────

class FirstSessionHandlerTest {

    // ── Test doubles ──────────────────────────────────────────────────────────

    /**
     * Fake [UserGraphService] that lets each test control exactly what the graph returns,
     * without needing a live ArcadeDB instance.
     *
     * Subclasses the real class but passes `null` unsafely — safe here because none of
     * the overridden methods delegate to the parent, so the `db` field is never used.
     */
    @Suppress("UNCHECKED_CAST")
    private class FakeUserGraphService(
        private val userRecord: UserGraphService.UserRecord? = UserGraphService.UserRecord(
            uid = "user-uid-1", email = "alice@example.com", username = "Alice",
        ),
        private val invitedEdge: UserGraphService.InvitedEdgeRecord? = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "We worked together at Acme Corp on the payments team.",
            trustPhase          = "Acquaintance",
            engagementIntent    = "productivity",
            timestamp           = 1_000_000L,
            openingContext      = "Jacob mentioned you from your time together at Acme — said you were great to work with.",
        ),
        private val selectedEdges: Boolean = false,
        private val verifiedEdge: Boolean = false,
        val verifiedEdgeWrites: MutableList<String> = mutableListOf(),
    ) : UserGraphService(null) {

        override fun findUserByEmail(email: String): UserRecord? = userRecord
        override fun findInvitedEdgeFromJacob(userId: String): InvitedEdgeRecord? = invitedEdge
        override fun hasSelectedEdges(userId: String): Boolean = selectedEdges
        override fun hasVerifiedEdge(userEmail: String): Boolean = verifiedEdge
        override fun writeVerifiedEdge(userId: String, timestamp: Long) {
            verifiedEdgeWrites.add(userId)
        }
    }

    private fun buildSessionManager(): SessionManager =
        SessionManager(factory = { CognitivePipeline() })

    private fun buildHandler(
        graphService: FakeUserGraphService = FakeUserGraphService(),
        sessionManager: SessionManager = buildSessionManager(),
    ): FirstSessionHandler = FirstSessionHandler(
        userGraphService = graphService,
        sessionManager   = sessionManager,
    )

    // ── Detection ─────────────────────────────────────────────────────────────

    @Test
    fun `detectFirstSession returns true for brand-new userId with no SELECTED edges`() = runTest {
        val handler = buildHandler(graphService = FakeUserGraphService(selectedEdges = false))
        val result  = handler.detectFirstSession("new-user-1", "alice@example.com")
        assertTrue(result.isFirstSession)
        assertNotNull(result.invitedEdge)
    }

    @Test
    fun `detectFirstSession returns false when SessionManager has already seen userId`() = runTest {
        val sm = buildSessionManager()
        sm.isFirstKnownSession("returning-user")  // marks as seen

        val handler = buildHandler(sessionManager = sm)
        val result  = handler.detectFirstSession("returning-user", "alice@example.com")
        assertFalse(result.isFirstSession)
    }

    @Test
    fun `detectFirstSession returns false when user has SELECTED edges in graph`() = runTest {
        val handler = buildHandler(graphService = FakeUserGraphService(selectedEdges = true))
        val result  = handler.detectFirstSession("user-with-history", "alice@example.com")
        assertFalse(result.isFirstSession)
    }

    @Test
    fun `detectFirstSession returns false and pre-loads invitedEdge for VERIFIED user with no SELECTED edges`() = runTest {
        val handler = buildHandler(
            graphService = FakeUserGraphService(selectedEdges = false, verifiedEdge = true),
        )
        val result = handler.detectFirstSession("verified-user", "alice@example.com")
        assertFalse(result.isFirstSession)
        assertNotNull(result.invitedEdge)
    }

    @Test
    fun `detectFirstSession preloads invitedEdge into detection result`() = runTest {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "College friends",
            trustPhase          = "Confidant",
            engagementIntent    = "growth",
            timestamp           = 99L,
            openingContext      = "Jacob said you two go way back.",
        )
        val handler = buildHandler(graphService = FakeUserGraphService(invitedEdge = edge))
        val result  = handler.detectFirstSession("new-user-2", "bob@example.com")
        assertEquals("College friends", result.invitedEdge?.relationshipContext)
        assertEquals("Confidant", result.invitedEdge?.trustPhase)
    }

    // ── Turn 1: closed-beta rejection ─────────────────────────────────────────

    @Test
    fun `handleTurn1 returns closed-beta rejection when no INVITED edge`() {
        val handler = buildHandler(graphService = FakeUserGraphService(invitedEdge = null))
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = null)
        val result = handler.handleTurn1(detection)
        assertTrue(result.rejected)
        assertFalse(result.seedingError)
        assertEquals(FirstSessionHandler.CLOSED_BETA_REJECTION, result.response)
    }

    // ── Turn 1: warm intro ─────────────────────────────────────────────────────

    @Test
    fun `handleTurn1 returns warm intro from openingContext for acquaintance`() {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "Work colleagues",
            trustPhase          = "Acquaintance",
            engagementIntent    = "productivity",
            timestamp           = 1L,
            openingContext      = "Jacob mentioned you work in design.",
        )
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = edge)
        val result = buildHandler().handleTurn1(detection)
        assertFalse(result.rejected)
        assertFalse(result.seedingError)
        assertTrue("Jacob mentioned you work in design." in result.response)
        assertTrue("Good to meet you" in result.response)
        assertFalse("How do you know Jacob?" in result.response)
    }

    @Test
    fun `handleTurn1 returns warm intro calibrated for confidant trustPhase`() {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "Old friends",
            trustPhase          = "Confidant",
            engagementIntent    = "personal",
            timestamp           = 1L,
            openingContext      = "You two go way back, Jacob says.",
        )
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = edge)
        val result = buildHandler().handleTurn1(detection)
        assertFalse(result.rejected)
        assertFalse(result.seedingError)
        assertTrue("Good to finally talk" in result.response)
        assertTrue("You two go way back, Jacob says." in result.response)
    }

    @Test
    fun `handleTurn1 returns warm intro calibrated for colleague trustPhase`() {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "Co-workers",
            trustPhase          = "Colleague",
            engagementIntent    = "collaboration",
            timestamp           = 1L,
            openingContext      = "Jacob said you were on the same product team.",
        )
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = edge)
        val result = buildHandler().handleTurn1(detection)
        assertFalse(result.rejected)
        assertFalse(result.seedingError)
        assertTrue("Good to hear from you" in result.response)
        assertTrue("Jacob said you were on the same product team." in result.response)
    }

    // ── Turn 1: seeding error ─────────────────────────────────────────────────

    @Test
    fun `handleTurn1 surfaces seeding error when openingContext is null`() {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "Old friends",
            trustPhase          = "Confidant",
            engagementIntent    = "personal",
            timestamp           = 1L,
            openingContext      = null,
        )
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = edge)
        val result = buildHandler().handleTurn1(detection)
        assertFalse(result.rejected)
        assertTrue(result.seedingError)
        assertEquals(FirstSessionHandler.SEEDING_ERROR, result.response)
    }

    @Test
    fun `handleTurn1 surfaces seeding error when openingContext is blank`() {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "Old friends",
            trustPhase          = "Confidant",
            engagementIntent    = "personal",
            timestamp           = 1L,
            openingContext      = "   ",
        )
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = edge)
        val result = buildHandler().handleTurn1(detection)
        assertFalse(result.rejected)
        assertTrue(result.seedingError)
        assertEquals(FirstSessionHandler.SEEDING_ERROR, result.response)
    }

    // ── CognitivePipeline integration ─────────────────────────────────────────

    @Test
    fun `pipeline returns warm intro at initSession for first-session user with openingContext`() = runTest {
        val graphService = FakeUserGraphService(
            invitedEdge = UserGraphService.InvitedEdgeRecord(
                relationshipContext = "Old colleagues",
                trustPhase          = "Acquaintance",
                engagementIntent    = "productivity",
                timestamp           = 1L,
                openingContext      = "Jacob mentioned you both worked on the payments team.",
            ),
        )
        val sm      = buildSessionManager()
        val handler = FirstSessionHandler(userGraphService = graphService, sessionManager = sm)
        val engram  = InMemoryEngramClient()
        val pipeline = CognitivePipeline(engramClient = engram, firstSessionHandler = handler)
        pipeline.init()

        val response = pipeline.initSession("session-1", "user-new", userEmail = "alice@example.com")

        assertEquals("first-session-turn1", response.phraseId)
        assertTrue("Jacob mentioned you both worked on the payments team." in response.greeting)
        assertFalse("How do you know Jacob?" in response.greeting)
        assertNotNull(pipeline.firstSessionState)
        // VERIFIED edge is written during Turn 1 so returning users skip this flow.
        assertEquals(1, graphService.verifiedEdgeWrites.size)
        // Scaffold state seeded immediately with trustPhase 1 (Acquaintance → ORIENTATION).
        assertEquals(1, engram.getScaffoldState("user-new").trustPhase)
        // First session counts as session 1.
        assertEquals(1, engram.getScaffoldState("user-new").sessionCount)
    }

    @Test
    fun `pipeline surfaces seeding error when openingContext is missing`() = runTest {
        val graphService = FakeUserGraphService(
            invitedEdge = UserGraphService.InvitedEdgeRecord(
                relationshipContext = "Old colleagues",
                trustPhase          = "Acquaintance",
                engagementIntent    = "productivity",
                timestamp           = 1L,
                openingContext      = null,
            ),
        )
        val sm      = buildSessionManager()
        val handler = FirstSessionHandler(userGraphService = graphService, sessionManager = sm)
        val pipeline = CognitivePipeline(firstSessionHandler = handler)
        pipeline.init()

        val response = pipeline.initSession("session-2", "user-seed-error", userEmail = "alice@example.com")

        assertEquals("first-session-seeding-error", response.phraseId)
        assertEquals(FirstSessionHandler.SEEDING_ERROR, response.greeting)
        // No VERIFIED edge written — user remains un-marked so the error surfaces again on next session.
        assertTrue(graphService.verifiedEdgeWrites.isEmpty())
    }

    @Test
    fun `pipeline falls through to normal greeting at initSession when no INVITED edge`() = runTest {
        val graphService = FakeUserGraphService(invitedEdge = null)
        val sm      = buildSessionManager()
        val handler = FirstSessionHandler(userGraphService = graphService, sessionManager = sm)
        val pipeline = CognitivePipeline(firstSessionHandler = handler)
        pipeline.init()

        val response = pipeline.initSession("session-3", "user-uninvited", userEmail = "stranger@example.com")

        assertNotEquals(FirstSessionHandler.CLOSED_BETA_REJECTION, response.greeting)
        assertNotEquals("first-session-rejected", response.phraseId)
        assertNull(pipeline.firstSessionState)
    }

    @Test
    fun `pipeline passes through normally when firstSessionState is null`() = runTest {
        val pipeline = CognitivePipeline()  // no firstSessionHandler
        pipeline.init()

        val response = pipeline.process("Hey", "session-4", "user-existing")
        assertTrue(response.isNotBlank())
        assertNull(pipeline.firstSessionState)
    }
}
