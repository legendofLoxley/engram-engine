package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.RecordItem
import app.alfrd.engram.cognitive.pipeline.horizon.ResolveItem
import app.alfrd.engram.cognitive.pipeline.horizon.SnapshotContainer
import app.alfrd.engram.cognitive.pipeline.horizon.SnapshotItem
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshot
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import app.alfrd.engram.cognitive.providers.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The [Interpreter] stage as a whole: what it sends the model, how it reads the answer, and that
 * every failure mode lands as an explicit, distinguishable [InterpretOutcome]. What each individual
 * operation is checked against lives in [app.alfrd.engram.cognitive.pipeline.horizon.OperationParserTest].
 */
class InterpreterTest {

    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 9, 19, 14, 0, 0, 0, zone) // a Saturday

    private val snapshot = ViewSnapshot(
        focusLines = listOf("pinned -> c2 Demo (project)"),
        containers = listOf(
            SnapshotContainer("c1", "u-c1", ContainerKind.DOMAIN, "Work", null, null),
            SnapshotContainer("c2", "u-c2", ContainerKind.PROJECT, "Demo", "c1", null),
        ),
        items = listOf(SnapshotItem("i1", "u-i1", ItemKind.TASK, "Send the invoice to Acme", "open", null, "c2", emptyList(), null)),
        zone = zone,
    )

    private fun interpreterAnswering(response: LlmResponse, sink: MutableList<LlmRequest> = mutableListOf()) =
        Interpreter(TestLlmClient { req -> sink += req; response })

    // ── What is sent ─────────────────────────────────────────────────────────

    @Test
    fun `the request forces the single apply_operations tool at temperature zero`() = runTest {
        val seen = mutableListOf<LlmRequest>()
        interpreterAnswering(opsResponse(), seen).interpret("Hi!", snapshot, now)

        val request = seen.single()
        assertEquals(Interpreter.TOOL_NAME, request.forcedToolName)
        assertEquals(listOf(Interpreter.TOOL_NAME), request.tools.map { it.name })
        assertEquals(0.0, request.temperature, "a stage that decides what to write must not vary run to run")
        assertTrue(request.isInterpretation)
    }

    @Test
    fun `the prompt carries the snapshot, the user's message and a calendar resolved from today`() = runTest {
        val seen = mutableListOf<LlmRequest>()
        interpreterAnswering(opsResponse(), seen).interpret("I need to send the slides by Wednesday.", snapshot, now)

        val request = seen.single()
        assertTrue(request.prompt.startsWith("Snapshot of what is in view:"), "a snapshot with content must not claim to be empty: ${request.prompt}")
        assertTrue(request.prompt.contains("c2 Demo (project, in c1)"), "the snapshot must be in the prompt: ${request.prompt}")
        assertTrue(request.prompt.contains("i1 task \"Send the invoice to Acme\" [in c2]"))
        assertTrue(request.prompt.contains("User message:\nI need to send the slides by Wednesday."))
        val system = request.systemPrompt!!
        assertTrue(system.contains("Today is Saturday 2026-09-19"))
        assertTrue(system.contains("Wed 2026-09-23 day-23"), "a bare weekday must be resolvable from the calendar rather than counted by the model")
        assertTrue(system.contains("Sat 2026-09-19 day-19 (today)") && system.contains("Sun 2026-09-20 day-20 (tomorrow)"))
        assertTrue(system.contains("Thu 2026-09-24 day-24"), "live, \"the 24th\" was read as October's; each row now carries its day number so it is a lookup")
    }

    @Test
    fun `an empty snapshot says so, and still shows the focus line`() {
        assertEquals("Snapshot: nothing recorded yet.\nFocus: none (zoomed out)", ViewSnapshot.EMPTY.render())
    }

    @Test
    fun `the tool schema is one strict variant per verb, each requiring what its verb needs`() {
        val schema = Interpreter.APPLY_OPERATIONS_TOOL.inputSchema
        val variants = (((schema["properties"] as JsonObject)["ops"] as JsonObject)["items"] as JsonObject)["anyOf"] as kotlinx.serialization.json.JsonArray
        val byVerb = variants.map { it as JsonObject }.associateBy { v ->
            (((v["properties"] as JsonObject)["verb"] as JsonObject)["enum"] as kotlinx.serialization.json.JsonArray).single().let { (it as JsonPrimitive).content }
        }
        assertEquals(
            setOf("record_item", "resolve_item", "reopen_item", "correct_item", "file_item", "create_container", "set_focus", "release_focus", "set_attention", "link_items", "hold_for_choice"),
            byVerb.keys,
        )
        fun required(verb: String) = (byVerb.getValue(verb)["required"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("verb", "kind", "quote"), required("record_item"), "a record with no kind can no longer be produced")
        assertEquals(listOf("verb", "item", "into", "quote"), required("file_item"))
        assertEquals(listOf("verb", "item", "attention", "quote"), required("set_attention"))
        assertEquals(listOf("verb", "quote"), required("hold_for_choice"))
        byVerb.values.forEach { assertEquals(JsonPrimitive(false), it["additionalProperties"], "no stray fields on any operation") }
        val withTitle = byVerb.filterValues { (it["properties"] as JsonObject).containsKey("title") }.keys
        assertEquals(setOf("record_item", "correct_item"), withTitle, "a title is offered where an item is created, and nowhere it can't apply")
        assertTrue("title" !in required("record_item"), "a title is optional when recording")
        assertTrue("title" in required("correct_item"), "but required on a correction, where the quote makes a poor label")
    }

    // ── How the answer is read ───────────────────────────────────────────────

    @Test
    fun `validated operations come back as Operations`() = runTest {
        val utterance = "I need to send the slides by Wednesday, and I sent the invoice."
        val response = opsResponse(
            op("record_item", "kind" to "task", "quote" to "I need to send the slides by Wednesday", "due" to "2026-09-23"),
            op("resolve_item", "item" to "i1", "quote" to "I sent the invoice"),
        )

        val outcome = interpreterAnswering(response).interpret(utterance, snapshot, now)

        val ops = outcome as InterpretOutcome.Operations
        assertEquals(2, ops.operations.size)
        assertTrue(ops.operations[0] is RecordItem)
        assertEquals(ResolveItem("u-i1", "I sent the invoice"), ops.operations[1])
        assertTrue(ops.rejections.isEmpty())
    }

    @Test
    fun `an empty ops list is NoOperation, the ordinary case`() = runTest {
        assertEquals(InterpretOutcome.NoOperation, interpreterAnswering(opsResponse()).interpret("What's the weather like?", snapshot, now))
    }

    @Test
    fun `no tool call at all is NoOperation, never a competing reply`() = runTest {
        val outcome = interpreterAnswering(LlmResponse(text = "Sure, happy to help with that!", latencyMs = 5, retryCount = 0))
            .interpret("What's a good way to structure a grocery list app's data model?", snapshot, now)
        assertEquals(InterpretOutcome.NoOperation, outcome)
    }

    @Test
    fun `plain text alongside the tool call is discarded`() = runTest {
        val response = LlmResponse(text = "Great, I have saved that for you!", toolCalls = listOf(opsToolCall()), latencyMs = 5, retryCount = 0)
        assertEquals(InterpretOutcome.NoOperation, interpreterAnswering(response).interpret("Hi!", snapshot, now))
    }

    @Test
    fun `a proposal that fails validation is reported as a rejection, not silently dropped and not trusted`() = runTest {
        val response = opsResponse(op("record_item", "kind" to "task", "quote" to "I will conquer the moon"))

        val outcome = interpreterAnswering(response).interpret("I need to send the invoice.", snapshot, now)

        val ops = outcome as InterpretOutcome.Operations
        assertTrue(ops.operations.isEmpty())
        assertEquals("ungrounded_quote", ops.rejections.single().reason)
    }

    @Test
    fun `a call without an ops list is a malformed rejection`() = runTest {
        val malformed = LlmResponse(text = "", toolCalls = listOf(ToolCall(Interpreter.TOOL_NAME, buildJsonObject { put("oops", JsonPrimitive("x")) })), latencyMs = 5, retryCount = 0)

        val outcome = interpreterAnswering(malformed).interpret("Hi!", snapshot, now)

        assertEquals("malformed_tool_input", (outcome as InterpretOutcome.Operations).rejections.single().reason)
    }

    @Test
    fun `a call to any other tool is ignored`() = runTest {
        val other = LlmResponse(text = "", toolCalls = listOf(ToolCall("delete_everything", buildJsonObject { })), latencyMs = 5, retryCount = 0)
        assertEquals(InterpretOutcome.NoOperation, interpreterAnswering(other).interpret("Hi!", snapshot, now))
    }

    @Test
    fun `only the first apply_operations call is honored`() = runTest {
        val utterance = "I need to send the invoice. I also need to call Sam."
        val response = LlmResponse(
            text = "",
            toolCalls = listOf(
                opsToolCall(op("record_item", "kind" to "task", "quote" to "I need to send the invoice")),
                opsToolCall(op("record_item", "kind" to "task", "quote" to "I also need to call Sam")),
            ),
            latencyMs = 5, retryCount = 0,
        )

        val ops = interpreterAnswering(response).interpret(utterance, snapshot, now) as InterpretOutcome.Operations

        assertEquals(listOf("I need to send the invoice"), ops.operations.map { it.quote })
    }

    // ── Failures are explicit ────────────────────────────────────────────────

    @Test
    fun `an LLM error is LlmFailure, distinct from a rejected proposal`() = runTest {
        val outcome = Interpreter(TestLlmClient { throw RuntimeException("timeout") }).interpret("Hi!", snapshot, now)
        assertEquals(InterpretOutcome.LlmFailure("timeout"), outcome)
    }

    @Test
    fun `no client configured is LlmFailure`() = runTest {
        val outcome = Interpreter(null).interpret("Hi!", snapshot, now)
        assertNotNull(outcome as? InterpretOutcome.LlmFailure)
    }
}
