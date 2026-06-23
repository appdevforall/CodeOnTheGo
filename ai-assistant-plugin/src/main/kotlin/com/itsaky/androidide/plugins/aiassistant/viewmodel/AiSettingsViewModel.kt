package com.itsaky.androidide.plugins.aiassistant.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * State for the model file loading.
 */
sealed class ModelLoadingState {
    object Idle : ModelLoadingState()
    object Loading : ModelLoadingState()
    data class Loaded(val modelName: String) : ModelLoadingState()
    data class Error(val message: String) : ModelLoadingState()
}

/**
 * State for the inference engine initialization.
 */
sealed class EngineState {
    object Uninitialized : EngineState()
    object Initializing : EngineState()
    object Initialized : EngineState()
    data class Error(val message: String) : EngineState()
}

/**
 * Available AI backends.
 */
enum class AiBackend(val displayName: String) {
    LOCAL_LLM("Local LLM"),
    GEMINI("Gemini API")
}

class AiSettingsViewModel(
    private val getContext: () -> com.itsaky.androidide.plugins.PluginContext?
) : ViewModel() {

    private val _savedModelPath = MutableLiveData<String?>(null)
    val savedModelPath: LiveData<String?> get() = _savedModelPath

    private val _modelLoadingState = MutableLiveData<ModelLoadingState>(ModelLoadingState.Idle)
    val modelLoadingState: LiveData<ModelLoadingState> get() = _modelLoadingState

    private val _engineState = MutableLiveData<EngineState>(EngineState.Initialized)
    val engineState: LiveData<EngineState> get() = _engineState

    init {
        checkInitialState()
    }

    private fun checkInitialState() {
        val prefs = getPluginPrefs()
        _savedModelPath.value = prefs?.getString("local_llm_model_path", null)

        // For plugin, engine is always "ready" since it's managed by ai-core plugin
        _engineState.value = EngineState.Initialized
        _modelLoadingState.value = ModelLoadingState.Idle
    }

    private fun getPluginPrefs() = getContext()?.getPluginSharedPreferences("AgentSettings")

    fun getAvailableBackends(): List<AiBackend> = AiBackend.entries

    fun saveBackend(backend: AiBackend) {
        getPluginPrefs()?.edit()?.apply {
            putString("ai_backend_preference", backend.name)
            apply()
        }
    }

    fun getCurrentBackend(): AiBackend {
        val backendName = getPluginPrefs()?.getString("ai_backend_preference", "LOCAL_LLM")
        return try {
            AiBackend.valueOf(backendName ?: "LOCAL_LLM")
        } catch (e: Exception) {
            AiBackend.LOCAL_LLM
        }
    }

    fun saveLocalModelPath(path: String) {
        getPluginPrefs()?.edit()?.apply {
            putString("local_llm_model_path", path)
            apply()
        }
        // Use postValue instead of value since this can be called from background threads
        _savedModelPath.postValue(path)
    }

    fun getLocalModelPath(): String? {
        return getPluginPrefs()?.getString("local_llm_model_path", null)
    }

    fun saveLocalModelSha256(hash: String?) {
        getPluginPrefs()?.edit()?.apply {
            putString("local_llm_model_sha256", hash?.trim() ?: "")
            apply()
        }
    }

    fun getLocalModelSha256(): String? {
        return getPluginPrefs()?.getString("local_llm_model_sha256", null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setUseSimpleLocalPrompt(enabled: Boolean) {
        getPluginPrefs()?.edit()?.apply {
            putBoolean("use_simple_local_prompt", enabled)
            apply()
        }
    }

    fun isUseSimpleLocalPromptEnabled(): Boolean {
        return getPluginPrefs()?.getBoolean("use_simple_local_prompt", true) ?: true
    }

    fun saveGeminiApiKey(apiKey: String) {
        getPluginPrefs()?.edit()?.apply {
            putString("gemini_api_key", apiKey)
            putLong("gemini_api_key_timestamp", System.currentTimeMillis())
            apply()
        }
    }

    fun getGeminiApiKey(): String? {
        return getPluginPrefs()?.getString("gemini_api_key", null)
    }

    fun getGeminiApiKeySaveTimestamp(): Long {
        return getPluginPrefs()?.getLong("gemini_api_key_timestamp", 0L) ?: 0L
    }

    fun clearGeminiApiKey() {
        getPluginPrefs()?.edit()?.apply {
            remove("gemini_api_key")
            remove("gemini_api_key_timestamp")
            apply()
        }
    }

    /**
     * Load a model from URI.
     * In the plugin context, we just save the path - the actual loading
     * is handled by the ai-core plugin's LocalLlmBackend.
     */
    fun loadModelFromUri(uriString: String, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _modelLoadingState.postValue(ModelLoadingState.Loading)

            try {
                // Extract filename from URI for display
                val fileName = uriString.substringAfterLast("/")

                // Save the path
                saveLocalModelPath(uriString)

                // In plugin context, we don't directly load the model
                // The ai-core plugin will load it when needed
                _modelLoadingState.postValue(
                    ModelLoadingState.Loaded(fileName)
                )

                android.util.Log.d("AiSettingsViewModel", "Model path saved: $uriString")
            } catch (e: Exception) {
                android.util.Log.e("AiSettingsViewModel", "Error saving model path", e)
                _modelLoadingState.postValue(
                    ModelLoadingState.Error("Failed to save model path: ${e.message}")
                )
            }
        }
    }
}
