package com.itsaky.androidide.agent.data

import kotlinx.serialization.Serializable

// For the request body
@Serializable
data class GeminiRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null
)

@Serializable
data class Content(
    val parts: List<Part>,
    // Add the role property
    val role: String? = null
)

@Serializable
data class Part(
    val text: String
)

// For parsing the response
@Serializable
data class GeminiResponse(
    val candidates: List<Candidate>
)

@Serializable
data class Candidate(
    val content: Content
)