package com.itsaky.androidide.git.core

import com.itsaky.androidide.git.core.models.GitBranch
import com.itsaky.androidide.git.core.models.GitCommit
import com.itsaky.androidide.git.core.models.GitStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.PushResult
import java.io.Closeable
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Interface defining core Git repository operations.
 */
interface GitRepository : Closeable {
	val rootDir: File

	// The `.git` metadata directory for this repository.
	val gitDir: File
		get() = File(rootDir, ".git")

	/**
	 * Creates a [GitRepositoryWatcher] bound to this repository's [gitDir].
	 */
	fun createWatcher(
		scope: CoroutineScope,
		debounceDelay: Duration = GitRepositoryWatcher.DEFAULT_DEBOUNCE_DELAY,
		context: CoroutineContext = Dispatchers.Main.immediate,
		onGitStateChanged: suspend () -> Unit,
	): GitRepositoryWatcher =
		GitRepositoryWatcher(
			gitDir = gitDir,
			scope = scope,
			debounceDelay = debounceDelay,
			context = context,
			onGitStateChanged = onGitStateChanged,
		)

	suspend fun getStatus(): GitStatus

	suspend fun getCurrentBranch(): GitBranch?

	suspend fun getBranches(): List<GitBranch>

	suspend fun getHistory(limit: Int = 50): List<GitCommit>

	suspend fun getDiff(file: File): String

	// Commit Operations
	suspend fun stageFiles(files: List<File>)

	/**
	 * Renames a tracked file or directory in Git's index cache.
	 *
	 * Evicts any entries matching [oldFile] (including case-insensitive variants or duplicate index entries)
	 * and stages the newly renamed [newFile] in the index.
	 *
	 * @param oldFile The original file or directory.
	 * @param newFile The new file or directory after rename.
	 * @return `true` if any tracked index entries were renamed, `false` otherwise.
	 */
	suspend fun renamePath(
		oldFile: File,
		newFile: File,
	): Boolean

	// Git commit watermark configurations
	suspend fun isCommitWatermarkEnabled(): Boolean

	suspend fun setCommitWatermarkEnabled(enabled: Boolean)

	suspend fun commit(
		message: String,
		authorName: String? = null,
		authorEmail: String? = null,
	): GitCommit?

	// Push Operations
	suspend fun push(
		remote: String = "origin",
		credentialsProvider: CredentialsProvider? = null,
		progressMonitor: ProgressMonitor? = null,
	): Iterable<PushResult>

	suspend fun getLocalCommitsCount(): Int

	suspend fun pull(
		remote: String = "origin",
		credentialsProvider: CredentialsProvider? = null,
		progressMonitor: ProgressMonitor? = null,
	): PullResult

	// Merge Operations

	/**
	 * Merges the specified branch into the current HEAD branch.
	 *
	 * @param branchName The name of the target branch to merge into current HEAD.
	 * @return [MergeResult] containing the merge status (e.g. FAST_FORWARD, CONFLICTING).
	 */
	suspend fun merge(branchName: String): MergeResult

	/**
	 * Aborts an ongoing conflicted merge, resetting the working tree and index back to HEAD.
	 */
	suspend fun abortMerge()

	// Branch Operations

	/**
	 * Checks out the specified branch.
	 *
	 * When [createNew] is true, creates a new local branch with [branchName] starting from [startPoint] (or HEAD if null).
	 * When [createNew] is false and [branchName] refers to a remote-tracking branch, creates or switches to a corresponding
	 * local branch configured to track the remote ref.
	 *
	 * @param branchName The target branch name or remote ref (e.g., "main", "feature", "origin/main").
	 * @param createNew If true, creates a new branch instead of switching to an existing one.
	 * @param startPoint Optional start commit or branch name when creating a new branch.
	 */
	suspend fun checkout(
		branchName: String,
		createNew: Boolean = false,
		startPoint: String? = null,
	): String
}
