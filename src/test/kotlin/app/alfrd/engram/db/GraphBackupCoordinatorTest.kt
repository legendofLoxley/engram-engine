package app.alfrd.engram.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class GraphBackupCoordinatorTest {

    companion object {
        private lateinit var tempDir: java.nio.file.Path
        private lateinit var dbManager: DatabaseManager
        private val testKey = Base64.getEncoder().encodeToString(ByteArray(32))

        @BeforeAll
        @JvmStatic
        fun setUp() {
            tempDir = Files.createTempDirectory("engram-backup-test-")
            dbManager = DatabaseManager(tempDir.resolve("test-db").toString())
            SchemaBootstrap.bootstrap(dbManager.getDatabase())
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            dbManager.close()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runBackupOnce uploads an encrypted checksummed snapshot with a valid manifest`() = runTest {
        val repository = FakeSnapshotRepository()
        val coordinator = GraphBackupCoordinator(
            db = dbManager.getDatabase(),
            repository = repository,
            encryptor = SnapshotEncryptor(testKey),
        )

        val result = coordinator.runBackupOnce()

        assertNull(result.error, "Backup should succeed: ${result.error}")
        assertTrue(result.sizeBytes > 0, "Snapshot should not be empty")
        assertEquals(1, repository.uploadCount)

        val manifest = repository.latestManifest()
        assertNotNull(manifest)
        assertEquals(ENGRAM_SCHEMA_VERSION, manifest!!.schemaVersion)
        assertTrue(manifest.checksumSha256.isNotBlank())
        assertEquals(result.sizeBytes, manifest.sizeBytes)

        assertEquals(result, coordinator.lastResult())
    }

    @Test
    fun `a failed upload is recorded on the result and never thrown`() = runTest {
        val repository = FakeSnapshotRepository().apply { failNextUpload = true }
        val coordinator = GraphBackupCoordinator(
            db = dbManager.getDatabase(),
            repository = repository,
            encryptor = SnapshotEncryptor(testKey),
        )

        val result = coordinator.runBackupOnce()

        assertNotNull(result.error, "Failure should be recorded, not thrown")
        assertEquals(0, repository.uploadCount)
        assertEquals(result, coordinator.lastResult())
    }

    @Test
    fun `periodic loop backs up on the configured interval and stops when cancelled`() {
        // Deliberately NOT runTest/virtual time: runBackupOnce() does real ArcadeDB I/O and real
        // AES encryption on Dispatchers.IO, which doesn't respond to advanceTimeBy — virtual time
        // only advances scheduling of delay()/launch, it doesn't wait for real background work to
        // finish. Real time with a short interval is the correct tool here, not the virtual-time
        // idiom from CLAUDE.md's coroutine pitfalls (that idiom assumes no real I/O in the loop body).
        val repository = FakeSnapshotRepository()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val coordinator = GraphBackupCoordinator(
            db = dbManager.getDatabase(),
            repository = repository,
            encryptor = SnapshotEncryptor(testKey),
            intervalMs = 200L,
            scope = scope,
        )

        try {
            val job = coordinator.start()
            // Generous real-wall-clock budget: the first BACKUP DATABASE call pays one-time
            // reflection/class-loading cost (com.arcadedb.integration.backup.Backup is loaded via
            // Class.forName), so early iterations can run noticeably slower than steady state.
            runBlocking { delay(2_500L) }
            job.cancel()

            assertTrue(repository.uploadCount >= 3, "Expected at least 3 backups in 2.5s at a 200ms interval, got ${repository.uploadCount}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `retention keeps only the most recent generations and never deletes the latest`() = runTest {
        val repository = FakeSnapshotRepository()
        repeat(5) { i ->
            val manifest = SnapshotManifest(
                key = "engram-db/backup-$i.enc",
                timestampMillis = i.toLong(),
                checksumSha256 = "checksum-$i",
                sizeBytes = 10L,
                engineVersion = "test",
                schemaVersion = ENGRAM_SCHEMA_VERSION,
            )
            repository.seed(manifest, byteArrayOf(i.toByte()))
        }

        repository.prune(keep = 2)

        assertEquals("engram-db/backup-4.enc", repository.latestManifest()?.key, "Latest pointer must survive pruning")
    }
}
