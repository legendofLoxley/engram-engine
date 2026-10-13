package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.FakeNotionApi
import app.alfrd.engram.notion.MentionKind
import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionMention
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionValue
import app.alfrd.engram.notion.RateLimitedNotionApi
import app.alfrd.engram.notion.RequestLimiter
import app.alfrd.engram.notion.RetryPolicy
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant

/** A Scope value the way Notion returns one: the text includes each mention's title, and the mentions are typed. */
fun scopeOf(words: String, vararg mentions: NotionMention): NotionValue.RichText =
    NotionValue.RichText(
        text = listOf(words, *mentions.map { it.title }.toTypedArray()).filter { it.isNotEmpty() }.joinToString(" "),
        links = mentions.map { notionPageUrl(it.id) },
        mentions = mentions.toList(),
    )

fun databaseMention(databaseId: String, title: String = "A database") = NotionMention(MentionKind.DATABASE, databaseId, title)
fun pageMention(pageId: String, title: String = "A page") = NotionMention(MentionKind.PAGE, pageId, title)

/**
 * A fake Notion workspace holding the real Agent Work schemas, the PPS and alfrd-memory tables and the
 * Persona page, a few helpers to put a goal and a cycle in it, and a guarded client over it built the way
 * the runtime builds one. For tests of the channel, the runtime wiring and the debug route; the
 * processor's own test keeps its own fixtures.
 */
class AgentWorkFixture(val base: Instant = Instant.parse("2026-10-12T10:00:00Z")) {
    val tableSet = AgentWorkConfig.load("deploy/config/notion-agent-worker.json").tableSet
    val fake = FakeNotionApi(now = base)
    val template: AgentWorkTemplate = fake.installAgentWorkTemplate(tableSet)

    /** The PPS Sources database, as a mention the way a goal's Scope would hold it. */
    val sourcesMention: NotionMention get() = databaseMention(tableSet.table(AgentWorkTables.SOURCES).databaseId!!, "PPS Sources")

    fun guarded(delegate: NotionApi = fake): NotionApi = AllowlistedNotionApi(
        RateLimitedNotionApi(delegate, RequestLimiter(100_000.0, 100_000), RetryPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 1, jitter = { 0 }), sleep = {}),
        tableSet,
    )

    fun processor(llm: LlmClient?, traces: CycleTraceStore = CycleTraceStore(), over: NotionApi = guarded(), worker: String = "arx-1") =
        CycleProcessor(
            over, tableSet, CyclePlanDirector(llm), DirectorTaskExecutor(llm), CycleQcDirector(llm), traces, worker, { base },
            leaseDuration = Duration.ofMinutes(2), sleep = {},
        )

    private fun tick() { fake.now = fake.now.plusSeconds(1) }

    fun source(title: String): NotionPage = runBlocking {
        fake.createPage(template.sources, mapOf("Title" to NotionValue.Title(title), "Type" to NotionValue.Select("PPS Document"), "URL" to NotionValue.UrlValue("https://example.org/${title.hashCode()}"))).also { tick() }
    }

    /** A goal whose Scope @-mentions the Sources database, the way the real one does. */
    fun goal(status: String = "Active", title: String = "Read the primary documents", scope: NotionValue.RichText = scopeOf("", sourcesMention)): NotionPage = runBlocking {
        fake.createPage(
            template.goals,
            mapOf(
                "Goal" to NotionValue.Title(title), "Status" to NotionValue.Select(status),
                "Done when" to NotionValue.RichText("Every listed document has a finding."),
                "Scope" to scope,
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
        /** A plan of Read tasks with no links, everything done, a QC close-out. */
        fun readPlan(vararg titles: String) = RoutedLlm.happy(*titles.map { ScriptedLlm.taskJson(it, "Read", "Read it and note what it says.") }.toTypedArray())
    }
}
