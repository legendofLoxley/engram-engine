package app.alfrd.engram.api

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

fun Application.configureRoutes(database: Database) {
    val startMs = System.currentTimeMillis()
    routing {
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    status          = "ok",
                    version         = APP_VERSION,
                    uptimeSeconds   = (System.currentTimeMillis() - startMs) / 1000,
                    database        = if (database.isOpen) "open" else "closed",
                    service         = "engram-engine",
                    anthropicKeySet = System.getenv("ANTHROPIC_API_KEY").isNullOrBlank().not(),
                    googleKeySet    = System.getenv("GOOGLE_AI_API_KEY").isNullOrBlank().not(),
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
