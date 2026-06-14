package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.cognitive.UserGraphService
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.ScaffoldState
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ─────────────────────────────────────────────────────────────────────────────
// FirstSessionHandler — unit tests for detection and identity verification flow
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
        llmResponse: String? = null,
    ): FirstSessionHandler {
        val llm = if (llmResponse != null) {
            TestLlmClient { LlmResponse(text = llmResponse, latencyMs = 0, retryCount = 0) }
        } else null
        return FirstSessionHandler(
            userGraphService = graphService,
            sessionManager   = sessionManager,
            llmClient        = llm,
        )
    }

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
        // invitedEdge pre-loaded so warm-intro can use opening_context
        assertNotNull(result.invitedEdge)
    }

    @Test
    fun `detectFirstSession preloads invitedEdge into detection result`() = runTest {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "College friends",
            trustPhase          = "Confidant",
            engagementIntent    = "growth",
            timestamp           = 99L,
        )
        val handler = buildHandler(graphService = FakeUserGraphService(invitedEdge = edge))
        val result  = handler.detectFirstSession("new-user-2", "bob@example.com")
        assertEquals("College friends", result.invitedEdge?.relationshipContext)
        assertEquals("Confidant", result.invitedEdge?.trustPhase)
    }

    // ── Turn 1 ────────────────────────────────────────────────────────────────

    @Test
    fun `handleTurn1 returns closed-beta rejection when no INVITED edge`() = runTest {
        val handler = buildHandler(graphService = FakeUserGraphService(invitedEdge = null))
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = null)
        val result = handler.handleTurn1(detection)
        assertTrue(result.rejected)
        assertEquals(FirstSessionHandler.CLOSED_BETA_REJECTION, result.response)
    }

    @Test
    fun `handleTurn1 returns acquaintance greeting for default trustPhase`() = runTest {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "Work colleagues",
            trustPhase          = "Acquaintance",
            engagementIntent    = "",
            timestamp           = 1L,
        )
        val handler = buildHandler()
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = edge)
        val result = handler.handleTurn1(detection)
        assertFalse(result.rejected)
        assertTrue("How do you know Jacob?" in result.response)
        assertTrue("Good to meet you" in result.response)
    }

    @Test
    fun `handleTurn1 returns confidant greeting when trustPhase is Confidant`() = runTest {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "Old friends",
            trustPhase          = "Confidant",
            engagementIntent    = "",
            timestamp           = 1L,
        )
        val handler = buildHandler()
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = edge)
        val result = handler.handleTurn1(detection)
        assertFalse(result.rejected)
        assertTrue("Good to finally talk" in result.response)
    }

    @Test
    fun `handleTurn1 returns colleague greeting when trustPhase is Colleague`() = runTest {
        val edge = UserGraphService.InvitedEdgeRecord(
            relationshipContext = "Co-workers",
            trustPhase          = "Colleague",
            engagementIntent    = "",
            timestamp           = 1L,
        )
        val handler = buildHandler()
        val detection = FirstSessionHandler.DetectionResult(isFirstSession = true, invitedEdge = edge)
        val result = handler.handleTurn1(detection)
        assertFalse(result.rejected)
        assertTrue("Good to hear from you" in result.response)
    }

    // ── Turn 2: match ─────────────────────────────────────────────────────────

    @Test
    fun `handleTurn2 verifies user when LLM returns high confidence`() = runTest {
        val llmJson = """{"match": true, "confidence": 0.9, "reasoning": "Same company mentioned"}"""
        val graphService = FakeUserGraphService()
        val handler = buildHandler(graphService = graphService, llmResponse = llmJson)

        val state = FirstSessionState(
            isFirstSession               = true,
            awaitingIdentityVerification = true,
            trustPhase                   = "Acquaintance",
            engagementIntent             = "productivity",
            relationshipContext          = "We worked together at Acme Corp.",
        )

        val result = handler.handleTurn2("user-1", "We both worked at Acme on payments.", state)

        assertTrue(result.newState.identityVerified)
        assertFalse(result.newState.awaitingIdentityVerification)
        assertFalse(result.newState.identityFlagged)
        assertEquals(1, graphService.verifiedEdgeWrites.size)
        // Acquaintance opener
        assertTrue("What are you working on right now?" in result.response)
    }

    @Test
    fun `handleTurn2 confidant verification returns warm opener`() = runTest {
        val llmJson = """{"match": true, "confidence": 0.85, "reasoning": "Old friends described consistently"}"""
        val handler = buildHandler(llmResponse = llmJson)

        val state = FirstSessionState(
            isFirstSession               = true,
            awaitingIdentityVerification = true,
            trustPhase                   = "Confidant",
            engagementIntent             = "personal",
            relationshipContext          = "We grew up together.",
        )

        val result = handler.handleTurn2("user-2", "We've been friends since high school.", state)

        assertTrue(result.newState.identityVerified)
        assertTrue("What's got your attention lately?" in result.response)
    }

    @Test
    fun `handleTurn2 colleague verification returns colleague opener`() = runTest {
        val llmJson = """{"match": true, "confidence": 0.75, "reasoning": "Matching work context"}"""
        val handler = buildHandler(llmResponse = llmJson)

        val state = FirstSessionState(
            isFirstSession               = true,
            awaitingIdentityVerification = true,
            trustPhase                   = "Colleague",
            engagementIntent             = "collaboration",
            relationshipContext          = "Worked on the same product team.",
        )

        val result = handler.handleTurn2("user-3", "We were on the same product team.", state)

        assertTrue(result.newState.identityVerified)
        assertTrue("What's on your plate this week?" in result.response)
    }

    // ── Turn 2: low confidence / reask ────────────────────────────────────────

    @Test
    fun `handleTurn2 reasks on first low-confidence attempt`() = runTest {
        val llmJson = """{"match": false, "confidence": 0.3, "reasoning": "No overlap found"}"""
        val graphService = FakeUserGraphService()
        val handler = buildHandler(graphService = graphService, llmResponse = llmJson)

        val state = FirstSessionState(
            isFirstSession               = true,
            awaitingIdentityVerification = true,
            trustPhase                   = "Acquaintance",
            relationshipContext          = "Work colleagues at TechCo",
            retryCount                   = 0,
        )

        val result = handler.handleTurn2("user-4", "I know him from the internet.", state)

        assertFalse(result.newState.identityVerified)
        assertFalse(result.newState.identityFlagged)
        assertEquals(1, result.newState.retryCount)
        assertEquals(FirstSessionHandler.REASK_PROMPT, result.response)
        assertTrue(graphService.verifiedEdgeWrites.isEmpty())
    }

    @Test
    fun `handleTurn2 flags user on second low-confidence attempt`() = runTest {
        val llmJson = """{"match": false, "confidence": 0.2, "reasoning": "Contradictory context"}"""
        val graphService = FakeUserGraphService()
        val handler = buildHandler(graphService = graphService, llmResponse = llmJson)

        val state = FirstSessionState(
            isFirstSession               = true,
            awaitingIdentityVerification = true,
            trustPhase                   = "Acquaintance",
            relationshipContext          = "Work colleagues at TechCo",
            retryCount                   = 1,  // already retried once
        )

        val result = handler.handleTurn2("user-5", "Never met him actually.", state)

        assertFalse(result.newState.identityVerified)
        assertTrue(result.newState.identityFlagged)
        assertFalse(result.newState.awaitingIdentityVerification)
        assertEquals(FirstSessionHandler.FLAG_MESSAGE, result.response)
        assertTrue(graphService.verifiedEdgeWrites.isEmpty())
    }

    // ── Turn 2: off-topic ────────────────────────────────────────────────────

    @Test
    fun `handleTurn2 reasks when response is a single-word non-answer`() = runTest {
        val handler = buildHandler(llmResponse = null)  // LLM not needed for off-topic detection

        val state = FirstSessionState(
            isFirstSession               = true,
            awaitingIdentityVerification = true,
            trustPhase                   = "Acquaintance",
            relationshipContext          = "Colleagues",
            retryCount                   = 0,
        )

        val result = handler.handleTurn2("user-6", "yes", state)

        assertEquals(FirstSessionHandler.REASK_PROMPT, result.response)
        assertEquals(1, result.newState.retryCount)
    }

    @Test
    fun `handleTurn2 flags user when off-topic on second attempt`() = runTest {
        val graphService = FakeUserGraphService()
        val handler = buildHandler(graphService = graphService, llmResponse = null)

        val state = FirstSessionState(
            isFirstSession               = true,
            awaitingIdentityVerification = true,
            trustPhase                   = "Acquaintance",
            relationshipContext          = "Colleagues",
            retryCount                   = 1,
        )

        val result = handler.handleTurn2("user-7", "ok", state)

        assertTrue(result.newState.identityFlagged)
        assertEquals(FirstSessionHandler.FLAG_MESSAGE, result.response)
    }

    // ── Turn 2: LLM unavailable ───────────────────────────────────────────────

    @Test
    fun `handleTurn2 flags user when LLM is unavailable`() = runTest {
        val graphService = FakeUserGraphService()
        val handler = buildHandler(graphService = graphService, llmResponse = null)

        val state = FirstSessionState(
            isFirstSession               = true,
            awaitingIdentityVerification = true,
            trustPhase                   = "Acquaintance",
            relationshipContext          = "We worked together",
            retryCount                   = 0,
        )

        // "We worked together at Acme." is non-trivial (> 5 chars, not a short non-answer)
        // but LLM is null → conservative flag.
        val result = handler.handleTurn2("user-8", "We worked together at Acme.", state)

        assertTrue(result.newState.identityFlagged)
        assertEquals(FirstSessionHandler.FLAG_MESSAGE, result.response)
    }

    // ── CognitivePipeline integration ─────────────────────────────────────────

    @Test
    fun `pipeline returns first-session greeting at initSession for new user`() = runTest {
        val graphService = FakeUserGraphService(
            invitedEdge = UserGraphService.InvitedEdgeRecord(
                relationshipContext = "Old colleagues",
                trustPhase          = "Acquaintance",
                engagementIntent    = "productivity",
                timestamp           = 1L,
            ),
        )
        val sm      = buildSessionManager()
        val handler = FirstSessionHandler(
            userGraphService = graphService,
            sessionManager   = sm,
            llmClient        = null,
        )
        val pipeline = CognitivePipeline(firstSessionHandler = handler)
        pipeline.init()

        val response = pipeline.initSession("session-1", "user-new", userEmail = "alice@example.com")

        assertTrue(response.phraseId == "first-session-turn1")
        assertTrue("How do you know Jacob?" in response.greeting)
        assertNotNull(pipeline.firstSessionState)
        assertTrue(pipeline.firstSessionState!!.awaitingIdentityVerification)
    }

    @Test
    fun `pipeline falls through to normal greeting at initSession when no INVITED edge`() = runTest {
        // Authenticated users (any call reaching initSession has passed JWT auth) must never
        // see the closed-beta rejection, even if they have no INVITED edge in the graph.
        val graphService = FakeUserGraphService(invitedEdge = null)
        val sm      = buildSessionManager()
        val handler = FirstSessionHandler(
            userGraphService = graphService,
            sessionManager   = sm,
            llmClient        = null,
        )
        val pipeline = CognitivePipeline(firstSessionHandler = handler)
        pipeline.init()

        val response = pipeline.initSession("session-2", "user-uninvited", userEmail = "stranger@example.com")

        assertNotEquals(FirstSessionHandler.CLOSED_BETA_REJECTION, response.greeting)
        assertNotEquals("first-session-rejected", response.phraseId)
        assertNull(pipeline.firstSessionState)
    }

    @Test
    fun `pipeline intercepts first process call for identity verification Turn 2`() = runTest {
        val llmJson = """{"match": true, "confidence": 0.8, "reasoning": "Matching work context"}"""
        val graphService = FakeUserGraphService()
        val sm = buildSessionManager()
        val llm = TestLlmClient { LlmResponse(text = llmJson, latencyMs = 0, retryCount = 0) }
        val handler = FirstSessionHandler(
            userGraphService = graphService,
            sessionManager   = sm,
            llmClient        = llm,
        )
        val engram   = InMemoryEngramClient()
        val pipeline = CognitivePipeline(engramClient = engram, firstSessionHandler = handler)
        pipeline.init()

        // Seed the first-session state directly (simulating Turn 1 completed)
        pipeline.firstSessionState = FirstSessionState(
            isFirstSession               = true,
            awaitingIdentityVerification = true,
            trustPhase                   = "Acquaintance",
            engagementIntent             = "productivity",
            relationshipContext          = "We worked at the same startup.",
        )

        val response = pipeline.process("We met at a startup I worked at.", "session-3", "user-9")

        // Should be Turn 2 verified response, not a normal pipeline response
        assertTrue("What are you working on right now?" in response)
        assertTrue(pipeline.firstSessionState!!.identityVerified)
        // Scaffold state should be seeded with trustPhase 1 (Acquaintance → ORIENTATION)
        val scaffoldState = engram.getScaffoldState("user-9")
        assertEquals(1, scaffoldState.trustPhase)
    }

    @Test
    fun `pipeline passes through normally when firstSessionState is null`() = runTest {
        val pipeline = CognitivePipeline()  // no firstSessionHandler
        pipeline.init()

        val response = pipeline.process("Hey", "session-4", "user-existing")
        assertTrue(response.isNotBlank())
        // Normal social response — not a first-session response
        assertNull(pipeline.firstSessionState)
    }
}
