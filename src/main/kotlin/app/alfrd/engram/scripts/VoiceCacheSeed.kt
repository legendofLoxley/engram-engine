package app.alfrd.engram.scripts

import app.alfrd.engram.cognitive.pipeline.VoiceRenderPolicy
import app.alfrd.engram.db.DatabaseManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// ── Manifest types ────────────────────────────────────────────────────────────

private val prettyJson = Json { prettyPrint = true }

@Serializable
data class ManifestEntry(
    val phraseHash: String,
    val audioUrl: String,
    val expressionPhase: String,
    val moveType: String?,
    val text: String,
)

@Serializable
data class VoiceCacheManifest(
    val voiceModelId: String,
    val phrases: List<ManifestEntry>,
)

// ── Internal query result ─────────────────────────────────────────────────────

private data class CandidatePhrase(
    val text: String,
    val expressionPhase: String,
    val moveType: String?,
)

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    val deepgramApiKey = requireEnv("DEEPGRAM_API_KEY")
    val supabaseUrl    = requireEnv("SUPABASE_URL").trimEnd('/')
    val supabaseKey    = requireEnv("SUPABASE_SERVICE_KEY")
    val voiceModelId   = System.getenv("VOICE_MODEL_ID") ?: "aura-2-butler"
    val dbPath         = System.getenv("DB_PATH") ?: "./data/engram-db"
    val bucket         = "voice-cache"

    println("=== Voice Cache Seed ===")
    println("model : $voiceModelId")
    println("db    : $dbPath")
    println()

    val phrases = loadPhrases(dbPath)
    println("Loaded ${phrases.size} renderable phrases from ArcadeDB\n")

    val client = buildHttpClient()
    val entries = mutableListOf<ManifestEntry>()

    // Rate-limit state: ≤ 20 flushes / 60 s, ≤ 2400 chars / 60 s
    var windowStart        = System.currentTimeMillis()
    var flushesInWindow    = 0
    var charsInWindow      = 0

    for ((index, phrase) in phrases.withIndex()) {
        val hash      = VoiceRenderPolicy.phraseHash(phrase.text, voiceModelId)
        val blobPath  = "$hash.pcm"
        val audioUrl  = "/$bucket/$blobPath"
        val label     = "[${index + 1}/${phrases.size}]"

        if (blobExists(client, supabaseUrl, supabaseKey, bucket, blobPath)) {
            println("$label SKIP  (exists) : ${phrase.text}")
            entries += ManifestEntry(hash, audioUrl, phrase.expressionPhase, phrase.moveType, phrase.text)
            continue
        }

        // Rate-limit: reset window when 60 s has elapsed
        val nowMs = System.currentTimeMillis()
        if (nowMs - windowStart >= 60_000) {
            windowStart     = nowMs
            flushesInWindow = 0
            charsInWindow   = 0
        }

        // Pause when approaching either limit
        if (flushesInWindow >= 20 || charsInWindow + phrase.text.length > 2400) {
            val pauseMs = 60_000L - (System.currentTimeMillis() - windowStart) + 200L
            println("$label PAUSE ${pauseMs}ms (rate limit)")
            Thread.sleep(pauseMs.coerceAtLeast(0L))
            windowStart     = System.currentTimeMillis()
            flushesInWindow = 0
            charsInWindow   = 0
        }

        println("$label RENDER : ${phrase.text}")
        val audio = renderTts(client, deepgramApiKey, voiceModelId, phrase.text)
        flushesInWindow++
        charsInWindow += phrase.text.length

        uploadBlob(client, supabaseUrl, supabaseKey, bucket, blobPath, audio)
        entries += ManifestEntry(hash, audioUrl, phrase.expressionPhase, phrase.moveType, phrase.text)
        println("        → ${audio.size} bytes · hash=${hash.take(12)}…")
    }

    // Always rewrite manifest to reflect current pool state
    val manifest     = VoiceCacheManifest(voiceModelId, entries)
    val manifestJson = prettyJson.encodeToString(manifest)
    uploadText(client, supabaseUrl, supabaseKey, bucket, "manifest.json", manifestJson)

    println()
    println("Manifest written: ${entries.size} entries → $supabaseUrl/storage/v1/object/public/$bucket/manifest.json")

    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
}

// ── ArcadeDB phrase loading ───────────────────────────────────────────────────

