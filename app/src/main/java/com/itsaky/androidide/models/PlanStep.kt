package com.itsaky.androidide.models

import kotlinx.serialization.Serializable

@Serializable // Add this annotation
data class PlanStep(
    val stepId: Int,
    val objective: String,
    val toolToUse: String,
    val parameters: Map<String, String>, // Use String for simplicity in serialization
    val expectedOutputFormat: String
)