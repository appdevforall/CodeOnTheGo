
package com.itsaky.androidide.viewmodel

import androidx.annotation.StringRes

sealed class InstallationState {
    object InstallationPending : InstallationState()

    object InstallationGranted : InstallationState()

    data class Installing(val progress: String = "") : InstallationState()

    object InstallationComplete : InstallationState()

    data class InstallationError(@StringRes val errorMessageResId: Int) : InstallationState()
}