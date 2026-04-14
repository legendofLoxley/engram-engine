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
                vt.createProperty("tier", Type.INTEGER)
                vt.createProperty("createdAt", Type.LONG)
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
                vt.createProperty("variants", Type.STRING)          // JSON array, nullable
                vt.createProperty("requiresInterpolation", Type.BOOLEAN)
                vt.createProperty("interpolationKeys", Type.STRING) // JSON array, nullable
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

            // ── Indexes ───────────────────────────────────────────────────
            ensureIndex(schema, "Phrase",      "uid")
            ensureIndex(schema, "Phrase",      "hash")
            ensureIndex(schema, "Concept",     "uid")
            ensureIndex(schema, "Concept",     "normalizedName")
            ensureIndex(schema, "Source",      "uid")
            ensureIndex(schema, "User",        "uid")
            ensureIndex(schema, "ScoreType",   "uid")
            ensureIndex(schema, "Scope",       "uid")
            ensureIndex(schema, "ResponsePhrase", "uid")
            ensureIndex(schema, "ResponsePhrase", "hash")
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
}
