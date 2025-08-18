package com.itsaky.androidide.models

data class StepResult(
    val stepId: Int,
    val wasSuccessful: Boolean,
    val output: String, // Raw output from the tool
    val error: String? = null
)