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

/**
 * Pins the exact grounding requirements in [HermesAcpClient.SUMMARIZE_DOCUMENT_INSTRUCTION] — the
 * instruction actually sent to Hermes for every [HermesAcpClient.summarizeDocument] assignment.
 * This is *deterministic* coverage of the instruction's own wording, nothing more: it proves what
 * Hermes is told to do, not that Hermes's own model actually does it on any given run. Whether a
 * real summary correctly honors this is *observed model behavior* — see this increment's own
 * demonstration record (a controlled document with a standalone milestone date, a deadline-free
 * action, and a positive-control action with an explicit date/owner/dependency, verified live
 * through the native WebUI) — never something a unit test can establish on its own, and one
 * successful live run there is evidence, not a guarantee, that grounding holds in general.
 */
class HermesAcpClientSummarizeInstructionTest {
    private val instruction = HermesAcpClient.SUMMARIZE_DOCUMENT_INSTRUCTION

    @Test
    fun `requires attaching dates owners and dependencies only to what the source explicitly connects them to`() {
        assertTrue(instruction.contains("date, owner, or dependency"), instruction)
        assertTrue(instruction.contains("explicitly connects"), instruction)
        assertTrue(instruction.contains("never to a different item"), instruction)
    }

    @Test
    fun `allows a useful inferred connection only if clearly labeled as an inference`() {
        assertTrue(instruction.contains("label it as your own inference", ignoreCase = true), instruction)
        assertTrue(instruction.contains("never present an inferred connection as if the source stated it directly", ignoreCase = true), instruction)
    }

    @Test
    fun `allows leaving an unconnected item's date owner or dependency unspecified`() {
        assertTrue(instruction.contains("leave it unspecified"), instruction)
    }

    @Test
    fun `still requires the four labeled parts and forbids inventing an uncovered part`() {
        assertTrue(instruction.contains("Goal") && instruction.contains("Deadlines") && instruction.contains("Risks") && instruction.contains("Next"))
        assertTrue(instruction.contains("guessing or inventing detail"))
    }
}
