package app.alfrd.engram.scripts

import app.alfrd.engram.cognitive.pipeline.agentwork.AgentWorkConfig
import app.alfrd.engram.notion.AllowlistedNotionApi
import app.alfrd.engram.notion.EnvAndFileSecretResolver
import app.alfrd.engram.notion.HttpNotionApi
import app.alfrd.engram.notion.NotionApiException
import app.alfrd.engram.notion.NotionConfigException
import app.alfrd.engram.notion.NotionPage
import app.alfrd.engram.notion.NotionRateLimitedException
import app.alfrd.engram.notion.NotionTable
import app.alfrd.engram.notion.NotionTransportException
import app.alfrd.engram.notion.RateLimitedNotionApi
import app.alfrd.engram.notion.RequestLimiter
import app.alfrd.engram.notion.RetryPolicy
import app.alfrd.engram.notion.SecretResolver
import app.alfrd.engram.notion.VisibleDataSource
import app.alfrd.engram.notion.kindName
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * The first live action of the Notion cycle channel, and only a read: **which of the configured data
 * sources (Agent Work, PPS and alfrd memory: 14) and which allowlisted pages (Persona) can the worker
 * token see, and what do the client's open assumptions look like against the real API?**
 *
 * It creates, updates and deletes nothing. It goes through the same client every table set uses
 * (the guard in front of the rate limiter in front of `HttpNotionApi`), with two deliberate
 * exceptions that are listed here so nobody has to find them: `searchDataSources` (a listing of
 * everything the token can see, which the guard has no business offering to planner code), and one
 * raw read per table by *database* id, which is the point of the "a database id is rejected" check.
 *
 * It prints ids, table names, property names and counts. It never prints a row's content, and it
 * never prints the token or anything derived from it.
 *
 * Exit status: 0 every data source and allowlisted page reachable, 2 the token is unreadable or
 * rejected, 3 at least one is not reachable (stop and report before running a cycle), 4 no network
 * answer, 64 usage or configuration.
 */
object NotionWorkerProbe {
    const val OK = 0
    const val TOKEN = 2
    const val MISSING = 3
    const val NETWORK = 4
    const val USAGE = 64

    private const val SAMPLE_ROWS = 5

    private class TableReport(val table: NotionTable) {
        var reachable = false
        var failure: String? = null
        var rows = 0
        var sampled: List<NotionPage> = emptyList()
        var parentPresent = 0
        var parentMatches = 0
        var wholeMinute = 0
        var queryProperties: Set<String> = emptySet()
        var retrieveOnly: List<String> = emptyList()
        var retrieveFailure: String? = null
        var databaseIdRejected: Boolean? = null
        var databaseIdDetail: String? = null
    }

    private class PageReport(val name: String, val pageId: String) {
        var reachable = false
        var failure: String? = null
    }

