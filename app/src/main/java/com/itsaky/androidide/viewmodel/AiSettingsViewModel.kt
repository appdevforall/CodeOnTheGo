package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.data.repository.AiBackend
import com.itsaky.androidide.data.repository.PREF_KEY_AI_BACKEND
import com.itsaky.androidide.data.repository.PREF_KEY_LOCAL_MODEL_PATH

class AiSettingsViewModel : ViewModel() {

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
}