package com.itsaky.androidide.git.core.models

/**
 * Represents a Git commit.
 */
data class GitCommit(
    val hash: String,
    val shortHash: String,
    val authorName: String,
    val authorEmail: String,
    val message: String,
    val timestamp: Long,
    val parentHashes: List<String>
)
