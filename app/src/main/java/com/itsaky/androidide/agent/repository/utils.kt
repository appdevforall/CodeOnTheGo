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
        // This function is now simpler. It just looks for the content between the first '{' and the last '}'.
        // This is robust enough to handle markdown code blocks or raw JSON output.
        val firstBraceIndex = responseText.indexOf('{')
        val lastBraceIndex = responseText.lastIndexOf('}')

        if (firstBraceIndex != -1 && lastBraceIndex != -1 && firstBraceIndex < lastBraceIndex) {
            return responseText.substring(firstBraceIndex, lastBraceIndex + 1)
        }
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
