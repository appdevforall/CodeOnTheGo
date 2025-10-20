package com.itsaky.androidide.agent.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

object Util {

    private val logger = LoggerFactory.getLogger(Util::class.java)

    // Define a lenient JSON parser instance
    private val jsonParser = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun parseToolCall(responseText: String, toolKeys: Set<String>): LocalLLMToolCall? {
        logger.debug("--- PARSER START ---")
        logger.debug("Input responseText: '{}'", responseText)

        // 1. Find the JSON string within the response.
        // This handles cases where the model wraps the JSON in markdown (```json ... ```)
        // or the required <tool_call> tags.
        val jsonString = findPotentialJsonObjectString(responseText)
        logger.debug("Extracted JSON string: '{}'", jsonString)
        if (jsonString == null) {
            logger.error("No potential JSON object found in the response.")
            return null
        }

        return parseJsonObject(jsonString)?.let { jsonObject ->
            val name = jsonObject["name"]?.jsonPrimitive?.contentOrNull
                ?: jsonObject["tool_name"]?.jsonPrimitive?.contentOrNull

            if (name.isNullOrBlank()) {
                logger.error(
                    "FAILURE: Tool call did not contain a 'name' or 'tool_name' field."
                )
                return null
            }

            if (!toolKeys.contains(name)) {
                logger.error(
                    "FAILURE: Parsed tool name '{}' is not in the list of available tools.",
                    name
                )
                return null
            }

            val argsObject = jsonObject["args"]?.jsonObject
            val args = argsObject?.mapValues { (_, value) ->
                value.toToolArgString()
            } ?: emptyMap()

            val result = LocalLLMToolCall(name, args)
            logger.debug("SUCCESS: Parsed and validated tool call: {}", result)
            result
        }
    }

    private fun findPotentialJsonObjectString(responseText: String): String? {
        // First, try to extract content within <tool_call> tags if present
        val toolCallMatch =
            Regex("<tool_call>\\s*(.+?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
                .find(responseText)
        if (toolCallMatch != null) {
            val extracted = toolCallMatch.groupValues[1].trim()
            logger.debug("Found content in <tool_call> tags: '{}'", extracted.take(100))
            return extractFirstJsonObject(extracted)
        }

        // Strip common LLM artifacts: markdown code fences
        var cleaned = responseText
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        // Remove conversational prefixes like "Current step (2/3):" or "Here is the tool call:"
        val colonIndex = cleaned.indexOf(':')
        if (colonIndex != -1 && colonIndex < 100) { // Only if colon is near the start
            val beforeColon = cleaned.substring(0, colonIndex).trim()
            // Check if it looks like a conversational prefix (no braces before colon)
            if (!beforeColon.contains('{') && !beforeColon.contains('}')) {
                val afterColon = cleaned.substring(colonIndex + 1).trim()
                if (afterColon.startsWith('{')) {
                    cleaned = afterColon
                }
            }
        }

        return extractFirstJsonObject(cleaned)
    }

    /**
     * Extracts the FIRST complete JSON object from the string,
     * ignoring any text that comes after it (like hallucinated conversation turns).
     */
    private fun extractFirstJsonObject(text: String): String? {
        val firstBraceIndex = text.indexOf('{')
        if (firstBraceIndex == -1) {
            logger.debug("No opening brace '{' found in text")
            return null
        }

        // Use a brace counter to find the matching closing brace
        var braceCount = 0
        var inString = false
        var escapeNext = false

        for (i in firstBraceIndex until text.length) {
            val char = text[i]

            // Handle string parsing (to ignore braces inside strings)
            when {
                escapeNext -> escapeNext = false
                char == '\\' -> escapeNext = true
                char == '"' -> inString = !inString
                inString -> continue // Skip all other characters while in a string
                char == '{' -> braceCount++
                char == '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        // Found the matching closing brace for the first JSON object
                        val jsonString = text.substring(firstBraceIndex, i + 1)
                        logger.debug(
                            "Extracted first complete JSON object (length: {})",
                            jsonString.length
                        )
                        return jsonString
                    }
                }
            }
        }

        logger.debug("Could not find matching closing brace for JSON object")
        return null
    }

    private fun parseJsonObject(jsonString: String): JsonObject? {
        return runCatching { jsonParser.parseToJsonElement(jsonString) }
            .onFailure { throwable ->
                logger.error("FAILURE: Unable to parse JSON string.", throwable)
            }
            .getOrNull() as? JsonObject
    }

    private fun JsonElement.toToolArgString(): String {
        return when (this) {
            is JsonPrimitive -> this.contentOrNull ?: this.toString()
            is JsonObject -> this.toString()
            else -> this.toString()
        }
    }
}
