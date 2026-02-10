package com.itsaky.androidide.git.core

import com.itsaky.androidide.git.core.models.GitBranch
import com.itsaky.androidide.git.core.models.GitCommit
import com.itsaky.androidide.git.core.models.GitStatus
import java.io.File

/**
 * Interface defining core Git repository operations.
 */
interface GitRepository {
    val rootDir: File
    
    fun getStatus(): GitStatus
    fun getCurrentBranch(): GitBranch?
    fun getBranches(): List<GitBranch>
    fun getHistory(limit: Int = 50): List<GitCommit>
    fun getDiff(file: File): String
}
