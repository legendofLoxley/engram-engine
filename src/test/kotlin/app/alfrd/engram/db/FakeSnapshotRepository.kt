package app.alfrd.engram.db

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [SnapshotRepository] test double. No network calls — mirrors the real
 * "latest pointer only advances after upload succeeds" contract so tests can exercise it.
 */
class FakeSnapshotRepository : SnapshotRepository {

    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val manifests = ConcurrentHashMap<String, SnapshotManifest>()

    @Volatile
    private var latest: SnapshotManifest? = null

    var uploadCount = 0
        private set

    /** Test hook: makes the next [upload] call throw, to exercise failure handling. */
    var failNextUpload = false

    override suspend fun latestManifest(): SnapshotManifest? = latest

    override suspend fun upload(localFile: File, manifest: SnapshotManifest) {
        if (failNextUpload) {
            failNextUpload = false
            error("Simulated upload failure")
        }
        objects[manifest.key] = localFile.readBytes()
        manifests[manifest.key] = manifest
        latest = manifest
        uploadCount++
    }

    override suspend fun download(manifest: SnapshotManifest, destination: File) {
        val bytes = objects[manifest.key] ?: error("No object for key ${manifest.key}")
        destination.writeBytes(bytes)
    }

    override suspend fun prune(keep: Int) {
        val sorted = manifests.values.sortedByDescending { it.timestampMillis }
        val toDrop = sorted.drop(keep).filter { it.key != latest?.key }
        toDrop.forEach {
            objects.remove(it.key)
            manifests.remove(it.key)
        }
    }

    /** Test hook: directly seed a manifest + bytes, bypassing [upload] (e.g. to simulate a pre-existing corrupt snapshot). */
    fun seed(manifest: SnapshotManifest, bytes: ByteArray) {
        objects[manifest.key] = bytes
        manifests[manifest.key] = manifest
        latest = manifest
    }
}
