package com.itsaky.androidide.git.core

import com.itsaky.androidide.git.core.models.GitBranch
import com.itsaky.androidide.git.core.models.GitCommit
import com.itsaky.androidide.git.core.models.GitStatus
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.PushResult
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
    
    // Commit Operations
    suspend fun stageFiles(files: List<File>)
    suspend fun commit(message: String, authorName: String? = null, authorEmail: String? = null): GitCommit?

    // Push Operations
    suspend fun push(
        remote: String = "origin",
        credentialsProvider: CredentialsProvider? = null,
        progressMonitor: ProgressMonitor? = null
    ): Iterable<PushResult>

    suspend fun getLocalCommitsCount(): Int

    suspend fun pull(
        remote: String = "origin",
        credentialsProvider: CredentialsProvider? = null,
        progressMonitor: ProgressMonitor? = null
    ): PullResult

    // Merge Operations
    suspend fun merge(branchName: String): MergeResult
    suspend fun abortMerge()
}
