package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.ToolCall
import app.alfrd.engram.notion.MentionKind
import app.alfrd.engram.notion.NotionMention
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** A model stand-in that records the request it was given and answers with whatever the test says. */
class ScriptedLlm(private val respond: (LlmRequest) -> LlmResponse) : LlmClient {
    val requests = ArrayList<LlmRequest>()
    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return respond(request)
    }

    companion object {
        fun answering(input: JsonObject, name: String = CyclePlanDirector.TOOL_NAME) =
            ScriptedLlm { LlmResponse(text = "", toolCalls = listOf(ToolCall(name, input)), latencyMs = 5, retryCount = 0, providerName = "local", modelName = "qwen-test") }

        fun planOf(vararg tasks: JsonObject, rationale: String? = "because") = buildJsonObject {
            putJsonArray("tasks") { tasks.forEach { add(it) } }
            rationale?.let { put("rationale", it) }
        }

        fun taskJson(title: String, type: String = "Read", instructions: String = "Read it.", vararg links: String) = buildJsonObject {
            put("title", title); put("type", type); put("instructions", instructions)
            putJsonArray("target_links") { links.forEach { add(it) } }
        }
    }
}

class CyclePlanDirectorTest {

    private val memoRow = ContextRow("sources", "aaaaaaaa-aaaa-4aaa-8aaa-000000000001", "https://www.notion.so/aaaaaaaaaaaa4aaa8aaa000000000001", "Oct 6 Board memo", listOf("Type: PPS Document", "URL: https://example.org/memo.pdf"))

    private val sourcesMention = NotionMention(MentionKind.DATABASE, "6e9ea25d-e733-4d89-9d7a-52783509ff54", "PPS Sources")

    private fun goal(scope: GoalScope = GoalScope(listOf(sourcesMention), 0, "", emptyList())) = Goal(
        "g", "Read the primary documents", "GOAL-1", GoalStatus.ACTIVE,
        doneWhen = "Every listed document has a finding.",
        scope = scope, serves = emptyList(), cycleBudget = 10, replanCap = 3, deadline = "2026-10-24",
    )

    private fun cycle() = Cycle("c", "Cycle A", "CYCLE-1", CycleStatus.RUNNING, "g", 1, 6, null, "arx-1", null, null, "", Instant.EPOCH, Instant.EPOCH)

    private fun context(rows: List<ContextRow> = listOf(memoRow), outcomes: List<ScopeOutcome> = listOf(ScopeOutcome.TableListed(sourcesMention, "sources", 1, false))) =
        ScopeContext(rows, outcomes, AllowedScope(rows.map { it.pageId }.toSet(), emptySet(), emptySet()), truncated = false, tablesByDatabaseId = emptyMap())

    private fun input(
        escalations: List<EscalationNote> = emptyList(),
        previousSummary: String? = null,
        context: ScopeContext = context(),
    ) = PlanningInput(goal(), cycle(), taskBudget = 6, escalations = escalations, previousCycleLabel = "CYCLE-0", previousSummary = previousSummary, context = context)

    private fun propose(llm: LlmClient?, input: PlanningInput = input()) = runBlocking { CyclePlanDirector(llm).propose(input) }

    // ── The call ─────────────────────────────────────────────────────────────

    @Test
    fun `one forced tool call is made, with the planner's rules and the goal in the prompt`() {
        val llm = ScriptedLlm.answering(ScriptedLlm.planOf(ScriptedLlm.taskJson("Read the memo", "Read", "Read it.", memoRow.pageUrl)))
        val proposal = propose(llm)

        assertEquals(1, llm.requests.size)
        val request = llm.requests.single()
        assertEquals(CyclePlanDirector.TOOL_NAME, request.forcedToolName)
        assertEquals(listOf(CyclePlanDirector.TOOL_NAME), request.tools.map { it.name })
        assertEquals(0.2, request.temperature)
        assertEquals(CyclePlanDirector.TIMEOUT_MS, request.timeoutMs)
        assertTrue(request.systemPrompt!!.contains("Never propose a task that locks"))
        assertTrue(request.systemPrompt!!.contains("Do not use Orchestrate, Gate or QC"))
        assertTrue(request.systemPrompt!!.contains("Tasks only read and report"))
        assertTrue(request.prompt.contains("Read the primary documents"))
        assertTrue(request.prompt.contains("TASK BUDGET: 6"))
        assertTrue(request.prompt.contains("- R1  [sources] Oct 6 Board memo") && !request.prompt.contains(memoRow.pageUrl), "rows are listed by handle, not by id")

        assertNull(proposal.rejectedReason)
        assertTrue(proposal.modelCalled)
        assertEquals("local", proposal.providerName)
        assertEquals("qwen-test", proposal.modelName)
        assertEquals("because", proposal.rationale)
    }

