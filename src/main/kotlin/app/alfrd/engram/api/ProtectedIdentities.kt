package app.alfrd.engram.api

import app.alfrd.engram.director.DirectorTick
import app.alfrd.engram.director.ENV_OWNER_IDENTITY

/**
 * The identities `DELETE /debug/converse/purge` must never touch: real data that happens to sit inside the synthetic email domain.
 *
 * The set is the union of
 *  - the configured list, `PURGE_PROTECTED_IDENTITIES` (comma-separated ids or full emails; **unset means [DEFAULT_CONFIGURED]**, i.e. `owner-jacob`), and
 *  - the identities this engine already serves, read from the same environment: `OWNER_IDENTITY` and `DIRECTOR_TICK_IDENTITY`,
 *    so an identity the engine serves cannot be left off the list by mistake.
 *
 * Ids resolve the way the debug routes resolve them ([DebugConverseService.resolveUserId]); emails compare case-insensitively.
 */
class ProtectedIdentities private constructor(val emails: Set<String>) {

    fun isProtected(email: String): Boolean = email.trim().lowercase() in emails

    /** Names only (emails), sorted, for logs and responses. */
    fun names(): List<String> = emails.sorted()

    companion object {
        const val ENV_CONFIGURED = "PURGE_PROTECTED_IDENTITIES"
        const val DEFAULT_CONFIGURED = "owner-jacob"

        fun fromEnvironment(env: Map<String, String> = System.getenv()): ProtectedIdentities {
            val configured = env[ENV_CONFIGURED] ?: DEFAULT_CONFIGURED
            val served = listOf(ENV_OWNER_IDENTITY, DirectorTick.ENV_IDENTITY).mapNotNull { env[it] }
            val all = configured.split(',') + served
            return ProtectedIdentities(all.map { it.trim() }.filter { it.isNotEmpty() }.map(::toEmail).toSet())
        }

        private fun toEmail(raw: String): String =
            if (raw.contains('@')) raw.lowercase() else DebugConverseService.resolveUserId(raw).lowercase()
    }
}
