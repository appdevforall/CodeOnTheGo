
package com.itsaky.androidide.viewmodel

sealed class PermissionsState {
    object PermissionsPending : PermissionsState()

    object PermissionsGranted : PermissionsState()

    data class Installing(val progress: String = "") : PermissionsState()

    object InstallationComplete : PermissionsState()

    data class InstallationError(val error: String) : PermissionsState()
}