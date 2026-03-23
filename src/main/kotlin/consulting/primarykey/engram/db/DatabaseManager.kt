package consulting.primarykey.engram.db

import com.arcadedb.database.Database
import com.arcadedb.database.DatabaseFactory
import java.io.File

class DatabaseManager(dbPath: String = "./data/engram-db") : AutoCloseable {

    private val factory = DatabaseFactory(dbPath)
    private val database: Database = openOrCreate()

    private fun openOrCreate(): Database {
        return if (factory.exists()) {
            factory.open()
        } else {
            factory.create()
        }
    }

    fun getDatabase(): Database = database

    override fun close() {
        if (database.isOpen) {
            database.close()
        }
    }
}
