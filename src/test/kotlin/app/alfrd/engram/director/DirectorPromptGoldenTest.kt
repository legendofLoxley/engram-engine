package app.alfrd.engram.director

import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.AssertionStatus
import app.alfrd.engram.cognitive.pipeline.horizon.BoundedText
import app.alfrd.engram.cognitive.pipeline.horizon.ContextHorizon
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonBudget
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItem
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItemCategory
import app.alfrd.engram.cognitive.pipeline.horizon.ProvenanceKind
import app.alfrd.engram.cognitive.pipeline.horizon.SurfacingReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

/**
 * The Director tick's prompt, byte for byte, against a golden file captured from the renderer before the channel wording changed
 * ("alfrd's situation:" and "alfrd's memory of the owner" are for the channel rendering only). `UPDATE_GOLDEN=1` rewrites the file.
 */
class DirectorPromptGoldenTest {
    private val marker = ChangeMarker(5L, mapOf("Source" to 2L, "Task" to 1L), 1_700_000_000_000L, null)

    private fun item(text: String) = HorizonItem(
        category = HorizonItemCategory.INTENTION, text = BoundedText(text, false), status = AssertionStatus.OPEN, attentionDirective = null,
        provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT, surfacing = SurfacingReason.DormantOpen(3), sourceRefs = emptyList(), sourceCount = 1, kind = "task",
    )

    @Test
    fun `the tick's prompt is byte-identical to before`() {
        val state = SelfState(
            true, 300, "2026-11-07T12:00:00Z", "marker changed", listOf(ServerStatus("text reader", 8081, true, 1)), MemoryUse(1, 2, 3, 4),
            NotionState(false), emptyMap(), BudgetDefaults(10, 25, 600, 12_000, false), marker, emptyList(),
        )
        val horizon = ContextHorizon("u@test.alfrd.internal", 1_700_000_000_000L, 2, listOf(item("Order the soil")), HorizonBudget(12, 1, false), emptyList(), 0, false)
        val actual = DirectorPromptRenderer.render(state, AssembleOutcome.Assembled(horizon), ZoneOffset.UTC, Instant.parse("2026-11-07T12:00:00Z"))
        val golden = Path.of("src", "test", "resources", "director-tick-prompt.golden.txt")
        if (System.getenv("UPDATE_GOLDEN") == "1") Files.writeString(golden, actual)
        assertEquals(Files.readString(golden), actual)
    }
}
