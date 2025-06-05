package com.itsaky.androidide.lsp.debug.model

/**
 * Result of a [StepResponse].
 */
sealed interface StepResult {

    /**
     * The step request was success.
     */
    data object Success: StepResult

    /**
     * The step request failed.
     *
     * @property cause An optional cause of the failure.
     */
    data class Failure(
        val cause: Throwable? = null
    ): StepResult {
        constructor(message: String) : this(IllegalStateException(message))
    }
}

/**
 * Response of a [step request][StepRequestParams].
 *
 * @property result The result of the step request.
 */
data class StepResponse(
    val result: StepResult
)