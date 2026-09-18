package app.alfrd.engram.cognitive.pipeline.hermes

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Covers the one piece of [HermesAcpClient] that is real, deterministic, and does not need
 * Docker or a live hermes-acp runtime to exercise: the [HermesWorkspacePath] validation
 * `runAssignment` performs before ever spawning a container. A missing/unsafe target filename
 * must fail fast with this specific reason, proving the check actually ran, rather than falling
 * through to whatever `docker run` itself would report (which would depend on whether Docker
 * happens to be installed in whatever environment runs this test — never something a unit test
 * should depend on). The real ACP exchange itself stays covered only by the live hardware runs
 * this codebase has always used for that (see this class's own top-of-file doc and
 * `webui-bridge/README.md`).
 */
class HermesAcpClientTest {
    private lateinit var workspaceDir: java.io.File
    private lateinit var homeDir: java.io.File

    @BeforeEach
    fun setUp() {
        workspaceDir = Files.createTempDirectory("hermes-acp-client-workspace").toFile()
        homeDir = Files.createTempDirectory("hermes-acp-client-home").toFile()
    }

    @AfterEach
    fun tearDown() {
        workspaceDir.deleteRecursively()
        homeDir.deleteRecursively()
    }

    private fun client() = HermesAcpClient(homeDir = homeDir.absolutePath, workspaceDir = workspaceDir.absolutePath)

    private fun assignment() = HermesAssignment(
        assignmentId = "test-assignment", userEmail = "debug+test@test.alfrd.internal",
        task = "test", originalRequest = "test", issuedAtCycleSeq = null,
    )

    @Test
    fun `inspectFixture fails fast for a missing file without ever needing Docker`() = runTest {
        val outcome = client().inspectFixture(assignment(), "does-not-exist.txt", HermesCancelHandle())

        assertTrue(outcome is HermesAssignmentOutcome.Failed, "got: $outcome")
        assertTrue(
            (outcome as HermesAssignmentOutcome.Failed).reason.contains("missing or not accessible"),
            "must name the real reason, not a generic spawn/docker failure — got: ${outcome.reason}",
        )
    }

    @Test
    fun `summarizeDocument fails fast for a missing file without ever needing Docker`() = runTest {
        val outcome = client().summarizeDocument(assignment(), "does-not-exist.md", HermesCancelHandle())

        assertTrue(outcome is HermesAssignmentOutcome.Failed, "got: $outcome")
        assertTrue((outcome as HermesAssignmentOutcome.Failed).reason.contains("missing or not accessible"))
    }

    @Test
    fun `summarizeDocument fails fast for a traversal attempt even if something exists at that real path`() = runTest {
        val outsideDir = Files.createTempDirectory("hermes-acp-client-outside").toFile()
        try {
            java.io.File(outsideDir, "secret.txt").writeText("outside content")

            val outcome = client().summarizeDocument(
                assignment(), "../${outsideDir.name}/secret.txt", HermesCancelHandle(),
            )

            assertTrue(outcome is HermesAssignmentOutcome.Failed, "got: $outcome")
            assertEquals(
                "requested file is missing or not accessible within the approved workspace: ../${outsideDir.name}/secret.txt",
                (outcome as HermesAssignmentOutcome.Failed).reason,
            )
        } finally {
            outsideDir.deleteRecursively()
        }
    }
}
