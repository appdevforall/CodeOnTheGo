package com.itsaky.androidide.viewmodel

import java.io.File

/** Represents the state of a build-and-install process. */
sealed class BuildState {
    object Idle : BuildState()
    object InProgress : BuildState()
    data class AwaitingInstall(val apkFile: File) : BuildState()
    data class Success(val message: String) : BuildState()
    data class Error(val reason: String) : BuildState()
}