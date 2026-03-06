package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.eventbus.events.editor.DocumentSaveEvent
import com.itsaky.androidide.events.ListProjectFilesRequestEvent
import com.itsaky.androidide.git.core.GitRepository
import com.itsaky.androidide.git.core.GitRepositoryManager
import com.itsaky.androidide.git.core.models.GitStatus
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.eventbus.events.file.FileDeletionEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import java.io.File

class GitBottomSheetViewModel : ViewModel() {

    private val _gitStatus = MutableStateFlow(GitStatus.EMPTY)
    val gitStatus: StateFlow<GitStatus> = _gitStatus.asStateFlow()

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
            val projectDir = File(IProjectManager.getInstance().projectDirPath)
            currentRepository = GitRepositoryManager.openRepository(projectDir)
            refreshStatus()
        }
    }

    /**
     * Refreshes the Git status of the project.
     */
    fun refreshStatus() {
        viewModelScope.launch {
            currentRepository?.let { repo ->
                val status = repo.getStatus()
                _gitStatus.value = status
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
