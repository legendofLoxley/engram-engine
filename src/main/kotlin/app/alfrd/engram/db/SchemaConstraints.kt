package app.alfrd.engram.db

import com.arcadedb.database.Database
import com.arcadedb.database.RID
import com.arcadedb.schema.Schema
import org.slf4j.LoggerFactory

/**
 * Database-level constraints for the graph's own rules, applied by [SchemaBootstrap] only when `SCHEMA_ENFORCE_CONSTRAINTS=true`.
 * Default off, so a graph that already has violations (and every existing test graph) starts exactly as before.
 *
 * What ArcadeDB 25.1.1 gives us, found by probing it (see SchemaConstraintsTest):
 * - `mandatory`, `notNull`, `readonly`, `regexp`, and unique indexes all exist and are enforced on write, in the writing transaction.
 * - A constraint on `Phrase` applies to its subtypes (`Task`, `Event`, `Question`), and a subtype cannot redeclare or loosen an inherited
 *   property, so "mandatory on the subtypes only" cannot be said for a property `Phrase` declares.
 * - A unique index on `Phrase[uid]` is enforced across the whole hierarchy (a `Task` and an `Event` cannot share a uid).
 * - Adding a constraint does not look at existing rows, but a row that breaks it can never be saved again, not even to change another
 *   field. That is why every rule below is gated on a pre-check: it is applied only when no existing row breaks it, and is otherwise
 *   skipped with a warning naming how many rows break it. Startup never fails because of a constraint.
 * - A unique index cannot sit beside a non-unique index on the same property; the non-unique one is replaced (and restored if the
 *   unique one cannot be built).
 *
 * Constraints are additive and idempotent: running this twice changes nothing the second time, and turning the flag off later does not
 * remove constraints already in place.
 */
object SchemaConstraints {
    const val ENV_FLAG = "SCHEMA_ENFORCE_CONSTRAINTS"
    const val STATE_PATTERN = "^(open|active|waiting|done|dropped)$"
    const val APPROVAL_PATTERN = "^(proposed|approved)$"

    private val log = LoggerFactory.getLogger(SchemaConstraints::class.java)

    fun enabled(env: Map<String, String> = System.getenv()): Boolean =
        env[ENV_FLAG]?.trim()?.lowercase() in setOf("true", "1", "yes", "on")

    data class Outcome(val applied: List<String>, val alreadyInPlace: List<String>, val skipped: Map<String, String>)

    private class Rule(val id: String, val description: String, val inPlace: (Schema) -> Boolean, val violations: (Database) -> Violations, val apply: (Schema) -> Unit)

    private data class Violations(val count: Long, val examples: List<String>) {
        companion object { val NONE = Violations(0, emptyList()) }
    }

    fun apply(db: Database): Outcome {
        val applied = mutableListOf<String>()
        val already = mutableListOf<String>()
        val skipped = linkedMapOf<String, String>()
        val schema = db.schema
        for (rule in rules(schema)) {
            try {
                if (rule.inPlace(schema)) { already += rule.id; continue }
                // scanType needs a transaction to read in; this one changes nothing.
                var v = Violations.NONE
                db.transaction { v = rule.violations(db) }
                if (v.count > 0) {
                    val why = "${v.count} existing row(s) break it (e.g. ${v.examples.joinToString(", ")})"
                    skipped[rule.id] = why
                    log.warn("schema-constraints: SKIPPED {} ({}): {}", rule.id, rule.description, why)
                    continue
                }
                rule.apply(schema)
                applied += rule.id
                log.info("schema-constraints: applied {} ({})", rule.id, rule.description)
            } catch (e: Exception) {
                // A constraint that cannot be applied must never stop the engine from starting.
                skipped[rule.id] = "could not apply: ${e::class.java.simpleName}: ${e.message}"
                log.warn("schema-constraints: SKIPPED {} ({}): {}", rule.id, rule.description, e.message)
            }
        }
        log.info("schema-constraints: {} applied, {} already in place, {} skipped", applied.size, already.size, skipped.size)
        return Outcome(applied, already, skipped)
    }

