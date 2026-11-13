package app.alfrd.engram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * deploy/scripts/scratch-engine.sh must never touch a live path, port or file. It runs here against a fake jar, a fake java and a throwaway zip: the refusals, and that
 * the only thing it removes is the directory it made. Needs bash and python3, which the cloud suite has.
 */
class ScratchEngineScriptTest {
    @TempDir lateinit var tmp: File

    private val script = File("deploy/scripts/scratch-engine.sh").absoluteFile

    private fun have(cmd: String) = runCatching { ProcessBuilder("sh", "-c", "command -v $cmd").start().waitFor() == 0 }.getOrDefault(false)

    private class Fixture(val root: File, val env: Map<String, String>)

    private fun fixture(rootName: String = "mine-scratch", alsoLive: File? = null): Fixture {
        val jar = File(tmp, "fake.jar").also { it.writeText("") }
        val java = File(tmp, "fakejava").also { it.writeText("#!/bin/sh\nsleep 60\n"); it.setExecutable(true) }
        val backups = File(tmp, "backups").also { it.mkdirs() }
        ZipOutputStream(File(backups, "graph-1.zip").outputStream()).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.write("x".toByteArray()); z.closeEntry() }
        val root = File(tmp, rootName)
        return Fixture(root, buildMap {
            put("ENGRAM_JAR", jar.path); put("JAVA_BIN", java.path); put("BACKUP_DIR", backups.path); put("SCRATCH_ROOT", root.path)
            put("SCRATCH_PORT", "18095"); put("SCRATCH_INFO", File(tmp, "info").path)
            alsoLive?.let { put("SCRATCH_ALSO_LIVE", it.path) }
        })
    }

    private fun start(env: Map<String, String>): Process = ProcessBuilder("bash", script.path).redirectErrorStream(true).also { it.environment().putAll(env) }.start()

    private fun refused(env: Map<String, String>): Pair<Int, String> {
        val p = start(env)
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the script should refuse and exit")
        return p.exitValue() to p.inputStream.bufferedReader().readText()
    }

    @Test
    fun `it refuses a live port, a root not named for scratch, a root inside or around a live path, and changes nothing it did not make`() {
        assumeTrue(have("bash") && have("python3"))
        val f = fixture()
        // a live port
        val (rc1, out1) = refused(f.env + ("SCRATCH_PORT" to "8081"))
        assertEquals(2, rc1); assertTrue(out1.contains("live service"), out1)
        // a root that is not named for scratch use, including the filesystem root: never created, never chmodded
        val plain = File(tmp, "plain-dir").also { it.mkdirs() }
        Files.setPosixFilePermissions(plain.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        for (bad in listOf(plain.path, "/")) {
            val (rc, out) = refused(f.env + ("SCRATCH_ROOT" to bad))
            assertEquals(2, rc, bad); assertTrue(out.contains("scratch"), out)
        }
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(plain.toPath())), "a directory it did not make keeps its mode")
        // a scratch-named root inside a live path, and one that contains it
        val live = File(tmp, "live").also { File(it, "data").mkdirs() }
        val (rc2, out2) = refused(f.env + ("SCRATCH_ROOT" to File(live, "data/x-scratch").path) + ("SCRATCH_ALSO_LIVE" to live.path))
        assertEquals(2, rc2); assertTrue(out2.contains("inside the live path"), out2)
        val inner = File(tmp, "outer-scratch/live").also { it.mkdirs() }
        val (rc3, out3) = refused(f.env + ("SCRATCH_ROOT" to inner.parentFile.path) + ("SCRATCH_ALSO_LIVE" to inner.path))
        assertEquals(2, rc3); assertTrue(out3.contains("contains the live path"), out3)
        assertFalse(File(live, "data/x-scratch").exists(), "nothing was created in the live path")
        // no jar, no run
        val (rc4, _) = refused(f.env + ("ENGRAM_JAR" to File(tmp, "missing.jar").path))
        assertEquals(2, rc4)
    }

    @Test
    fun `on SIGTERM it removes its own run directory and leaves what else is in the root`() {
        assumeTrue(have("bash") && have("python3"))
        val f = fixture("kept-scratch")
        f.root.mkdirs()
        Files.setPosixFilePermissions(f.root.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val keep = File(f.root, "KEEP").also { it.writeText("mine") }
        val sibling = File(f.root, "run-other").also { it.mkdirs(); File(it, "x").writeText("not this script's") }
        val p = start(f.env)
        val info = File(f.env.getValue("SCRATCH_INFO"))
        val deadline = System.currentTimeMillis() + 20_000
        while (!(info.exists() && info.readText().contains("owner_token_file")) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val dir = File(info.readLines().first { it.startsWith("dir=") }.removePrefix("dir="))
        assertTrue(dir.isDirectory && dir.parentFile.canonicalPath == f.root.canonicalPath, "its run directory is inside the root: $dir")
        assertTrue(File(dir, "engram-db/a.txt").exists(), "the backup was restored into it")
        p.destroy()
        assertTrue(p.waitFor(40, TimeUnit.SECONDS))
        assertFalse(dir.exists(), "its own directory is gone")
        assertTrue(keep.exists() && File(sibling, "x").exists(), "nothing else in the root was removed")
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.root.toPath())), "an existing root keeps its mode")
    }
}
