package app.alfrd.engram.db

import com.arcadedb.database.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

class LocalGraphBackupTest {

    private lateinit var base: File
    private lateinit var manager: DatabaseManager
    private lateinit var dbDir: File
    private lateinit var root: File

    @BeforeEach
    fun setUp() {
        base = Files.createTempDirectory("engram-local-backup-").toFile()
        dbDir = File(base, "data/engram-db")
        root = File(base, "backups")
        manager = DatabaseManager(dbDir.path)
        val db = manager.getDatabase()
        SchemaBootstrap.bootstrap(db)
        db.schema.createVertexType("Probe")
        db.schema.createEdgeType("PROBES")
        db.transaction {
            val a = db.newVertex("Probe").set("n", 1).save()
            val b = db.newVertex("Probe").set("n", 2).save()
            a.newEdge("PROBES", b, false)
            db.newVertex("User").set("uid", "u1").set("email", "debug+owner-jacob@test.alfrd.internal").set("username", "x").save()
        }
    }

    @AfterEach
    fun tearDown() {
        runCatching { manager.close() }
        base.deleteRecursively()
    }

    private fun backup(second: File? = null, clock: () -> Instant = Instant::now) = LocalGraphBackup(manager.getDatabase(), root, second, dbDir, clock)

    private fun mode(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test
    fun `a backup is a verified zip in a private folder, and restoring it gives back the same counts`() = runBlocking<Unit> {
        val result = backup().run()

        val file = File(File(root, "auto"), result.fileName)
        assertTrue(file.exists() && file.length() == result.sizeBytes && result.sizeBytes > 0)
        assertTrue(result.entries > 0)
        assertEquals("rwx------", mode(root))
        assertEquals("rwx------", mode(File(root, "auto")))
        assertEquals("rw-------", mode(file))
        assertEquals(1, result.kept)
        assertEquals(0, result.pruned)
        assertEquals(null, result.copiedToSecond)
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".staging") }, "the staging folder is removed")
        assertTrue(Regex("graph-\\d{8}T\\d{6}Z\\.zip").matches(result.fileName))

        // Restore it into a scratch directory and open it with the engine's own factory.
        val scratch = File(base, "scratch/engram-db")
        ArcadeSnapshot.extractZip(file, scratch)
        val restored = DatabaseFactory(scratch.path).open()
        try {
            val live = manager.getDatabase()
            for (type in listOf("Probe", "PROBES", "User")) assertEquals(live.countType(type, false), restored.countType(type, false), type)
            assertEquals(2, restored.countType("Probe", false))
            assertEquals(1, restored.countType("PROBES", false))
        } finally {
            restored.close()
        }
    }

    @Test
    fun `a second destination gets a copy when set, and a broken one is reported without failing the backup`() = runBlocking<Unit> {
        val second = File(base, "mounted")
        val ok = backup(second).run()
        assertEquals(true, ok.copiedToSecond)
        assertEquals(File(File(root, "auto"), ok.fileName).length(), File(second, ok.fileName).length())
        assertTrue(second.listFiles()!!.none { it.name.endsWith(".part") })

        val blocker = File(base, "blocker").also { it.writeText("a file, not a folder") }
        val broken = backup(File(blocker, "sub")) { Instant.now().plusSeconds(120) }.run()
        assertEquals(false, broken.copiedToSecond)
        assertTrue(File(File(root, "auto"), broken.fileName).exists(), "the primary backup still stands")
    }

    @Test
    fun `the backup folder cannot be inside the data directory or contain it`() {
        assertThrows<IllegalArgumentException> { LocalGraphBackup(manager.getDatabase(), File(dbDir, "backups"), null, dbDir) }
        assertThrows<IllegalArgumentException> { LocalGraphBackup(manager.getDatabase(), dbDir, null, dbDir) }
        assertThrows<IllegalArgumentException> { LocalGraphBackup(manager.getDatabase(), base, null, dbDir) }
        assertThrows<IllegalArgumentException> { LocalGraphBackup(manager.getDatabase(), root, File(dbDir, "copy"), dbDir) }
    }

    @Test
    fun `a failed backup prunes nothing and leaves the existing backups alone`() = runBlocking<Unit> {
        val auto = File(root, "auto").also { it.mkdirs() }
        val names = (1..20).map { "graph-202609%02dT041000Z.zip".format(it) }
        names.forEach { File(auto, it).writeText("old") }
        val b = backup()
        manager.close() // the engine's BACKUP DATABASE now fails
        assertThrows<Exception> { b.run() }
        assertEquals(names.sorted(), auto.list()!!.sorted(), "nothing pruned, nothing added")
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".staging") })
    }

    @Test
    fun `cold copies and other files beside the backups are never pruned`() = runBlocking<Unit> {
        val cold = File(root, "cold-20261014T000000Z").also { it.mkdirs() }
        File(cold, "x").writeText("x")
        val auto = File(root, "auto").also { it.mkdirs() }
        File(auto, "notes.txt").writeText("not a backup")
        (1..30).forEach { File(auto, "graph-202608%02dT041000Z.zip".format(it)).writeText("old") }
        backup().run()
        assertTrue(File(cold, "x").exists())
        assertTrue(File(auto, "notes.txt").exists())
    }

    // ── Retention ────────────────────────────────────────────────────────────

    private fun name(date: String) = "graph-${date.replace("-", "")}T041000Z.zip"

    @Test
    fun `retention keeps 14 daily and 8 weekly, by UTC date`() {
        val days = generateSequence(java.time.LocalDate.of(2026, 8, 1)) { it.plusDays(1) }.takeWhile { !it.isAfter(java.time.LocalDate.of(2026, 10, 13)) }.map { name(it.toString()) }.toList()
        assertEquals(74, days.size)
        val pruned = BackupRetention.toPrune(days)
        val kept = days - pruned.toSet()

        // The 14 newest days (Sep 30 to Oct 13) plus the newest day of each earlier ISO week among the 8 most recent weeks.
        val daily = (30..30).map { name("2026-09-30") } + (1..13).map { name("2026-10-%02d".format(it)) }
        val weeklyExtra = listOf("2026-09-27", "2026-09-20", "2026-09-13", "2026-09-06", "2026-08-30").map(::name)
        assertEquals((daily + weeklyExtra).toSet(), kept.toSet())
        assertEquals(19, kept.size)
        assertEquals(55, pruned.size)
        assertFalse(name("2026-08-01") in kept)
        assertTrue(days.last() in kept)
    }

    @Test
    fun `retention keeps the newest backup of a day and drops the earlier ones of an old day`() {
        val today = listOf("graph-20261013T041000Z.zip", "graph-20261013T201000Z.zip")
        val older = (1..20).flatMap { d -> listOf("graph-202609%02dT011000Z.zip".format(d), "graph-202609%02dT231000Z.zip".format(d)) }
        val pruned = BackupRetention.toPrune(today + older)
        // Both of today's are different backups of the same day: only the newest of that day counts toward the daily 14.
        assertTrue("graph-20261013T201000Z.zip" !in pruned)
        assertTrue("graph-20261013T041000Z.zip" in pruned)
    }

    @Test
    fun `retention never prunes the only backup or the newest one, and ignores names that are not backups`() {
        assertEquals(emptyList<String>(), BackupRetention.toPrune(emptyList()))
        assertEquals(emptyList<String>(), BackupRetention.toPrune(listOf("graph-20261013T041000Z.zip")))
        assertEquals(emptyList<String>(), BackupRetention.toPrune(listOf("notes.txt", "cold-20261014T000000Z", "graph-bad.zip", "graph-20261013T041000Z.zip")))
        val many = (1..40).map { name("2026-09-%02d".format(it.coerceAtMost(30)).takeIf { _ -> it <= 30 } ?: "2026-10-%02d".format(it - 30)) }
        assertTrue(many.last() !in BackupRetention.toPrune(many))
        assertEquals(null, BackupRetention.parse("graph-20261399T041000Z.zip"))
    }
}
