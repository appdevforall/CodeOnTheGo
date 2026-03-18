package com.itsaky.androidide.git.core.models

sealed interface CommitHistoryUiState {
    /**
     * History is currently being fetched.
     */
    object Loading : CommitHistoryUiState

    /**
     * History was fetched successfully, but the repository has no commits yet.
     */
    object Empty : CommitHistoryUiState

    /**
     * History was fetched successfully with a list of commits.
     */
    data class Success(val commits: List<GitCommit>) : CommitHistoryUiState

    /**
     * An error occurred while fetching the history.
     */
    data class Error(val message: String?) : CommitHistoryUiState
}
