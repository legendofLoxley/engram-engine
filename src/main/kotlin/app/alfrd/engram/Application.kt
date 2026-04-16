package app.alfrd.engram

import app.alfrd.engram.api.configureCognitiveRoutes
import app.alfrd.engram.api.configurePhrasesRoutes
import app.alfrd.engram.api.configureRoutes
import app.alfrd.engram.api.configureScaffoldRoutes
import app.alfrd.engram.api.configureSelectionRoutes
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.CognitivePipelineFactory
import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import kotlinx.serialization.json.Json

fun main() {
    val dbManager = DatabaseManager()
    val db = dbManager.getDatabase()

    SchemaBootstrap.bootstrap(db)
    ResponsePhraseSeed.seed(db)

    val sessionManager = SessionManager(factory = { CognitivePipelineFactory.create(db) })

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(CORS) {
            allowHost("alfrd.app", schemes = listOf("https"))
            allowHost("localhost:3000", schemes = listOf("http"))
            allowHost("localhost:5173", schemes = listOf("http"))
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Options)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }
        configureRoutes(db)
        configureCognitiveRoutes(sessionManager)
        configureSelectionRoutes(ResponseSelectionService(db))
        configureScaffoldRoutes(db)
        configurePhrasesRoutes(db)
    }.start(wait = true)

    Runtime.getRuntime().addShutdownHook(Thread {
        dbManager.close()
    })
}
