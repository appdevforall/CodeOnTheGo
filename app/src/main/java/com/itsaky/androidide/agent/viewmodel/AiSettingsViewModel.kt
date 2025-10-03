package com.itsaky.androidide.agent.viewmodel

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
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

class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _savedModelPath = MutableLiveData<String?>(null)
    val savedModelPath: LiveData<String?> get() = _savedModelPath

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
            LlmInferenceEngine.initModelFromFile(context, path)
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
    }
}