package app.alfrd.engram.db

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Metadata for one durable ArcadeDB snapshot, per the Memory Custody & Portability design's
 * manifest requirements: checksum, size, snapshot time, engine version, and schema version.
 *
 * [key] is the storage-layer identifier for the encrypted snapshot object (e.g. an S3 key) —
 * opaque to callers, only meaningful to the [SnapshotRepository] implementation that produced it.
 */
@Serializable
data class SnapshotManifest(
    val key: String,
    val timestampMillis: Long,
    val checksumSha256: String,
    val sizeBytes: Long,
    val engineVersion: String,
    val schemaVersion: Int,
)

/**
 * Durable storage for encrypted ArcadeDB snapshots, independent of any specific object-storage
 * vendor — per the custody design's requirement that "storage clients must depend on a general
 * snapshot repository interface rather than DigitalOcean Spaces-specific calls."
 *
 * Callers are responsible for encryption ([SnapshotEncryptor]) before [upload] and decryption
 * after [download] — this interface only moves already-encrypted bytes.
 */
interface SnapshotRepository {

    /**
     * Returns the manifest for the most recently *fully uploaded and validated* snapshot,
     * or `null` if the repository has never had a successful upload (the expected first-boot case).
     */
    suspend fun latestManifest(): SnapshotManifest?

    /**
     * Uploads [localFile] as a new snapshot and, only after the upload's checksum is confirmed,
     * advances the repository's "latest" pointer to [manifest]. A failure partway through must
     * never leave the "latest" pointer referencing a manifest whose object isn't actually present.
     */
    suspend fun upload(localFile: File, manifest: SnapshotManifest)

    /** Downloads the snapshot object referenced by [manifest] to [destination]. */
    suspend fun download(manifest: SnapshotManifest, destination: File)

    /** Deletes older snapshot generations, retaining the [keep] most recent. Never deletes the current "latest". */
    suspend fun prune(keep: Int = DEFAULT_RETAIN_GENERATIONS)

    companion object {
        const val DEFAULT_RETAIN_GENERATIONS = 10
    }
}
