package app.alfrd.engram.director

import app.alfrd.engram.api.configureDebugSourceRoutes
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class DirectorTickOwnerIdentityTest {

    @Test
    fun `the tick accepts owner-jacob, while a debug fixture write to owner-jacob is still refused`() {
        val dir = Files.createTempDirectory("engram-tick-owner-").toFile()
        val manager = DatabaseManager(java.io.File(dir, "db").path)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            SchemaBootstrap.bootstrap(manager.getDatabase())
            val started = bootstrapDirectorTick(
                scope, manager.getDatabase(), NotionCycleStartup.Disabled("off", false), HermesActiveAssignmentRegistry(),
                mapOf("DIRECTOR_TICK_IDENTITY" to "owner-jacob", "DIRECTOR_TICK_DIR" to dir.resolve("o").path),
                start = false,
            )
            assertTrue(started is DirectorTickStartup.Running, "the read-only tick runs for owner-jacob: $started")

            val token = "tick-owner-test-token"
            testApplication {
                application {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    install(Authentication) { bearer("debug-token") { authenticate { c -> if (c.token == token) UserIdPrincipal("debug") else null } } }
                    configureDebugSourceRoutes(manager.getDatabase(), isLoopback = { true })
                }
                val r = client.post("/debug/sources/commit") {
                    header(HttpHeaders.Authorization, "Bearer $token"); header(HttpHeaders.ContentType, "application/json")
                    setBody("""{"syntheticUserId":"owner-jacob","source":{"type":"email_message","locator":"<m@x.org>","title":"t","threadId":"t","text":"x"},"items":[]}""")
                }
                assertEquals(HttpStatusCode.Forbidden, r.status)
            }
            val sources = manager.getDatabase().query("sql", "SELECT count(*) AS n FROM `Source`").use { it.next().getProperty<Number>("n").toLong() }
            assertEquals(0L, sources, "the refused fixture write left nothing behind")
        } finally { scope.cancel(); manager.close(); dir.deleteRecursively() }
    }
}
