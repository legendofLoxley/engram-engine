package app.alfrd.engram

import app.alfrd.engram.api.configureCognitiveRoutes
import app.alfrd.engram.api.configureAuth
import app.alfrd.engram.api.configureDebugActorEventRoutes
import app.alfrd.engram.api.configureDebugConverseRoutes
import app.alfrd.engram.api.configureDebugEnvironmentSignalRoutes
import app.alfrd.engram.api.configureDebugGraphBackupRoutes
import app.alfrd.engram.api.configureDebugHermesActivityRoutes
import app.alfrd.engram.api.configureDebugHermesAssignmentRoutes
import app.alfrd.engram.api.configureDebugNotionCycleRoutes
import app.alfrd.engram.cognitive.pipeline.agentwork.bootstrapNotionCycles
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.hermes.HermesAssignmentCompletionStore
import app.alfrd.engram.api.configureOnboardingRoutes
import app.alfrd.engram.api.configurePhrasesRoutes
import app.alfrd.engram.api.configureRoutes
import app.alfrd.engram.api.configureScaffoldRoutes
import app.alfrd.engram.api.configureSelectionRoutes
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.CognitivePipelineFactory
import app.alfrd.engram.cognitive.SessionManager
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.DigitalOceanSpacesRepository
import app.alfrd.engram.db.GraphBackupCoordinator
import java.io.File
import app.alfrd.engram.db.LocalGraphBackup
import app.alfrd.engram.db.GraphRestore
import app.alfrd.engram.db.PhantomUserCleanup
import app.alfrd.engram.db.ResponsePhraseSeed
import app.alfrd.engram.db.RestoreOutcome
import app.alfrd.engram.db.SchemaBootstrap
import app.alfrd.engram.db.SnapshotEncryptor
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("app.alfrd.engram.Application")

/**
 * Required for hosted persistence (restore-on-boot + periodic/shutdown snapshots). All must be
 * set together — see the README Configuration table. Absent (e.g. local `./gradlew run`), the
 * app runs exactly as before: ephemeral, no restore, no backup.
 */
private fun graphPersistenceEnvVarsPresent(): Boolean =
    listOf("SPACES_ACCESS_KEY", "SPACES_SECRET_KEY", "SPACES_BUCKET", "SPACES_ENDPOINT", "SNAPSHOT_ENCRYPTION_KEY")
        .all { !System.getenv(it).isNullOrBlank() }

