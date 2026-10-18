package app.alfrd.engram.db

import com.arcadedb.database.DatabaseFactory
import com.arcadedb.database.DatabaseInternal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Proof, not a fix: a transaction whose commit has returned can be missing from `BACKUP DATABASE`.
 *
 * Each round writes one User in a committed transaction on a fresh database, backs up through [ArcadeSnapshot.backupInto]
 * (the call both backup paths use), unpacks the zip and counts what the restored database holds. No second thread, no
 * async API, no timing in the test: the write is committed before the backup starts. A round that restores 0 users
 * is the engine's backup zipping a bucket file the page-flush thread has not written yet.
 * The control run is identical except that it waits for the page flush before the backup.
 */
class BackupIncludesCommittedWritesProofTest {

    private fun restoredUsers(waitForFlush: Boolean): Long {
        val base = Files.createTempDirectory("engram-backup-proof-").toFile()
        try {
            val manager = DatabaseManager(File(base, "db").path)
            val db = manager.getDatabase()
            SchemaBootstrap.bootstrap(db)
            db.transaction { db.newVertex("User").set("uid", "u1").set("email", "e@test.internal").set("username", "x").save() }
            if (waitForFlush) (db as DatabaseInternal).pageManager.waitAllPagesOfDatabaseAreFlushed(db)
            val zip = ArcadeSnapshot.backupInto(db, File(base, "bk").also { it.mkdirs() }, "graph.zip")
            manager.close()
            val scratch = File(base, "restored/db")
            ArcadeSnapshot.extractZip(zip, scratch)
            val restored = DatabaseFactory(scratch.path).open()
            return try { restored.countType("User", false) } finally { restored.close() }
        } finally {
            base.deleteRecursively()
        }
    }

    private fun roundsWithMissingWrite(rounds: Int, waitForFlush: Boolean) = (1..rounds).count { restoredUsers(waitForFlush) != 1L }

    @Test
    fun `a backup taken right after a committed write contains it`() {
        // ~15-30% of rounds lose the write on this box, so 200 rounds without one is not a thing that happens by chance.
        assertEquals(0, roundsWithMissingWrite(200, waitForFlush = false), "rounds (of 200) whose backup lacked an already-committed write")
    }

    @Test
    fun `control - the same rounds with the page flush awaited before the backup lose nothing`() {
        assertEquals(0, roundsWithMissingWrite(200, waitForFlush = true), "rounds (of 200) whose backup lacked a write after the flush was awaited")
    }
}
