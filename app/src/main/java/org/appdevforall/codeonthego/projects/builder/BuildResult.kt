package org.appdevforall.codeonthego.projects.builder

data class BuildResult(
    val isSuccess: Boolean,
    val message: String,
    val launchResult: LaunchResult? = null
)