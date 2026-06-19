package app.alfrd.engram.api

import com.arcadedb.database.Database
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Manages synthetic users and test data for the debug-converse endpoint.
 *
 * All synthetic users are identified by the [SYNTHETIC_EMAIL_DOMAIN] suffix, which is an
 * internal domain that can never be issued by Supabase. This makes test data trivially
 * isolable and purge-safe without touching real user data.
 */
internal object DebugConverseService {

    private val logger = LoggerFactory.getLogger(DebugConverseService::class.java)

    const val SYNTHETIC_EMAIL_DOMAIN = "@test.alfrd.internal"

    /**
     * Maps a caller-supplied label to a stable synthetic user email.
     * If [syntheticUserId] is blank/null a UUID is generated so each omission starts a new user.
     * Input is sanitised so it cannot escape the synthetic domain.
     */
    fun resolveUserId(syntheticUserId: String?): String {
        val base = if (syntheticUserId.isNullOrBlank()) {
            UUID.randomUUID().toString()
        } else {
            syntheticUserId.replace(Regex("[^a-zA-Z0-9_.-]"), "-").take(64)
        }
        return "debug+$base$SYNTHETIC_EMAIL_DOMAIN"
    }

    /**
     * Idempotently creates a User vertex for [email] if one does not already exist.
     * Failures are logged and swallowed — this is a best-effort operation.
     */
    fun ensureSyntheticUser(db: Database, email: String) {
        try {
            db.transaction {
                val exists = db.query(
                    "sql",
                    "SELECT FROM User WHERE email = :email",
                    mapOf("email" to email),
                ).use { rs -> rs.hasNext() }
                if (!exists) {
                    val now = System.currentTimeMillis()
                    db.newVertex("User").apply {
                        set("uid", UUID.randomUUID().toString())
                        set("username", "debug-synthetic")
                        set("email", email)
                        set("tier", SYNTHETIC_TIER)
                        set("createdAt", now)
                        set("updatedAt", now)
                        save()
                    }
                    logger.info("debug: created synthetic user email={}", email)
                }
            }
        } catch (e: Exception) {
            logger.warn("debug: ensureSyntheticUser failed for email={}: {}", email, e.message)
        }
    }

    /**
     * Upserts a [UserScaffoldState] row for [email] with the requested [trustPhase].
     * Silently normalises unknown phase strings to ORIENTATION.
     */
    fun seedScaffoldState(db: Database, email: String, trustPhase: String) {
        val phaseString = when (trustPhase.uppercase().trim()) {
            "WORKING_RHYTHM" -> "WORKING_RHYTHM"
            "CONTEXT"        -> "CONTEXT"
            "UNDERSTANDING"  -> "UNDERSTANDING"
            else             -> "ORIENTATION"
        }
        try {
            db.transaction {
                val exists = db.query(
                    "sql",
                    "SELECT FROM UserScaffoldState WHERE userId = :userId",
                    mapOf("userId" to email),
                ).use { rs -> rs.hasNext() }
                if (exists) {
                    db.command(
                        "sql",
                        "UPDATE UserScaffoldState SET trustPhase = :phase, updatedAt = :now WHERE userId = :userId",
                        mapOf("phase" to phaseString, "now" to System.currentTimeMillis(), "userId" to email),
                    ).close()
                } else {
                    db.newVertex("UserScaffoldState").apply {
                        set("userId", email)
                        set("trustPhase", phaseString)
                        set("answeredCategories", "[]")
                        set("sessionCount", 0)
                        set("phaseTransitions", "[]")
                        set("updatedAt", System.currentTimeMillis())
                        save()
                    }
                }
                logger.info("debug: scaffold state set email={} trustPhase={}", email, phaseString)
            }
        } catch (e: Exception) {
            logger.warn("debug: seedScaffoldState failed for email={}: {}", email, e.message)
        }
    }

    /**
     * Deletes all synthetic users (identified by [SYNTHETIC_EMAIL_DOMAIN]) and every
     * SELECTED, OUTCOME, and UserScaffoldState record linked to them.
     *
     * @return the number of User vertices deleted.
     * @throws RuntimeException if the initial user lookup fails.
     */
    fun purgeAllSyntheticUsers(db: Database): Int {
        val emails = mutableListOf<String>()
        db.query(
            "sql",
            "SELECT email FROM User WHERE email LIKE :pattern",
            mapOf("pattern" to "%$SYNTHETIC_EMAIL_DOMAIN"),
        ).use { rs ->
            while (rs.hasNext()) {
                val email = rs.next().toMap()["email"] as? String ?: continue
                emails.add(email)
            }
        }
        if (emails.isEmpty()) return 0

        var deleted = 0
        for (email in emails) {
            try {
                db.transaction {
                    db.command("sql", "DELETE FROM SELECTED WHERE userId = :e", mapOf("e" to email)).close()
                    db.command("sql", "DELETE FROM OUTCOME  WHERE userId = :e", mapOf("e" to email)).close()
                    db.command("sql", "DELETE FROM UserScaffoldState WHERE userId = :e", mapOf("e" to email)).close()
                    // Delete vertex via graph API so ArcadeDB cascades any remaining TRUSTS/INVITED edges
                    db.query("sql", "SELECT FROM User WHERE email = :e", mapOf("e" to email)).use { rs ->
                        if (rs.hasNext()) rs.next().toElement().asVertex().delete()
                    }
                }
                logger.info("debug: purged synthetic user email={}", email)
                deleted++
            } catch (e: Exception) {
                logger.warn("debug: purge failed for email={}: {}", email, e.message)
            }
        }
        return deleted
    }

    private const val SYNTHETIC_TIER = -1
}
