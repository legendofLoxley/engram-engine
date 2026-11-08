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
                assertTrue(suppressed.none { it.uid in shown }, "no suppressed item has a ref")
                // A resolved item may fill a place left over (so it can be named to reopen it) but never displaces an open one.
                assertTrue(resolved.count { it.uid in shown } <= 6)
                assertEquals(15, snap.items.count { it.status != "resolved" })
                assertTrue(snap.items.size <= 60, "bounded")
            }
        } finally { manager.close(); tmp.toFile().deleteRecursively() }
    }
}
