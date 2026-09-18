package app.alfrd.engram.cognitive.pipeline.hermes

import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves a requested filename against the approved Hermes dev workspace root — in code, not
 * merely by trusting whatever the prompt text says or relying on the read-only Docker bind mount
 * alone (that mount remains real defense in depth; this is an independent, second layer this
 * codebase itself controls before ever spawning a container or building a prompt).
 *
 * Uses real filesystem path resolution ([Path.toRealPath]), not string prefix matching: this
 * transparently rejects both `..` traversal (normalizes away, then the containment check catches
 * anything that walked outside the root) and a symlink placed *inside* the workspace that points
 * elsewhere (`toRealPath` follows the link to its actual target before the containment check
 * runs, so a link like `workspace/escape -> /etc/passwd` resolves to `/etc/passwd` and is
 * correctly rejected, not silently followed).
 */
object HermesWorkspacePath {

    /**
     * Returns the real, canonical path for [requestedFilename] resolved against [workspaceRoot],
     * or null if it does not exist, cannot be resolved, or resolves to anything outside the root
     * — these three cases are deliberately not distinguished by this function's return value
     * alone (a caller that needs to report "missing" honestly still can, from the same null, by
     * knowing there is nothing else to say — see [HermesAcpClient]'s own call site).
     */
    fun resolve(workspaceRoot: String, requestedFilename: String): Path? {
        if (requestedFilename.isBlank()) return null
        return try {
            val root = Paths.get(workspaceRoot).toRealPath()
            val candidate = root.resolve(requestedFilename).normalize().toRealPath()
            if (candidate.startsWith(root)) candidate else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
