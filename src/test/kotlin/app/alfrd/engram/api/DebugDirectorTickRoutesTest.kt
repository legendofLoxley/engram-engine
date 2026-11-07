package app.alfrd.engram.api

import app.alfrd.engram.director.DirectorTickStartup
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DebugDirectorTickRoutesTest {
    private val token = "test-debug-token-12345"

    private fun Application.module(startup: DirectorTickStartup, loopback: Boolean = true) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == token) UserIdPrincipal("debug") else null } } }
        configureDebugDirectorTickRoutes(startup) { loopback }
    }

    @Test
    fun `no token is rejected`() = testApplication {
        application { module(DirectorTickStartup.Disabled("off", false)) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/debug/director-tick").status)
    }

    @Test
    fun `a disabled tick says so and why`() = testApplication {
        application { module(DirectorTickStartup.Disabled("DIRECTOR_TICK_IDENTITY is not set", false)) }
        val response = client.get("/debug/director-tick") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("DIRECTOR_TICK_IDENTITY is not set", body["disabledReason"]!!.jsonPrimitive.content)
        assertEquals(0, body["changes"]!!.jsonArray.size, "an empty list is written, not left out")
        assertEquals(0L, body["ticks"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a caller that is not on loopback is refused`() = testApplication {
        application { module(DirectorTickStartup.Disabled("off", false), loopback = false) }
        assertEquals(HttpStatusCode.Forbidden, client.get("/debug/director-tick") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }
}
