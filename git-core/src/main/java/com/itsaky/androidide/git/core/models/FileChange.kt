package com.itsaky.androidide.git.core.models

/**
 * Represents the type of change in a file tracked by Git.
 */
enum class ChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    UNTRACKED,
    RENAMED,
    CONFLICTED
}

/**
 * Represents a single file change in the repository.
 */
data class FileChange(
    val path: String,
    val type: ChangeType,
    val oldPath: String? = null
)
