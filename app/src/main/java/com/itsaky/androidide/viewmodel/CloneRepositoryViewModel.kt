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
import kotlinx.coroutines.launch
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class CloneRepositoryViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CloneRepoUiState())
    val uiState: StateFlow<CloneRepoUiState> = _uiState.asStateFlow()

    fun onInputChanged(url: String, path: String) {
        updateState(
            url = url,
            localPath = path,
            isCloneButtonEnabled = url.isNotBlank() && path.isNotBlank() && !uiState.value.isLoading
        )
    }

    fun cloneRepository(
        url: String,
        localPath: String,
        username: String? = null,
        token: String? = null
    ) {
        val destDir = File(localPath)
        if (destDir.exists() && destDir.listFiles()?.isNotEmpty() == true) {
            updateState(statusResId = R.string.destination_directory_not_empty)
            return
        }

        viewModelScope.launch {
            updateState(isLoading = true, statusResId = R.string.cloning_repo)
            try {
                val credentials = if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                    UsernamePasswordCredentialsProvider(username, token)
                } else {
                    null
                }

                GitRepositoryManager.cloneRepository(url, destDir, credentials)
                updateState(statusResId = R.string.clone_successful, isSuccess = true)
            } catch (e: Exception) {
                val errorMessage = e.message ?: application.getString(R.string.unknown_error)
                updateState(
                    statusMessage = application.getString(
                        R.string.clone_failed, errorMessage
                    ),
                    isSuccess = false
                )
            } finally {
                updateState(isLoading = false)
            }
        }
    }

    private fun updateState(
        url: String? = null,
        localPath: String? = null,
        statusMessage: String? = null,
        statusResId: Int? = null,
        isLoading: Boolean? = null,
        isSuccess: Boolean? = null,
        isAuthRequired: Boolean? = null,
        isCloneButtonEnabled: Boolean? = null,
    ) {
        _uiState.value = _uiState.value.copy(
            url = url ?: uiState.value.url,
            localPath = localPath ?: uiState.value.localPath,
            statusMessage = statusMessage ?: uiState.value.statusMessage,
            statusResId = statusResId ?: uiState.value.statusResId,
            isLoading = isLoading ?: uiState.value.isLoading,
            isSuccess = isSuccess ?: uiState.value.isSuccess,
            isAuthRequired = isAuthRequired ?: uiState.value.isAuthRequired,
            isCloneButtonEnabled = isCloneButtonEnabled ?: uiState.value.isCloneButtonEnabled,
        )
    }
}
