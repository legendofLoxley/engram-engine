package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionBlock
import app.alfrd.engram.notion.RichSegment
import app.alfrd.engram.notion.chunkText

/**
 * What the report of one cycle is built from, collected as the cycle runs. [rationale] is the planner's, in full
 * (the trace keeps only the first 400 characters). [tasks] are the cycle's tasks as they stand, with their Results.
 * [decision] is QC's decision, when it gave one.
 */
class ReportDraft {
    var rationale: String? = null
    var tasks: List<AgentTask> = emptyList()
    var decision: QcDecision? = null
}

/** The sentence added to a Summary and to the report when the plan endpoint failed and the default endpoint served the plan. */
fun planFallbackLine(fallback: FallbackTrace): String =
    "Plan served by fallback endpoint (the plan endpoint ${fallback.endpoint} failed with ${fallback.reason} after ${fallback.failedAfterMs / 1000} s)."

/**
 * The one-page report written into a Cycle's own page body when the cycle finishes. Pure: it builds blocks from
 * what the Director already holds, and nothing here reads or writes Notion ([CycleReportWriter] does the write).
 *
 * Plain headings, in this order: Verdict, Result, QC, Plan, What each task found, Notes. The first block is a
 * marker line that [CycleReportWriter] looks for, so a retry never appends the report twice.
 */
object CycleReport {
    const val MARKER_PREFIX = "Director report · cycle-report:v1:"
    const val END_LINE = "End of Director report."
    const val FOUND_CHARS = 400

    fun marker(cyclePageId: String) = MARKER_PREFIX + cyclePageId

    fun build(cycle: Cycle, status: CycleStatus, summary: String, draft: ReportDraft, trace: CyclePlanTrace): List<NotionBlock> {
        val out = ArrayList<NotionBlock>()
        fun heading(text: String) { out += NotionBlock.Heading(2, text) }
        fun text(body: String) { body.lineSequence().filter { it.isNotBlank() }.forEach { out += paragraph(it) } }

        out += paragraph(marker(cycle.pageId))
        val tasks = draft.tasks.filter { it.status != TaskStatus.SUPERSEDED }.sortedBy { it.order ?: Int.MAX_VALUE }
        val decision = draft.decision

        heading("Verdict")
        out += paragraph("Goal looks met: ${decision?.goalLooksMet ?: "not assessed"}")
        out += paragraph(verdictLine(status, summary, tasks, decision))

        heading("Result")
        val final = tasks.lastOrNull { it.type == TaskType.CONSOLIDATE && it.result.isNotBlank() } ?: tasks.lastOrNull { it.result.isNotBlank() }
        if (final == null) out += paragraph("No task produced a result.")
        else {
            out += paragraph("From task ${final.order ?: "?"} (${final.type?.label ?: "?"}: ${final.title})", bold = true)
            text(final.result)
        }

        heading("QC")
        if (decision == null) {
            out += paragraph("QC gave no decision" + (trace.qc?.rejectedReason?.let { " ($it)" } ?: if (trace.qc == null) " (the cycle ended before QC)" else "") + ".")
        } else {
            out += paragraph("Summary", bold = true); text(decision.summary)
            out += paragraph("Goal Feedback", bold = true); text(decision.goalFeedback)
            if (!decision.suggestedText.isNullOrBlank()) {
                out += paragraph("Suggested rewrite of ${decision.suggestedField ?: "the goal"}", bold = true); text(decision.suggestedText)
            }
        }

        heading("Plan")
        out += paragraph(draft.rationale?.takeIf { it.isNotBlank() } ?: "No rationale is available (the plan was written by an earlier run, or planning did not finish).")
        if (tasks.isEmpty()) out += paragraph("No tasks were planned.")
        for (t in tasks) {
            out += NotionBlock.Paragraph(
                listOf(RichSegment("${t.type?.label ?: "?"}: ${t.title}", link = notionPageUrl(t.pageId))),
            )
        }

        heading("What each task found")
        if (tasks.isEmpty()) out += paragraph("No task ran.")
        for (t in tasks) {
            out += paragraph("${t.order ?: "?"}. ${t.type?.label ?: "?"}: ${t.title} (${t.status?.label ?: "?"})", bold = true)
            out += paragraph(if (t.result.isBlank()) "(no result)" else first(t.result, FOUND_CHARS).replace('\n', ' '))
        }

        heading("Notes")
        notes(draft, tasks, decision, trace).forEach { out += paragraph(it) }
        out += paragraph(END_LINE)
        return out
    }

    private fun verdictLine(status: CycleStatus, summary: String, tasks: List<AgentTask>, decision: QcDecision?): String {
        if (decision == null) return "Cycle ${status.label.lowercase()}: " + first(summary, 300).replace('\n', ' ')
        val done = tasks.count { it.status == TaskStatus.DONE }
        val blocked = tasks.count { it.status == TaskStatus.BLOCKED }
        val failed = tasks.count { it.status == TaskStatus.FAILED }
        return "Cycle ${status.label.lowercase()}: ${tasks.size} task${if (tasks.size == 1) "" else "s"} ($done done, $blocked blocked, $failed failed); QC reads the goal as ${decision.goalLooksMet}."
    }

