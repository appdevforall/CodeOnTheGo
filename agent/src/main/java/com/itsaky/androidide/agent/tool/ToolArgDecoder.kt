package com.itsaky.androidide.agent.tool

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

@PublishedApi
internal val toolJson = Json {
    ignoreUnknownKeys = true
}

inline fun <reified T> decodeArgs(args: Map<String, Any?>): T {
    val jsonElement = args.toJsonElement()
    return toolJson.decodeFromJsonElement(jsonElement)
}

internal fun encodeStringMap(value: Map<String, String>): String {
    return toolJson.encodeToString(value)
}

@PublishedApi
internal fun Map<String, Any?>.toJsonElement(): JsonElement {
    val content = entries.associate { (key, value) ->
        key to value.toJsonElement()
    }
    return JsonObject(content)
}

@PublishedApi
internal fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Map<*, *> -> {
            val content = mutableMapOf<String, JsonElement>()
            for ((key, value) in this) {
                if (key != null) {
                    content[key.toString()] = value.toJsonElement()
                }
            }
            JsonObject(content)
        }

        is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
        is Array<*> -> JsonArray(this.map { it.toJsonElement() })
        else -> JsonPrimitive(toString())
    }
