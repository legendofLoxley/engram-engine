package app.alfrd.engram.db

import com.arcadedb.database.Database
import java.util.logging.Logger

/**
 * One-shot, idempotent cleanup for phantom User vertices created by the uid/email mismatch
 * that existed in ResponseSelectionService.recordSelected / recordOutcome (fixed in the same
 * deploy).
 *
 * Phantoms are identified by two criteria that never apply to legitimate seeded users:
 *   1. uid LIKE '%@%'  — contains '@', i.e. was set to an email string instead of a UUID.
 *   2. email IS NULL   — the `email` property was never set on creation.
 *
 * For each phantom:
 *   1. Delete all outgoing SELECTED edges (keyed on the userId edge-field = phantom uid).
 *   2. Delete all outgoing OUTCOME edges (same key).
 *   3. Delete the phantom User vertex.
 *
 * Safe to re-run after the fact: once all phantoms are gone, the lookup returns an empty
 * list and the function exits immediately without touching the database.
 */
object PhantomUserCleanup {

    private val logger = Logger.getLogger(PhantomUserCleanup::class.java.name)

    data class Report(val phantomsFound: Int, val edgesDeleted: Long)

    fun run(db: Database): Report {
        val phantomUids = findPhantomUids(db)

        if (phantomUids.isEmpty()) {
            logger.info("PhantomUserCleanup: no phantom User vertices found — nothing to do")
            return Report(phantomsFound = 0, edgesDeleted = 0L)
        }

        logger.info("PhantomUserCleanup: found ${phantomUids.size} phantom(s): $phantomUids")
        var totalEdgesDeleted = 0L

        for (uid in phantomUids) {
            try {
                db.transaction {
                    totalEdgesDeleted += deleteEdgesByUserId(db, "SELECTED", uid)
                    totalEdgesDeleted += deleteEdgesByUserId(db, "OUTCOME",  uid)
                    db.command(
                        "sql",
                        "DELETE FROM User WHERE uid = :uid",
                        mapOf("uid" to uid),
                    ).close()
                }
                logger.info("PhantomUserCleanup: removed phantom uid=$uid")
            } catch (e: Exception) {
                logger.warning("PhantomUserCleanup: failed on uid=$uid — ${e.message}")
            }
        }

        logger.info(
            "PhantomUserCleanup: complete — ${phantomUids.size} phantom(s) removed, " +
            "$totalEdgesDeleted orphaned edge(s) deleted"
        )
        return Report(phantomsFound = phantomUids.size, edgesDeleted = totalEdgesDeleted)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findPhantomUids(db: Database): List<String> {
        val uids = mutableListOf<String>()
        try {
            db.query(
                "sql",
                "SELECT uid, email FROM User WHERE uid LIKE '%@%'",
                emptyMap<String, Any>(),
            ).use { rs ->
                while (rs.hasNext()) {
                    val row   = rs.next().toMap()
                    val uid   = row["uid"]   as? String ?: continue
                    val email = row["email"] as? String
                    // Seeded users have a real email field; phantoms do not.
                    if (email.isNullOrBlank()) uids.add(uid)
                }
            }
        } catch (e: Exception) {
            logger.warning("PhantomUserCleanup: phantom lookup failed — ${e.message}")
        }
        return uids
    }

    /**
     * Counts then deletes all records of [edgeType] where the `userId` edge-field equals
     * [userId]. Returns the count deleted so the caller can accumulate a total.
     */
    private fun deleteEdgesByUserId(db: Database, edgeType: String, userId: String): Long {
        val count = try {
            db.query(
                "sql",
                "SELECT count(*) as cnt FROM $edgeType WHERE userId = :userId",
                mapOf("userId" to userId),
            ).use { rs ->
                if (rs.hasNext()) (rs.next().toMap()["cnt"] as? Number)?.toLong() ?: 0L
                else 0L
            }
        } catch (e: Exception) {
            logger.warning("PhantomUserCleanup: count($edgeType, $userId) failed — ${e.message}")
            0L
        }

        if (count > 0L) {
            try {
                db.command(
                    "sql",
                    "DELETE FROM $edgeType WHERE userId = :userId",
                    mapOf("userId" to userId),
                ).close()
            } catch (e: Exception) {
                logger.warning("PhantomUserCleanup: DELETE $edgeType userId=$userId — ${e.message}")
            }
        }
        return count
    }
}
