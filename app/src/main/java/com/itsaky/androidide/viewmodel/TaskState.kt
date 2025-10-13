package com.itsaky.androidide.viewmodel

import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult

/**
 * Represents the state of a long-running background task, like project initialization.
 */
sealed class TaskState {
    /** The task is not running. */
    object Idle : TaskState()

    /** The task is currently in progress. */
    object InProgress : TaskState()

    /** The task completed successfully. */
    data class Success(val result: InitializeResult.Success) : TaskState()

    /** The task failed. */
    data class Error(val failure: TaskExecutionResult.Failure?, val throwable: Throwable?) :
        TaskState()
}