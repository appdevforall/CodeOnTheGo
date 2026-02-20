package com.itsaky.androidide.git.core.models

/**
 * Represents a Git branch.
 */
data class GitBranch(
    val name: String,
    val fullName: String,
    val isCurrent: Boolean,
    val isRemote: Boolean,
    val remoteName: String? = null
)