fun main() {
    // Route JUL (used by ArcadeDB and other deps) through Logback.
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    log.info("engram-engine starting")

    val dbPath = System.getenv("DB_PATH") ?: "./data/engram-db"

    // Persistence is optional: absent SPACES_*/SNAPSHOT_ENCRYPTION_KEY (e.g. local `./gradlew run`)
    // means no restore and no backup — the app behaves exactly as before this feature existed.
    val repository = if (graphPersistenceEnvVarsPresent()) DigitalOceanSpacesRepository() else null
    val encryptor = repository?.let { SnapshotEncryptor(System.getenv("SNAPSHOT_ENCRYPTION_KEY")) }
    if (repository == null) {
        log.warn("Hosted graph persistence disabled: SPACES_*/SNAPSHOT_ENCRYPTION_KEY not fully configured")
    }

    if (repository != null && encryptor != null) {
        when (val outcome = runBlocking { GraphRestore.restoreIfAvailable(dbPath, repository, encryptor) }) {
            is RestoreOutcome.Failed -> {
                // Per the hosted-durability decision record: a manifest that exists but fails
                // validation is a hard startup failure, never a silent fallback to an empty
                // database — a crashed deploy is recoverable, a silently reset relationship isn't.
                log.error("GraphRestore failed, aborting startup: ${outcome.reason}")
                exitProcess(1)
            }
            RestoreOutcome.FreshStart -> log.info("GraphRestore: no snapshot found, starting fresh")
            RestoreOutcome.AlreadyPresent -> log.info("GraphRestore: local database already present, skipping restore")
            RestoreOutcome.Restored -> log.info("GraphRestore: restored from snapshot")
        }
    }

    val dbManager = DatabaseManager(dbPath)
    val db = dbManager.getDatabase()
    log.info("database ready")

    val backupCoordinator = if (repository != null && encryptor != null) {
        GraphBackupCoordinator(db, repository, encryptor).also { it.start() }
            .also { log.info("GraphBackupCoordinator started") }
    } else null

    // The box's own daily backup: the engine's BACKUP DATABASE into a local folder, no Spaces, no encryption, no network.
    // Off unless GRAPH_BACKUP_DIR is set; GRAPH_BACKUP_COPY_TO is an optional second folder, unset by default.
    // A bad setting is logged and leaves it off, never stops the engine.
    val localBackup = System.getenv(LocalGraphBackup.ENV_DIR)?.trim()?.takeIf { it.isNotEmpty() }?.let { dir ->
        try {
            LocalGraphBackup(db, File(dir), System.getenv(LocalGraphBackup.ENV_SECOND)?.trim()?.takeIf { it.isNotEmpty() }?.let(::File))
                .also { log.info("local graph backup enabled") }
        } catch (e: IllegalArgumentException) {
            log.error("local graph backup NOT enabled: {}", e.message)
            null
        }
    }

    // Must be registered before the blocking embeddedServer(...).start(wait = true) call below —
    // once that call returns, the JVM shutdown sequence has already begun and
    // Runtime.addShutdownHook throws IllegalStateException, so dbManager.close() would never run.
    Runtime.getRuntime().addShutdownHook(Thread {
        // Best-effort final snapshot before close — per the decision record, this is a bonus that
        // may lose the race with a replacement instance's restore, never the stated guarantee.
        backupCoordinator?.let { runBlocking { it.runBackupOnce() } }
        dbManager.close()
    })

    SchemaBootstrap.bootstrap(db)
    ResponsePhraseSeed.seed(db)
    PhantomUserCleanup.run(db)

    // SessionManager is forward-declared so CognitivePipelineFactory can pass it to
    // FirstSessionHandler (the handler needs SessionManager.isFirstKnownSession).
    lateinit var sessionManager: SessionManager
    sessionManager = SessionManager(factory = { CognitivePipelineFactory.create(db, sessionManager) })

    // The Director's Notion input channel (Queued cycles -> dry-run plan). Off unless NOTION_WORKER_CONFIG
    // is set; a bad configuration is logged and leaves it off, never stops the engine. Cancelling the
    // scope on shutdown leaves a cycle that is mid-plan Running with a lease that runs out, which is
    // exactly what the next process reclaims.
    val notionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val notionStartup = bootstrapNotionCycles(notionScope)
    Runtime.getRuntime().addShutdownHook(Thread { notionScope.cancel() })

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // Defaults to Ktor's own default (0.0.0.0) so production/container behavior is unchanged;
    // set HOST=127.0.0.1 for a loopback-only local run.
    val host = System.getenv("HOST") ?: "0.0.0.0"
    log.info("listening on $host:$port")

    // TODO: add Ktor CallLogging plugin here for HTTP request/response tracing when needed

    embeddedServer(Netty, port = port, host = host) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(CORS) {
            allowHost("alfrd.app", schemes = listOf("https"))
            allowHost("localhost:3000", schemes = listOf("http"))
            allowHost("localhost:5173", schemes = listOf("http"))
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }
        configureAuth()
        configureRoutes(db, backupCoordinator)
        configureCognitiveRoutes(sessionManager)
        configureSelectionRoutes(ResponseSelectionService(db))
        configureScaffoldRoutes(db)
        configurePhrasesRoutes(db)
        configureOnboardingRoutes(db)

        if (System.getenv("DEBUG_CONVERSE_ENABLED") == "true") {
            // Isolated session pool — debug sessions never share state with production sessions.
            // No sessionManager passed to factory → FirstSessionHandler is disabled for synthetic users.
            // One shared HermesAssignmentCompletionStore instance across every session's pipeline
            // AND the polling route below — a per-call store would make a completion invisible
            // to whatever polls for it. HermesActiveAssignmentRegistry needs the same sharing for
            // the mirror-image reason: a cancellation request (by assignmentId only) must reach
            // whichever session's pipeline actually dispatched it.
            val hermesCompletionStore = HermesAssignmentCompletionStore()
            val hermesActiveAssignments = HermesActiveAssignmentRegistry()
            val debugSessionManager = SessionManager(factory = {
                CognitivePipelineFactory.create(db, hermesCompletionStore = hermesCompletionStore, hermesActiveAssignments = hermesActiveAssignments)
            })
            configureDebugConverseRoutes(debugSessionManager, db)
            configureDebugEnvironmentSignalRoutes(db)
            configureDebugActorEventRoutes(db)
            configureDebugHermesAssignmentRoutes(hermesCompletionStore, hermesActiveAssignments)
            configureDebugHermesActivityRoutes(db, hermesActiveAssignments)
            configureDebugNotionCycleRoutes(notionStartup)
            configureDebugGraphBackupRoutes(db, localBackup)
            log.info("debug-converse endpoint enabled")
        }
        routing {
            staticResources("/", "static") {
                default("index.html")
            }
        }
    }.start(wait = true)
}
