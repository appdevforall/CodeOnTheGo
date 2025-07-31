package com.itsaky.androidide.data.repository

import com.google.firebase.ai.type.FunctionCallPart

interface GeminiRepository {
    var onToolCall: ((FunctionCallPart) -> Unit)?
    /**
     * Generates a structured JSON report by orchestrating function calls to providers.
     * @param prompt The initial user prompt to start the generation process.
     * @return A string containing the final JSON report.
     */
    suspend fun generateASimpleResponse(prompt: String): String
}