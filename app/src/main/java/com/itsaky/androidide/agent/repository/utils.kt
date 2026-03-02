@file:OptIn(InternalSerializationApi::class)

package com.itsaky.androidide.agent.repository

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

object Util {

    private val log = LoggerFactory.getLogger(Util::class.java)

    // Define a lenient JSON parser instance
    private val jsonParser = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    @OptIn(InternalSerializationApi::class)
    fun parseToolCall(responseText: String, toolKeys: Set<String>): LocalLLMToolCall? {
        log.debug("--- PARSER START ---")
        log.debug("Input responseText: '{}'", responseText)

        // 1. Handle "Tool Call: name({...})" format.
        parseFunctionStyleToolCall(responseText)?.let { parsed ->
            return validateToolCall(parsed, toolKeys)
        }

        // 2. Find the JSON string within the response.
        // This handles cases where the model wraps the JSON in markdown (```json ... ```)
        // or the required <tool_call> tags.
        val jsonString = findPotentialJsonObjectString(responseText)
        log.debug("Extracted JSON string: '{}'", jsonString)
        if (jsonString == null) {
            log.error("No potential JSON object found in the response.")
            return null
        }

        // 2. Try to decode the JSON string directly into our ToolCall data class.
        return try {
            val jsonObject = jsonParser.parseToJsonElement(jsonString).jsonObject
            val rawName = jsonObject["name"]?.jsonPrimitive?.content
                ?: jsonObject["tool_name"]?.jsonPrimitive?.content
            val toolCall = buildToolCall(rawName, jsonObject["args"]?.jsonObject)
            validateToolCall(toolCall, toolKeys)
        } catch (e: Exception) {
            log.error("FAILURE: Unable to parse tool call JSON: {}", e.message)
            null
        }
    }

    private fun findPotentialJsonObjectString(responseText: String): String? {
        val toolBlock = extractFirstToolCallBlock(responseText)
        if (toolBlock != null) {
            extractFirstJsonObject(toolBlock)?.let { return it }
            return null
        }

        val trimmed = responseText.trimStart()
        if (!trimmed.startsWith("{")) {
            return null
        }

        extractFirstJsonObject(responseText)?.let { return it }

        return null
    }

    private fun extractFirstToolCallBlock(text: String): String? {
        val start = text.indexOf("<tool_call>")
        if (start == -1) return null
        val end = text.indexOf("</tool_call>", start + "<tool_call>".length)
        if (end == -1) return text.substring(start + "<tool_call>".length)
        return text.substring(start + "<tool_call>".length, end)
    }

    private fun extractFirstJsonObject(text: String): String? {
        var inString = false
        var escape = false
        var depth = 0
        var start = -1
        for (i in text.indices) {
            val ch = text[i]
            if (escape) {
                escape = false
                continue
            }
            when (ch) {
                '\\' -> if (inString) escape = true
                '"' -> inString = !inString
                '{' -> if (!inString) {
                    if (depth == 0) start = i
                    depth += 1
                }

                '}' -> if (!inString && depth > 0) {
                    depth -= 1
                    if (depth == 0 && start != -1) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    @OptIn(InternalSerializationApi::class)
    private fun parseFunctionStyleToolCall(text: String): LocalLLMToolCall? {
        val match = Regex(
            "Tool Call:\\s*([a-zA-Z0-9_]+)\\s*\\((.*)\\)",
            RegexOption.DOT_MATCHES_ALL
        )
            .find(text) ?: return null
        val name = match.groupValues[1].trim()
        val argsRaw = match.groupValues[2].substringBefore("<").trim()
        val argsObject = when {
            argsRaw.isBlank() -> null
            argsRaw.startsWith("{") -> jsonParser.parseToJsonElement(argsRaw).jsonObject
            else -> null
        }
        return buildToolCall(name, argsObject)
    }

    private fun buildToolCall(
        rawName: String?,
        argsObject: JsonObject?
    ): LocalLLMToolCall? {
        if (rawName.isNullOrBlank()) {
            log.error("FAILURE: Tool call did not contain a 'name' or 'tool_name' field.")
            return null
        }
        val resolvedName = if (rawName == "list_dir") "list_files" else rawName
        val args =
            argsObject
                ?.mapValues { (_, value) -> value.toToolArgString() }
                ?.filterValues { it != "null" }
                ?: emptyMap()
        return LocalLLMToolCall(resolvedName, args)
    }

    private fun validateToolCall(
        toolCall: LocalLLMToolCall?,
        toolKeys: Set<String>
    ): LocalLLMToolCall? {
        if (toolCall == null) return null
        if (!toolKeys.contains(toolCall.name)) {
            log.error(
                "FAILURE: Parsed tool name '{}' is not in the list of available tools.",
                toolCall.name
            )
            return null
        }
        log.debug("SUCCESS: Parsed and validated tool call: {}", toolCall)
        return toolCall
    }

    private fun JsonElement.toToolArgString(): String {
        return when (this) {
            is kotlinx.serialization.json.JsonPrimitive -> this.contentOrNull ?: this.toString()
            is JsonObject -> this.toString()
            else -> this.toString()
        }
    }
}
