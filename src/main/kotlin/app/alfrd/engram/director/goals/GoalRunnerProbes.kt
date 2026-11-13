package app.alfrd.engram.director.goals

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What the box has left. Null for a figure it cannot read: an unknown figure never blocks a cycle, a known low one always does. */
interface ResourceProbe {
    fun freeRamMb(): Long?
    fun freeGpuMb(): Long?
}

/** Reads `MemAvailable` and, on an AMD GPU with unified memory, the VRAM total and used from sysfs. */
class SystemResourceProbe(
    private val meminfo: File = File("/proc/meminfo"),
    private val drm: File = File("/sys/class/drm"),
) : ResourceProbe {
    override fun freeRamMb(): Long? = runCatching {
        meminfo.useLines { lines -> lines.firstOrNull { it.startsWith("MemAvailable:") } }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024)
    }.getOrNull()

    override fun freeGpuMb(): Long? = runCatching {
        drm.listFiles { f -> f.name.matches(Regex("card\\d+")) }.orEmpty().sortedBy { it.name }.firstNotNullOfOrNull { card ->
            val total = File(card, "device/mem_info_vram_total").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            val used = File(card, "device/mem_info_vram_used").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            if (total != null && used != null) (total - used) / (1024 * 1024) else null
        }
    }.getOrNull()
}

/** How many of a llama.cpp server's slots are free. */
data class Slots(val total: Int, val free: Int)

interface SlotProbe {
    /** Null when the server cannot be asked (down, no `/slots`): a runner treats that as "not available", never as "free". */
    suspend fun slots(baseUrl: String): Slots?
}

/** Asks `GET <server>/slots`: a slot with `is_processing` true is busy. [baseUrl] is the OpenAI-style `.../v1` address; the server root is that without `/v1`. */
class LlamaSlotProbe(private val http: HttpClient = HttpClient.newHttpClient(), private val timeout: Duration = Duration.ofSeconds(3)) : SlotProbe {
    override suspend fun slots(baseUrl: String): Slots? = withContext(Dispatchers.IO) {
        runCatching {
            val root = baseUrl.trimEnd('/').removeSuffix("/v1")
            val response = http.send(HttpRequest.newBuilder(URI.create("$root/slots")).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@runCatching null
            val array = Json.parseToJsonElement(response.body()) as? JsonArray ?: return@runCatching null
            val busy = array.count { ((it as? JsonObject)?.get("is_processing") as? JsonPrimitive)?.booleanOrNull == true }
            Slots(array.size, array.size - busy)
        }.getOrNull()
    }
}
