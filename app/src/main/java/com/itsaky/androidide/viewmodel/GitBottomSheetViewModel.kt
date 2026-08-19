package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.events.ListProjectFilesRequestEvent
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.git.core.GitRepository
import com.itsaky.androidide.git.core.GitRepositoryManager
import com.itsaky.androidide.git.core.models.CommitHistoryUiState
import com.itsaky.androidide.git.core.models.GitBranch
import com.itsaky.androidide.git.core.models.GitStatus
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.isNetworkConnected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.MergeResult.MergeStatus
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class GitBottomSheetViewModel(
	private val credentialsManager: GitCredentialsManager,
	private val isNetworkConnected: () -> Boolean = { BaseApplication.baseInstance.isNetworkConnected() },
	repository: GitRepository? = null,
) : ViewModel() {
	private val log = LoggerFactory.getLogger(GitBottomSheetViewModel::class.java)

	private val _gitStatus = MutableStateFlow(GitStatus.EMPTY)
	val gitStatus: StateFlow<GitStatus> = _gitStatus.asStateFlow()

	private val _currentBranch = MutableStateFlow<String?>(null)
	val currentBranch: StateFlow<String?> = _currentBranch.asStateFlow()

	private val _branches = MutableStateFlow<BranchesUiState>(BranchesUiState.None)
	val branches: StateFlow<BranchesUiState> = _branches.asStateFlow()

	private val _checkoutState = MutableStateFlow<CheckoutUiState>(CheckoutUiState.Idle)
	val checkoutState: StateFlow<CheckoutUiState> = _checkoutState.asStateFlow()

	private val _commitHistory =
		MutableStateFlow<CommitHistoryUiState>(CommitHistoryUiState.Loading)
	val commitHistory: StateFlow<CommitHistoryUiState> = _commitHistory.asStateFlow()

	private val _isGitRepository = MutableStateFlow(false)
	val isGitRepository: StateFlow<Boolean> = _isGitRepository.asStateFlow()

	private val _localCommitsCount = MutableStateFlow(0)
	val localCommitsCount: StateFlow<Int> = _localCommitsCount.asStateFlow()

	private val _pullState = MutableStateFlow<PullUiState>(PullUiState.Idle)
	val pullState: StateFlow<PullUiState> = _pullState.asStateFlow()

	private val _pushState = MutableStateFlow<PushUiState>(PushUiState.Idle)
	val pushState: StateFlow<PushUiState> = _pushState.asStateFlow()

	private val _mergeState = MutableStateFlow<MergeUiState>(MergeUiState.Idle)
	val mergeState: StateFlow<MergeUiState> = _mergeState.asStateFlow()

	private var pullResetJob: Job? = null
	private var pushResetJob: Job? = null
	private var checkoutResetJob: Job? = null
	private var mergeResetJob: Job? = null

	var currentRepository: GitRepository? = repository
		private set

	init {
		EventBus.getDefault().register(this)
		if (currentRepository == null) {
			initializeRepository()
		} else {
			_isGitRepository.value = true
		}
	}

	override fun onCleared() {
		super.onCleared()
		EventBus.getDefault().unregister(this)
		currentRepository?.close()
	}

	private fun initializeRepository() {
		viewModelScope.launch {
			try {
				val projectDir = File(IProjectManager.getInstance().projectDirPath)
				currentRepository = GitRepositoryManager.openRepository(projectDir)
				_isGitRepository.value = currentRepository != null
				refreshStatus()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to initialize repository", e)
				_isGitRepository.value = false
				_gitStatus.value = GitStatus.EMPTY
			}
		}
	}

	/**
	 * Refreshes the Git status of the project.
	 */
	fun refreshStatus() {
		viewModelScope.launch {
			try {
				currentRepository?.let { repo ->
					val status = repo.getStatus()
					_gitStatus.value = status
					_currentBranch.value = repo.getCurrentBranch()?.name
					_branches.value = BranchesUiState.Success(repo.getBranches())
					getLocalCommitsCount()
				} ?: run {
					_gitStatus.value = GitStatus.EMPTY
					_currentBranch.value = null
					_branches.value = BranchesUiState.None
					_localCommitsCount.value = 0
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to refresh git status", e)
				_gitStatus.value = GitStatus.EMPTY
				_currentBranch.value = null
				_branches.value = BranchesUiState.Error(e.message)
				_localCommitsCount.value = 0
			}
		}
	}

	/**
	 * Fetches the list of all local and remote branches from the current repository
	 * and updates the [_branches] flow with [BranchesUiState].
	 */
	fun fetchBranches() {
		viewModelScope.launch {
			_branches.value = BranchesUiState.Loading
			try {
				val repo = currentRepository
				if (repo == null) {
					_branches.value = BranchesUiState.None
					return@launch
				}
				_branches.value = BranchesUiState.Success(repo.getBranches())
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to fetch branches", e)
				_branches.value = BranchesUiState.Error(e.message)
			}
		}
	}

	/**
	 * Checks out the given [branchName].
	 *
	 * @param branchName The branch name or remote reference to switch to or create.
	 * @param createNew If true, creates a new branch.
	 * @param startPoint Optional start commit or branch name when creating a new branch.
	 * @param onSuccess Optional callback invoked when the checkout succeeds.
	 */
	fun checkoutBranch(
		branchName: String,
		createNew: Boolean = false,
		startPoint: String? = null,
		onSuccess: (() -> Unit)? = null,
	) {
		checkoutResetJob?.cancel()
		viewModelScope.launch {
			try {
				_checkoutState.value = CheckoutUiState.CheckingOut
				val repository = currentRepository ?: return@launch
				repository.checkout(branchName, createNew, startPoint)
				refreshStatus()
				_checkoutState.value = CheckoutUiState.Success(branchName)
				onSuccess?.invoke()
			} catch (e: CheckoutConflictException) {
				log.error("Checkout conflict occurred", e)
				_checkoutState.value = CheckoutUiState.Conflicts(e.conflictingPaths ?: emptyList())
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Checkout failed", e)
				_checkoutState.value = CheckoutUiState.Error(message = e.message)
			} finally {
				checkoutResetJob =
					viewModelScope.launch {
						delay(3000.milliseconds)
						_checkoutState.value = CheckoutUiState.Idle
					}
			}
		}
	}

	suspend fun getLocalCommitsCount() {
		_localCommitsCount.value = currentRepository?.getLocalCommitsCount() ?: 0
	}

	fun commitChanges(
		summary: String,
		description: String? = null,
		selectedPaths: List<String>,
		onSuccess: () -> Unit,
	) {
		viewModelScope.launch {
			try {
				if (selectedPaths.isEmpty()) return@launch

				val repository = currentRepository ?: return@launch

				val projectDir = File(IProjectManager.getInstance().projectDirPath)
				val filesToStage = selectedPaths.map { File(projectDir, it) }

				repository.stageFiles(filesToStage)

				val message =
					if (!description.isNullOrBlank()) "$summary\n\n$description" else summary
				repository.commit(
					message = message,
					authorName = GitPreferences.userName,
					authorEmail = GitPreferences.userEmail,
				)

				refreshStatus()
				onSuccess()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to commit changes", e)
			}
		}
	}

	fun getCommitHistoryList() {
		viewModelScope.launch {
			_commitHistory.value = CommitHistoryUiState.Loading
			try {
				val history = currentRepository?.getHistory()
				if (history.isNullOrEmpty()) {
					_commitHistory.value = CommitHistoryUiState.Empty
				} else {
					_commitHistory.value = CommitHistoryUiState.Success(history)
				}
				getLocalCommitsCount()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to fetch commit history", e)
				_commitHistory.value = CommitHistoryUiState.Error(e.message)
			}
		}
	}

	fun push(
		username: String?,
		token: String?,
	) {
		pushResetJob?.cancel()

		viewModelScope.launch {
			try {
				if (!isNetworkConnected()) {
					_pushState.value = PushUiState.Error(errorResId = R.string.no_internet_connection)
					return@launch
				}

				_pushState.value = PushUiState.Pushing
				val repository = currentRepository ?: return@launch
				val credentials = buildCredentials(username, token)
				val results = repository.push(credentialsProvider = credentials)
				val error =
					results
						.flatMap { it.remoteUpdates }
						.firstOrNull {
							it.status != RemoteRefUpdate.Status.OK &&
								it.status != RemoteRefUpdate.Status.UP_TO_DATE
						}

				if (error != null) {
					handlePushError(error)
					return@launch
				}

				handlePushSuccess(username, token)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				if (e.message?.contains("not authorized", ignoreCase = true) == true) {
					credentialsManager.clearCredentials()
					_pushState.value = PushUiState.Error(errorResId = R.string.repo_authorization_error)
					return@launch
				}
				_pushState.value = PushUiState.Error(e.message)
			} finally {
				pushResetJob =
					viewModelScope.launch {
						delay(3000.milliseconds)
						_pushState.value = PushUiState.Idle
					}
			}
		}
	}

	private fun buildCredentials(
		username: String?,
		token: String?,
	) = if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
		UsernamePasswordCredentialsProvider(username, token)
	} else {
		null
	}

	private fun handlePushError(update: RemoteRefUpdate) {
		val resId =
			if (update.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
				R.string.push_rejected_nonfastforward
			} else {
				R.string.unknown_error
			}
		_pushState.value =
			PushUiState.Error(
				message = update.message ?: update.status.name,
				errorResId = resId,
			)
	}

	private suspend fun handlePushSuccess(
		username: String?,
		token: String?,
	) {
		_pushState.value = PushUiState.Success
		credentialsManager.saveCredentialsIfNeeded(username, token)
		refreshStatus()
		getLocalCommitsCount()
		getCommitHistoryList()
	}

	fun pull(
		username: String?,
		token: String?,
	) {
		pullResetJob?.cancel()

		viewModelScope.launch {
			try {
				if (!isNetworkConnected()) {
					_pullState.value = PullUiState.Error(errorResId = R.string.no_internet_connection)
					return@launch
				}

				_pullState.value = PullUiState.Pulling
				val repository = currentRepository ?: return@launch
				val credentials = buildCredentials(username, token)
				val result = repository.pull(credentialsProvider = credentials)

				if (!result.isSuccessful) {
					handlePullError(result)
					return@launch
				}

				handlePullSuccess(username, token)
			} catch (e: CheckoutConflictException) {
				log.error("Pull checkout conflict occurred", e)
				val paths = e.conflictingPaths?.joinToString("\n") ?: ""
				_pullState.value = PullUiState.Error(errorResId = R.string.checkout_conflict_message, errorArgs = listOf(paths))
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Pull failed", e)
				if (e.message?.contains("not authorized", ignoreCase = true) == true) {
					credentialsManager.clearCredentials()
					_pullState.value = PullUiState.Error(errorResId = R.string.repo_authorization_error)
					return@launch
				}
				_pullState.value = PullUiState.Error(e.message)
			} finally {
				pullResetJob =
					viewModelScope.launch {
						delay(3000.milliseconds)
						_pullState.value = PullUiState.Idle
					}
			}
		}
	}

	private fun handlePullError(result: PullResult) {
		val mergeStatus = result.mergeResult?.mergeStatus
		val statusName = mergeStatus?.name ?: "Unknown error"

		if (mergeStatus == MergeStatus.CONFLICTING) {
			_pullState.value = PullUiState.Conflicts()
			refreshStatus()
		} else {
			_pullState.value = PullUiState.Error("Pull failed: $statusName")
		}
	}

	private fun handlePullSuccess(
		username: String?,
		token: String?,
	) {
		_pullState.value = PullUiState.Success
		credentialsManager.saveCredentialsIfNeeded(username, token)
		refreshStatus()
		getCommitHistoryList()
	}

	fun resetPullState() {
		pullResetJob?.cancel()
		_pullState.value = PullUiState.Idle
	}

	fun resetPushState() {
		pushResetJob?.cancel()
		_pushState.value = PushUiState.Idle
	}

	/**
	 * Cancels any scheduled checkout state reset timer and resets [_checkoutState] to [CheckoutUiState.Idle].
	 */
	fun resetCheckoutState() {
		checkoutResetJob?.cancel()
		_checkoutState.value = CheckoutUiState.Idle
	}

	/**
	 * Cancels any scheduled merge state reset timer and resets [_mergeState] to [MergeUiState.Idle].
	 */
	fun resetMergeState() {
		mergeResetJob?.cancel()
		_mergeState.value = MergeUiState.Idle
	}

	/**
	 * Merges [targetBranchName] into the currently checked-out branch and updates [_mergeState].
	 *
	 * @param targetBranchName The name of the branch to merge into HEAD.
	 */
	fun mergeBranch(targetBranchName: String) {
		mergeResetJob?.cancel()

		viewModelScope.launch {
			val repo = currentRepository ?: return@launch
			val currentBranchName = _currentBranch.value ?: "HEAD"
			_mergeState.value = MergeUiState.Merging

			try {
				val result = repo.merge(targetBranchName)
				when (result.mergeStatus) {
					MergeStatus.FAST_FORWARD,
					MergeStatus.FAST_FORWARD_SQUASHED,
					MergeStatus.MERGED,
					MergeStatus.MERGED_SQUASHED,
					MergeStatus.MERGED_SQUASHED_NOT_COMMITTED,
					-> {
						_mergeState.value =
							MergeUiState.Success(
								targetBranch = targetBranchName,
								currentBranch = currentBranchName,
							)
						refreshStatus()
						getCommitHistoryList()
						getLocalCommitsCount()
					}

					MergeStatus.ALREADY_UP_TO_DATE -> {
						_mergeState.value = MergeUiState.AlreadyUpToDate(targetBranch = targetBranchName)
					}

					MergeStatus.CONFLICTING -> {
						val conflictingFiles = repo.getStatus().conflicted.map { it.path }
						_mergeState.value =
							MergeUiState.Conflicts(
								targetBranch = targetBranchName,
								currentBranch = currentBranchName,
								conflictingFiles = conflictingFiles,
							)
						refreshStatus()
					}

					else -> {
						_mergeState.value =
							MergeUiState.Error(
								targetBranch = targetBranchName,
								message = "Merge status: ${result.mergeStatus.name}",
							)
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to merge branch $targetBranchName", e)
				_mergeState.value = MergeUiState.Error(targetBranch = targetBranchName, message = e.message)
			} finally {
				mergeResetJob =
					viewModelScope.launch {
						delay(3000.milliseconds)
						_mergeState.value = MergeUiState.Idle
					}
			}
		}
	}

	sealed class BranchesUiState {
		object None : BranchesUiState()

		object Loading : BranchesUiState()

		data class Success(
			val branches: List<GitBranch>,
		) : BranchesUiState()

		data class Error(
			val message: String? = null,
		) : BranchesUiState()
	}

	sealed class CheckoutUiState {
		object Idle : CheckoutUiState()

		object CheckingOut : CheckoutUiState()

		data class Success(
			val branchName: String,
		) : CheckoutUiState()

		data class Conflicts(
			val conflictingPaths: List<String> = emptyList(),
		) : CheckoutUiState()

		data class Error(
			val message: String? = null,
			val errorResId: Int? = R.string.unknown_error,
		) : CheckoutUiState()
	}

	sealed class MergeUiState {
		object Idle : MergeUiState()

		object Merging : MergeUiState()

		data class Success(
			val targetBranch: String,
			val currentBranch: String,
		) : MergeUiState()

		data class AlreadyUpToDate(
			val targetBranch: String,
		) : MergeUiState()

		data class Conflicts(
			val targetBranch: String,
			val currentBranch: String,
			val conflictingFiles: List<String> = emptyList(),
		) : MergeUiState()

		data class Error(
			val message: String? = null,
			val targetBranch: String? = null,
			val errorResId: Int? = R.string.unknown_error,
		) : MergeUiState()
	}

	sealed class PullUiState {
		object Idle : PullUiState()

		object Pulling : PullUiState()

		object Success : PullUiState()

		data class Conflicts(
			val message: String? = null,
		) : PullUiState()

		data class Error(
			val message: String? = null,
			val errorResId: Int? = R.string.unknown_error,
			val errorArgs: List<String>? = null,
		) : PullUiState()
	}

	sealed class PushUiState {
		object Idle : PushUiState()

		object Pushing : PushUiState()

		object Success : PushUiState()

		data class Error(
			val message: String? = null,
			val errorResId: Int? = R.string.unknown_error,
		) : PushUiState()
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onDocumentSaved(event: DocumentSaveEvent) {
		refreshStatus()
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onProjectFilesChanged(event: ListProjectFilesRequestEvent) {
		refreshStatus()
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onFileCreated(event: FileCreationEvent) {
		refreshStatus()
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onFileDeleted(event: FileDeletionEvent) {
		refreshStatus()
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onFileRenamed(event: FileRenameEvent) {
		refreshStatus()
	}

	fun abortMerge(onSuccess: (() -> Unit)? = null) {
		viewModelScope.launch {
			try {
				currentRepository?.abortMerge()
				refreshStatus()
				onSuccess?.invoke()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to abort merge", e)
			}
		}
	}

	fun resolveConflict(path: String) {
		viewModelScope.launch {
			try {
				val repository = currentRepository ?: return@launch
				val projectDir = File(IProjectManager.getInstance().projectDirPath)
				repository.stageFiles(listOf(File(projectDir, path)))
				refreshStatus()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to resolve conflict for $path", e)
			}
		}
	}
}
