package com.itsaky.androidide.agent.viewmodel

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.agent.fragments.EncryptedPrefs
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.repository.LlmInferenceEngine
import com.itsaky.androidide.agent.repository.PREF_KEY_AI_BACKEND
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_PATH
import com.itsaky.androidide.app.BaseApplication
import kotlinx.coroutines.launch

sealed class ModelLoadingState {
    object Idle : ModelLoadingState()
    object Loading : ModelLoadingState()
    data class Loaded(val modelName: String) : ModelLoadingState()
    data class Error(val message: String) : ModelLoadingState()
}

class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _savedModelPath = MutableLiveData<String?>(null)
    val savedModelPath: LiveData<String?> get() = _savedModelPath

    // ✨ New LiveData to track and expose the model loading status ✨
    private val _modelLoadingState = MutableLiveData<ModelLoadingState>(ModelLoadingState.Idle)
    val modelLoadingState: LiveData<ModelLoadingState> get() = _modelLoadingState

    fun getAvailableBackends(): List<AiBackend> {
        return AiBackend.entries
    }

    fun getCurrentBackend(): AiBackend {
        val prefs = BaseApplication.getBaseInstance().prefManager
        val backendName = prefs.getString(PREF_KEY_AI_BACKEND, AiBackend.GEMINI.name)
        return AiBackend.valueOf(backendName ?: AiBackend.GEMINI.name)
    }

    fun saveBackend(backend: AiBackend) {
        val prefs = BaseApplication.getBaseInstance().prefManager
        prefs.putString(PREF_KEY_AI_BACKEND, backend.name)
    }

    fun saveLocalModelPath(uriString: String) {
        val prefs = BaseApplication.getBaseInstance().prefManager
        prefs.putString(PREF_KEY_LOCAL_MODEL_PATH, uriString)
        onNewModelSelected(uriString)
    }

    fun getLocalModelPath(): String? {
        val prefs = BaseApplication.getBaseInstance().prefManager
        return prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
    }


    fun saveGeminiApiKey(apiKey: String) {
        EncryptedPrefs.saveGeminiApiKey(getApplication(), apiKey)
    }

    fun getGeminiApiKey(): String? {
        return EncryptedPrefs.getGeminiApiKey(getApplication())
    }

    fun loadModelFromUri(path: String, context: Context) {
        viewModelScope.launch {
            // 🔄 Set state to Loading before starting
            _modelLoadingState.value = ModelLoadingState.Loading
            try {
                LlmInferenceEngine.initModelFromFile(context, path)
                val fileName = getFileNameFromUri(path.toUri(), context)
                // ✅ If successful, set state to Loaded
                _modelLoadingState.value = ModelLoadingState.Loaded(fileName)
            } catch (e: Exception) {
                log("Model loading failed: ${e.message}")
                // ❌ If failed, set state to Error
                _modelLoadingState.value = ModelLoadingState.Error(e.localizedMessage ?: "An unknown error occurred")
            }
        }
    }

    fun onNewModelSelected(uri: String?) {
        _savedModelPath.value = uri
    }

    fun log(message: String) {
        Log.e("ModelLoad", message)
    }

    fun checkInitialSavedModel(context: Context) {
        val savedUriString = getLocalModelPath()
        if (savedUriString != null) {
            _savedModelPath.value = savedUriString
        }
        // When the app starts, we don't know if a model is in memory,
        // so we start at an Idle state. The user must click "Load".
        _modelLoadingState.value = ModelLoadingState.Idle
    }

    // Helper to get filename from a URI, useful for display
    private fun getFileNameFromUri(uri: Uri, context: Context): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (colIndex >= 0) {
                        result = cursor.getString(colIndex)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result ?: "Unknown File"
    }
}