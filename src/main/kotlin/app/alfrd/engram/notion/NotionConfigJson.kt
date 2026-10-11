package app.alfrd.engram.notion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** JSON helpers shared by the configuration parsers in this package. Errors name the field, never its value. */

internal fun parseJsonObject(json: String, what: String): JsonObject =
    try {
        Json.parseToJsonElement(json).jsonObject
    } catch (_: Exception) {
        throw NotionConfigException("the $what is not a JSON object")
    }

internal fun rejectUnknownKeys(obj: JsonObject, allowed: Set<String>, where: String) {
    val unknown = obj.keys - allowed
    if (unknown.isNotEmpty()) throw NotionConfigException("unknown key(s) in $where: ${unknown.sorted().joinToString(", ") { it.take(40) }}")
}

internal fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.requiredString(key: String, where: String? = null): String =
    optionalString(key) ?: throw NotionConfigException("${where?.let { "$it." }.orEmpty()}$key is required and must be a non-empty string")

/** Absent is null; present but not a JSON true/false is an error. */
internal fun JsonObject.optionalBoolean(key: String, where: String): Boolean? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.isString) throw NotionConfigException("$where.$key must be true or false")
    return primitive.booleanOrNull ?: throw NotionConfigException("$where.$key must be true or false")
}

/** A token *reference*: `env:NAME` or `file:/path`. A raw token pasted here is refused without being echoed. */
internal fun requireTokenRef(tokenRef: String) {
    if (!(tokenRef.startsWith("env:") && tokenRef.length > 4) && !(tokenRef.startsWith("file:") && tokenRef.length > 5)) {
        throw NotionConfigException("tokenRef must be 'env:NAME' or 'file:/path'")
    }
}
