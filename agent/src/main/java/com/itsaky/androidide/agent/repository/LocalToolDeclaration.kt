package com.itsaky.androidide.agent.repository

// You can place this in a new file, like `LocalToolDefinition.kt`, or inside your repository file.
data class LocalToolDeclaration(
    val name: String,
    val description: String,
    val parameters: Map<String, String> // A simple map of parameter name to its description
)
