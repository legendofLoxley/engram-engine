package app.alfrd.engram.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArcadeSnapshotGuardTest {

    private fun zipOf(file: File, entries: Map<String, ByteArray>) = ZipOutputStream(file.outputStream()).use { z ->
        entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
    }

    @Test
    fun `a data file that is empty in the zip but not on disk is flagged, with its live size`() {
        val base = Files.createTempDirectory("engram-guard-").toFile()
        try {
            val live = File(base, "db").also { it.mkdirs() }
            File(live, "User_0.bucket").writeBytes(ByteArray(300))
            File(live, "Probe_0.bucket").writeBytes(ByteArray(0))
            File(live, "User_0.wal").writeBytes(ByteArray(50))
            File(live, "Phrase_0.bucket").writeBytes(ByteArray(10))
            val zip = File(base, "g.zip")
            zipOf(zip, mapOf("User_0.bucket" to ByteArray(0), "Probe_0.bucket" to ByteArray(0), "User_0.wal" to ByteArray(0), "Phrase_0.bucket" to ByteArray(10)))
            assertEquals(listOf(300L), ArcadeSnapshot.emptiedDataFiles(zip, live))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `a backup whose data files are all present is not flagged`() {
        val base = Files.createTempDirectory("engram-guard-").toFile()
        try {
            val live = File(base, "db").also { it.mkdirs() }
            File(live, "User_0.bucket").writeBytes(ByteArray(300))
            val zip = File(base, "g.zip")
            zipOf(zip, mapOf("User_0.bucket" to ByteArray(300)))
            assertEquals(emptyList<Long>(), ArcadeSnapshot.emptiedDataFiles(zip, live))
        } finally {
            base.deleteRecursively()
        }
    }
}
