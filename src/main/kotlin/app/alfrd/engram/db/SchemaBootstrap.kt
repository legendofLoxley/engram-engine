package app.alfrd.engram.db

import com.arcadedb.database.Database
import com.arcadedb.schema.Schema
import com.arcadedb.schema.Type

object SchemaBootstrap {

    fun bootstrap(db: Database) {
        db.transaction {
            val schema = db.schema

            // ── Vertex types ──────────────────────────────────────────────
            ensureVertex(schema, "Phrase") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("text", Type.STRING)
                vt.createProperty("hash", Type.STRING)
                vt.createProperty("visibility", Type.STRING)   // public/private/archived
                vt.createProperty("createdAt", Type.LONG)
                vt.createProperty("updatedAt", Type.LONG)
                // Read-path fields — written by the ingest pipeline (separate task)
                vt.createProperty("userId", Type.STRING)
                vt.createProperty("source", Type.STRING)
                vt.createProperty("trustPhase", Type.INTEGER)
                vt.createProperty("score", Type.DOUBLE)
            }

            ensureVertex(schema, "Concept") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("name", Type.STRING)
                vt.createProperty("type", Type.STRING)          // person/place/term/entity/keyword
                vt.createProperty("normalizedName", Type.STRING)
            }

            ensureVertex(schema, "Source") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("name", Type.STRING)
                vt.createProperty("type", Type.STRING)          // person/book/document/system/institution
                vt.createProperty("metadata", Type.STRING)      // JSON
            }

            ensureVertex(schema, "User") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("username", Type.STRING)
                vt.createProperty("email", Type.STRING)
                vt.createProperty("tier", Type.INTEGER)
                vt.createProperty("createdAt", Type.LONG)
                vt.createProperty("updatedAt", Type.LONG)
            }

            ensureVertex(schema, "ScoreType") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("name", Type.STRING)
                vt.createProperty("minValue", Type.DOUBLE)
                vt.createProperty("maxValue", Type.DOUBLE)
                vt.createProperty("aggregation", Type.STRING)   // mean/max/sum
                vt.createProperty("description", Type.STRING)
            }

            ensureVertex(schema, "Scope") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("name", Type.STRING)
                vt.createProperty("parentScope", Type.STRING)   // nullable
            }

            ensureVertex(schema, "ResponsePhrase") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("text", Type.STRING)
                vt.createProperty("hash", Type.STRING)
                vt.createProperty("visibility", Type.STRING)        // internal
                vt.createProperty("createdAt", Type.LONG)
                vt.createProperty("updatedAt", Type.LONG)
                vt.createProperty("branchAffinity", Type.STRING)    // JSON array
                vt.createProperty("phaseAffinity", Type.STRING)     // JSON array
                vt.createProperty("expressionPhase", Type.STRING)
                vt.createProperty("category", Type.STRING)
                vt.createProperty("moveType", Type.STRING)          // PostureMoveType name; null for non-posture phrases
                vt.createProperty("postureAffinity", Type.STRING)   // JSON PostureAffinity; null for non-posture phrases
                vt.createProperty("variants", Type.STRING)          // JSON array, nullable
                vt.createProperty("requiresInterpolation", Type.BOOLEAN)
                vt.createProperty("interpolationKeys", Type.STRING) // JSON array, nullable
            }

            ensureVertex(schema, "Utterance") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("sessionId", Type.STRING)
                vt.createProperty("userId", Type.STRING)
                vt.createProperty("turnIndex", Type.INTEGER)
                vt.createProperty("role", Type.STRING)      // user | alfrd
                vt.createProperty("text", Type.STRING)
                vt.createProperty("createdAt", Type.LONG)
            }

            ensureVertex(schema, "UserScaffoldState") { vt ->
                vt.createProperty("userId", Type.STRING)
                vt.createProperty("trustPhase", Type.STRING)         // ORIENTATION | WORKING_RHYTHM | CONTEXT | UNDERSTANDING
                vt.createProperty("answeredCategories", Type.STRING) // JSON array of PhraseCategory names
                vt.createProperty("activeScaffoldQuestion", Type.STRING) // nullable
                vt.createProperty("sessionCount", Type.INTEGER)
                vt.createProperty("lastInteractionAt", Type.LONG)    // nullable
                vt.createProperty("phaseTransitions", Type.STRING)   // JSON array of PhaseTransitionRecord
                vt.createProperty("updatedAt", Type.LONG)
            }

            // ── Edge types ────────────────────────────────────────────────
            ensureEdge(schema, "FOLLOWS") { et ->
                et.createProperty("attributions", Type.STRING)  // JSON array
                et.createProperty("scores", Type.STRING)        // JSON array
            }

            ensureEdge(schema, "CONTAINS") { et ->
                et.createProperty("position", Type.INTEGER)
                et.createProperty("salience", Type.DOUBLE)
            }

            ensureEdge(schema, "ASSERTS") { et ->
                et.createProperty("context", Type.STRING)
                et.createProperty("timestamp", Type.LONG)
                et.createProperty("scores", Type.STRING)        // JSON array
            }

            ensureEdge(schema, "RELATED_TO") { et ->
                et.createProperty("relationType", Type.STRING)  // is_a/part_of/synonym/antonym
                et.createProperty("strength", Type.DOUBLE)
            }

            ensureEdge(schema, "TRUSTS") { et ->
                et.createProperty("scores", Type.STRING)        // JSON array
            }

            ensureEdge(schema, "INVITED") { et ->
                et.createProperty("timestamp", Type.LONG)
                et.createProperty("resultingTier", Type.INTEGER)
                et.createProperty("relationshipContext", Type.STRING)
                et.createProperty("trustPhase", Type.STRING)
                et.createProperty("engagementIntent", Type.STRING)
                et.createProperty("tier", Type.INTEGER)
                et.createProperty("openingContext", Type.STRING)
            }

            ensureEdge(schema, "QUOTES") { et ->
                et.createProperty("attributions", Type.STRING)  // JSON array
                et.createProperty("scores", Type.STRING)        // JSON array
            }

            ensureEdge(schema, "SELECTED") { et ->
                et.createProperty("phraseUid", Type.STRING)
                et.createProperty("sessionId", Type.STRING)
                et.createProperty("userId", Type.STRING)
                et.createProperty("turnIndex", Type.INTEGER)
                et.createProperty("branch", Type.STRING)
                et.createProperty("moveType", Type.STRING)          // null for post-comprehension; non-null for first-response
                et.createProperty("compositeScore", Type.DOUBLE)
                et.createProperty("scoreBreakdown", Type.STRING)  // JSON map
                et.createProperty("timestamp", Type.LONG)
            }

            ensureEdge(schema, "OUTCOME") { et ->
                et.createProperty("phraseUid", Type.STRING)
                et.createProperty("sessionId", Type.STRING)
                et.createProperty("userId", Type.STRING)
                et.createProperty("turnIndex", Type.INTEGER)
                et.createProperty("signal", Type.STRING)
                et.createProperty("contextSnapshot", Type.STRING)
                et.createProperty("timestamp", Type.LONG)
            }

            ensureEdge(schema, "VERIFIED") { et ->
                et.createProperty("timestamp", Type.LONG)
            }

            // User -CONFIDENT_IN-> Concept — per-topic confidence (replaces the old global
            // session/category-counting trust model). One edge per (user, topic); properties
            // hold the evolving TopicConfidence state.
            ensureEdge(schema, "CONFIDENT_IN") { et ->
                et.createProperty("score", Type.DOUBLE)
                et.createProperty("phase", Type.STRING)
                et.createProperty("hasUnresolvedContradiction", Type.BOOLEAN)
                et.createProperty("evidence", Type.STRING)   // JSON array, capped
                et.createProperty("updatedAt", Type.LONG)
            }

            // ── Additive migrations — safe to run on pre-existing types ──
            ensureProperty(schema, "User",    "email",               Type.STRING)
            ensureProperty(schema, "User",    "updatedAt",           Type.LONG)
            ensureProperty(schema, "ASSERTS", "scores",              Type.STRING)
            ensureProperty(schema, "INVITED", "relationshipContext", Type.STRING)
            ensureProperty(schema, "INVITED", "trustPhase",          Type.STRING)
            ensureProperty(schema, "INVITED", "engagementIntent",    Type.STRING)
            ensureProperty(schema, "INVITED", "tier",                Type.INTEGER)
            ensureProperty(schema, "INVITED", "openingContext",      Type.STRING)

            // Context Horizon state — additive only, no new vertex/edge types. See
            // app.alfrd.engram.cognitive.pipeline.horizon for the read/write contract these back.
            // `status`/`statusHistory` deliberately live outside ASSERTS.scores: scores entries are
            // parsed as {"type","value":<Double>} by PhrasesRoutes.parseEdgeScores, and a
            // non-numeric "value" (e.g. a string status) throws inside that parse, which is caught
            // by its outer try/catch and silently zeroes out every score on the edge — degrading
            // queryPhrases for any phrase that ever received a status. Separate properties avoid
            // that entirely.
            ensureProperty(schema, "ASSERTS",   "status",        Type.STRING)  // current value: "open" | "resolved"; absent = not intention-shaped
            ensureProperty(schema, "ASSERTS",   "statusHistory", Type.STRING)  // JSON array of {"state","at"}, append-only, latest "at" wins
            ensureProperty(schema, "ASSERTS",   "cycleSeq",      Type.LONG)    // caller-supplied per-user monotonic cycle number — identity, not a timestamp
            ensureProperty(schema, "RELATED_TO", "createdAt",    Type.LONG)    // audit timestamp only — never used for identity/decay comparisons
            ensureProperty(schema, "RELATED_TO", "cycleSeq",     Type.LONG)    // cycle the edge was asserted in — drives reactivation decay

            // ── Indexes ───────────────────────────────────────────────────
            ensureIndex(schema, "Phrase",      "uid")
            ensureIndex(schema, "Phrase",      "hash")
            ensureIndex(schema, "Phrase",      "userId")
            ensureIndex(schema, "Concept",     "uid")
            ensureIndex(schema, "Concept",     "normalizedName")
            ensureIndex(schema, "Source",      "uid")
            ensureIndex(schema, "Source",      "name")
            ensureIndex(schema, "User",        "uid")
            ensureIndex(schema, "User",        "email")
            ensureIndex(schema, "ScoreType",   "uid")
            ensureIndex(schema, "Scope",       "uid")
            ensureIndex(schema, "ResponsePhrase", "uid")
            ensureIndex(schema, "ResponsePhrase", "hash")
            ensureIndex(schema, "ResponsePhrase", "moveType")
            ensureIndex(schema, "UserScaffoldState", "userId")
            // Context Horizon retrieval — see HorizonAssembler. Bounds the "status=open" and
            // "cycleSeq=currentCycleSeq" filters so they don't degrade into a full scan as ASSERTS
            // edges accumulate on a long-lived Source; whether the query planner actually uses these
            // for the WHERE+ORDER BY shapes HorizonAssembler issues is verified empirically by its
            // scale test, not assumed.
            ensureIndex(schema, "ASSERTS", "status")
            ensureIndex(schema, "ASSERTS", "cycleSeq")
            // Bounds the relevant_to reactivation-window lookup, which is otherwise a global scan
            // of every RELATED_TO edge regardless of relationType.
            ensureCompositeIndex(schema, "RELATED_TO", "relationType", "cycleSeq")
            // SELECTED edge indexes — freshness queries (phraseUid+userId) and session analytics (sessionId)
            ensureIndex(schema, "SELECTED", "sessionId")
            ensureCompositeIndex(schema, "SELECTED", "phraseUid", "userId")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun ensureVertex(
        schema: Schema,
        name: String,
        configure: (com.arcadedb.schema.VertexType) -> Unit
    ) {
        if (!schema.existsType(name)) {
            configure(schema.createVertexType(name))
        }
    }

    /**
     * Adds a property to an existing type if it is not already present.
     * Safe to call on types created before the property was added to the schema definition.
     *
     * `DocumentType.getProperty` throws rather than returning null for a genuinely missing
     * property, so existence must be checked via try/catch — every property this helper was
     * previously called for also happened to be defined inline in its type's `ensureVertex`/
     * `ensureEdge` block, so `getProperty` never actually hit the missing case before.
     */
    private fun ensureProperty(schema: Schema, typeName: String, propName: String, propType: Type) {
        if (schema.existsType(typeName)) {
            val t = schema.getType(typeName)
            val exists = try { t.getProperty(propName) != null } catch (_: Exception) { false }
            if (!exists) {
                t.createProperty(propName, propType)
            }
        }
    }

    private fun ensureEdge(
        schema: Schema,
        name: String,
        configure: (com.arcadedb.schema.EdgeType) -> Unit
    ) {
        if (!schema.existsType(name)) {
            configure(schema.createEdgeType(name))
        }
    }

    private fun ensureIndex(schema: Schema, typeName: String, property: String) {
        if (schema.existsType(typeName)) {
            schema.getType(typeName).getOrCreateTypeIndex(
                Schema.INDEX_TYPE.LSM_TREE,
                false,
                property
            )
        }
    }

    private fun ensureCompositeIndex(schema: Schema, typeName: String, vararg properties: String) {
        if (schema.existsType(typeName)) {
            schema.getType(typeName).getOrCreateTypeIndex(
                Schema.INDEX_TYPE.LSM_TREE,
                false,
                *properties
            )
        }
    }
}
