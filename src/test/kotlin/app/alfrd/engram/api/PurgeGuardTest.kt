package app.alfrd.engram.api

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import com.arcadedb.database.Database
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/** Scratch databases only: each test gets its own directory under ./data and removes it. */
class PurgeGuardTest {
    private val jacob = "debug+owner-jacob@test.alfrd.internal"
    private lateinit var dbManager: DatabaseManager
    private lateinit var path: String
    private val db: Database get() = dbManager.getDatabase()

    @BeforeEach
    fun setup() {
        path = "./data/test-purge-guard-${System.nanoTime()}"
        dbManager = DatabaseManager(path)
        SchemaBootstrap.bootstrap(db)
    }

    @AfterEach
    fun teardown() {
        dbManager.close()
        File(path).deleteRecursively()
    }

    private fun exists(email: String) = db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email)).use { it.hasNext() }

    private fun seed(vararg labels: String): List<String> = labels.map { DebugConverseService.resolveUserId(it).also { e -> DebugConverseService.ensureSyntheticUser(db, e) } }

    // ── ProtectedIdentities ──────────────────────────────────────────────────

    @Test
    fun `default protects owner-jacob and nothing else`() {
        val p = ProtectedIdentities.fromEnvironment(emptyMap())
        assertEquals(listOf(jacob), p.names())
    }

    @Test
    fun `the configured list replaces the default but the served identities are still added`() {
        val p = ProtectedIdentities.fromEnvironment(mapOf(
            "PURGE_PROTECTED_IDENTITIES" to "alice, Bob@Example.com",
            "OWNER_IDENTITY" to "owner-x", "DIRECTOR_TICK_IDENTITY" to "tick-y",
        ))
        assertEquals(
            setOf("debug+alice@test.alfrd.internal", "bob@example.com", "debug+owner-x@test.alfrd.internal", "debug+tick-y@test.alfrd.internal"),
            p.names().toSet(),
        )
        assertTrue(p.isProtected("DEBUG+ALICE@test.alfrd.internal"))
        assertFalse(p.isProtected(jacob))
    }

    // ── purge ────────────────────────────────────────────────────────────────

    @Test
    fun `purge deletes the synthetic user and leaves owner-jacob`() {
        val (jacobEmail, other) = seed("owner-jacob", "synthetic-one")
        val outcome = DebugConverseService.purge(db, ProtectedIdentities.fromEnvironment(emptyMap()))
        assertEquals(1, outcome.deleted)
        assertEquals(listOf(jacobEmail), outcome.skippedProtected)
        assertTrue(exists(jacobEmail), "owner-jacob must remain")
        assertFalse(exists(other), "the synthetic user must be gone")
    }

    @Test
    fun `an identity protected only through OWNER_IDENTITY survives, with the configured list empty`() {
        val (owner, other) = seed("someone-else", "synthetic-two")
        val p = ProtectedIdentities.fromEnvironment(mapOf("PURGE_PROTECTED_IDENTITIES" to "", "OWNER_IDENTITY" to "someone-else"))
        val outcome = DebugConverseService.purge(db, p)
        assertEquals(1, outcome.deleted)
        assertEquals(listOf(owner), outcome.skippedProtected)
        assertTrue(exists(owner))
        assertFalse(exists(other))
    }

    @Test
    fun `an identity protected only through DIRECTOR_TICK_IDENTITY survives`() {
        val (tick) = seed("tick-only")
        val p = ProtectedIdentities.fromEnvironment(mapOf("PURGE_PROTECTED_IDENTITIES" to "", "DIRECTOR_TICK_IDENTITY" to "tick-only"))
        assertEquals(0, DebugConverseService.purge(db, p).deleted)
        assertTrue(exists(tick))
    }

    @Test
    fun `purge with only protected users matching returns cleanly`() {
        val (jacobEmail) = seed("owner-jacob")
        val outcome = DebugConverseService.purge(db, ProtectedIdentities.fromEnvironment(emptyMap()))
        assertEquals(0, outcome.deleted)
        assertEquals(listOf(jacobEmail), outcome.skippedProtected)
        assertTrue(exists(jacobEmail))
    }

    @Test
    fun `purge with nothing at all returns cleanly`() {
        val outcome = DebugConverseService.purge(db, ProtectedIdentities.fromEnvironment(emptyMap()))
        assertEquals(0, outcome.deleted)
        assertTrue(outcome.skippedProtected.isEmpty())
    }

    @Test
    fun `the convenience overload is guarded by the environment default too`() {
        val (jacobEmail, other) = seed("owner-jacob", "synthetic-three")
        // no env in the test JVM sets PURGE_PROTECTED_IDENTITIES, so the default (owner-jacob) applies
        assertEquals(1, DebugConverseService.purgeAllSyntheticUsers(db))
        assertTrue(exists(jacobEmail))
        assertFalse(exists(other))
    }

    // ── the route ────────────────────────────────────────────────────────────

    @Test
    fun `the purge route reports what it deleted and what it skipped`() = testApplication {
        val (jacobEmail, other) = seed("owner-jacob", "synthetic-four")
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == "t") UserIdPrincipal("debug") else null } } }
            configureDebugConverseRoutes(app.alfrd.engram.cognitive.SessionManager(factory = { error("unused") }), db, ProtectedIdentities.fromEnvironment(emptyMap()))
        }
        val response = client.delete("/debug/converse/purge") { header(HttpHeaders.Authorization, "Bearer t") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["deletedUserCount"]!!.jsonPrimitive.int)
        assertEquals(listOf(jacobEmail), body["skippedProtected"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(exists(jacobEmail))
        assertFalse(exists(other))

        // a second call, with only the protected user left, is a clean 200 with zero deleted
        val again = Json.parseToJsonElement(client.delete("/debug/converse/purge") { header(HttpHeaders.Authorization, "Bearer t") }.bodyAsText()).jsonObject
        assertEquals(0, again["deletedUserCount"]!!.jsonPrimitive.int)
    }
}
