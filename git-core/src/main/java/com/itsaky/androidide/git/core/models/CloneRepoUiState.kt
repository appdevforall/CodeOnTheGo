package com.itsaky.androidide.git.core.models

import androidx.annotation.StringRes

sealed interface CloneRepoUiState {
    
    data class Idle(
        val url: String = "",
        val localPath: String = "",
        val isCloneButtonEnabled: Boolean = false
    ) : CloneRepoUiState
    
    data class Cloning(
        val url: String,
        val localPath: String,
        val cloneProgress: String = "",
        val clonePercentage: Int = 0,
        val isCancellable: Boolean = false,
        val statusTextResId: Int? = null,
    ) : CloneRepoUiState
    
    data class Success(
        val localPath: String
    ) : CloneRepoUiState
    
    data class Error(
        val url: String,
        val localPath: String,
        val errorMessage: String? = null,
        @StringRes val errorResId: Int? = null,
        val canRetry: Boolean = false,
    ) : CloneRepoUiState
}
