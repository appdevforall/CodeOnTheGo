package com.itsaky.androidide.git.core.models

import androidx.annotation.StringRes

data class CloneRepoUiState(
    val url: String = "",
    val localPath: String = "",
    val statusMessage: String = "",
    @get:StringRes val statusResId: Int? = null,
    val isLoading: Boolean = false,
    val isSuccess: Boolean? = null,
    val isAuthRequired: Boolean = false,
    val isCloneButtonEnabled: Boolean = false
)
