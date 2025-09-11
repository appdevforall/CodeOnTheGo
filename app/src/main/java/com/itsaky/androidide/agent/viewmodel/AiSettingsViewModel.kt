package com.itsaky.androidide.agent.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.itsaky.androidide.agent.fragments.EncryptedPrefs
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.repository.PREF_KEY_AI_BACKEND
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_PATH
import com.itsaky.androidide.app.BaseApplication

class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {

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
}