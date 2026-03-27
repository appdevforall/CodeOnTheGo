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
import com.itsaky.androidide.git.core.models.GitStatus
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File

class GitBottomSheetViewModel : ViewModel() {

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

    fun getLocalCommitsCount() {
        viewModelScope.launch {
            _localCommitsCount.value = currentRepository?.getLocalCommitsCount() ?: 0
        }
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

    fun push(username: String?, token: String?, shouldSaveCredentials: Boolean = true) {
        pushResetJob?.cancel()
        viewModelScope.launch {
            _pushState.value = PushUiState.Pushing
            try {
                val repository = currentRepository ?: return@launch
                val credentials = if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                    UsernamePasswordCredentialsProvider(username, token)
                } else null

                val results = repository.push(credentialsProvider = credentials)
                var hasError = false
                var errorMessage: String? = null

                for (result in results) {
                    for (update in result.remoteUpdates) {
                        if (update.status != RemoteRefUpdate.Status.OK &&
                            update.status != RemoteRefUpdate.Status.UP_TO_DATE
                        ) {
                            hasError = true
                            errorMessage = update.message ?: update.status.name
                            break
                        }
                    }
                    if (hasError) break
                }

                if (hasError) {
                    _pushState.value = PushUiState.Error(errorMessage)
                } else {
                    _pushState.value = PushUiState.Success
                    // Persist credentials on success if requested
                    if (shouldSaveCredentials && username != null && token != null) {
                        GitCredentialsManager.saveCredentials(BaseApplication.baseInstance, username, token)
                    }
                    refreshStatus()
                    getLocalCommitsCount()
                    getCommitHistoryList()
                }
            } catch (e: Exception) {
                log.error("Push failed", e)
                _pushState.value = PushUiState.Error(e.message)
            } finally {
                pushResetJob = viewModelScope.launch {
                    delay(3000)
                    _pushState.value = PushUiState.Idle
                }
            }
        }
    }

    fun pull(username: String?, token: String?, shouldSaveCredentials: Boolean = true) {
        pullResetJob?.cancel()
        viewModelScope.launch {
            _pullState.value = PullUiState.Pulling
            try {
                val repository = currentRepository ?: return@launch
                val credentials = if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                    UsernamePasswordCredentialsProvider(username, token)
                } else null

                val result = repository.pull(credentialsProvider = credentials)
                
                if (result.isSuccessful) {
                    _pullState.value = PullUiState.Success
                    // Persist credentials on success if requested
                    if (shouldSaveCredentials && username != null && token != null) {
                        GitCredentialsManager.saveCredentials(BaseApplication.baseInstance, username, token)
                    }
                    refreshStatus()
                    getCommitHistoryList()
                } else {
                    val status = result.mergeResult?.mergeStatus?.name ?: "Unknown error"
                    _pullState.value = PullUiState.Error("Pull failed: $status")
                }
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
        data class Error(val message: String?) : PullUiState()
    }

    sealed class PushUiState {
        object Idle : PushUiState()
        object Pushing : PushUiState()
        object Success : PushUiState()
        data class Error(val message: String?) : PushUiState()
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
