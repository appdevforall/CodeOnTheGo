package com.itsaky.androidide.agent.model

sealed interface ModelLoadResult {
    data class Loaded(
        val modelName: String
    ) : ModelLoadResult

    data class Rejected(
        val message: String
    ) : ModelLoadResult

    data class Failed(
        val message: String,
        val cause: Throwable? = null
    ) : ModelLoadResult
}
