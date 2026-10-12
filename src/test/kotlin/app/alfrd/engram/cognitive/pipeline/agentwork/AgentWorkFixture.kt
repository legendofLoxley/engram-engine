package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.RateLimitedNotionApi
import app.alfrd.engram.notion.RequestLimiter
import app.alfrd.engram.notion.RetryPolicy
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant

/**
 * A fake Notion workspace holding the real Agent Work + PPS schemas, a few helpers to put a goal and
 * a cycle in it, and a guarded client over it built the way the runtime builds one. For tests of the
 * channel, the runtime wiring and the debug route; the processor's own test keeps its own fixtures.
 */
class AgentWorkFixture(val base: Instant = Instant.parse("2026-10-12T10:00:00Z")) {
    val tableSet = AgentWorkConfig.load("deploy/config/notion-agent-worker.json").tableSet
    val fake = FakeNotionApi(now = base)
    val template: AgentWorkTemplate = fake.installAgentWorkTemplate(tableSet)

    fun guarded(delegate: NotionApi = fake): NotionApi = AllowlistedNotionApi(
        RateLimitedNotionApi(delegate, RequestLimiter(100_000.0, 100_000), RetryPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 1, jitter = { 0 }), sleep = {}),
        tableSet,
    )

    fun processor(llm: ScriptedLlm?, traces: CycleTraceStore = CycleTraceStore(), over: NotionApi = guarded(), worker: String = "arx-1") =
        CycleProcessor(over, tableSet, CyclePlanDirector(llm), traces, worker, { base }, leaseDuration = Duration.ofMinutes(2), sleep = {})

    private fun tick() { fake.now = fake.now.plusSeconds(1) }

    fun source(title: String): NotionPage = runBlocking {
        fake.createPage(template.sources, mapOf("Title" to NotionValue.Title(title), "Type" to NotionValue.Select("PPS Document"), "URL" to NotionValue.UrlValue("https://example.org/${title.hashCode()}"))).also { tick() }
    }

    /** A goal that links the Sources database, the way the real one does. */
    fun goal(status: String = "Active", title: String = "PPS: read before lock"): NotionPage = runBlocking {
        fake.createPage(
            template.goals,
            mapOf(
                "Goal" to NotionValue.Title(title), "Goal Type" to NotionValue.Select("Work"), "Status" to NotionValue.Select(status),
                "Intent" to NotionValue.RichText("Read the primary documents before answer standards are locked."),
                "Scope" to NotionValue.RichText("The listed primary documents. No answer standards or locking."),
                "Target Links" to NotionValue.RichText("PPS Sources", links = listOf("https://www.notion.so/6e9ea25de7334d899d7a52783509ff54")),
                "Cycle Budget" to NotionValue.NumberValue(5.0),
            ),
        ).also { tick() }
    }

    fun cycle(goalId: String, status: String = "Queued"): NotionPage = runBlocking {
        fake.createPage(
            template.cycles,
            mapOf("Cycle" to NotionValue.Title("a cycle"), "Status" to NotionValue.Select(status), "Goal" to NotionValue.Relation(listOf(goalId))),
        ).also { tick() }
    }

    fun cycleStatus(id: String): String? = fake.page(id).select("Status")
    fun tasksFor(cycleId: String): List<NotionPage> = fake.rows(template.tasks).filter { cycleId in it.relation("Cycle") }.sortedBy { it.number("Order") }

    companion object {
        fun readPlan(vararg titles: String) = ScriptedLlm.answering(
            ScriptedLlm.planOf(*titles.map { ScriptedLlm.taskJson(it, "Read", "Read it and note what it says.") }.toTypedArray()),
        )
    }
}
