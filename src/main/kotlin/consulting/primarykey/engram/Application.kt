package consulting.primarykey.engram

import consulting.primarykey.engram.api.configureRoutes
import consulting.primarykey.engram.db.DatabaseManager
import consulting.primarykey.engram.db.SchemaBootstrap
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*

fun main() {
    val dbManager = DatabaseManager()
    val db = dbManager.getDatabase()

    SchemaBootstrap.bootstrap(db)

    embeddedServer(Netty, port = 18792) {
        install(ContentNegotiation) {
            json()
        }
        configureRoutes(db)
    }.start(wait = true)

    Runtime.getRuntime().addShutdownHook(Thread {
        dbManager.close()
    })
}
