package app.alfrd.engram.director

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** One model server and whether its health endpoint answered in time. [ms] is how long the answer took; null when it did not come. */
@Serializable
data class ServerStatus(val name: String, val port: Int, val up: Boolean, val ms: Long? = null)

/** Memory in bytes; a field is null when it could not be read on this machine. */
@Serializable
data class MemoryUse(val vramUsedBytes: Long? = null, val vramTotalBytes: Long? = null, val ramUsedBytes: Long? = null, val ramTotalBytes: Long? = null)

/** The Notion cycle channel as the Director can see it. [enabled] false carries the reason it is not running. */
@Serializable
data class NotionState(
    val enabled: Boolean,
    val reason: String? = null,
    val pollSeconds: Long? = null,
    val lastPollAt: String? = null,
    val consecutiveFailures: Int? = null,
    val cyclesHeld: Int? = null,
)

/** The limits one cycle runs under by default: how many tasks a plan may hold, how long its plan call may take, how many tokens it may use. */
@Serializable
data class BudgetDefaults(val taskBudget: Int, val taskBudgetCeiling: Int, val planTimeoutSeconds: Long, val planMaxTokens: Int, val configured: Boolean)

/** Everything the "Your situation" section says, as data. */
@Serializable
data class SelfState(
    val tickEnabled: Boolean,
    val intervalSeconds: Long,
    val tickAt: String,
    val renderReason: String,
    val servers: List<ServerStatus>,
    val memory: MemoryUse,
    val notion: NotionState,
    val hermesInFlight: Map<String, Int>,
    val budgets: BudgetDefaults,
    val marker: ChangeMarker,
    val changes: List<ChangeMarker.Change>,
)

/** What the tick asks the machine about itself. An interface so a test can stand in for the box. */
interface SelfStateProbe {
    fun servers(): List<ServerStatus>
    fun memory(): MemoryUse
}

/**
 * The real probe: a GET on each server's `/health` with a short timeout (loopback only), the largest GPU's VRAM from sysfs, and RAM from
 * `/proc/meminfo`. It reads and asks; it changes nothing. Failures are a `down` or a null, never an exception.
 */
class SystemProbe(
    private val servers: List<Pair<String, Int>> = DEFAULT_SERVERS,
    private val timeout: Duration = Duration.ofSeconds(2),
    private val drmRoot: Path = Path.of("/sys/class/drm"),
    private val meminfo: Path = Path.of("/proc/meminfo"),
    private val host: String = "127.0.0.1",
) : SelfStateProbe {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun servers(): List<ServerStatus> = servers.map { (name, port) ->
        val started = System.nanoTime()
        val up = try {
            val request = HttpRequest.newBuilder(URI("http://$host:$port/health")).timeout(timeout).GET().build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        } catch (_: Exception) {
            false
        }
        ServerStatus(name, port, up, if (up) (System.nanoTime() - started) / 1_000_000 else null)
    }

    override fun memory(): MemoryUse {
        var vramUsed: Long? = null
        var vramTotal: Long? = null
        runCatching {
            Files.newDirectoryStream(drmRoot, "card?").use { cards ->
                for (card in cards) {
                    val total = card.resolve("device/mem_info_vram_total").readLong() ?: continue
                    if (vramTotal == null || total > vramTotal!!) {
                        vramTotal = total
                        vramUsed = card.resolve("device/mem_info_vram_used").readLong()
                    }
                }
            }
        }
        var ramTotal: Long? = null
        var ramAvailable: Long? = null
        runCatching {
            Files.readAllLines(meminfo).forEach { line ->
                val kb = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
                when {
                    line.startsWith("MemTotal:") -> ramTotal = kb?.times(1024)
                    line.startsWith("MemAvailable:") -> ramAvailable = kb?.times(1024)
                }
            }
        }
        val ramUsed = if (ramTotal != null && ramAvailable != null) ramTotal!! - ramAvailable!! else null
        return MemoryUse(vramUsed, vramTotal, ramUsed, ramTotal)
    }

    private fun Path.readLong(): Long? = runCatching { Files.readString(this).trim().toLong() }.getOrNull()

    companion object {
        /** The box's model servers: the shared text reader, the vision reader, the OCR server. */
        val DEFAULT_SERVERS = listOf("text reader" to 8081, "vision reader" to 8083, "OCR" to 8084)
    }
}
