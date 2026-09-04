package app.alfrd.engram.api

import app.alfrd.engram.db.GraphBackupCoordinator
import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private const val APP_VERSION = "0.1.0"

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val uptimeSeconds: Long,
    val database: String,
    val service: String,
    val anthropicKeySet: Boolean,
    val googleKeySet: Boolean,
    /** Null when hosted persistence is disabled (e.g. local dev) or no backup has completed yet. */
    val lastBackupAgeSeconds: Long? = null,
    val lastBackupDurationMs: Long? = null,
    val lastBackupSizeBytes: Long? = null,
    val lastBackupError: String? = null,
)

@Serializable
data class SchemaResponse(
    val vertexTypes: List<TypeInfo>,
    val edgeTypes: List<TypeInfo>
)

@Serializable
data class TypeInfo(
    val name: String,
    val properties: List<String>
)

fun Application.configureRoutes(database: Database, backupCoordinator: GraphBackupCoordinator? = null) {
    val startMs = System.currentTimeMillis()
    routing {
        get("/health") {
            val backup = backupCoordinator?.lastResult()
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    status               = "ok",
                    version              = APP_VERSION,
                    uptimeSeconds        = (System.currentTimeMillis() - startMs) / 1000,
                    database             = if (database.isOpen) "open" else "closed",
                    service              = "engram-engine",
                    anthropicKeySet      = System.getenv("ANTHROPIC_API_KEY").isNullOrBlank().not(),
                    googleKeySet         = System.getenv("GOOGLE_AI_API_KEY").isNullOrBlank().not(),
                    lastBackupAgeSeconds = backup?.let { (System.currentTimeMillis() - it.atMillis) / 1000 },
                    lastBackupDurationMs = backup?.durationMs,
                    lastBackupSizeBytes  = backup?.sizeBytes,
                    lastBackupError      = backup?.error,
                )
            )
        }

        get("/schema") {
            val schema = database.schema
            val vertexTypes = schema.types
                .filterIsInstance<com.arcadedb.schema.VertexType>()
                .map { t ->
                    TypeInfo(
                        name = t.name,
                        properties = t.properties.map { it.name }
                    )
                }
            val edgeTypes = schema.types
                .filterIsInstance<com.arcadedb.schema.EdgeType>()
                .map { t ->
                    TypeInfo(
                        name = t.name,
                        properties = t.properties.map { it.name }
                    )
                }
            call.respond(HttpStatusCode.OK, SchemaResponse(vertexTypes, edgeTypes))
        }
    }
}