    suspend fun run(
        args: List<String>,
        env: Map<String, String>,
        out: PrintStream,
        resolver: SecretResolver = EnvAndFileSecretResolver(env),
        baseUrl: String = HttpNotionApi.DEFAULT_BASE_URL,
        httpClient: OkHttpClient = HttpNotionApi.defaultClient(),
        limiter: RequestLimiter = RequestLimiter(),
        retryPolicy: RetryPolicy = RetryPolicy(),
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): Int {
        if ("--help" in args || "-h" in args) {
            out.println("usage: NotionWorkerProbe [--config PATH] [--check-token]\n  --check-token  only read the token through this process and say whether it is readable (no network)")
            return OK
        }
        val checkTokenOnly = "--check-token" in args
        val configPath = args.indexOf("--config").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            ?: env["NOTION_WORKER_CONFIG"]?.takeIf { it.isNotBlank() }
        if (configPath == null) {
            out.println("no configuration: pass --config PATH or set NOTION_WORKER_CONFIG")
            return USAGE
        }
        val config = try {
            AgentWorkConfig.load(configPath)
        } catch (e: NotionConfigException) {
            out.println("configuration: ${e.message}")
            return USAGE
        }
        val set = config.tableSet

        try {
            resolver.resolve(config.tokenRef)
        } catch (e: NotionConfigException) {
            out.println("token: NOT readable by this process (${e.message})")
            return TOKEN
        }
        out.println("token: readable by this process")
        if (checkTokenOnly) return OK

        val http = HttpNotionApi({ resolver.resolve(config.tokenRef) }, baseUrl, httpClient)
        val guarded = AllowlistedNotionApi(RateLimitedNotionApi(http, limiter, retryPolicy, sleep), set)

        out.println("set: ${set.name} (${set.tables.size} tables; read-only probe: nothing is created, updated or deleted)")
        out.println()

        // ── 1. What the token can see ────────────────────────────────────────
        val visible: List<VisibleDataSource>? = try {
            withBackoff(sleep) { http.searchDataSources() }
        } catch (e: NotionApiException) {
            if (e.status == 401) { out.println("token: REJECTED by Notion (HTTP 401 ${e.code ?: ""})"); return TOKEN }
            out.println("listing: could not list data sources (HTTP ${e.status} ${e.code ?: ""}); continuing with the per-table reads")
            null
        } catch (e: NotionTransportException) {
            out.println("network: no answer from Notion (${e::class.java.simpleName})")
            return NETWORK
        }

        val reports = set.tables.map { TableReport(it) }
        for (report in reports) {
            try {
                probeTable(report, guarded, http, sleep)
            } catch (e: NotionTransportException) {
                out.println("network: no answer from Notion (${e::class.java.simpleName})")
                return NETWORK
            }
        }
        if (reports.any { it.failure?.startsWith("HTTP 401") == true }) {
            out.println("token: REJECTED by Notion (HTTP 401)")
            return TOKEN
        }

        val pageReports = set.readablePages.map { PageReport(it.name, it.pageId) }
        for (report in pageReports) {
            try {
                // A read of one allowlisted page's properties: no body, no content is printed.
                guarded.retrievePage(report.pageId)
                report.reachable = true
            } catch (e: NotionApiException) {
                report.failure = "HTTP ${e.status} ${e.code ?: ""}".trim()
            } catch (e: NotionRateLimitedException) {
                report.failure = "rate limited past the retry budget"
            } catch (e: NotionTransportException) {
                out.println("network: no answer from Notion (${e::class.java.simpleName})")
                return NETWORK
            }
        }

        out.println("== The ${reports.size} data sources ==")
        for (r in reports) {
            val listed = visible?.any { it.id == r.table.dataSourceId }
            val state = if (r.reachable) "VISIBLE" else "MISSING"
            val extra = buildList {
                if (listed != null) add(if (listed) "in search listing" else "not in search listing")
                r.failure?.let { add(it) }
            }.joinToString("; ")
            out.println("  %-8s %-12s %s%s".format(state, r.table.name, r.table.dataSourceId, if (extra.isEmpty()) "" else "  ($extra)"))
        }
        out.println()
        out.println("== Allowlisted pages (read-only; the only plain pages the client may read) ==")
        pageReports.forEach { r ->
            out.println("  %-8s %-12s %s%s".format(if (r.reachable) "VISIBLE" else "MISSING", r.name, r.pageId, r.failure?.let { "  ($it)" } ?: ""))
        }
        val configured = set.tables.map { it.dataSourceId }.toSet()
        val beyond = visible.orEmpty().filter { it.id !in configured }
        out.println()
        out.println("== Other data sources the token can see (not used; the integration may be shared wider than this increment needs) ==")
        when {
            visible == null -> out.println("  (listing unavailable)")
            beyond.isEmpty() -> out.println("  none")
            else -> beyond.forEach { out.println("  ${it.id}  \"${it.title.take(80)}\"") }
        }

        out.println()
        out.println("== Per-table read ==")
        for (r in reports.filter { it.reachable }) {
            out.println("  ${r.table.name}: ${r.rows} row(s) sampled")
            if (r.rows > 0) {
                out.println("    parent.data_source_id: present on ${r.parentPresent}/${r.rows}, equal to the configured id on ${r.parentMatches}/${r.rows}")
                out.println("    last_edited_time: whole minute on ${r.wholeMinute}/${r.rows}")
                out.println("    properties in a query result: ${r.queryProperties.size}; only on a page read: ${if (r.retrieveOnly.isEmpty()) "none" else r.retrieveOnly.joinToString(", ")}")
                r.retrieveFailure?.let { out.println("    page read failed: $it") }
            }
        }

        out.println()
        out.println("== Open client assumptions, as the live API answered them ==")
        val reachable = reports.filter { it.reachable }
        out.println("  1. collection:// UUID accepted by the data source endpoints: " +
            if (reachable.size == reports.size) "YES, all ${reports.size} answered a query by data source id" else "${reachable.size} of ${reports.size} answered; the rest are listed MISSING above")
        val dbProbed = reports.filter { it.databaseIdRejected != null }
        out.println("  2. a database id is rejected where a data source id is expected: " + when {
            dbProbed.isEmpty() -> "NOT TESTED (no table has a databaseId)"
            dbProbed.all { it.databaseIdRejected == true } -> "YES, rejected for all ${dbProbed.size} (${dbProbed.mapNotNull { it.databaseIdDetail }.distinct().joinToString("; ")})"
            else -> "NO: accepted for ${dbProbed.filter { it.databaseIdRejected == false }.joinToString(", ") { it.table.name }}; the docs and the guard's data-source-only allowlist need revisiting"
        })
        val withRows = reachable.filter { it.rows > 0 }
        out.println("  3. parent.data_source_id present on returned pages: " + when {
            withRows.isEmpty() -> "NOT TESTED (no rows)"
            withRows.all { it.parentPresent == it.rows } -> "YES, on every sampled page of ${withRows.size} table(s)"
            else -> "NOT ALWAYS: absent on ${withRows.filter { it.parentPresent < it.rows }.joinToString(", ") { it.table.name }}; the guard's page provenance depends on it"
        })
        val sampledTotal = withRows.sumOf { it.rows }
        val minuteTotal = withRows.sumOf { it.wholeMinute }
        out.println("  4. last_edited_time granularity: " + when {
            sampledTotal == 0 -> "NOT TESTED (no rows)"
            minuteTotal == sampledTotal -> "whole minute on all $sampledTotal sampled pages; compare with a tolerance of at least a minute, never exact equality"
            minuteTotal == 0 -> "finer than a minute on all $sampledTotal sampled pages"
            else -> "mixed: whole minute on $minuteTotal of $sampledTotal sampled pages"
        })
        val computed = withRows.flatMap { it.retrieveOnly.map { name -> "${it.table.name}.$name" } }
        out.println("  5. formula and rollup values: " + when {
            withRows.isEmpty() -> "NOT TESTED (no rows)"
            computed.isEmpty() -> "every property of a page read was also in the query result"
            else -> "only on a page read, not in a query: ${computed.joinToString(", ")}"
        })
        out.println("  6. select option behavior (an unknown option name on create/update): NOT PROBED. It needs a write, and this probe writes nothing. It is observable on the first real cycle.")

        val missing = reports.filter { !it.reachable }.map { it.table.name } + pageReports.filter { !it.reachable }.map { "page:${it.name}" }
        out.println()
        if (missing.isNotEmpty()) {
            out.println("RESULT: ${missing.size} of ${reports.size + pageReports.size} data source(s) and page(s) are not reachable with this token: ${missing.joinToString(", ")}.")
            out.println("Stop here. Connect the alfrd-worker integration to each (Jacob connects it to his top alfrd page; Agent Work and PPS are shared separately), or fix the id in the configuration, before a cycle runs.")
            return MISSING
        }
        out.println("RESULT: all ${reports.size} data sources and ${pageReports.size} allowlisted page(s) are reachable. Nothing was written.")
        return OK
    }

