package com.itsaky.androidide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.R
import com.itsaky.androidide.git.core.GitRepositoryManager
import com.itsaky.androidide.git.core.models.CloneRepoUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class CloneRepositoryViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CloneRepoUiState())
    val uiState: StateFlow<CloneRepoUiState> = _uiState.asStateFlow()

    fun onInputChanged(url: String, path: String) {
        _uiState.update {
            it.copy(
                url = url,
                localPath = path,
                isCloneButtonEnabled = url.isNotBlank() && path.isNotBlank() && !uiState.value.isLoading
            )
        }
    }

    fun resetState() {
        _uiState.value = CloneRepoUiState()
    }

    fun cloneRepository(
        url: String,
        localPath: String,
        username: String? = null,
        token: String? = null
    ) {
        val destDir = File(localPath)
        if (destDir.exists() && destDir.listFiles()?.isNotEmpty() == true) {
            _uiState.update { it.copy(statusResId = R.string.destination_directory_not_empty) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusResId = R.string.cloning_repo,
                    isCloneButtonEnabled = false
                )
            }
            try {
                val credentials = if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                    UsernamePasswordCredentialsProvider(username, token)
                } else {
                    null
                }

                val progressMonitor = object : ProgressMonitor {
                    private var totalWork = 0
                    private var completedWork = 0
                    private var currentTaskTitle = ""

                    override fun start(totalTasks: Int) {}

                    override fun beginTask(title: String, totalWork: Int) {
                        this.currentTaskTitle = title
                        this.totalWork = totalWork
                        this.completedWork = 0
                        updateProgressUI()
                    }

                    override fun update(completed: Int) {
                        this.completedWork += completed
                        updateProgressUI()
                    }

                    override fun endTask() {}

                    override fun isCancelled(): Boolean = false

                    override fun showDuration(enabled: Boolean) {}

                    private fun updateProgressUI() {
                        val percentage = if (totalWork > 0) {
                            ((completedWork.toFloat() / totalWork.toFloat()) * 100).toInt()
                        } else {
                            0
                        }

                        val progressMsg = if (totalWork > 0) {
                            "$currentTaskTitle: $percentage% -- ($completedWork/$totalWork)"
                        } else {
                            currentTaskTitle
                        }

                        _uiState.update {
                            it.copy(
                                cloneProgress = progressMsg,
                                clonePercentage = percentage,
                            )
                        }
                    }
                }

                GitRepositoryManager.cloneRepository(url, destDir, credentials, progressMonitor)
                _uiState.update {
                    it.copy(
                        statusResId = R.string.clone_successful,
                        isSuccess = true,
                        isCloneButtonEnabled = true
                    )
                }
            } catch (e: Exception) {
                val errorMessage = e.message ?: application.getString(R.string.unknown_error)
                _uiState.update {
                    it.copy(
                        statusResId = null,
                        statusMessage = application.getString(
                            R.string.clone_failed, errorMessage
                        ),
                        isSuccess = false
                    )
                }
            } finally {
                _uiState.update {
                    it.copy(isLoading = false, isCloneButtonEnabled = true)
                }
            }
        }
    }

}
