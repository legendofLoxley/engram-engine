package app.alfrd.engram.cognitive.pipeline.hermes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class HermesWorkspacePathTest {
    private lateinit var root: File
    private lateinit var outside: File

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("hermes-workspace-root").toFile()
        outside = Files.createTempDirectory("hermes-workspace-outside").toFile()
    }

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun `resolves a real file directly inside the root`() {
        val target = File(root, "brief.md").apply { writeText("hello") }

        val resolved = HermesWorkspacePath.resolve(root.absolutePath, "brief.md")

        assertEquals(target.toPath().toRealPath(), resolved)
    }

    @Test
    fun `rejects a filename that does not exist`() {
        assertNull(HermesWorkspacePath.resolve(root.absolutePath, "does-not-exist.md"))
    }

    @Test
    fun `rejects parent-directory traversal that escapes the root`() {
        File(outside, "secret.txt").writeText("outside content")

        val resolved = HermesWorkspacePath.resolve(root.absolutePath, "../${outside.name}/secret.txt")

        assertNull(resolved, "a path that normalizes outside the root must never resolve")
    }

    @Test
    fun `rejects a symlink inside the root that points outside it`() {
        val secretOutside = File(outside, "secret.txt").apply { writeText("outside content") }
        val link = File(root, "link.txt")
        Files.createSymbolicLink(link.toPath(), secretOutside.toPath())

        val resolved = HermesWorkspacePath.resolve(root.absolutePath, "link.txt")

        assertNull(resolved, "a symlink resolving outside the root must be rejected, not silently followed")
    }

    @Test
    fun `accepts a symlink inside the root that points to another file also inside the root`() {
        val real = File(root, "real.md").apply { writeText("hello") }
        val link = File(root, "alias.md")
        Files.createSymbolicLink(link.toPath(), real.toPath())

        val resolved = HermesWorkspacePath.resolve(root.absolutePath, "alias.md")

        assertEquals(real.toPath().toRealPath(), resolved)
    }

    @Test
    fun `rejects a blank filename`() {
        assertNull(HermesWorkspacePath.resolve(root.absolutePath, ""))
        assertNull(HermesWorkspacePath.resolve(root.absolutePath, "   "))
    }

    @Test
    fun `rejects when the workspace root itself does not exist`() {
        assertNull(HermesWorkspacePath.resolve("${root.absolutePath}/does-not-exist-root", "brief.md"))
    }

    @Test
    fun `a subdirectory file inside the root still resolves`() {
        val subdir = File(root, "sub").apply { mkdir() }
        val target = File(subdir, "nested.md").apply { writeText("hello") }

        val resolved = HermesWorkspacePath.resolve(root.absolutePath, "sub/nested.md")

        assertEquals(target.toPath().toRealPath(), resolved)
    }

    @Test
    fun `deeply nested traversal that still lands inside the root is accepted`() {
        // "sub/../brief.md" normalizes to "brief.md" — still inside the root, so this must be
        // accepted: the boundary is "does it end up inside the root", not "does it contain any dots".
        File(root, "sub").mkdir()
        val target = File(root, "brief.md").apply { writeText("hello") }

        val resolved = HermesWorkspacePath.resolve(root.absolutePath, "sub/../brief.md")

        assertEquals(target.toPath().toRealPath(), resolved)
        assertTrue(resolved!!.startsWith(root.toPath().toRealPath()))
    }
}
