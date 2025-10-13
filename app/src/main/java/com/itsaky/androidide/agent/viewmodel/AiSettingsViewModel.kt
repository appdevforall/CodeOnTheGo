package com.itsaky.androidide.agent.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
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

    private val _modelLoadingState = MutableLiveData<ModelLoadingState>()
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
            _modelLoadingState.value = ModelLoadingState.Loading
            val success = LlmInferenceEngine.initModelFromFile(context, path)
            if (success && LlmInferenceEngine.loadedModelName != null) {
                _modelLoadingState.value =
                    ModelLoadingState.Loaded(LlmInferenceEngine.loadedModelName!!)
            } else {
                _modelLoadingState.value = ModelLoadingState.Error("Failed to load model")
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
        if (LlmInferenceEngine.isModelLoaded && LlmInferenceEngine.loadedModelName != null) {
            _modelLoadingState.value =
                ModelLoadingState.Loaded(LlmInferenceEngine.loadedModelName!!)
        } else {
            _modelLoadingState.value = ModelLoadingState.Idle
        }
        val savedUriString = getLocalModelPath()
        _savedModelPath.value = savedUriString
    }

    fun getGeminiApiKeySaveTimestamp(): Long {
        return EncryptedPrefs.getGeminiApiKeySaveTimestamp(getApplication())
    }

    fun clearGeminiApiKey() {
        EncryptedPrefs.clearGeminiApiKey(getApplication())
    }
}