    /** The constraint set, built from the schema as it is now. Rule ids are stable: `<kind>:<Type>[.<property>]`. */
    private fun rules(schema: Schema): List<Rule> {
        val out = mutableListOf<Rule>()
        val vertexTypes = schema.types.filter { it is com.arcadedb.schema.VertexType }
        // uid: present and unique on every type that declares one. Phrase's covers Task, Event and Question.
        for (t in vertexTypes.filter { "uid" in it.propertyNames }.sortedBy { it.name }) {
            val name = t.name
            out += Rule("uid-mandatory:$name", "$name.uid is mandatory and not null",
                { s -> s.getType(name).getProperty("uid").let { it.isMandatory && it.isNotNull } },
                { db -> missing(db, name, "uid") },
                { s -> s.getType(name).getProperty("uid").setMandatory(true).setNotNull(true) })
            out += Rule("uid-unique:$name", "$name.uid has a unique index",
                { s -> s.getType(name).getAllIndexes(false).any { it.isUnique && it.propertyNames == listOf("uid") } },
                { db -> duplicates(db, name, "uid") },
                { s -> makeUnique(s, name, "uid") })
        }
        // An Email is a Source subtype: its uid is the Source's (mandatory and unique across Source and Email by Source's rules, as Task's is by Phrase's), so it has
        // no uid rules of its own. What is its own is its identity: a Message-ID is stored once per owner, and an address once per owner.
        for ((name, props) in listOf(EmailGraph.EMAIL to listOf("ownerEmail", "type", "locator"), EmailGraph.ADDRESS to listOf("ownerEmail", "address"))) {
            if (!schema.existsType(name)) continue
            out += Rule("identity-unique:$name", "$name (${props.joinToString(", ")}) has a unique index",
                { s -> s.getType(name).getAllIndexes(false).any { it.isUnique && it.propertyNames == props } },
                { db -> duplicatesOn(db, name, props) },
                { s -> makeUniqueComposite(s, name, props) })
        }
        // Containers: an owner and a creation time. (Items inherit ownerEmail from Phrase, which has legitimately ownerless rows, so it cannot be
        // made mandatory for the subtypes alone.)
        for (name in listOf("Domain", "Project")) {
            if (!schema.existsType(name)) continue
            out += mandatory(name, "ownerEmail")
            out += mandatory(name, "createdAt")
        }
        // Every Phrase-family row is created with a creation time; declared on Phrase, so it covers the subtypes too.
        if (schema.existsType("Phrase")) out += mandatory("Phrase", "createdAt")
        // The user's own words are written once, with the item.
        if (schema.existsType("Phrase")) {
            out += Rule("readonly:Phrase.sourceQuote", "Phrase.sourceQuote cannot change after the item is written",
                { s -> s.getType("Phrase").getProperty("sourceQuote").isReadonly },
                { Violations.NONE },
                { s -> s.getType("Phrase").getProperty("sourceQuote").setReadonly(true) })
        }
        // Who wrote an item, and in which cycle, is written once with the item: no later write can move an item between goals or cycles (which would move it
        // in or out of a retraction set).
        if (schema.existsType("Phrase")) {
            for (property in listOf("directorGoalUid", "directorCycle")) {
                out += Rule("readonly:Phrase.$property", "Phrase.$property cannot change after the item is written",
                    { s -> s.getType("Phrase").getProperty(property).isReadonly },
                    { Violations.NONE },
                    { s -> s.getType("Phrase").getProperty(property).setReadonly(true) })
            }
        }
        // State is one of five words when it is set. (It may still be absent on rows written before it existed; that is not a violation of this rule.)
        for (name in app.alfrd.engram.cognitive.pipeline.horizon.ItemKinds.stateful().map { it.typeName }) {
            if (!schema.existsType(name)) continue
            out += Rule("state-vocabulary:$name", "$name.state is one of open, active, waiting, done, dropped",
                { s -> s.getType(name).getProperty("state").regexp == STATE_PATTERN },
                { db -> badState(db, name) },
                { s -> s.getType(name).getProperty("state").setRegexp(STATE_PATTERN) })
        }
        // A goal is proposed or approved, never anything else, and a goal written without an approval is not allowed to exist.
        if (schema.existsType("Goal")) {
            out += Rule("approval-vocabulary:Goal", "Goal.approval is proposed or approved",
                { s -> s.getType("Goal").getProperty("approval").regexp == APPROVAL_PATTERN },
                { db -> badApproval(db) },
                { s -> s.getType("Goal").getProperty("approval").setRegexp(APPROVAL_PATTERN) })
            out += mandatory("Goal", "approval")
        }
        return out
    }

