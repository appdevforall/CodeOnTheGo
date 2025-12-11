
package com.itsaky.androidide.viewmodel

sealed class InstallationState {
    object InstallationPending : InstallationState()

    object InstallationGranted : InstallationState()

    data class Installing(val progress: String = "") : InstallationState()

    object InstallationComplete : InstallationState()

    data class InstallationError(val errorMessage: String) : InstallationState()
}