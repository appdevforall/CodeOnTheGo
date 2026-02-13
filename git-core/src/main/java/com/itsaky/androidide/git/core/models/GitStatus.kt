package com.itsaky.androidide.git.core.models

/**
 * Represents the aggregate status of a Git repository.
 */
data class GitStatus(
    val isClean: Boolean,
    val hasConflicts: Boolean,
    val staged: List<FileChange>,
    val unstaged: List<FileChange>,
    val untracked: List<FileChange>,
    val conflicted: List<FileChange>
) {
    companion object {
        val EMPTY = GitStatus(
            isClean = true,
            hasConflicts = false,
            staged = emptyList(),
            unstaged = emptyList(),
            untracked = emptyList(),
            conflicted = emptyList()
        )
    }
}
