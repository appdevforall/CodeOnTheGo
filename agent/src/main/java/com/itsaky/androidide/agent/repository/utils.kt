package com.itsaky.androidide.agent.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
            val toolOnly =
                Regex("<tool_call>\\s*([a-zA-Z0-9_]+)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
                    .find(responseText)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
            if (toolOnly != null && toolKeys.contains(toolOnly)) {
                val fallback = buildJsonObject {
                    put("name", toolOnly)
                    put("args", buildJsonObject { })
                }.toString()
                logger.debug("Fallback tool-only call parsed as JSON: {}", fallback)
                return parseJsonObject(fallback)?.let { jsonObject ->
                    val argsObject = jsonObject["args"]?.jsonObject
                    val args = argsObject?.mapValues { (_, value) ->
                        value.toToolArgString()
                    } ?: emptyMap()
                    val result = LocalLLMToolCall(toolOnly, args)
                    logger.debug("SUCCESS: Parsed tool-only call: {}", result)
                    result
                }
            }
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

            val resolvedName = when (name) {
                "list_dir" -> "list_files"
                else -> name
            }

            if (!toolKeys.contains(resolvedName)) {
                logger.error(
                    "FAILURE: Parsed tool name '{}' is not in the list of available tools.",
                    resolvedName
                )
                return null
            }

            val argsObject = jsonObject["args"]?.jsonObject
            val args = argsObject?.mapValues { (_, value) ->
                value.toToolArgString()
            } ?: emptyMap()

            val result = LocalLLMToolCall(resolvedName, args)
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
            val fromTags =
                extractFirstJsonObject(extracted) ?: parseFunctionStyleToolCall(extracted)
            if (fromTags != null) {
                return fromTags
            }
            // Some models emit <tool_call>name</tool_call>{...}; fall back to whole text.
            val fromWhole = extractFirstJsonObject(responseText)
                ?: parseFunctionStyleToolCall(responseText)
            if (fromWhole != null) {
                return fromWhole
            }
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

        return extractFirstJsonObject(cleaned) ?: parseFunctionStyleToolCall(cleaned)
    }

    private fun parseFunctionStyleToolCall(text: String): String? {
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isBlank() || firstLine.startsWith("{")) {
            return null
        }

        val match =
            Regex("^([a-zA-Z0-9_]+)\\s*\\((.*)\\)\\s*$", RegexOption.DOT_MATCHES_ALL)
                .find(firstLine)
                ?: return null

        val name = match.groupValues[1]
        val argsRaw = match.groupValues[2].trim()
        val args = parseArgs(name, argsRaw) ?: return null

        return buildJsonObject {
            put("name", name)
            put("args", buildJsonObject {
                args.forEach { (key, value) -> put(key, value) }
            })
        }.toString()
    }

    private fun parseArgs(toolName: String, raw: String): Map<String, String>? {
        if (raw.isBlank()) return emptyMap()

        val parts = splitArgs(raw)
        val keyValue = mutableMapOf<String, String>()
        val positional = mutableListOf<String>()

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val kv = splitKeyValue(trimmed)
            if (kv != null) {
                keyValue[kv.first] = kv.second
            } else {
                positional.add(unquote(trimmed))
            }
        }

        if (keyValue.isNotEmpty()) {
            return keyValue
        }

        if (positional.size == 1) {
            val key = defaultArgKeyForTool(toolName) ?: return null
            return mapOf(key to positional[0])
        }

        return null
    }

    private fun defaultArgKeyForTool(toolName: String): String? {
        return when (toolName) {
            "list_files" -> "path"
            "read_file" -> "file_path"
            "search_project" -> "query"
            "create_file" -> "path"
            "update_file" -> "path"
            "add_dependency" -> "dependency_string"
            "get_weather" -> "city"
            else -> null
        }
    }

    private fun splitKeyValue(token: String): Pair<String, String>? {
        val eqIndex = token.indexOf('=')
        val colonIndex = token.indexOf(':')
        val splitIndex = when {
            eqIndex >= 0 -> eqIndex
            colonIndex >= 0 -> colonIndex
            else -> -1
        }
        if (splitIndex <= 0) return null
        val key = token.substring(0, splitIndex).trim()
        val value = token.substring(splitIndex + 1).trim()
        if (key.isBlank() || value.isBlank()) return null
        return key to unquote(value)
    }

    private fun unquote(value: String): String {
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))
        ) {
            return value.substring(1, value.length - 1)
        }
        return value
    }

    private fun splitArgs(raw: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var stringChar = '\u0000'
        var escapeNext = false

        raw.forEach { ch ->
            when {
                escapeNext -> {
                    current.append(ch)
                    escapeNext = false
                }

                ch == '\\' -> {
                    current.append(ch)
                    escapeNext = true
                }

                inString -> {
                    current.append(ch)
                    if (ch == stringChar) inString = false
                }

                ch == '"' || ch == '\'' -> {
                    inString = true
                    stringChar = ch
                    current.append(ch)
                }

                ch == ',' -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }

                else -> current.append(ch)
            }
        }

        if (current.isNotEmpty()) {
            parts.add(current.toString())
        }

        return parts
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
