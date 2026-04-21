package com.itsaky.androidide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.git.core.GitRepositoryManager
import com.itsaky.androidide.git.core.parseGitRepositoryUrl
import com.itsaky.androidide.git.core.models.CloneRepoUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.api.errors.TransportException
import java.net.UnknownHostException
import java.io.EOFException
import java.io.File
import com.blankj.utilcode.util.NetworkUtils
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.resources.R

class CloneRepositoryViewModel(
    application: Application,
    private val credentialsManager: GitCredentialsManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<CloneRepoUiState>(CloneRepoUiState.Idle())
    val uiState: StateFlow<CloneRepoUiState> = _uiState.asStateFlow()

    @Volatile
    private var isCloneCancelled = false

    fun onInputChanged(url: String, path: String) {
        val normalizedUrl = parseGitRepositoryUrl(url)
        val currentState = _uiState.value
        if (currentState is CloneRepoUiState.Idle) {
            _uiState.update {
                currentState.copy(
                    url = url,
                    localPath = path,
                    isCloneButtonEnabled = normalizedUrl != null && path.isNotBlank()
                )
            }
        } else if (currentState is CloneRepoUiState.Error) {
            _uiState.update {
                CloneRepoUiState.Idle(
                    url = url,
                    localPath = path,
                    isCloneButtonEnabled = normalizedUrl != null && path.isNotBlank()
                )
            }
        }
    }

    fun resetState() {
        _uiState.value = CloneRepoUiState.Idle()
    }

    fun cloneRepository(
        url: String,
        localPath: String,
        username: String? = null,
        token: String? = null
    ) {
        val normalizedUrl = parseGitRepositoryUrl(url)
        if (normalizedUrl == null) {
            _uiState.update {
                CloneRepoUiState.Error(
                    url = url,
                    localPath = localPath,
                    errorResId = R.string.msg_invalid_url
                )
            }
            return
        }

        isCloneCancelled = false
        val destDir = File(localPath)
        val isExistingDir = destDir.exists()
        if (isExistingDir && destDir.listFiles()?.isNotEmpty() == true) {
            _uiState.update {
                CloneRepoUiState.Error(
                    url = normalizedUrl,
                    localPath = localPath,
                    errorResId = R.string.destination_directory_not_empty
                )
            }
            return
        }

        if (!NetworkUtils.isConnected()) {
            _uiState.update {
                CloneRepoUiState.Error(
                    url = normalizedUrl,
                    localPath = localPath,
                    errorResId = R.string.no_internet_connection,
                    canRetry = true
                )
            }
            return
        }

        viewModelScope.launch {
            var hasCloned = false
            _uiState.update {
                CloneRepoUiState.Cloning(
                    url = normalizedUrl,
                    localPath = localPath,
                    statusTextResId = R.string.initialising_clone
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

                    override fun isCancelled(): Boolean {
                        return isCloneCancelled
                    }

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

                        _uiState.update { currentState ->
                            if (currentState is CloneRepoUiState.Cloning) {
                                currentState.copy(
                                    cloneProgress = progressMsg,
                                    clonePercentage = percentage,
                                    isCancellable = true,
                                    statusTextResId = if (isCloneCancelled) R.string.cancelling_clone else R.string.cloning_repo
                                )
                            } else {
                                currentState
                            }
                        }
                    }
                }

                GitRepositoryManager.cloneRepository(normalizedUrl, destDir, credentials, progressMonitor)
                
                if (!destDir.exists()) {
                    throw Exception("Destination directory was not created.")
                }
                
                hasCloned = true
                _uiState.update {
                    CloneRepoUiState.Success(localPath = localPath)
                }
                credentialsManager.saveCredentialsIfNeeded(username, token)
            } catch (e: Exception) {
                // Error handling
                if (isCloneCancelled) {
                    _uiState.update {
                        CloneRepoUiState.Idle(
                            url = normalizedUrl,
                            localPath = localPath,
                            isCloneButtonEnabled = true
                        )
                    }
                    return@launch
                }

                val isNetworkError = e is TransportException && e.cause is UnknownHostException
                val isConnectionDrop = e.cause is EOFException || 
                    e.message?.contains("Unexpected end of stream") == true ||
                    e.message?.contains("Software caused connection abort") == true
                
                val errorResId = when {
                    isNetworkError -> R.string.no_internet_connection
                    isConnectionDrop -> R.string.connection_lost
                    else -> null
                }
                
                val errorMessage = if (errorResId == null) {
                    e.message ?: application.getString(R.string.unknown_error)
                } else null
                
                _uiState.update {
                    CloneRepoUiState.Error(
                        url = normalizedUrl,
                        localPath = localPath,
                        errorResId = errorResId,
                        errorMessage = errorMessage?.let { application.getString(R.string.clone_failed, it) },
                        canRetry = true
                    )
                }
            } finally {
                // Clean up partial clone directories
                if (!hasCloned) {
                    withContext(Dispatchers.IO) {
                        if (!isExistingDir) {
                            destDir.deleteRecursively()
                        } else {
                            destDir.listFiles()?.forEach { it.deleteRecursively() }
                        }
                    }

                    val currentState = _uiState.value
                    if (currentState is CloneRepoUiState.Cloning) {
                        _uiState.update {
                            CloneRepoUiState.Idle(
                                url = normalizedUrl,
                                localPath = localPath,
                                isCloneButtonEnabled = true
                            )
                        }
                    }
                }
            }
        }
    }

    fun cancelClone() {
        isCloneCancelled = true

        _uiState.update { currentState ->
            if (currentState is CloneRepoUiState.Cloning) {
                currentState.copy(statusTextResId = R.string.cancelling_clone)
            } else {
                currentState
            }
        }
    }

}
