package com.itsaky.androidide.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PlanStep(
    val stepId: Int,
    val objective: String,
    val toolToUse: String,
    val parameters: Map<String, JsonElement>,
    val expectedOutputFormat: String
)