package com.itsaky.androidide.projects.builder

data class BuildResult(
    val isSuccess: Boolean,
    val message: String,
    val launchResult: LaunchResult? = null
)