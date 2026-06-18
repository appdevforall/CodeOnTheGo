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
import com.itsaky.androidide.agent.repository.ModelPurpose
import com.itsaky.androidide.agent.repository.ModelPurpose.Companion.getPreferenceKey
import com.itsaky.androidide.agent.repository.ModelPurpose.Companion.getSha256PreferenceKey
import com.itsaky.androidide.agent.repository.PREF_KEY_AI_BACKEND
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_PATH
import com.itsaky.androidide.agent.repository.PREF_KEY_LOCAL_MODEL_SHA256
import com.itsaky.androidide.agent.repository.PREF_KEY_USE_SIMPLE_LOCAL_PROMPT
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
    private var pendingModelUri: String? = null

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

                pendingModelUri?.let { queuedPath ->
                    loadModelFromUri(queuedPath, getApplication())
                    pendingModelUri = null
                }

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
        val currentState = _engineState.value

        if (currentState is EngineState.Uninitialized || currentState is EngineState.Initializing) {
            pendingModelUri = path
            _modelLoadingState.value = ModelLoadingState.Loading
            return
        }

        // Guard clause: Don't proceed if the engine isn't ready.
        if (currentState !is EngineState.Initialized) {
            _modelLoadingState.value = ModelLoadingState.Error("Inference engine not ready.")
            Log.e("ModelLoad", "Attempted to load model, but engine is not initialized.")
            return
        }

        viewModelScope.launch {
            _modelLoadingState.value = ModelLoadingState.Loading
            val expectedHash = getLocalModelSha256()
            val success = llmInferenceEngine.initModelFromFile(context, path, expectedHash)
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

    fun saveBackend(backend: AiBackend) {
        val prefs = BaseApplication.baseInstance.prefManager
        prefs.putString(PREF_KEY_AI_BACKEND, backend.name)
    }

    fun saveLocalModelPath(uriString: String) {
        val prefs = BaseApplication.baseInstance.prefManager
        prefs.putString(PREF_KEY_LOCAL_MODEL_PATH, uriString)
        _savedModelPath.value = uriString
    }

    fun getLocalModelPath(): String? {
        val prefs = BaseApplication.baseInstance.prefManager
        return prefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
    }

    fun saveLocalModelSha256(hash: String?) {
        val prefs = BaseApplication.baseInstance.prefManager
        val normalized = hash?.trim().orEmpty()
        prefs.putString(PREF_KEY_LOCAL_MODEL_SHA256, normalized)
    }

    fun getLocalModelSha256(): String? {
        val prefs = BaseApplication.baseInstance.prefManager
        val value = prefs.getString(PREF_KEY_LOCAL_MODEL_SHA256, null)
        return value?.trim().takeIf { !it.isNullOrBlank() }
    }

    fun setUseSimpleLocalPrompt(enabled: Boolean) {
        val prefs = BaseApplication.baseInstance.prefManager
        prefs.putBoolean(PREF_KEY_USE_SIMPLE_LOCAL_PROMPT, enabled)
    }

    fun isUseSimpleLocalPromptEnabled(): Boolean {
        val prefs = BaseApplication.baseInstance.prefManager
        return prefs.getBoolean(PREF_KEY_USE_SIMPLE_LOCAL_PROMPT, true)
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

    // --- Multi-Model Support ---

    /**
     * State for models organized by purpose
     */
    data class ModelPurposeState(
        val purpose: ModelPurpose,
        val savedPath: String? = null,
        val loadingState: ModelLoadingState = ModelLoadingState.Idle
    )

    private val _modelStates = MutableLiveData<Map<ModelPurpose, ModelPurposeState>>(emptyMap())
    val modelStates: LiveData<Map<ModelPurpose, ModelPurposeState>> get() = _modelStates

    /**
     * Get available model purposes (currently showing only CHAT and EMBEDDINGS)
     */
    fun getAvailableModelPurposes(): List<ModelPurpose> {
        return listOf(
            ModelPurpose.CHAT,
            ModelPurpose.EMBEDDINGS,
            ModelPurpose.SPEECH_TO_TEXT
            // Will add CODE_COMPLETION when implemented
        )
    }

    /**
     * Load initial model states for all purposes
     */
    fun loadModelPurposeStates() {
        val prefs = BaseApplication.baseInstance.prefManager
        val states = mutableMapOf<ModelPurpose, ModelPurposeState>()

        for (purpose in getAvailableModelPurposes()) {
            val savedPath = prefs.getString(purpose.getPreferenceKey(), null)
            states[purpose] = ModelPurposeState(
                purpose = purpose,
                savedPath = savedPath,
                loadingState = ModelLoadingState.Idle
            )
        }

        _modelStates.value = states
    }

    /**
     * Save model path for a specific purpose
     */
    fun saveModelPath(purpose: ModelPurpose, uriString: String) {
        val prefs = BaseApplication.baseInstance.prefManager
        prefs.putString(purpose.getPreferenceKey(), uriString)

        // Update state
        val currentStates = _modelStates.value.orEmpty().toMutableMap()
        currentStates[purpose] = currentStates[purpose]?.copy(savedPath = uriString)
            ?: ModelPurposeState(purpose, savedPath = uriString)
        _modelStates.value = currentStates
    }

    /**
     * Get saved model path for a specific purpose
     */
    fun getModelPath(purpose: ModelPurpose): String? {
        val prefs = BaseApplication.baseInstance.prefManager
        return prefs.getString(purpose.getPreferenceKey(), null)
    }

    /**
     * Save SHA-256 for a model purpose
     */
    fun saveModelSha256(purpose: ModelPurpose, hash: String?) {
        val prefs = BaseApplication.baseInstance.prefManager
        val normalized = hash?.trim().orEmpty()
        prefs.putString(purpose.getSha256PreferenceKey(), normalized)
    }

    /**
     * Get SHA-256 for a model purpose
     */
    fun getModelSha256(purpose: ModelPurpose): String? {
        val prefs = BaseApplication.baseInstance.prefManager
        val value = prefs.getString(purpose.getSha256PreferenceKey(), null)
        return value?.trim().takeIf { !it.isNullOrBlank() }
    }

    /**
     * Load a model for a specific purpose
     */
    fun loadModelForPurpose(purpose: ModelPurpose, path: String, context: Context) {
        val currentState = _engineState.value

        if (currentState !is EngineState.Initialized) {
            updateModelPurposeState(
                purpose,
                ModelLoadingState.Error("Engine not initialized")
            )
            return
        }

        viewModelScope.launch {
            updateModelPurposeState(purpose, ModelLoadingState.Loading)

            val expectedHash = getModelSha256(purpose)
            val success = llmInferenceEngine.initModelFromFile(context, path, expectedHash)

            if (success && llmInferenceEngine.loadedModelName != null) {
                updateModelPurposeState(
                    purpose,
                    ModelLoadingState.Loaded(llmInferenceEngine.loadedModelName!!)
                )
                saveModelPath(purpose, path)
            } else {
                updateModelPurposeState(
                    purpose,
                    ModelLoadingState.Error("Failed to load model")
                )
            }
        }
    }

    /**
     * Update the loading state for a specific model purpose
     */
    private fun updateModelPurposeState(purpose: ModelPurpose, state: ModelLoadingState) {
        val currentStates = _modelStates.value.orEmpty().toMutableMap()
        val existing = currentStates[purpose] ?: ModelPurposeState(purpose)
        currentStates[purpose] = existing.copy(loadingState = state)
        _modelStates.value = currentStates
    }
}
