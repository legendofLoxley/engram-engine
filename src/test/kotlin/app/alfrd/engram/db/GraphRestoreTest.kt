package app.alfrd.engram.db

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class GraphRestoreTest {

    private val testKey = Base64.getEncoder().encodeToString(ByteArray(32))
    private val encryptor = SnapshotEncryptor(testKey)

    @Test
    fun `returns AlreadyPresent and does not touch a non-empty local directory`() = runTest {
        val dbDir = Files.createTempDirectory("engram-restore-present-").toFile()
        try {
            File(dbDir, "marker.txt").writeText("existing db files")
            val repository = FakeSnapshotRepository()

            val outcome = GraphRestore.restoreIfAvailable(dbDir.absolutePath, repository, encryptor)

            assertEquals(RestoreOutcome.AlreadyPresent, outcome)
            assertTrue(File(dbDir, "marker.txt").exists(), "Existing local files must be untouched")
        } finally {
            dbDir.deleteRecursively()
        }
    }

    @Test
    fun `returns FreshStart when the repository has never had a successful upload`() = runTest {
        val dbDir = Files.createTempDirectory("engram-restore-fresh-").toFile()
        try {
            dbDir.delete() // simulate a path that doesn't exist yet, as on first-ever boot
            val repository = FakeSnapshotRepository()

            val outcome = GraphRestore.restoreIfAvailable(dbDir.absolutePath, repository, encryptor)

            assertEquals(RestoreOutcome.FreshStart, outcome)
        } finally {
            dbDir.deleteRecursively()
        }
    }

    @Test
    fun `restores a real backup produced by GraphBackupCoordinator into a fresh directory`() = runTest {
        val sourceDbDir = Files.createTempDirectory("engram-restore-source-").toFile()
        val destDbDir = Files.createTempDirectory("engram-restore-dest-").toFile()
        destDbDir.delete() // GraphRestore must create it
        try {
            val sourceManager = DatabaseManager(sourceDbDir.absolutePath)
            SchemaBootstrap.bootstrap(sourceManager.getDatabase())
            sourceManager.getDatabase().transaction {
                val phrase = sourceManager.getDatabase().newVertex("Phrase")
                phrase.set("uid", "restore-test-phrase")
                phrase.set("text", "Norton is the dog's name")
                phrase.set("hash", "h1")
                phrase.set("visibility", "public")
                phrase.set("createdAt", 1L)
                phrase.set("updatedAt", 1L)
                phrase.save()
            }

            val repository = FakeSnapshotRepository()
            val coordinator = GraphBackupCoordinator(sourceManager.getDatabase(), repository, encryptor)
            val backupResult = coordinator.runBackupOnce()
            assertEquals(null, backupResult.error)
            sourceManager.close()

            val outcome = GraphRestore.restoreIfAvailable(destDbDir.absolutePath, repository, encryptor)
            assertEquals(RestoreOutcome.Restored, outcome)

            val restoredManager = DatabaseManager(destDbDir.absolutePath)
            try {
                val count = restoredManager.getDatabase()
                    .query("sql", "SELECT count(*) as c FROM Phrase WHERE uid = 'restore-test-phrase'")
                    .next().getProperty<Long>("c")
                assertEquals(1L, count, "Restored database should contain the phrase written before backup")
            } finally {
                restoredManager.close()
            }
        } finally {
            sourceDbDir.deleteRecursively()
            destDbDir.deleteRecursively()
        }
    }

    @Test
    fun `fails safe on checksum mismatch rather than silently starting fresh`() = runTest {
        val dbDir = Files.createTempDirectory("engram-restore-corrupt-").toFile()
        dbDir.delete()
        try {
            val repository = FakeSnapshotRepository()
            val manifest = SnapshotManifest(
                key = "engram-db/backup-corrupt.enc",
                timestampMillis = 1L,
                checksumSha256 = "wrong-checksum",
                sizeBytes = 3L,
                engineVersion = "test",
                schemaVersion = ENGRAM_SCHEMA_VERSION,
            )
            repository.seed(manifest, byteArrayOf(1, 2, 3))

            val outcome = GraphRestore.restoreIfAvailable(dbDir.absolutePath, repository, encryptor)

            assertTrue(outcome is RestoreOutcome.Failed, "Checksum mismatch must fail, not silently create a fresh DB")
            assertTrue(!dbDir.exists() || dbDir.listFiles().isNullOrEmpty(), "Must not have created a fresh empty DB in place of a failed restore")
        } finally {
            dbDir.deleteRecursively()
        }
    }

    @Test
    fun `fails safe on schema version mismatch`() = runTest {
        val dbDir = Files.createTempDirectory("engram-restore-schema-").toFile()
        dbDir.delete()
        try {
            val repository = FakeSnapshotRepository()
            val bytes = byteArrayOf(1, 2, 3, 4)
            val manifest = SnapshotManifest(
                key = "engram-db/backup-oldschema.enc",
                timestampMillis = 1L,
                checksumSha256 = sha256Hex(bytes),
                sizeBytes = bytes.size.toLong(),
                engineVersion = "test",
                schemaVersion = ENGRAM_SCHEMA_VERSION + 1,
            )
            repository.seed(manifest, bytes)

            val outcome = GraphRestore.restoreIfAvailable(dbDir.absolutePath, repository, encryptor)

            assertTrue(outcome is RestoreOutcome.Failed, "Schema version mismatch must fail")
        } finally {
            dbDir.deleteRecursively()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