    private fun notes(draft: ReportDraft, tasks: List<AgentTask>, decision: QcDecision?, trace: CyclePlanTrace): List<String> {
        val lines = ArrayList<String>()
        val plan = trace.model
        lines += plan?.fallback?.let { "Plan: ${planFallbackLine(it)}" } ?: "Plan: served by the endpoint it was sent to (no fallback)."
        lines += "QC: served by the default endpoint (QC has no second endpoint, so no fallback)."
        if (trace.rejected.isEmpty()) lines += "Rejected tasks: none."
        else trace.rejected.forEach { lines += "Rejected task ${it.index + 1}${it.title?.let { t -> " \"$t\"" }.orEmpty()}: ${it.reason.lowercase().replace('_', ' ')}${it.detail?.let { d -> " ($d)" }.orEmpty()}" }

        val cuts = ArrayList<String>()
        fun scan(where: String, body: String?) { val n = CUT.findAll(body.orEmpty()).count(); if (n > 0) cuts += "$where: $n cut marker${if (n == 1) "" else "s"}" }
        scan("QC Summary", decision?.summary); scan("QC Goal Feedback", decision?.goalFeedback); scan("QC suggested rewrite", decision?.suggestedText)
        tasks.forEach { scan("task ${it.order} result", it.result) }
        fun given(where: String, g: GivenTrace?) {
            if (g == null) return
            if (g.cutItemIds.isNotEmpty()) cuts += "$where: ${g.cutItemIds.size} item${if (g.cutItemIds.size == 1) "" else "s"} given only in part (${g.cutItemIds.joinToString(", ")})"
            if (g.leftOutCount > 0) cuts += "$where: ${g.leftOutCount} item${if (g.leftOutCount == 1) "" else "s"} not given"
        }
        given("Plan prompt", plan?.given); trace.execution.forEach { given("Task ${it.order} prompt", it.given) }; given("QC prompt", trace.qc?.given)
        lines += if (cuts.isEmpty()) "Cuts and limits: none applied." else "Cuts and limits: " + cuts.joinToString("; ") + "."

        fun call(who: String, endpoint: String?, model: String?, ms: Long?) =
            "$who: ${endpoint ?: "endpoint unknown"} · ${model ?: "model unknown"} · ${ms?.let { "%.1f s".format(it / 1000.0) } ?: "no duration"}"
        lines += if (plan == null || !plan.called) "Plan call: none." else call("Plan call", plan.answer?.endpoint, plan.answer?.model ?: plan.model, plan.latencyMs)
        trace.execution.sortedBy { it.order }.forEach {
            lines += if (it.answer == null && it.latencyMs == null) "Task ${it.order} call: none in this attempt (${it.outcome})."
            else call("Task ${it.order} call", it.answer?.endpoint, it.answer?.model, it.latencyMs)
        }
        val qc = trace.qc
        lines += if (qc == null || !qc.called) "QC call: none." else call("QC call", qc.answer?.endpoint, qc.answer?.model ?: qc.model, qc.latencyMs)
        return lines
    }

    private val CUT = Regex("""\[CUT \d+ chars?\]""")

    /** The first [max] characters, and a visible marker with the count when it was shortened. */
    fun first(text: String, max: Int): String {
        val t = text.trim()
        return if (t.length <= max) t else t.take(max) + " [CUT ${t.length - max} chars]"
    }

    private fun paragraph(text: String, bold: Boolean = false) =
        NotionBlock.Paragraph(chunkText(text).ifEmpty { listOf("") }.map { RichSegment(it, bold = bold) })
}

/**
 * Writes a [CycleReport] to a Cycle's own page body. Idempotent: the first block is a marker line, and a page that
 * already has it is left alone, so a retry, a reclaim or a second finish never appends the report twice.
 * It does not catch failures; the caller decides what a failed report means (for the processor: a trace note,
 * never a failed cycle).
 */
class CycleReportWriter(private val api: NotionApi) {

    enum class Outcome { WRITTEN, ALREADY_PRESENT, ALREADY_PRESENT_INCOMPLETE }

    suspend fun write(cyclePageId: String, blocks: List<NotionBlock>): Outcome {
        val marker = CycleReport.marker(cyclePageId)
        var hasMarker = false
        var hasEnd = false
        var cursor: String? = null
        var pages = 0
        do {
            val page = api.listBlocks(cyclePageId, cursor, NotionApi.MAX_PAGE_SIZE)
            for (b in page.blocks) {
                val t = b.plainText.trim()
                if (t == marker) hasMarker = true
                if (hasMarker && t == CycleReport.END_LINE) hasEnd = true
            }
            cursor = page.nextCursor
        } while (cursor != null && ++pages < MAX_LIST_PAGES)
        if (hasMarker) return if (hasEnd) Outcome.ALREADY_PRESENT else Outcome.ALREADY_PRESENT_INCOMPLETE
        blocks.chunked(NotionApi.MAX_BLOCKS_PER_APPEND).forEach { api.appendBlocks(cyclePageId, it) }
        return Outcome.WRITTEN
    }

    private companion object { const val MAX_LIST_PAGES = 20 }
}
