package com.itsaky.androidide.agent.repository

import android.util.Log
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json

object Util {

    // Define a lenient JSON parser instance
    private val jsonParser = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    @OptIn(InternalSerializationApi::class)
    fun parseToolCall(responseText: String, toolKeys: Set<String>): LocalLLMToolCall? {
        Log.d("ToolParse", "--- PARSER START ---")
        Log.d("ToolParse", "Input responseText: '$responseText'")

        // 1. Find the JSON string within the response.
        // This handles cases where the model wraps the JSON in markdown (```json ... ```)
        // or the required <tool_call> tags.
        val jsonString = findPotentialJsonObjectString(responseText)
        Log.d("ToolParse", "Extracted JSON string: '$jsonString'")
        if (jsonString == null) {
            Log.e("ToolParse", "No potential JSON object found in the response.")
            return null
        }

        // 2. Try to decode the JSON string directly into our ToolCall data class.
        return try {
            val toolCall = jsonParser.decodeFromString<LocalLLMToolCall>(jsonString)

            // 3. Validate that the tool name is one we actually support.
            if (toolKeys.contains(toolCall.name)) {
                Log.d("ToolParse", "SUCCESS: Parsed and validated tool call: $toolCall")
                toolCall
            } else {
                Log.e(
                    "ToolParse",
                    "FAILURE: Parsed tool name '${toolCall.name}' is not in the list of available tools."
                )
                null
            }
        } catch (e: Exception) {
            Log.e("ToolParse", "FAILURE: kotlinx.serialization failed to parse JSON: ${e.message}")
            null
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
}
