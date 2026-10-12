package app.alfrd.engram.api

import app.alfrd.engram.cognitive.pipeline.agentwork.AgentWorkFixture
import app.alfrd.engram.cognitive.pipeline.agentwork.CycleTraceStore
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleChannel
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleInfo
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleRuntime
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.notion.RateLimitedNotionApi
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

private const val DEBUG_TOKEN = "test-debug-token-12345"

class DebugNotionCycleRoutesTest {

    private fun Application.module(startup: NotionCycleStartup) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) {
            bearer("debug-token") {
                authenticate { credential -> if (credential.token == DEBUG_TOKEN) UserIdPrincipal("debug") else null }
            }
        }
        configureDebugNotionCycleRoutes(startup)
    }

    /** A running channel that has planned two cycles, so there are two traces to read. */
    private fun runningWithTwoTraces(): NotionCycleStartup.Running {
        val fixture = AgentWorkFixture()
        val goal = fixture.goal("Active")
        fixture.cycle(goal.id)
        fixture.cycle(goal.id)
        val traces = CycleTraceStore()
        val processor = fixture.processor(AgentWorkFixture.readPlan("Read the board memo"), traces)
        val channel = NotionCycleChannel(processor, Duration.ofMinutes(3), clock = { fixture.base })
        runBlocking { channel.pollOnce() }
        val info = NotionCycleInfo("agent-worker", "arx-1#1", 180, 120, listOf("agent-tasks", "cycles"), listOf("claims", "evidence", "escalations", "goals", "questions", "sources"))
        val limited = RateLimitedNotionApi(fixture.fake)
        return NotionCycleStartup.Running(NotionCycleRuntime(channel, traces, info, limited))
    }

    @Test
    fun `a request with no Authorization header is rejected with 401`() = testApplication {
        application { module(NotionCycleStartup.Disabled("off", misconfigured = false)) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/debug/notion-cycles").status)
    }

    @Test
    fun `a request with the wrong debug token is rejected with 401`() = testApplication {
        application { module(NotionCycleStartup.Disabled("off", misconfigured = false)) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/debug/notion-cycles") { header(HttpHeaders.Authorization, "Bearer wrong") }.status)
    }

    @Test
    fun `a disabled channel says why`() = testApplication {
        application { module(NotionCycleStartup.Disabled("the Director's local model is not configured", misconfigured = true)) }
        val response = client.get("/debug/notion-cycles") { header(HttpHeaders.Authorization, "Bearer $DEBUG_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("false", body["enabled"]!!.jsonPrimitive.content)
        assertEquals("the Director's local model is not configured", body["disabledReason"]!!.jsonPrimitive.content)
        assertTrue(body["traces"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `a running channel shows its setup, its last poll and the planning decision for each recent cycle`() = testApplication {
        val running = runningWithTwoTraces()
        application { module(running) }
        val response = client.get("/debug/notion-cycles") { header(HttpHeaders.Authorization, "Bearer $DEBUG_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals("true", body["enabled"]!!.jsonPrimitive.content)
        assertEquals("agent-worker", body["setup"]!!.jsonObject["setName"]!!.jsonPrimitive.content)
        assertEquals("1", body["channel"]!!.jsonObject["polls"]!!.jsonPrimitive.content)
        val traces = body["traces"]!!.jsonArray
        assertEquals(2, traces.size)
        val first = traces.first().jsonObject
        assertEquals("done", first["outcome"]!!.jsonPrimitive.content)
        assertEquals("Read the board memo", first["accepted"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue(first["writes"]!!.jsonArray.isNotEmpty(), "a trace lists the rows it wrote, by op key")
    }

    @Test
    fun `limit bounds the traces returned`() = testApplication {
        val running = runningWithTwoTraces()
        application { module(running) }
        val body = Json.parseToJsonElement(client.get("/debug/notion-cycles?limit=1") { header(HttpHeaders.Authorization, "Bearer $DEBUG_TOKEN") }.bodyAsText()).jsonObject
        assertEquals(1, body["traces"]!!.jsonArray.size)
    }

    @Test
    fun `the response carries no token path and nothing that looks like a Notion secret`() = testApplication {
        val running = runningWithTwoTraces()
        application { module(running) }
        val text = client.get("/debug/notion-cycles") { header(HttpHeaders.Authorization, "Bearer $DEBUG_TOKEN") }.bodyAsText()
        assertFalse(text.contains(".notion-worker-token"), text)
        assertFalse(Regex("""(ntn_|secret_)[A-Za-z0-9]{10,}""").containsMatchIn(text), text)
    }
}