    private suspend fun probeTable(report: TableReport, guarded: app.alfrd.engram.notion.NotionApi, http: HttpNotionApi, sleep: suspend (Long) -> Unit) {
        val table = report.table
        val page = try {
            guarded.query(table.dataSourceId, pageSize = SAMPLE_ROWS)
        } catch (e: NotionApiException) {
            report.failure = "HTTP ${e.status} ${e.code ?: ""}".trim() + " " + e.detail.take(120)
            return
        } catch (e: NotionRateLimitedException) {
            report.failure = "rate limited past the retry budget"
            return
        }
        report.reachable = true
        report.sampled = page.pages
        report.rows = page.pages.size
        for (row in page.pages) {
            if (row.dataSourceId != null) report.parentPresent++
            if (row.dataSourceId == table.dataSourceId) report.parentMatches++
            if (row.lastEditedTime.epochSecond % 60 == 0L && row.lastEditedTime.nano == 0) report.wholeMinute++
        }
        report.queryProperties = page.pages.firstOrNull()?.properties?.keys.orEmpty()

        page.pages.firstOrNull()?.let { first ->
            try {
                val full = guarded.retrievePage(first.id)
                report.retrieveOnly = (full.properties.keys - report.queryProperties).sorted().map { name -> "$name (${full.properties.getValue(name).kindName.lowercase()})" }
            } catch (e: NotionApiException) {
                report.retrieveFailure = "HTTP ${e.status} ${e.code ?: ""}".trim()
            }
        }

        table.databaseId?.let { databaseId ->
            // Raw, on purpose: the guard would refuse an id that is not a configured data source before Notion saw it.
            try {
                val answered = withBackoff(sleep) { http.query(databaseId, pageSize = 1) }
                report.databaseIdRejected = false
                report.databaseIdDetail = "answered with ${answered.pages.size} row(s)"
            } catch (e: NotionApiException) {
                report.databaseIdRejected = true
                report.databaseIdDetail = "HTTP ${e.status}${e.code?.let { " $it" }.orEmpty()}"
            }
        }
    }

    /** One honest retry loop for the two reads that bypass the rate-limited client: honor `Retry-After`, a few times, then give up. */
    private suspend fun <T> withBackoff(sleep: suspend (Long) -> Unit, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: NotionRateLimitedException) {
                if (++attempt >= 5) throw e
                sleep(e.retryAfterMs ?: (1_000L * attempt))
            }
        }
    }
}

fun main(args: Array<String>) {
    val status = runBlocking { NotionWorkerProbe.run(args.toList(), System.getenv(), System.out) }
    exitProcess(status)
}
