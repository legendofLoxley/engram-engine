package consulting.primarykey.engram.api

import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    val service: String
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
    routing {
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    status = "ok",
                    database = if (database.isOpen) "open" else "closed",
                    service = "engram-engine"
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