private fun loadPhrases(dbPath: String): List<CandidatePhrase> {
    DatabaseManager(dbPath).use { mgr ->
        val db = mgr.getDatabase()
        return db.query(
            "sql",
            """
            SELECT text, expressionPhase, moveType
            FROM ResponsePhrase
            WHERE expressionPhase IN ['FIRST_RESPONSE', 'BRIDGE']
              AND visibility = 'internal'
              AND requiresInterpolation = false
            """.trimIndent()
        ).use { rs ->
            val result = mutableListOf<CandidatePhrase>()
            while (rs.hasNext()) {
                val row       = rs.next().toMap()
                val moveType  = row["moveType"] as? String
                // Exclude move types that produce no audio
                if (moveType == "WAIT" || moveType == "YIELD") continue
                result += CandidatePhrase(
                    text            = row["text"] as String,
                    expressionPhase = row["expressionPhase"] as String,
                    moveType        = moveType,
                )
            }
            result
        }
    }
}

// ── Deepgram Aura-2 TTS via WebSocket ─────────────────────────────────────────
//
// Protocol:
//  1. Connect to wss://api.deepgram.com/v1/speak?model=…&encoding=linear16&sample_rate=24000
//  2. Send {"type":"Speak","text":"…"}
//  3. Send {"type":"Flush"}
//  4. Receive binary frames (raw PCM) until {"type":"Flushed"} text frame arrives
//  5. Send {"type":"Close"} and close the socket

private fun renderTts(
    client: OkHttpClient,
    apiKey: String,
    voiceModelId: String,
    text: String,
): ByteArray {
    val audioBuffer = ByteArrayOutputStream()
    val doneLatch   = CountDownLatch(1)
    val errorRef    = AtomicReference<Throwable?>()

    val url = "wss://api.deepgram.com/v1/speak" +
            "?model=$voiceModelId&encoding=linear16&sample_rate=24000"

    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Token $apiKey")
        .build()

    val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            ws.send("""{"type":"Speak","text":${Json.encodeToString(text)}}""")
            ws.send("""{"type":"Flush"}""")
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            audioBuffer.write(bytes.toByteArray())
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val obj  = Json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            if (type == "Flushed") {
                ws.send("""{"type":"Close"}""")
                ws.close(1000, null)
                doneLatch.countDown()
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            doneLatch.countDown()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            errorRef.set(t)
            doneLatch.countDown()
        }
    }

    client.newWebSocket(request, listener)

    if (!doneLatch.await(30, TimeUnit.SECONDS)) {
        throw RuntimeException("TTS render timed out for: \"$text\"")
    }
    errorRef.get()?.let { throw RuntimeException("TTS WebSocket failure for: \"$text\"", it) }

    val audio = audioBuffer.toByteArray()
    if (audio.isEmpty()) throw RuntimeException("TTS returned empty audio for: \"$text\"")
    return audio
}

// ── Supabase Storage helpers ──────────────────────────────────────────────────

/**
 * Returns true when the blob already exists in the bucket (HEAD → 200).
 * Uses the service-role key so private buckets are readable too.
 */
private fun blobExists(
    client: OkHttpClient,
    supabaseUrl: String,
    serviceKey: String,
    bucket: String,
    path: String,
): Boolean {
    val req = Request.Builder()
        .url("$supabaseUrl/storage/v1/object/$bucket/$path")
        .header("Authorization", "Bearer $serviceKey")
        .head()
        .build()
    return client.newCall(req).execute().use { it.isSuccessful }
}

/** Upload a raw PCM blob. Fails if the object already exists (callers must check first). */
private fun uploadBlob(
    client: OkHttpClient,
    supabaseUrl: String,
    serviceKey: String,
    bucket: String,
    path: String,
    bytes: ByteArray,
) {
    val body = bytes.toRequestBody("application/octet-stream".toMediaType())
    val req  = Request.Builder()
        .url("$supabaseUrl/storage/v1/object/$bucket/$path")
        .header("Authorization", "Bearer $serviceKey")
        .post(body)
        .build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            val detail = resp.body?.string() ?: "(no body)"
            throw RuntimeException("Blob upload failed [${resp.code}]: $detail")
        }
    }
}

/** Upload / overwrite a UTF-8 text object (always upserts — used for the manifest). */
private fun uploadText(
    client: OkHttpClient,
    supabaseUrl: String,
    serviceKey: String,
    bucket: String,
    path: String,
    text: String,
) {
    val body = text.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType())
    val req  = Request.Builder()
        .url("$supabaseUrl/storage/v1/object/$bucket/$path?upsert=true")
        .header("Authorization", "Bearer $serviceKey")
        .post(body)
        .build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            val detail = resp.body?.string() ?: "(no body)"
            throw RuntimeException("Manifest upload failed [${resp.code}]: $detail")
        }
    }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

private fun buildHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

private fun requireEnv(name: String): String =
    System.getenv(name) ?: error("Required environment variable '$name' is not set.")
