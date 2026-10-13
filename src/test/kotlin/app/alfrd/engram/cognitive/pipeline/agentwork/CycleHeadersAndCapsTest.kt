package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.ToolCall
import app.alfrd.engram.notion.NotionValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** No goal or cycle codes in any prompt; QC output is never cut without a visible marker; plan v5 rules. Added after the 27B quoted "(GOAL-3)" back as if it were in the goal, and its feedback was cut at 1,500 characters. */
class CycleHeadersAndCapsTest {

    private val code = Regex("""\b(?:GOAL|CYC|CYCLE|TASK)-\d+\b""")
    private val f = AgentWorkFixture()
    private val t = f.template

    private fun goal(title: String = "Judge the items against the reference") = Goal(
        "g", title, "GOAL-3", GoalStatus.ACTIVE, "Every item is judged.", GoalScope.EMPTY, emptyList(), 8, 3, null,
    )
    private fun cycle() = Cycle("c", "Cycle", "CYC-5", CycleStatus.RUNNING, "g", 1, 8, null, "w", null, null, "", Instant.EPOCH, Instant.EPOCH)

    // ── 1. No codes in any prompt ────────────────────────────────────────────

    @Test
    fun `stripCodes removes goal, cycle and task codes from text a model or the Director wrote`() {
        assertEquals("Found it earlier.", PromptText.stripCodes("Found it earlier (GOAL-3)."))
        assertEquals("the goal asked for this", PromptText.stripCodes("GOAL-3 asked for this").replace("the goal asked", "the goal asked"))
        assertEquals("As an earlier cycle found, a task agrees.", PromptText.stripCodes("As CYC-4 found, TASK-2 agrees."))
        assertEquals("an earlier cycle and an earlier cycle", PromptText.stripCodes("CYCLE-1 and CYC-22"))
        assertEquals("nothing to remove: R1, P1, 2026", PromptText.stripCodes("nothing to remove: R1, P1, 2026"))
    }

    @Test
    fun `the plan prompt shows no goal or cycle code, even when the last summary, an escalation or the goal object carries one`() {
        val context = ScopeContext.EMPTY
        val prompt = PlanPromptBuilder.build(
            PlanningInput(
                goal(), cycle(), 8,
                listOf(EscalationNote("Error found (GOAL-3)", "Resolved", "Which one, CYC-2?", "Box B, per CYC-2 and GOAL-3.")),
                previousCycleLabel = "CYC-4", previousSummary = "Executed 3 tasks in CYC-4 (GOAL-3). TASK-2 was cut.", context = context,
            ),
        )
        assertFalse(code.containsMatchIn(prompt), prompt)
        assertTrue(prompt.contains("GOAL: Judge the items against the reference\n") && prompt.contains("LAST CYCLE SUMMARY:"))
        assertTrue(prompt.contains("Executed 3 tasks in an earlier cycle"), prompt)
    }

    @Test
    fun `the task prompt shows no code, including in earlier work`() {
        val input = TaskExecutionInput(
            goal(), cycle(),
            AgentTask("t", "Check one (repeat of CYC-4)", "TASK-4", TaskType.CHECK, TaskStatus.PLANNED, 4, 1, "Carry forward the finding from CYC-4 for GOAL-3.", "", emptyList(), "", "cycle:CYC-5:v1:o4", listOf("c")),
            8, StagedContext.EMPTY, listOf(EarlierResult(1, "Read for CYC-4", TaskStatus.DONE, "As GOAL-3 says (CYC-4), TASK-1 found it.")),
        )
        val prompt = TaskPromptBuilder.build(input)
        assertFalse(code.containsMatchIn(prompt), prompt)
        assertTrue(prompt.startsWith("GOAL: Judge the items against the reference\n"))
    }

    @Test
    fun `the QC prompt shows no code, including in task results`() {
        val input = QcInput(
            goal(), cycle(), listOf("page Persona (persona): read"),
            listOf(QcTaskLine(1, "Read (repeat from CYC-4)", "Read", TaskStatus.DONE, "Per CYC-4 and GOAL-3 (TASK-1) it holds.")), 0,
        )
        val prompt = QcPromptBuilder.build(input)
        assertFalse(code.containsMatchIn(prompt), prompt)
        assertTrue(prompt.contains("GOAL: Judge the items against the reference\n"))
    }

    @Test
    fun `the goal's own Title, Done when and Scope text is kept exactly, even if it contains a code`() {
        val g = goal(title = "Review GOAL-9 follow-ups").copy(doneWhen = "Done when CYC-1 is closed.")
        val prompt = QcPromptBuilder.build(QcInput(g, cycle(), emptyList(), emptyList(), 0))
        assertTrue(prompt.contains("GOAL: Review GOAL-9 follow-ups") && prompt.contains("DONE WHEN: Done when CYC-1 is closed."), "the goal's words are the goal's words")
    }

