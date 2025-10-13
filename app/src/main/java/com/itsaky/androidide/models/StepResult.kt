package com.itsaky.androidide.models

data class StepResult(
    val stepId: Int,
    val wasSuccessful: Boolean,
    val output: String,
    val error: String? = null
)