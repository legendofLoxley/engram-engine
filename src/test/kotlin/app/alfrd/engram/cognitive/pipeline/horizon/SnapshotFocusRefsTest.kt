package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.ZoneId
import java.util.UUID

/** Which items the snapshot gives refs to: a focused project's open items all get one; suppressed and resolved items never crowd them out. */
class SnapshotFocusRefsTest {
    @Test
    fun `with focus on a project of 15 open and 5 suppressed items every open item has a ref and no suppressed item does`() {
        val tmp = Files.createTempDirectory("engram-snapshot-refs-")
        val manager = DatabaseManager(tmp.resolve("db").toString())
        try {
            SchemaBootstrap.bootstrap(manager.getDatabase())
            val db = manager.getDatabase()
            val email = DebugConverseService.resolveUserId("refs-test")
            db.transaction { db.newVertex("User").apply { set("uid", UUID.randomUUID().toString()); set("username", "refs-test"); set("email", email); set("tier", -1); set("createdAt", 1L); set("updatedAt", 1L); save() } }
            val structure = ArcadeHorizonStructureStore(db)
            runBlocking {
                val project = structure.createContainer(email, 1, ContainerKind.PROJECT, "Kitchen", null, tentative = false)!!
                fun task(text: String) = runBlocking {
                    structure.createItem(email, 1, ItemKind.TASK, text, null, project.uid, askedBy = "user", opKey = null, sourceQuote = null, sourceType = "claude_session")!!
                }
                val open = (1..15).map { task("Open job number $it") }
                val suppressed = (1..5).map { task("Quiet job number $it") }
                val resolved = (1..3).map { task("Finished job number $it") }
                suppressed.forEach { structure.setAttention(email, 2, it.uid, AttentionDirective.SUPPRESSED) }
                resolved.forEach { structure.resolve(email, 2, it.uid, resolved = true) }
                structure.setFocus(email, 3, project.uid, FocusMode.PINNED)

                val snap = ViewSnapshotBuilder(structure).build(email, ZoneId.of("UTC"))
                val shown = snap.items.map { it.uid }.toSet()
                assertTrue(open.all { it.uid in shown }, "every open item of the focused project has a ref")
                val shownOpen = snap.items.filter { !it.hidden }.map { it.uid }.toSet()
                assertTrue(suppressed.none { it.uid in shownOpen }, "no suppressed item takes an item slot")
                // A resolved item may fill a place left over (so it can be named to reopen it) but never displaces an open one.
                assertTrue(resolved.count { it.uid in shown } <= 6)
                assertEquals(15, snap.items.count { it.status != "resolved" && !it.hidden })
                assertTrue(snap.items.size <= 80, "bounded")

                // The way back: in focus, the suppressed items are listed apart, with refs, and set_attention normal restores one.
                val hiddenItems = snap.items.filter { it.hidden }
                assertEquals(5, hiddenItems.size)
                assertTrue(snap.render().contains("Hidden (suppressed"), snap.render())
                val target = hiddenItems.first()
                val parsed = OperationParser.parse(
                    kotlinx.serialization.json.Json.parseToJsonElement("""{"ops":[{"verb":"set_attention","item":"${target.ref}","attention":"normal","quote":"bring it back"}]}""") as kotlinx.serialization.json.JsonObject,
                    "bring it back", snap, ZoneId.of("UTC"),
                )
                assertEquals(1, parsed.operations.size, parsed.rejections.toString())
                structure.setAttention(email, 4, target.uid, null)
                val after = ViewSnapshotBuilder(structure).build(email, ZoneId.of("UTC"))
                assertTrue(after.items.first { it.uid == target.uid }.let { !it.hidden && it.attention == null }, "restored: back among the items")
                assertEquals(4, after.items.count { it.hidden })

                // Out of focus they are invisible and take no slots.
                structure.releaseFocus(email)
                val unfocused = ViewSnapshotBuilder(structure).build(email, ZoneId.of("UTC"))
                assertTrue(unfocused.items.none { it.hidden || it.attention == AttentionDirective.SUPPRESSED })
                assertTrue(!unfocused.render().contains("Hidden"))
            }
        } finally { manager.close(); tmp.toFile().deleteRecursively() }
    }
}
