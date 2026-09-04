package app.alfrd.engram.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.File
import java.net.URI
import java.util.logging.Logger

/**
 * [SnapshotRepository] backed by a DigitalOcean Spaces bucket (S3-compatible). Configuration is
 * read from `SPACES_ACCESS_KEY`, `SPACES_SECRET_KEY`, `SPACES_BUCKET`, `SPACES_ENDPOINT`,
 * `SPACES_REGION` — see the README Configuration table.
 *
 * The "latest" pointer is a small JSON object at [LATEST_MANIFEST_KEY], overwritten only after
 * the corresponding snapshot object's upload has completed — see [upload].
 */
class DigitalOceanSpacesRepository(
    private val bucket: String = requireEnv("SPACES_BUCKET"),
    endpoint: String = requireEnv("SPACES_ENDPOINT"),
    region: String = System.getenv("SPACES_REGION") ?: "us-east-1",
    accessKey: String = requireEnv("SPACES_ACCESS_KEY"),
    secretKey: String = requireEnv("SPACES_SECRET_KEY"),
) : SnapshotRepository {

    private val logger = Logger.getLogger(DigitalOceanSpacesRepository::class.java.name)
    private val json = Json { ignoreUnknownKeys = true }

    private val client: S3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .build()

    override suspend fun latestManifest(): SnapshotManifest? = withContext(Dispatchers.IO) {
        try {
            val bytes = client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(LATEST_MANIFEST_KEY).build()
            ).asByteArray()
            json.decodeFromString(SnapshotManifest.serializer(), String(bytes))
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    override suspend fun upload(localFile: File, manifest: SnapshotManifest) = withContext(Dispatchers.IO) {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(manifest.key).build(),
            RequestBody.fromFile(localFile),
        )
        // Only advance the "latest" pointer after the snapshot object itself is confirmed uploaded.
        val manifestJson = json.encodeToString(manifest)
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(LATEST_MANIFEST_KEY).build(),
            RequestBody.fromString(manifestJson),
        )
        logger.info("Uploaded snapshot ${manifest.key} (${manifest.sizeBytes} bytes) and advanced latest pointer")
    }

    override suspend fun download(manifest: SnapshotManifest, destination: File) = withContext(Dispatchers.IO) {
        client.getObject(
            GetObjectRequest.builder().bucket(bucket).key(manifest.key).build(),
            ResponseTransformer.toFile(destination.toPath()),
        )
        Unit
    }

    override suspend fun prune(keep: Int) = withContext(Dispatchers.IO) {
        val latest = latestManifest()
        val allBackups = client.listObjectsV2 { it.bucket(bucket).prefix(SNAPSHOT_PREFIX) }
            .contents()
            .sortedByDescending { it.lastModified() }

        val toDelete = allBackups.drop(keep).filter { it.key() != latest?.key }
        if (toDelete.isEmpty()) return@withContext

        client.deleteObjects(
            DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(toDelete.map { ObjectIdentifier.builder().key(it.key()).build() }).build())
                .build()
        )
        logger.info("Pruned ${toDelete.size} old snapshot(s), retaining $keep")
    }

    companion object {
        private const val SNAPSHOT_PREFIX = "engram-db/backup-"
        private const val LATEST_MANIFEST_KEY = "engram-db/latest.json"

        private fun requireEnv(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: error("$name environment variable is required for DigitalOceanSpacesRepository")
    }
}
