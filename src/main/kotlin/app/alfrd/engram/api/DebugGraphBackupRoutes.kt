package app.alfrd.engram.api

import app.alfrd.engram.db.LocalBackupResult
import app.alfrd.engram.db.LocalGraphBackup
import com.arcadedb.database.Database
import com.arcadedb.schema.EdgeType
import com.arcadedb.schema.VertexType
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.net.InetAddress

@Serializable
data class GraphCountsResponse(
    val vertices: Map<String, Long>,
    val edges: Map<String, Long>,
    /** Whether the owner-jacob dev identity's User vertex is present. A boolean, never the user's data. */
    val ownerJacobPresent: Boolean,
)

/** The synthetic email the Hermes WebUI sends as for Jacob's own dev identity (see CLAUDE.md). */
private const val OWNER_JACOB_EMAIL = "debug+owner-jacob@test.alfrd.internal"

/**
 * `POST /debug/graph-backup` runs one local backup ([LocalGraphBackup]) and returns metadata only: the file name, its
 * size and entry count, the duration, how many backups are kept and pruned. It takes no input at all, so nothing in a
 * request can name a path. `GET /debug/graph-counts` returns the number of vertices and edges per type, and whether
 * owner-jacob is present, for the restore proof to compare a restored graph with the live one.
 *
 * Same `debug-token` auth and `DEBUG_CONVERSE_ENABLED` gate as every other debug route, and a second check of its own:
 * the caller must be on the loopback interface, whatever HOST the engine binds. The backup route exists only when a
 * backup folder is configured (`GRAPH_BACKUP_DIR`); the counts route is always there with the other debug routes.
 */
fun Application.configureDebugGraphBackupRoutes(db: Database, backup: LocalGraphBackup?) {
    routing {
        authenticate("debug-token") {
            route("/debug") {
                get("/graph-counts") {
                    if (!call.fromLoopback()) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    call.respond(HttpStatusCode.OK, graphCounts(db))
                }
                if (backup != null) {
                    post("/graph-backup") {
                        if (!call.fromLoopback()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                        val result: LocalBackupResult = try {
                            backup.run()
                        } catch (e: Exception) {
                            // The class name only: an exception message could carry a path.
                            return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "backup failed: ${e::class.java.simpleName}"))
                        }
                        call.respond(HttpStatusCode.OK, result)
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.fromLoopback(): Boolean =
    runCatching { InetAddress.getByName(request.origin.remoteAddress).isLoopbackAddress }.getOrDefault(false)

internal fun graphCounts(db: Database): GraphCountsResponse {
    val vertices = LinkedHashMap<String, Long>()
    val edges = LinkedHashMap<String, Long>()
    for (type in db.schema.types.sortedBy { it.name }) {
        when (type) {
            is VertexType -> vertices[type.name] = db.countType(type.name, false)
            is EdgeType -> edges[type.name] = db.countType(type.name, false)
            else -> Unit
        }
    }
    val owner = db.query("sql", "SELECT count(*) AS n FROM User WHERE email = :email", mapOf("email" to OWNER_JACOB_EMAIL)).use { rs ->
        rs.hasNext() && (rs.next().getProperty<Number>("n")?.toLong() ?: 0L) > 0
    }
    return GraphCountsResponse(vertices, edges, owner)
}
