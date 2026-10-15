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

            // A passage of a Source: where it sits (page, section, message), its text and a hash of that text.
            // Linked from its Source by CONTAINS (position = ordinal) and quoted by items (QUOTES, Item -> Chunk).
            // Never a Phrase subtype and never ASSERTed, so the Context Horizon cannot surface a chunk as an item.
            ensureVertex(schema, "Chunk") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("ownerEmail", Type.STRING)
                vt.createProperty("sourceUid", Type.STRING)
                vt.createProperty("ordinal", Type.INTEGER)
                vt.createProperty("location", Type.STRING)
                vt.createProperty("text", Type.STRING)
                vt.createProperty("hash", Type.STRING)
                vt.createProperty("createdAt", Type.LONG)
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

            // ── Context Horizon structure: containers and typed items ─────
            // Domain > Project > Item. Domain and Project are plain vertex types owned via the
            // denormalized `ownerEmail` (there is no Source/ASSERTS chain for a container the way
            // there is for a claim). Items are Phrase subtypes so every existing Phrase query and
            // index keeps working (ArcadeDB queries are polymorphic) and an item is still reached
            // through Source -ASSERTS-> item, i.e. the same ownership rule as any other claim.
            // See HorizonStructureStore for the write/read contract.
            ensureVertex(schema, "Domain") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("name", Type.STRING)
                vt.createProperty("normalizedName", Type.STRING)
                vt.createProperty("ownerEmail", Type.STRING)
                vt.createProperty("status", Type.STRING)        // active | archived
                vt.createProperty("attention", Type.STRING)     // pinned | suppressed; absent = normal
                vt.createProperty("tentative", Type.BOOLEAN)    // true = a label the model chose, not a name the user said
                vt.createProperty("createdAt", Type.LONG)
                vt.createProperty("updatedAt", Type.LONG)
            }

            ensureVertex(schema, "Project") { vt ->
                vt.createProperty("uid", Type.STRING)
                vt.createProperty("name", Type.STRING)
                vt.createProperty("normalizedName", Type.STRING)
                vt.createProperty("ownerEmail", Type.STRING)
                vt.createProperty("status", Type.STRING)        // active | done | archived
                vt.createProperty("dueAt", Type.LONG)           // nullable, epoch millis
                vt.createProperty("attention", Type.STRING)     // pinned | suppressed; absent = normal
                vt.createProperty("tentative", Type.BOOLEAN)    // true = a label the model chose, not a name the user said
                vt.createProperty("createdAt", Type.LONG)
                vt.createProperty("updatedAt", Type.LONG)
            }

            ensureVertex(schema, "Task") { vt ->
                vt.addSuperType("Phrase")
                vt.createProperty("dueAt", Type.LONG)           // nullable, epoch millis
                vt.createProperty("status", Type.STRING)        // open | resolved
                vt.createProperty("resolvedAt", Type.LONG)      // nullable
            }

            ensureVertex(schema, "Event") { vt ->
                vt.addSuperType("Phrase")
                vt.createProperty("occursAt", Type.LONG)        // nullable, epoch millis
                vt.createProperty("status", Type.STRING)        // open | resolved (past or cancelled)
                vt.createProperty("resolvedAt", Type.LONG)      // nullable
            }

            ensureVertex(schema, "Question") { vt ->
                vt.addSuperType("Phrase")
                vt.createProperty("status", Type.STRING)        // open | resolved
                vt.createProperty("askedBy", Type.STRING)       // alfrd | user
                vt.createProperty("resolvedAt", Type.LONG)      // nullable
            }

            // Durable per-user cycle-sequence identity and a bounded, checkpointed record of
            // in-flight/completed conversational-cycle requests. See
            // app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer and RequestLedger. The
            // request record exists so a retried request (identified by a caller-supplied
            // requestId, never by matching text) can be recognized and resumed/short-circuited
            // instead of re-running interpretation or re-committing writes — see RequestLedger's
            // doc for the checkpoint state machine.
            ensureVertex(schema, "ProcessedRequest") { vt ->
                vt.createProperty("requestId", Type.STRING)
                vt.createProperty("userEmail", Type.STRING)
                vt.createProperty("cycleSeq", Type.LONG)
                vt.createProperty("checkpoint", Type.STRING)        // cycle_allocated | ops_planned | facts_ingested | writes_committed | completed | failed
                vt.createProperty("phraseUids", Type.STRING)        // JSON array, populated as known
                vt.createProperty("intentionPhraseUid", Type.STRING) // nullable
                vt.createProperty("failureReason", Type.STRING)     // nullable
                vt.createProperty("opsPlan", Type.STRING)           // nullable JSON: the validated write plan, stored before it is applied
                vt.createProperty("createdAt", Type.LONG)
                vt.createProperty("updatedAt", Type.LONG)
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

            // Item -PART_OF-> Project, Project -PART_OF-> Domain. Created bidirectional so a
            // container's members can be read by incoming traversal (see RelatedToEdges for why
            // bidirectional=false leaves that silently empty). Not to be confused with
            // RELATED_TO(relationType=part_of), which relates Concepts, not containers.
            ensureEdge(schema, "PART_OF") { et ->
                et.createProperty("createdAt", Type.LONG)
                et.createProperty("cycleSeq", Type.LONG)
                et.createProperty("ownerEmail", Type.STRING)
            }

            // Item -ABOUT-> Concept — ties an item to the shared entity hubs so recall can
            // travel item -> Concept -> other items ("my sister" -> allergy). Bidirectional for
            // the same reason as PART_OF. Concepts are global, not per user, so any traversal
            // through one MUST re-check ownership of what it reaches.
            ensureEdge(schema, "ABOUT") { et ->
                et.createProperty("createdAt", Type.LONG)
                et.createProperty("cycleSeq", Type.LONG)
                et.createProperty("ownerEmail", Type.STRING)
                et.createProperty("strength", Type.DOUBLE)
            }

            // User -FOCUSES_ON-> Domain | Project | item. The user-directed focus: `mode` is
            // "pinned" (an explicit instruction; persists until changed, at most one per user) or
            // "nudge" (a casual mention; fades). `zoom` is overview | binoculars | telescope.
            ensureEdge(schema, "FOCUSES_ON") { et ->
                et.createProperty("weight", Type.DOUBLE)        // a nudge's strength (0..1) before it fades; 1.0 for a pin
                et.createProperty("zoom", Type.STRING)
                et.createProperty("mode", Type.STRING)
                et.createProperty("setCycleSeq", Type.LONG)
                et.createProperty("updatedAt", Type.LONG)
            }

            // ── Additive migrations — safe to run on pre-existing types ──
            ensureProperty(schema, "User",    "email",               Type.STRING)
            ensureProperty(schema, "User",    "updatedAt",           Type.LONG)
            // Caller-owned, durable per-user monotonic cycle identity — see
            // app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer. Never a wall-clock
            // timestamp; allocated atomically under HorizonConsistencyLock's write lock.
            ensureProperty(schema, "User",    "lastCycleSeq",        Type.LONG)
            // Wall-clock time of the user's most recent turn — read to detect a long silence
            // (HorizonStructureStore.lastActiveAt). Deliberately wall-clock, unlike lastCycleSeq.
            ensureProperty(schema, "User",    "lastActiveAt",        Type.LONG)
            // Idempotency key for an item created by a Horizon write operation, namespaced by
            // owner — see HorizonStructureStore.createItem. Null for ordinarily-ingested phrases.
            ensureProperty(schema, "Phrase",  "opKey",               Type.STRING)
            // The user's explicit "keep this in front of me" / "stop bringing this up" — pinned |
            // suppressed, absent = normal. Persisted here; the selector (not built yet) is what acts on it.
            ensureProperty(schema, "Phrase",  "attention",           Type.STRING)
            // The user's own words, kept when `text` is a short label the interpretation model wrote for a
            // task or event ("Order the soil" for "I never got around to ordering the soil"). Absent when
            // `text` already is the user's words.
            ensureProperty(schema, "Phrase",  "sourceQuote",         Type.STRING)
            // Denormalized owner of an item created through HorizonStructureStore, so deadline and
            // attention lookups can be scoped to one user by an index BEFORE any LIMIT — the same reason
            // RELATED_TO carries one. A scoping aid only: ownership is still re-verified through
            // TRUSTS -> Source -> ASSERTS before anything is surfaced.
            ensureProperty(schema, "Phrase",  "ownerEmail",          Type.STRING)
            // Source gains what a read source needs (all optional; every existing Source row simply has none of them):
            // where it lives, a hash of the content as read, when it was read, how much of it was read (full |
            // partial | ocr), the owner, and for an email message the thread it belongs to. One email message
            // is one Source; the thread is a property. See SourceStore.
            ensureProperty(schema, "Source",  "locator",             Type.STRING)
            ensureProperty(schema, "Source",  "contentHash",         Type.STRING)
            ensureProperty(schema, "Source",  "readAt",              Type.LONG)
            ensureProperty(schema, "Source",  "readMode",            Type.STRING)
            ensureProperty(schema, "Source",  "threadId",            Type.STRING)
            ensureProperty(schema, "Source",  "ownerEmail",          Type.STRING)
            // QUOTES is now Item -> Chunk: the item quotes that passage. It carries who recorded the item (the
            // writer: director, user, hermes) and, separately, who asserted the claim (the claimant: a sender, an
            // author), plus the owner for scoping. No score of any kind is computed from it.
            ensureProperty(schema, "QUOTES",  "writer",              Type.STRING)
            ensureProperty(schema, "QUOTES",  "claimant",            Type.STRING)
            ensureProperty(schema, "QUOTES",  "ownerEmail",          Type.STRING)
            ensureProperty(schema, "QUOTES",  "createdAt",           Type.LONG)
            // Same properties as the ensureVertex blocks above, repeated so a database that already ran
            // an earlier revision of those blocks picks them up.
            ensureProperty(schema, "ProcessedRequest", "opsPlan",    Type.STRING)
            ensureProperty(schema, "Domain",  "attention",           Type.STRING)
            ensureProperty(schema, "Project", "attention",           Type.STRING)
            ensureProperty(schema, "FOCUSES_ON", "weight",           Type.DOUBLE)
            ensureProperty(schema, "Domain",  "tentative",           Type.BOOLEAN)
            ensureProperty(schema, "Project", "tentative",           Type.BOOLEAN)
            ensureProperty(schema, "Event",   "status",              Type.STRING)
            ensureProperty(schema, "Event",   "resolvedAt",          Type.LONG)
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
            ensureProperty(schema, "ASSERTS",   "cycleSeq",      Type.LONG)    // ORIGINAL assertion cycle — set once at creation, never overwritten by a later status change
            ensureProperty(schema, "ASSERTS",   "statusCycleSeq", Type.LONG)   // cycle of the MOST RECENT status change — distinct from cycleSeq; see HorizonGraphStore.markAssertionStatus
            ensureProperty(schema, "RELATED_TO", "createdAt",    Type.LONG)    // audit timestamp only — never used for identity/decay comparisons
            ensureProperty(schema, "RELATED_TO", "cycleSeq",     Type.LONG)    // cycle the edge was asserted in — drives reactivation decay
            // Denormalized owner stamp — the userEmail HorizonGraphStore.markRelevant/markSuperseded
            // already validated both endpoints against when the edge was created. Lets
            // HorizonAssembler.queryActiveReactivations scope its candidate query to the requesting
            // user BEFORE applying LIMIT (via the composite index below), rather than after — a
            // global, unscoped LIMIT would let another user's edges crowd this user's own out of the
            // window. This is a scoping optimization only, never a trust boundary on its own: the
            // read path still independently re-verifies both endpoints via HorizonOwnership, since a
            // stamp can be stale, wrong, or bypassed by a writer that doesn't go through the store.
            ensureProperty(schema, "RELATED_TO", "ownerEmail",   Type.STRING)

            // Actor-event identity and retry state — see
            // app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService. Additive only,
            // same ASSERTS edge every other Horizon-specific property already lives on, so identity
            // is committed atomically with the evidence it identifies (same db.transaction), not a
            // separate ledger that could drift out of sync with it.
            ensureProperty(schema, "ASSERTS", "eventId",      Type.STRING)  // idempotency key, scoped per-user (see the composite index below)
            ensureProperty(schema, "ASSERTS", "contentHash",  Type.STRING)  // actorEventFingerprint() of the immutable event payload — detects conflicting reuse of eventId
            ensureProperty(schema, "ASSERTS", "assignmentId", Type.STRING) // nullable — correlates events under one broader task; never used for dedup, distinct from eventId
            ensureProperty(schema, "ASSERTS", "occurredAt",   Type.LONG)   // nullable — when the event happened at the source; existing `timestamp` already serves as receipt time
            ensureProperty(schema, "ASSERTS", "kindMetadata", Type.STRING) // JSON, event-specific fields (e.g. toolName/toolSucceeded) — on the event's own edge, never only in the (reused, not-updated-on-reuse) Source.metadata
            ensureProperty(schema, "ASSERTS", "propagationStatus", Type.STRING) // "completed" | "incomplete", null before first propagate() attempt
            ensureProperty(schema, "ASSERTS", "incompletePropagationTargets", Type.STRING) // JSON array of {toPhraseUid,strength} still owed — precise retry target, never a blind full recompute

            // ── Indexes ───────────────────────────────────────────────────
            ensureIndex(schema, "Phrase",      "uid")
            ensureIndex(schema, "Phrase",      "hash")
            ensureIndex(schema, "Phrase",      "userId")
            ensureIndex(schema, "Phrase",      "opKey")
            // Container lookup by owner + normalized name (createContainer is idempotent on this
            // pair) and by uid; item time-bound lookups for urgency selection.
            ensureIndex(schema, "Domain",      "uid")
            ensureIndex(schema, "Project",     "uid")
            ensureCompositeIndex(schema, "Domain",  "ownerEmail", "normalizedName")
            ensureCompositeIndex(schema, "Project", "ownerEmail", "normalizedName")
            ensureIndex(schema, "Project",     "dueAt")
            ensureIndex(schema, "Task",        "dueAt")
            ensureIndex(schema, "Event",       "occursAt")
            // Deadline selection: one user's OPEN things, by index; the deadline itself is filtered in code.
            // Deliberately NOT (ownerEmail, status, dueAt): in ArcadeDB 25.1.1 a composite index whose last
            // column is null for some rows (an undated task, an undated project) returns those rows several
            // times to a prefix lookup — a project listed three times — and duplicates suggest missing rows
            // too. Both columns here are always set.
            ensureCompositeIndex(schema, "Task",    "ownerEmail", "status")
            ensureCompositeIndex(schema, "Event",   "ownerEmail", "status")
            ensureCompositeIndex(schema, "Project", "ownerEmail", "status")
            // "Everything this user pinned/suppressed": rare rows, found without scanning their items.
            ensureCompositeIndex(schema, "Phrase",  "attention", "ownerEmail")
            ensureIndex(schema, "ABOUT",       "ownerEmail")
            ensureIndex(schema, "Concept",     "uid")
            ensureIndex(schema, "Concept",     "normalizedName")
            ensureIndex(schema, "Source",      "uid")
            ensureIndex(schema, "Source",      "name")
            ensureIndex(schema, "Source",      "contentHash")
            ensureIndex(schema, "Chunk",       "uid")
            ensureIndex(schema, "Chunk",       "sourceUid")
            ensureIndex(schema, "User",        "uid")
            ensureIndex(schema, "User",        "email")
            ensureIndex(schema, "ScoreType",   "uid")
            ensureIndex(schema, "Scope",       "uid")
            ensureIndex(schema, "ResponsePhrase", "uid")
            ensureIndex(schema, "ResponsePhrase", "hash")
            ensureIndex(schema, "ResponsePhrase", "moveType")
            ensureIndex(schema, "UserScaffoldState", "userId")
            // ProcessedRequest — scoped lookup by (userEmail, requestId) before LIMIT, and a
            // cycleSeq index supporting bounded pruning of old rows. See RequestLedger.
            ensureCompositeIndex(schema, "ProcessedRequest", "userEmail", "requestId")
            ensureIndex(schema, "ProcessedRequest", "cycleSeq")
            // Context Horizon retrieval — see HorizonAssembler. Bounds the "status=open" and
            // "cycleSeq=currentCycleSeq" filters so they don't degrade into a full scan as ASSERTS
            // edges accumulate on a long-lived Source. Confirmed by direct execution-plan
            // inspection (ArcadeHorizonAssemblerTest's plan-evidence test), not inferred from
            // timing alone: ArcadeDB's planner does select "FETCH FROM INDEX ASSERTS[status]" for
            // the open-item query.
            ensureIndex(schema, "ASSERTS", "status")
            ensureIndex(schema, "ASSERTS", "cycleSeq")
            ensureIndex(schema, "ASSERTS", "statusCycleSeq")
            // Actor-event idempotency lookup — see ActorEventIngestionService. Scoped to one user's
            // trusted sources via `@out.uid IN :sourceUids` after this index narrows by eventId,
            // same pattern as HorizonOwnership.ownedAssertsEdges; two different users independently
            // choosing the same eventId string never collide.
            ensureIndex(schema, "ASSERTS", "eventId")
            // Bounds the relevant_to reactivation-window lookup, which is otherwise a global scan
            // of every RELATED_TO edge regardless of relationType.
            ensureCompositeIndex(schema, "RELATED_TO", "relationType", "cycleSeq")
            // Scopes the same reactivation-window lookup to one user before LIMIT is applied — see
            // the ownerEmail property doc and ArcadeHorizonAssembler.queryActiveReactivations.
            ensureCompositeIndex(schema, "RELATED_TO", "ownerEmail", "relationType", "cycleSeq")
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