    private fun badApproval(db: Database): Violations {
        var n = 0L
        val examples = mutableListOf<String>()
        val ok = Regex(APPROVAL_PATTERN)
        db.scanType("Goal", false) { doc ->
            val a = doc.getString("approval")
            if (a == null || !ok.matches(a)) { n++; if (examples.size < 3) examples += (doc.getString("uid") ?: doc.identity.toString()) }
            true
        }
        return Violations(n, examples)
    }

    private fun mandatory(type: String, property: String) = Rule(
        "mandatory:$type.$property", "$type.$property is mandatory and not null",
        { s -> s.getType(type).getProperty(property).let { it.isMandatory && it.isNotNull } },
        { db -> missing(db, type, property) },
        { s -> s.getType(type).getProperty(property).setMandatory(true).setNotNull(true) },
    )

    /** Rows of [type] (and its subtypes) where [property] is absent or null. */
    private fun missing(db: Database, type: String, property: String): Violations {
        var n = 0L
        val examples = mutableListOf<String>()
        db.scanType(type, true) { doc ->
            if (doc.get(property) == null) { n++; if (examples.size < 3) examples += (doc.getString("uid") ?: doc.identity.toString()) }
            true
        }
        return Violations(n, examples)
    }

    /** Distinct rows of [type] (and its subtypes) sharing a [property] value, counted by distinct record id so an inherited row is not its own duplicate. */
    private fun duplicates(db: Database, type: String, property: String): Violations {
        val first = HashMap<Any, RID>()
        var n = 0L
        val examples = mutableListOf<String>()
        val seen = HashSet<RID>()
        db.scanType(type, true) { doc ->
            if (!seen.add(doc.identity)) return@scanType true
            val v = doc.get(property) ?: return@scanType true
            val prior = first.putIfAbsent(v, doc.identity)
            if (prior != null) { n++; if (examples.size < 3) examples += v.toString() }
            true
        }
        return Violations(n, examples)
    }

    /** Rows of [type] (not its subtypes) that share the whole combination of [properties]; a row missing one of them is not a duplicate of anything. */
    private fun duplicatesOn(db: Database, type: String, properties: List<String>): Violations {
        val seen = HashSet<String>()
        var n = 0L
        val examples = mutableListOf<String>()
        db.scanType(type, false) { doc ->
            val parts = properties.map { doc.get(it) ?: return@scanType true }
            if (!seen.add(parts.joinToString("\u0000"))) { n++; if (examples.size < 3) examples += (doc.getString("uid") ?: doc.identity.toString()) }
            true
        }
        return Violations(n, examples)
    }

    private fun badState(db: Database, type: String): Violations {
        var n = 0L
        val examples = mutableListOf<String>()
        val ok = Regex(STATE_PATTERN)
        db.scanType(type, false) { doc ->
            val s = doc.getString("state")
            if (s != null && !ok.matches(s)) { n++; if (examples.size < 3) examples += (doc.getString("uid") ?: doc.identity.toString()) }
            true
        }
        return Violations(n, examples)
    }

    /** The same for an index over several properties: a non-unique one on exactly these is replaced, and put back if the unique one cannot be built. */
    internal fun makeUniqueComposite(schema: Schema, typeName: String, properties: List<String>) {
        val type = schema.getType(typeName)
        val old = type.getAllIndexes(false).firstOrNull { it.propertyNames == properties }
        if (old != null && old.isUnique) return
        if (old != null) schema.dropIndex(old.name)
        try {
            type.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, *properties.toTypedArray())
        } catch (e: Exception) {
            if (old != null) type.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, *properties.toTypedArray())
            throw e
        }
    }

    /**
     * Replaces the non-unique index on [property] with a unique one. If the unique one cannot be built, the non-unique one is put back, so
     * lookups by that property never go without an index.
     */
    internal fun makeUnique(schema: Schema, typeName: String, property: String) {
        val type = schema.getType(typeName)
        val old = type.getAllIndexes(false).firstOrNull { it.propertyNames == listOf(property) }
        if (old != null && old.isUnique) return
        if (old != null) schema.dropIndex(old.name)
        try {
            type.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, property)
        } catch (e: Exception) {
            if (old != null) type.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, property)
            throw e
        }
    }
}
