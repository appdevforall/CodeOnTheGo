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
import com.itsaky.androidide.agent.repository.LlmInferenceEngineProvider
import com.itsaky.androidide.agent.repository.PREF_KEY_AI_BACKEND
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_PATH
import com.itsaky.androidide.app.BaseApplication
import kotlinx.coroutines.launch

// State for the model file itself
sealed class ModelLoadingState {
    object Idle : ModelLoadingState()
    object Loading : ModelLoadingState()
    data class Loaded(val modelName: String) : ModelLoadingState()
    data class Error(val message: String) : ModelLoadingState()
}

sealed class EngineState {
    object Uninitialized : EngineState()
    object Initializing : EngineState()
    object Initialized : EngineState()
    data class Error(val message: String) : EngineState()
}

class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {
    // Keep this as is, it correctly gets the singleton instance
    private val llmInferenceEngine: LlmInferenceEngine = LlmInferenceEngineProvider.instance

    // --- State LiveData ---
    private val _savedModelPath = MutableLiveData<String?>(null)
    val savedModelPath: LiveData<String?> get() = _savedModelPath

    private val _modelLoadingState = MutableLiveData<ModelLoadingState>()
    val modelLoadingState: LiveData<ModelLoadingState> get() = _modelLoadingState

    // NEW: LiveData to track if the engine library is ready
    private val _engineState = MutableLiveData<EngineState>(EngineState.Uninitialized)
    val engineState: LiveData<EngineState> get() = _engineState

    // --- Initialization ---
    init {
        initializeLlmEngine()
        checkInitialSavedModel()
    }

    /**
     * Attempts to initialize the inference engine. This should be called once.
     */
    private fun initializeLlmEngine() {
        // Prevent re-initialization if already done or in progress
        if (_engineState.value is EngineState.Initializing || _engineState.value is EngineState.Initialized) {
            return
        }

        viewModelScope.launch {
            _engineState.value = EngineState.Initializing
            val success = llmInferenceEngine.initialize(getApplication())
            if (success) {
                _engineState.value = EngineState.Initialized
                Log.d("AiSettingsViewModel", "LLM Inference Engine initialized successfully.")
            } else {
                _engineState.value = EngineState.Error("Failed to load inference library. Please ensure it's installed.")
                Log.e("AiSettingsViewModel", "LLM Inference Engine initialization failed.")
            }
        }
    }

    /**
     * Loads a model from a given URI string.
     * This function now requires the engine to be initialized first.
     */
    fun loadModelFromUri(path: String, context: Context) {
        // Guard clause: Don't proceed if the engine isn't ready.
        if (_engineState.value !is EngineState.Initialized) {
            _modelLoadingState.value = ModelLoadingState.Error("Inference engine not ready.")
            Log.e("ModelLoad", "Attempted to load model, but engine is not initialized.")
            return
        }

        viewModelScope.launch {
            _modelLoadingState.value = ModelLoadingState.Loading
            val success = llmInferenceEngine.initModelFromFile(context, path)
            if (success && llmInferenceEngine.loadedModelName != null) {
                _modelLoadingState.value = ModelLoadingState.Loaded(llmInferenceEngine.loadedModelName!!)
                // Also save the path on successful load
                saveLocalModelPath(path)
            } else {
                _modelLoadingState.value = ModelLoadingState.Error("Failed to load model file.")
            }
        }
    }

    /**
     * Checks the initial state of the engine and saved model path when the screen loads.
     */
    fun checkInitialSavedModel() {
        if (llmInferenceEngine.isModelLoaded && llmInferenceEngine.loadedModelName != null) {
            _modelLoadingState.value = ModelLoadingState.Loaded(llmInferenceEngine.loadedModelName!!)
        } else {
            _modelLoadingState.value = ModelLoadingState.Idle
        }
        _savedModelPath.value = getLocalModelPath()
    }

    // --- Preference and Key Management (No changes needed here) ---

    fun getAvailableBackends(): List<AiBackend> = AiBackend.entries

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
        _savedModelPath.value = uriString
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

    fun getGeminiApiKeySaveTimestamp(): Long {
        return EncryptedPrefs.getGeminiApiKeySaveTimestamp(getApplication())
    }

    fun clearGeminiApiKey() {
        EncryptedPrefs.clearGeminiApiKey(getApplication())
    }
}