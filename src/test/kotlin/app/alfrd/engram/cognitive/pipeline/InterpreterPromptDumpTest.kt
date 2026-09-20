package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Writes the interpretation stage's system prompt, tool schema and three rendered fixture snapshots
 * to the JSON file named by `INTERPRETER_PROMPT_DUMP`, so `deploy/eval/interpreter/eval.py` can
 * measure a live model against the prompt exactly as it ships rather than a hand-copied approximation.
 * Skipped unless that variable is set. See deploy/eval/interpreter/README.md.
 *
 *     INTERPRETER_PROMPT_DUMP=/tmp/shipped.json ./gradlew test --rerun --tests '*InterpreterPromptDumpTest'
 */
class InterpreterPromptDumpTest {
    @Test
    fun dump() {
        val path = System.getenv("INTERPRETER_PROMPT_DUMP")
        assumeTrue(path != null)
        val zone = ZoneId.of("UTC")
        fun ms(d: String, end: Boolean = true) = LocalDate.parse(d).let { if (end) it.plusDays(1).atStartOfDay(zone).minusNanos(1_000_000) else it.atStartOfDay(zone) }.toInstant().toEpochMilli()
        val containers = listOf(
            SnapshotContainer("c1", "u-c1", ContainerKind.DOMAIN, "Work", null, null),
            SnapshotContainer("c2", "u-c2", ContainerKind.PROJECT, "Demo", "c1", null),
            SnapshotContainer("c3", "u-c3", ContainerKind.PROJECT, "Garden redesign", null, null),
        )
        val items = listOf(
            SnapshotItem("i1", "u-i1", ItemKind.TASK, "Send the invoice to Acme", "open", ms("2026-09-22"), "c2", emptyList(), null),
            SnapshotItem("i2", "u-i2", ItemKind.EVENT, "Sister's birthday", "open", ms("2026-09-21", false), null, listOf("sister"), null),
            SnapshotItem("i3", "u-i3", ItemKind.FACT, "My sister is allergic to peanuts", null, null, null, listOf("sister"), null),
            SnapshotItem("i4", "u-i4", ItemKind.TASK, "Buy sister a birthday gift", "open", null, null, listOf("sister"), null),
            SnapshotItem("i5", "u-i5", ItemKind.TASK, "Order soil for the raised beds", "open", null, "c3", emptyList(), null),
        )
        val now = ZonedDateTime.of(2026, 9, 19, 14, 0, 0, 0, zone)
        val interp = Interpreter(null)
        val req = interp.buildRequest("x", ViewSnapshot(emptyList(), emptyList(), emptyList(), zone = zone), now)
        val out = buildJsonObject {
            put("system", req.systemPrompt)
            put("tools", buildJsonArray { req.tools.forEach { t -> add(buildJsonObject { put("type", "function"); putJsonObject("function") { put("name", t.name); put("description", t.description); put("parameters", t.inputSchema) } }) } })
            putJsonObject("snapshots") {
                put("empty", ViewSnapshot(emptyList(), emptyList(), emptyList(), zone = zone).render())
                put("focus", ViewSnapshot(listOf("pinned -> c2 Demo (project)"), containers, items, zone = zone).render())
                put("nofocus", ViewSnapshot(emptyList(), containers, items, zone = zone).render())
            }
        }
        File(path!!).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), out))
    }
}
