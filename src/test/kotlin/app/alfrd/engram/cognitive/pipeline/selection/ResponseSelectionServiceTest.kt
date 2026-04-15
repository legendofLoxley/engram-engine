package app.alfrd.engram.cognitive.pipeline.selection

import app.alfrd.engram.cognitive.pipeline.CognitiveContext
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.model.BranchType
import app.alfrd.engram.model.ExpressionPhase
import app.alfrd.engram.model.ResponseCategory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseSelectionServiceTest {

    private lateinit var dbManager: DatabaseManager
    private lateinit var service: ResponseSelectionService

    @BeforeAll
    fun setup() {
        val testDbPath = "./data/test-selection-db-${System.currentTimeMillis()}"
        dbManager = DatabaseManager(testDbPath)
        val db = dbManager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        ResponsePhraseSeed.seed(db)
        service = ResponseSelectionService(db)
    }

    @AfterAll
    fun teardown() {
        dbManager.close()
        File("./data").listFiles()
            ?.filter { it.name.startsWith("test-selection-db-") }
            ?.forEach { it.deleteRecursively() }
    }

    @Test
    fun `selects a greeting phrase for SOCIAL branch`() {
        val ctx = CognitiveContext(
            utterance = "hello",
            sessionId = "s1",
            userId = "test-user",
            timestamp = Instant.now(),
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.ACKNOWLEDGE,
            category = ResponseCategory.GREETING,
            context = ctx,
            limit = 1,
        )
        val results = service.select(query)
        assertEquals(1, results.size)
        val result = results.first()
        assertTrue(result.compositeScore > 0.0, "Score should be positive")
        assertTrue(result.interpolated.isNotBlank())
        assertTrue(result.scoreBreakdown.containsKey("freshness"))
        assertTrue(result.scoreBreakdown.containsKey("contextualFit"))
        assertTrue(result.scoreBreakdown.containsKey("communicationFit"))
        assertTrue(result.scoreBreakdown.containsKey("phaseAppropriateness"))
        assertTrue(result.scoreBreakdown.containsKey("effectiveness"))
    }

    @Test
    fun `selects a sign-off phrase`() {
        val ctx = CognitiveContext(
            utterance = "goodbye",
            sessionId = "s1",
            userId = "test-user",
            timestamp = Instant.now(),
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.SYNTHESIS,
            category = ResponseCategory.SIGN_OFF,
            context = ctx,
            limit = 3,
        )
        val results = service.select(query)
        assertTrue(results.isNotEmpty(), "Expected at least one sign-off phrase")
        assertTrue(results.size <= 3)
        // Verify ordering is by descending score
        for (i in 0 until results.size - 1) {
            assertTrue(
                results[i].compositeScore >= results[i + 1].compositeScore,
                "Results should be ordered by descending composite score",
            )
        }
    }

    @Test
    fun `selects acknowledgment phrases`() {
        val ctx = CognitiveContext(
            utterance = "thanks",
            sessionId = "s1",
            userId = "test-user",
            timestamp = Instant.now(),
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.ACKNOWLEDGE,
            category = ResponseCategory.ACKNOWLEDGMENT,
            context = ctx,
            limit = 5,
        )
        val results = service.select(query)
        assertTrue(results.isNotEmpty(), "Expected at least one acknowledgment phrase")
    }

    @Test
    fun `returns empty list when no candidates match`() {
        val ctx = CognitiveContext(
            utterance = "hey",
            sessionId = "s1",
            userId = "test-user",
            timestamp = Instant.now(),
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.ACKNOWLEDGE,
            category = ResponseCategory.DECLINE,
            context = ctx,
            limit = 1,
        )
        val results = service.select(query)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `exclude set filters out specific phrases`() {
        val ctx = CognitiveContext(
            utterance = "hello",
            sessionId = "s-exclude-test",
            userId = "test-user-exclude",
            timestamp = Instant.now(),
        )

        // First, get all greeting phrases
        val allQuery = ResponseSelectionQuery(
            branch = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.ACKNOWLEDGE,
            category = ResponseCategory.GREETING,
            context = ctx,
            limit = 10,
        )
        val allResults = service.select(allQuery)
        assertTrue(allResults.size > 1, "Need at least 2 greetings for this test")

        // Exclude the top result
        val excludeUid = allResults.first().phrase.uid
        val filteredQuery = allQuery.copy(
            exclude = setOf(excludeUid),
        )
        val filteredResults = service.select(filteredQuery)
        assertTrue(filteredResults.none { it.phrase.uid == excludeUid })
    }

    @Test
    fun `interpolation resolves userName placeholder`() {
        val ctx = CognitiveContext(
            utterance = "hello",
            sessionId = "s1",
            userId = "Alice",
            trustPhase = "CONTEXT",
            timestamp = Instant.now(),
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.ACKNOWLEDGE,
            category = ResponseCategory.GREETING,
            context = ctx,
            limit = 10,
        )
        val results = service.select(query)
        val interpolatedResult = results.find { it.phrase.requiresInterpolation }
        if (interpolatedResult != null) {
            assertTrue(
                interpolatedResult.interpolated.contains("Alice"),
                "Expected userName interpolation, got: ${interpolatedResult.interpolated}",
            )
            assertFalse(interpolatedResult.interpolated.contains("{userName}"))
        }
    }

    @Test
    fun `morning greeting scores higher contextual fit than evening in morning`() {
        val morningInstant = LocalDate.now().atTime(9, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val ctx = CognitiveContext(
            utterance = "hello",
            sessionId = "s-morning-test",
            userId = "test-user-morning",
            timestamp = morningInstant,
        )
        val query = ResponseSelectionQuery(
            branch = BranchType.SOCIAL,
            expressionPhase = ExpressionPhase.ACKNOWLEDGE,
            category = ResponseCategory.GREETING,
            context = ctx,
            limit = 10,
        )
        val results = service.select(query)
        val morningPhrase = results.find { it.phrase.text == "Good morning." }
        val eveningPhrase = results.find { it.phrase.text == "Good evening." }
        assertNotNull(morningPhrase, "Expected to find 'Good morning.' phrase")
        assertNotNull(eveningPhrase, "Expected to find 'Good evening.' phrase")
        assertTrue(
            morningPhrase!!.scoreBreakdown["contextualFit"]!! > eveningPhrase!!.scoreBreakdown["contextualFit"]!!,
            "Morning phrase should have higher contextualFit in morning: morning=${morningPhrase.scoreBreakdown} evening=${eveningPhrase.scoreBreakdown}",
        )
        // Morning phrase should also rank higher overall
        assertTrue(
            morningPhrase.compositeScore > eveningPhrase.compositeScore,
            "Morning phrase should score higher overall in morning: morning=${morningPhrase.compositeScore} evening=${eveningPhrase.compositeScore}",
        )
    }
}
