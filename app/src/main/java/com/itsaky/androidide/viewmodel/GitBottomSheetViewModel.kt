package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.events.ListProjectFilesRequestEvent
import com.itsaky.androidide.git.core.GitRepository
import com.itsaky.androidide.git.core.GitRepositoryManager
import com.itsaky.androidide.git.core.models.CommitHistoryUiState
import com.itsaky.androidide.git.core.models.GitStatus
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File

class GitBottomSheetViewModel : ViewModel() {

    private val log = LoggerFactory.getLogger(GitBottomSheetViewModel::class.java)

    private val _gitStatus = MutableStateFlow(GitStatus.EMPTY)
    val gitStatus: StateFlow<GitStatus> = _gitStatus.asStateFlow()
    private val _commitHistory =
        MutableStateFlow<CommitHistoryUiState>(CommitHistoryUiState.Loading)
    val commitHistory: StateFlow<CommitHistoryUiState> = _commitHistory.asStateFlow()

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
                refreshStatus()
            } catch (e: Exception) {
                log.error("Failed to initialize repository", e)
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
                } ?: run {
                    _gitStatus.value = GitStatus.EMPTY
                }
            } catch (e: Exception) {
                log.error("Failed to refresh git status", e)
                _gitStatus.value = GitStatus.EMPTY
            }
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
            } catch (e: Exception) {
                log.error("Failed to fetch commit history", e)
                _commitHistory.value = CommitHistoryUiState.Error(e.message)
            }
        }
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
