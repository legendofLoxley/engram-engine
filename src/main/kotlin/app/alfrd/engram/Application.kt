package app.alfrd.engram

import app.alfrd.engram.api.configureCognitiveRoutes
import app.alfrd.engram.api.configureAuth
import app.alfrd.engram.api.configureOnboardingRoutes
import app.alfrd.engram.api.configurePhrasesRoutes
import app.alfrd.engram.api.configureRoutes
import app.alfrd.engram.api.configureScaffoldRoutes
import app.alfrd.engram.api.configureSelectionRoutes
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.CognitivePipelineFactory
import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.PhantomUserCleanup
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.SchemaBootstrap
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

private val log = LoggerFactory.getLogger("app.alfrd.engram.Application")

fun main() {
    // Route JUL (used by ArcadeDB and other deps) through Logback.
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    log.info("engram-engine starting")
    val dbManager = DatabaseManager()
    val db = dbManager.getDatabase()
    log.info("database ready")

    SchemaBootstrap.bootstrap(db)
    ResponsePhraseSeed.seed(db)
    PhantomUserCleanup.run(db)

    // SessionManager is forward-declared so CognitivePipelineFactory can pass it to
    // FirstSessionHandler (the handler needs SessionManager.isFirstKnownSession).
    lateinit var sessionManager: SessionManager
    sessionManager = SessionManager(factory = { CognitivePipelineFactory.create(db, sessionManager) })

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    log.info("listening on port $port")

    // TODO: add Ktor CallLogging plugin here for HTTP request/response tracing when needed

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
        configureAuth()
        configureRoutes(db)
        configureCognitiveRoutes(sessionManager)
        configureSelectionRoutes(ResponseSelectionService(db))
        configureScaffoldRoutes(db)
        configurePhrasesRoutes(db)
        configureOnboardingRoutes(db)
        routing {
            staticResources("/", "static") {
                default("index.html")
            }
        }
    }.start(wait = true)

    Runtime.getRuntime().addShutdownHook(Thread {
        dbManager.close()
    })
}
