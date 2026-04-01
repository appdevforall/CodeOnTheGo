package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.NetworkUtils
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.events.ListProjectFilesRequestEvent
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.git.core.GitRepository
import com.itsaky.androidide.git.core.GitRepositoryManager
import com.itsaky.androidide.git.core.models.CommitHistoryUiState
import com.itsaky.androidide.git.core.models.GitStatus
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File

class GitBottomSheetViewModel(private val credentialsManager: GitCredentialsManager) : ViewModel() {

    private val log = LoggerFactory.getLogger(GitBottomSheetViewModel::class.java)

    private val _gitStatus = MutableStateFlow(GitStatus.EMPTY)
    val gitStatus: StateFlow<GitStatus> = _gitStatus.asStateFlow()
    
    private val _currentBranch = MutableStateFlow<String?>(null)
    val currentBranch: StateFlow<String?> = _currentBranch.asStateFlow()
    
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

    private var pullResetJob: Job? = null
    private var pushResetJob: Job? = null

    var currentRepository: GitRepository? = null
        private set

    init {
        EventBus.getDefault().register(this)
        initializeRepository()
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
                    getLocalCommitsCount()
                } ?: run {
                    _gitStatus.value = GitStatus.EMPTY
                    _currentBranch.value = null
                    _localCommitsCount.value = 0
                }
            } catch (e: Exception) {
                log.error("Failed to refresh git status", e)
                _gitStatus.value = GitStatus.EMPTY
                _currentBranch.value = null
                _localCommitsCount.value = 0
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
        onSuccess: () -> Unit
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
                    authorEmail = GitPreferences.userEmail
                )

                refreshStatus()
                onSuccess()
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
            } catch (e: Exception) {
                log.error("Failed to fetch commit history", e)
                _commitHistory.value = CommitHistoryUiState.Error(e.message)
            }
        }
    }

    fun push(username: String?, token: String?) {
        pushResetJob?.cancel()

        if (!NetworkUtils.isConnected()){
            _pushState.value = PushUiState.Error(errorResId = R.string.no_internet_connection)
            return
        }

        viewModelScope.launch {
            _pushState.value = PushUiState.Pushing
            try {
                val repository = currentRepository ?: return@launch
                val credentials = buildCredentials(username, token)
                val results = repository.push(credentialsProvider = credentials)
                val error = results.flatMap { it.remoteUpdates }
                    .firstOrNull {
                        it.status != RemoteRefUpdate.Status.OK &&
                                it.status != RemoteRefUpdate.Status.UP_TO_DATE
                    }

                if (error != null) {
                    handlePushError(error)
                    return@launch
                }

                handlePushSuccess(username, token)
            } catch (e: Exception) {
                log.error("Push failed", e)
                if (e is NoRemoteRepositoryException) {
                    _pushState.value = PushUiState.Error("No remote repository found")
                    return@launch
                }
                _pushState.value = PushUiState.Error(e.message)
            } finally {
                pushResetJob = viewModelScope.launch {
                    delay(3000)
                    _pushState.value = PushUiState.Idle
                }
            }
        }
    }

    private fun buildCredentials(username: String?, token: String?) =
        if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
            UsernamePasswordCredentialsProvider(username, token)
        } else null

    private fun handlePushError(update: RemoteRefUpdate) {
        _pushState.value = PushUiState.Error(
            update.message ?: update.status.name
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

    fun pull(username: String?, token: String?) {
        pullResetJob?.cancel()

        if (!NetworkUtils.isConnected()){
            _pullState.value = PullUiState.Error(errorResId = R.string.no_internet_connection)
            return
        }

        viewModelScope.launch {
            _pullState.value = PullUiState.Pulling
            try {
                val repository = currentRepository ?: return@launch
                val credentials = buildCredentials(username, token)
                val result = repository.pull(credentialsProvider = credentials)

                if (!result.isSuccessful) {
                    handlePullError(result)
                    return@launch
                }

                handlePullSuccess(username, token)
            } catch (e: Exception) {
                log.error("Pull failed", e)
                _pullState.value = PullUiState.Error(e.message)
            } finally {
                pullResetJob = viewModelScope.launch {
                    delay(3000)
                    _pullState.value = PullUiState.Idle
                }
            }
        }
    }

    private fun handlePullError(result: PullResult) {
        val status = result.mergeResult?.mergeStatus?.name ?: "Unknown error"
        _pullState.value = PullUiState.Error("Pull failed: $status")
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

    sealed class PullUiState {
        object Idle : PullUiState()
        object Pulling : PullUiState()
        object Success : PullUiState()
        data class Error(val message: String? = null, val errorResId: Int? = R.string.unknown_error) : PullUiState()
    }

    sealed class PushUiState {
        object Idle : PushUiState()
        object Pushing : PushUiState()
        object Success : PushUiState()
        data class Error(val message: String? = null, val errorResId: Int? = R.string.unknown_error) : PushUiState()
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

}