    @Test
    fun `end to end, no request of a whole cycle contains a goal, cycle or task code`() {
        runBlocking {
            f.source("A source")
            val goalRow = f.goal(title = "Judge the sources")
            f.cycle(goalRow.id)
            val seen = ArrayList<LlmRequest>()
            val model = object : LlmClient {
                override suspend fun complete(request: LlmRequest): LlmResponse {
                    seen += request
                    val input = when (request.forcedToolName) {
                        CyclePlanDirector.TOOL_NAME -> ScriptedLlm.planOf(ScriptedLlm.taskJson("Check it", "Check", "Do it."), ScriptedLlm.taskJson("Gather", "Consolidate", "Do it."))
                        DirectorTaskExecutor.TOOL_NAME -> RoutedLlm.taskDone("As GOAL-1 says in CYC-1, it holds (TASK-1).")
                        else -> RoutedLlm.qcOf()
                    }
                    return LlmResponse("", toolCalls = listOf(ToolCall(request.forcedToolName!!, input)), latencyMs = 1, retryCount = 0)
                }
            }
            f.processor(model).runOnce()
            assertTrue(seen.size >= 4)
            for (r in seen) {
                assertFalse(code.containsMatchIn(r.prompt), "${r.forcedToolName}: ${code.find(r.prompt)?.value}")
                assertFalse(code.containsMatchIn(r.systemPrompt ?: ""), "${r.forcedToolName} system prompt")
            }
        }
    }

    @Test
    fun `the QC system prompt no longer carries the rule about header labels`() {
        assertFalse(CyclePrompts.QC.text.contains("ids, codes or labels"))
    }

    // ── 2. QC output caps ────────────────────────────────────────────────────

    private fun decide(summary: String, feedback: String): QcDecision =
        runBlocking { CycleQcDirector(ScriptedLlm.answering(RoutedLlm.qcOf(summary = summary, feedback = feedback), CycleQcDirector.TOOL_NAME)).decide(QcInput(goal(), cycle(), emptyList(), emptyList(), 0)) }.decision!!

    @Test
    fun `a 3000 character summary and feedback pass through whole`() {
        val summary = (1..100).joinToString(" ") { "Sentence $it of the summary." }.take(3_000)
        val feedback = "1. First point. " + "More words about the point. ".repeat(110) + "LAST-POINT-5."
        val d = decide(summary, feedback)
        assertEquals(summary.trim(), d.summary)
        assertEquals(feedback.trim(), d.goalFeedback)
        assertTrue(feedback.length > 3_000 && d.goalFeedback.endsWith("LAST-POINT-5."), "the fifth point survives")
        assertFalse(d.summary.contains("[CUT") || d.goalFeedback.contains("[CUT"))
    }

    @Test
    fun `anything beyond the cap ends with a visible marker that counts what was cut`() {
        val d = decide("s".repeat(10_000), "f".repeat(8_001))
        assertTrue(d.summary.endsWith(" [CUT 2000 chars]") && d.summary.length == CycleQcDirector.MAX_TEXT_CHARS + " [CUT 2000 chars]".length)
        assertTrue(d.goalFeedback.endsWith(" [CUT 1 chars]"))
        val rewrite = runBlocking {
            CycleQcDirector(ScriptedLlm.answering(RoutedLlm.qcOf(rewriteField = "Done when", rewriteText = "r".repeat(2_500)), CycleQcDirector.TOOL_NAME)).decide(QcInput(goal(), cycle(), emptyList(), emptyList(), 0))
        }.decision!!
        assertTrue(rewrite.suggestedText!!.endsWith(" [CUT 500 chars]"))
    }

    @Test
    fun `the composed Summary and Goal Feedback keep a 3000 character answer whole`() {
        val feedback = "x".repeat(2_999) + "!"
        val d = decide("short", feedback)
        assertTrue(QcComposer.feedback(d).startsWith(feedback))
        assertTrue(QcComposer.summary(1, 0, 0, 1, d).startsWith("Executed 1 task: 1 done, 0 blocked, 0 failed. short"))
    }

    @Test
    fun `a 3000 character Goal Feedback is written to Notion whole, as two rich text segments, and reads back whole`() {
        val text = (0 until 3_000).map { 'a' + (it % 26) }.joinToString("")
        val props = CycleMapper.finish(CycleStatus.DONE, Instant.parse("2026-10-13T10:00:00Z"), "s", 1, text)
        assertEquals(text, (props[AgentWorkSchema.Cycles.GOAL_FEEDBACK] as NotionValue.RichText).text, "the mapper does not cut")
        val segments = app.alfrd.engram.notion.chunkText(text, app.alfrd.engram.notion.NotionApi.MAX_TEXT_CHARS)
        assertEquals(listOf(2_000, 1_000), segments.map { it.length })
        assertEquals(text, segments.joinToString(""))
    }

    // ── 3. Plan v5 ───────────────────────────────────────────────────────────

    @Test
    fun `plan v5 asks for a check between items, coverage of every item, and honesty about the budget, with no names`() {
        val plan = CyclePrompts.PLAN.text
        for (rule in listOf(
            "also plan at least one Check that compares the items against each other",
            "does one item change, retire or replace something another item depends on",
            "Every item you are given must be covered by at least one Check task",
            "the rationale must say which items and why",
            "If the budget cannot cover both the checks against the reference and the checks between items, say so in the rationale",
        )) assertTrue(plan.contains(rule), "missing: $rule")
        assertEquals("5", CyclePrompts.PLAN.version)
        assertEquals("5", CyclePrompts.QC.version)
    }
}