    @Test
    fun `the tool schema offers only the types a plan may propose`() {
        val llm = ScriptedLlm.answering(ScriptedLlm.planOf())
        propose(llm)
        val schema = llm.requests.single().tools.single().inputSchema.toString()
        for (type in TaskType.PROPOSABLE) assertTrue(schema.contains("\"${type.label}\""), type.label)
        for (type in listOf("Orchestrate", "Gate", "QC")) assertFalse(schema.contains("\"$type\""), "$type is offered to the model")
    }

    @Test
    fun `the tasks of the answer are parsed into proposals, untrusted`() {
        val llm = ScriptedLlm.answering(
            ScriptedLlm.planOf(
                ScriptedLlm.taskJson("A", "Read", "do a", "https://x.example/1"),
                ScriptedLlm.taskJson("B", "Orchestrate", "do b"),
            ),
        )
        val tasks = propose(llm).tasks
        assertEquals(listOf("A", "B"), tasks.map { it.title })
        assertEquals(listOf("Read", "Orchestrate"), tasks.map { it.type }, "a bad type survives parsing; the validator is what rejects it")
        assertEquals(listOf("https://x.example/1"), tasks[0].targetLinks)
    }

    @Test
    fun `odd shapes are tolerated, not trusted`() {
        val odd = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject { put("title", "Single link as a string"); put("type", "Read"); put("instructions", "x"); put("target_links", "https://x.example/1") })
                add(buildJsonObject { put("title", "No links key"); put("type", "Read"); put("instructions", "x") })
                add(buildJsonObject { put("title", 42); put("type", true) })
                add(kotlinx.serialization.json.JsonPrimitive("not an object"))
            }
        }
        val tasks = propose(ScriptedLlm.answering(odd)).tasks
        assertEquals(3, tasks.size, "a non-object element is dropped")
        assertEquals(listOf("https://x.example/1"), tasks[0].targetLinks)
        assertEquals(emptyList<String>(), tasks[1].targetLinks)
        assertNull(tasks[2].title)
        assertNull(tasks[2].type)
    }

    @Test
    fun `a second tool call in the answer is ignored`() {
        val llm = ScriptedLlm {
            LlmResponse(
                "", toolCalls = listOf(
                    ToolCall(CyclePlanDirector.TOOL_NAME, ScriptedLlm.planOf(ScriptedLlm.taskJson("First"))),
                    ToolCall(CyclePlanDirector.TOOL_NAME, ScriptedLlm.planOf(ScriptedLlm.taskJson("Second"))),
                ), latencyMs = 1, retryCount = 0,
            )
        }
        assertEquals(listOf("First"), propose(llm).tasks.map { it.title })
    }

    // ── What goes wrong ──────────────────────────────────────────────────────

    @Test
    fun `no model means no proposal, and nothing is called`() {
        val proposal = propose(null)
        assertEquals("no_llm_client", proposal.rejectedReason)
        assertFalse(proposal.modelCalled)
        assertTrue(proposal.tasks.isEmpty())
    }

    @Test
    fun `a failing model call is reported by class only, never by its message`() {
        val proposal = propose(ScriptedLlm { throw IllegalStateException("the prompt said SECRET-PROMPT-TEXT and token ntn_SECRET") })
        assertEquals("llm_call_failed: IllegalStateException", proposal.rejectedReason)
        assertTrue(proposal.modelCalled)
        assertFalse(proposal.rejectedReason!!.contains("SECRET"))
    }

    @Test
    fun `cancellation is not a model failure and propagates, so the cycle is left Running for a later reclaim`() {
        val llm = ScriptedLlm { throw kotlinx.coroutines.CancellationException("the poll was cancelled") }
        val thrown = runCatching { propose(llm) }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException, "expected cancellation to propagate, got $thrown")
    }

    @Test
    fun `a database the goal mentions is shown to the model with the link to copy, since the validator allows it`() {
        val prompt = PlanPromptBuilder.build(input(context = context(rows = listOf(memoRow))))
        val scope = prompt.substringAfter("SCOPE:").substringBefore("LINKS YOU MAY USE")
        assertTrue(scope.contains("- D1  [database PPS Sources") && scope.contains("table sources"), scope)
        assertFalse(prompt.contains(notionPageUrl(sourcesMention.id)), "the database is named by handle, not by id")
        val links = prompt.substringAfter("LINKS YOU MAY USE")
        assertTrue(links.contains("- R1  [sources]"), "rows are still listed: $links")
    }

    @Test
    fun `a goal whose only mention is a database with no rows does not say it links to nothing`() {
        val prompt = PlanPromptBuilder.build(input(context = context(rows = emptyList(), outcomes = listOf(ScopeOutcome.TableListed(sourcesMention, "sources", 0, false)))))
        assertTrue(prompt.contains("- D1  [database"))
        assertFalse(prompt.contains("tasks have no links"), prompt)
    }

    @Test
    fun `an answer with no tool call, a wrong tool, or no task list is rejected with a code`() {
        assertEquals("no_tool_call", propose(ScriptedLlm { LlmResponse("I would plan this...", latencyMs = 1, retryCount = 0) }).rejectedReason)
        assertEquals("no_tool_call", propose(ScriptedLlm.answering(ScriptedLlm.planOf(), name = "some_other_tool")).rejectedReason)
        assertEquals("malformed_tool_input", propose(ScriptedLlm.answering(buildJsonObject { put("tasks", "none") })).rejectedReason)
        assertEquals("malformed_tool_input", propose(ScriptedLlm.answering(buildJsonObject { put("rationale", "no tasks key") })).rejectedReason)
    }

    @Test
    fun `an empty plan is a usable answer, not an error`() {
        val proposal = propose(ScriptedLlm.answering(ScriptedLlm.planOf()))
        assertNull(proposal.rejectedReason)
        assertTrue(proposal.tasks.isEmpty())
    }

    // ── The prompt ───────────────────────────────────────────────────────────

    @Test
    fun `the prompt carries what Jacob resolved, the last summary, Done when and the allowed links with their facts`() {
        val prompt = PlanPromptBuilder.build(
            input(
                escalations = listOf(
                    EscalationNote("Error found", "Resolved", "Which A/B box is right?", "Box B. Use it."),
                    EscalationNote("Review digest", "Open", "Please review", ""),
                ),
                previousSummary = "Executed 4 tasks: 4 done, 0 blocked, 0 failed.",
            ),
        )
        assertTrue(prompt.contains("Error found [Resolved]: Which A/B box is right? => Box B. Use it."))
        assertFalse(prompt.contains("Please review"), "an escalation with no resolution has nothing for the planner")
        assertTrue(prompt.contains("LAST CYCLE (CYCLE-0) SUMMARY:"))
        assertTrue(prompt.contains("- R1  [sources] Oct 6 Board memo  | Type: PPS Document; URL: https://example.org/memo.pdf"))
        assertTrue(prompt.contains("DONE WHEN (a check anyone could apply; plan so it can be checked from your tasks' results): Every listed document has a finding."))
        assertTrue(prompt.contains("- D1  [database PPS Sources, table sources: 1 row(s) listed below]"))
        assertFalse(prompt.contains("external:"), "no external URL is offered: none can be in scope")
    }

    @Test
    fun `the prompt names only the converged goal fields`() {
        val prompt = PlanPromptBuilder.build(input())
        for (retired in listOf("INTENT", "METRIC", "DONE CRITERIA", "TARGET LINKS", "Target Space", "Goal Type", "Metric Locked")) {
            assertFalse(prompt.contains(retired), "retired field '$retired' appears in the planner's prompt")
        }
    }

    @Test
    fun `mentions that could not be used are listed as such, so the model does not use them`() {
        val skippedMention = NotionMention(MentionKind.PAGE, "3f6d0721-d4e4-814d-9f91-f290a1eea863", "How this ledger works")
        val skipped = ScopeOutcome.Skipped(skippedMention, SkipReason.NOT_READABLE)
        val prompt = PlanPromptBuilder.build(input(context = context(outcomes = listOf(ScopeOutcome.TableListed(sourcesMention, "sources", 1, false), skipped))))
        assertTrue(prompt.contains("COULD NOT BE USED"))
        assertTrue(prompt.contains("How this ledger works: not readable"))
    }

    @Test
    fun `with nothing readable the prompt says so`() {
        val prompt = PlanPromptBuilder.build(input(context = context(rows = emptyList(), outcomes = emptyList())))
        assertTrue(prompt.contains("nothing in this goal's scope could be read"))
    }

    @Test
    fun `a long list of rows is cut to fit and says how many were left out`() {
        val rows = (1..400).map { i -> ContextRow("sources", "bbbbbbbb-bbbb-4bbb-8bbb-%012d".format(i), "https://www.notion.so/bbbbbbbbbbbb4bbb8bbb%012d".format(i), "Source number $i " + "x".repeat(80), listOf("Notes: " + "y".repeat(200))) }
        val prompt = PlanPromptBuilder.build(input(context = context(rows = rows)))
        assertTrue(prompt.length <= PlanPromptBuilder.MAX_CHARS, "prompt is ${prompt.length} characters")
        assertTrue(Regex("""\(\d+ more rows not shown: out of room\)""").containsMatchIn(prompt))
        assertTrue(prompt.endsWith("Propose the plan now."))
    }

    @Test
    fun `the prompt never contains a token or a path to one`() {
        val prompt = PlanPromptBuilder.build(input())
        assertFalse(prompt.contains("ntn_") || prompt.contains("notion-worker-token") || prompt.contains("Bearer"))
    }
}
