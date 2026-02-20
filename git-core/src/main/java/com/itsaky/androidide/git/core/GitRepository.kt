package com.itsaky.androidide.git.core

import com.itsaky.androidide.git.core.models.GitBranch
import com.itsaky.androidide.git.core.models.GitCommit
import com.itsaky.androidide.git.core.models.GitStatus
import java.io.File

import java.io.Closeable

/**
 * Interface defining core Git repository operations.
 */
interface GitRepository : Closeable {
    val rootDir: File
    
    suspend fun getStatus(): GitStatus
    suspend fun getCurrentBranch(): GitBranch?
    suspend fun getBranches(): List<GitBranch>
    suspend fun getHistory(limit: Int = 50): List<GitCommit>
    suspend fun getDiff(file: File): String
}
