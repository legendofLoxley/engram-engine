package app.alfrd.engram.db

import com.arcadedb.database.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArcadeSnapshotGuardTest {

    private lateinit var base: File
    private lateinit var manager: DatabaseManager

    @BeforeEach
    fun setUp() {
        base = Files.createTempDirectory("engram-guard-").toFile()
        manager = DatabaseManager(File(base, "db").path)
    }

    @AfterEach
    fun tearDown() {
        runCatching { manager.close() }
        base.deleteRecursively()
    }

    private fun zipOf(file: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return file
    }

    /** A scripted backup attempt: each call hands back the next prepared zip and counts itself. */
    private class Script(val attempts: List<ArcadeSnapshot.Attempt>) {
        var calls = 0
        val fn: (Database, File, String) -> ArcadeSnapshot.Attempt = { _, _, _ -> attempts[calls++] }
    }

    private fun bad(name: String) = ArcadeSnapshot.Attempt(zipOf(File(base, name), mapOf("User_0.bucket" to ByteArray(0))), mapOf("User_0.bucket" to 300L))
    private fun good(name: String) = ArcadeSnapshot.Attempt(zipOf(File(base, name), mapOf("User_0.bucket" to ByteArray(300))), mapOf("User_0.bucket" to 300L))

    private fun run(script: Script) = ArcadeSnapshot.backupVerified(manager.getDatabase(), File(base, "bk"), "graph.zip", script.fn)

    // ── The check itself ─────────────────────────────────────────────────────

    @Test
    fun `a data file that is empty in the zip but was not empty before the backup is flagged, with its earlier size`() {
        val zip = zipOf(File(base, "g.zip"), mapOf("User_0.bucket" to ByteArray(0), "Probe_0.bucket" to ByteArray(0), "User_0.wal" to ByteArray(0), "Phrase_0.bucket" to ByteArray(10)))
        val before = mapOf("User_0.bucket" to 300L, "Probe_0.bucket" to 0L, "User_0.wal" to 50L, "Phrase_0.bucket" to 10L)
        assertEquals(listOf(300L), ArcadeSnapshot.emptiedDataFiles(zip, before))
    }

    @Test
    fun `a backup whose data files are all present is not flagged`() {
        assertEquals(emptyList<Long>(), ArcadeSnapshot.emptiedDataFiles(good("g.zip").zip, mapOf("User_0.bucket" to 300L)))
    }

    @Test
    fun `live sizes are keyed by the path relative to the data directory, as the zip names them`() {
        val dir = File(base, "sizes").also { File(it, "sub").mkdirs() }
        File(dir, "a.bucket").writeBytes(ByteArray(7))
        File(dir, "sub/b.bucket").writeBytes(ByteArray(0))
        assertEquals(mapOf("a.bucket" to 7L, "sub/b.bucket" to 0L), ArcadeSnapshot.liveFileSizes(dir))
    }

    // ── Retry and rejection, driven by a scripted attempt, no timing ─────────

    @Test
    fun `a clean first attempt is returned as it is`() {
        val s = Script(listOf(good("one.zip")))
        assertEquals(File(base, "one.zip"), run(s))
        assertEquals(1, s.calls)
    }

    @Test
    fun `a rejected first attempt is deleted and the retry's zip is returned`() {
        val s = Script(listOf(bad("first.zip"), good("second.zip")))
        val result = run(s)
        assertEquals(2, s.calls)
        assertEquals(File(base, "second.zip"), result)
        assertFalse(File(base, "first.zip").exists(), "the rejected zip is deleted")
        assertTrue(result.exists())
    }

    @Test
    fun `two rejected attempts fail loudly, delete both zips and say counts only`() {
        val s = Script(listOf(bad("first.zip"), bad("second.zip")))
        val e = assertThrows<IllegalStateException> { run(s) }
        assertEquals(2, s.calls, "one retry, no more")
        assertFalse(File(base, "first.zip").exists())
        assertFalse(File(base, "second.zip").exists())
        assertEquals("the backup holds 1 empty data file(s) that were not empty before it started", e.message)
        assertFalse(e.message!!.contains(base.path), "no path in the message")
    }

    // ── Sizes are read before the backup, not after ──────────────────────────

    @Test
    fun `a file empty when the backup started is not rejected when it is written to during the backup`() {
        // The file was 0 bytes before the backup, so the zip's 0 bytes is right. It grows on disk while the backup runs.
        val live = File(base, "db-live").also { it.mkdirs() }
        val growing = File(live, "User_0.bucket").also { it.writeBytes(ByteArray(0)) }
        val before = ArcadeSnapshot.liveFileSizes(live)
        val s = Script(listOf(ArcadeSnapshot.Attempt(zipOf(File(base, "one.zip"), mapOf("User_0.bucket" to ByteArray(0))), before)))
        val attempt: (Database, File, String) -> ArcadeSnapshot.Attempt = { d, dir, n -> s.fn(d, dir, n).also { growing.writeBytes(ByteArray(300)) } }
        val result = ArcadeSnapshot.backupVerified(manager.getDatabase(), File(base, "bk"), "graph.zip", attempt)
        assertEquals(1, s.calls, "no retry")
        assertEquals(File(base, "one.zip"), result)
        assertEquals(300L, growing.length(), "the live file did grow during the backup")
    }

    // ── The real thing, end to end ───────────────────────────────────────────

    @Test
    fun `a real backup of a database with a committed write goes through the guard in one pass`() {
        val db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        db.transaction { db.newVertex("User").set("uid", "u1").set("email", "e@test.internal").set("username", "x").save() }
        val zip = ArcadeSnapshot.backupInto(db, File(base, "bk").also { it.mkdirs() }, "graph.zip")
        assertTrue(zip.exists() && zip.length() > 0)
    }
}
