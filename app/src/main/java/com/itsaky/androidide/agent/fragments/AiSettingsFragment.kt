package com.itsaky.androidide.agent.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.materialswitch.MaterialSwitch
import com.itsaky.androidide.R
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.repository.ModelPurpose
import com.itsaky.androidide.agent.repository.Util.getCurrentBackend
import com.itsaky.androidide.agent.viewmodel.AiSettingsViewModel
import com.itsaky.androidide.agent.viewmodel.EngineState
import com.itsaky.androidide.agent.viewmodel.ModelLoadingState
import com.itsaky.androidide.databinding.FragmentAiSettingsBinding
import com.itsaky.androidide.speech.VoicePreferences
import com.itsaky.androidide.ui.voice.VoiceLanguageDialog
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.utils.getFileName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


const val SAVED_MODEL_URI_KEY = "saved_model_uri"

class AiSettingsFragment : Fragment(R.layout.fragment_ai_settings) {

    private var _binding: FragmentAiSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AiSettingsViewModel by viewModels()

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

                val uriString = it.toString()

                // If browsing for a specific purpose, load for that purpose
                currentBrowsingPurpose?.let { purpose ->
                    viewModel.loadModelForPurpose(purpose, uriString, requireContext())
                    flashInfo("Loading ${purpose.displayName}...")
                    currentBrowsingPurpose = null
                } ?: run {
                    // Fallback to legacy single model loading
                    viewModel.loadModelFromUri(uriString, requireContext())
                    flashInfo("Attempting to load selected model...")
                }
            }
        }

    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

                val uriString = it.toString()

                // Save directory path for SPEECH_TO_TEXT purpose
                currentBrowsingPurpose?.let { purpose ->
                    viewModel.saveModelPath(purpose, uriString)
                    flashInfo("${purpose.displayName} directory saved: ${it.lastPathSegment}")
                    currentBrowsingPurpose = null
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAiSettingsBinding.bind(view)

        setupToolbar()
        setupSpeechToCode()
        setupBackendSelector()
    }

    private fun setupToolbar() {
        binding.settingsToolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupSpeechToCode() {
        // Setup voice code enabled switch
        val voiceEnabled = VoicePreferences.isVoiceCodeEnabled(requireContext())
        binding.voiceCodeEnabledSwitch.isChecked = voiceEnabled

        binding.voiceCodeEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            VoicePreferences.setVoiceCodeEnabled(requireContext(), isChecked)
            updateSttModeAvailability(isChecked)

            // Notify editor activities to refresh their toolbar
            requireActivity().sendBroadcast(
                android.content.Intent("com.itsaky.androidide.VOICE_CODE_SETTING_CHANGED")
            )
        }

        val sttMode = VoicePreferences.getSttMode(requireContext())
        selectSttMode(sttMode, persistSelection = false)

        binding.cloudSttCard.setOnClickListener {
            selectSttMode(VoicePreferences.STT_MODE_CLOUD)
        }
        binding.offlineSttCard.setOnClickListener {
            selectSttMode(VoicePreferences.STT_MODE_MOONSHINE)
        }

        // Setup voice language button
        binding.voiceLanguageButton.setOnClickListener {
            VoiceLanguageDialog.show(requireContext()) { newLanguage ->
                flashInfo("Voice language: ${VoicePreferences.getLanguageDisplayName(requireContext(), newLanguage)}")
            }
        }

        // Initial state
        updateSttModeAvailability(voiceEnabled)
    }

    private fun updateSttModeAvailability(enabled: Boolean) {
        binding.sttModeContainer.alpha = if (enabled) 1.0f else 0.5f
        binding.cloudSttCard.isEnabled = enabled
        binding.offlineSttCard.isEnabled = enabled
        binding.voiceLanguageButton.isEnabled = enabled
    }

    private fun selectSttMode(mode: String, persistSelection: Boolean = true) {
        val cloudSelected = mode == VoicePreferences.STT_MODE_CLOUD

        updateSelectionCard(binding.cloudSttCard, cloudSelected)
        updateSelectionCard(binding.offlineSttCard, !cloudSelected)
        binding.cloudSttRadio.isChecked = cloudSelected
        binding.offlineSttRadio.isChecked = !cloudSelected

        if (persistSelection) {
            VoicePreferences.setSttMode(requireContext(), mode)
        }
    }

    private fun setupBackendSelector() {
        val currentBackend = getCurrentBackend()

        selectBackend(currentBackend, persistSelection = false)

        binding.geminiBackendCard.setOnClickListener {
            selectBackend(AiBackend.GEMINI)
        }
        binding.localLlmBackendCard.setOnClickListener {
            selectBackend(AiBackend.LOCAL_LLM)
        }
    }

    private fun selectBackend(backend: AiBackend, persistSelection: Boolean = true) {
        updateSelectionCard(
            card = binding.geminiBackendCard,
            selected = backend == AiBackend.GEMINI
        )
        updateSelectionCard(
            card = binding.localLlmBackendCard,
            selected = backend == AiBackend.LOCAL_LLM
        )
        binding.geminiBackendRadio.isChecked = backend == AiBackend.GEMINI
        binding.localLlmBackendRadio.isChecked = backend == AiBackend.LOCAL_LLM

        if (persistSelection) {
            viewModel.saveBackend(backend)
        }
        updateBackendSpecificUi(backend)
    }

    private fun updateSelectionCard(card: MaterialCardView, selected: Boolean) {
        card.strokeWidth = resources.getDimensionPixelSize(
            if (selected) R.dimen.ai_backend_selected_stroke_width
            else R.dimen.ai_backend_default_stroke_width
        )
        card.setStrokeColor(
            MaterialColors.getColor(
                card,
                if (selected) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutline
            )
        )
    }

    private fun updateBackendSpecificUi(backend: AiBackend) {
        val container = binding.backendSpecificSettingsContainer
        container.removeAllViews()

        when (backend) {
            AiBackend.LOCAL_LLM -> {
                val localLlmView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.layout_settings_local_llm_multi, container, true)
                setupMultiModelUi(localLlmView)
            }
            AiBackend.GEMINI -> {
                val geminiApiView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.layout_settings_gemini_api, container, true)
                setupGeminiApiUi(geminiApiView)
            }
        }
    }

    /**
     * Setup UI for managing multiple models by purpose
     */
    private fun setupMultiModelUi(view: View) {
        val engineStatusTextView = view.findViewById<TextView>(R.id.engine_status_text)
        val modelPurposesContainer = view.findViewById<android.widget.LinearLayout>(R.id.model_purposes_container)
        val simplePromptSwitch = view.findViewById<MaterialSwitch>(R.id.switch_simple_local_prompt)

        // Setup simple prompt switch
        simplePromptSwitch?.apply {
            isChecked = viewModel.isUseSimpleLocalPromptEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                viewModel.setUseSimpleLocalPrompt(isChecked)
            }
        }

        // Observe engine state
        viewModel.engineState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is EngineState.Initializing, EngineState.Uninitialized -> {
                    engineStatusTextView.text = getString(R.string.ai_setting_initializing_engine)
                }
                is EngineState.Initialized -> {
                    engineStatusTextView.text = getString(R.string.ai_setting_engine_ready)
                }
                is EngineState.Error -> {
                    engineStatusTextView.text = state.message
                }
            }
        }

        // Load model states
        viewModel.loadModelPurposeStates()

        // Create cards for each model purpose
        for (purpose in viewModel.getAvailableModelPurposes()) {
            val purposeCard = createModelPurposeCard(purpose, modelPurposesContainer)
            modelPurposesContainer.addView(purposeCard)
        }

        // Observe model states
        viewModel.modelStates.observe(viewLifecycleOwner) { states ->
            updateModelPurposeCards(modelPurposesContainer, states)
        }
    }

    /**
     * Create a card for a specific model purpose
     */
    private fun createModelPurposeCard(
        purpose: ModelPurpose,
        container: android.view.ViewGroup
    ): View {
        val inflater = LayoutInflater.from(requireContext())
        val card = inflater.inflate(R.layout.layout_model_purpose_item, container, false)

        // Set purpose info
        card.findViewById<TextView>(R.id.purpose_title).text = purpose.displayName
        card.findViewById<TextView>(R.id.purpose_description).text = purpose.description
        card.tag = purpose.name // Tag for finding the card later

        // Setup browse button
        card.findViewById<Button>(R.id.btn_browse).setOnClickListener {
            currentBrowsingPurpose = purpose

            // Use directory picker for SPEECH_TO_TEXT, file picker for others
            when (purpose) {
                ModelPurpose.SPEECH_TO_TEXT -> {
                    directoryPickerLauncher.launch(null)
                }
                else -> {
                    filePickerLauncher.launch(arrayOf("*/*"))
                }
            }
        }

        // Setup load saved button
        card.findViewById<Button>(R.id.btn_load_saved).setOnClickListener {
            viewModel.getModelPath(purpose)?.let { path ->
                when (purpose) {
                    ModelPurpose.SPEECH_TO_TEXT -> {
                        // STT doesn't load into engine, just verify path exists
                        flashInfo("${purpose.displayName} path: ${Uri.parse(path).lastPathSegment}")
                    }
                    else -> {
                        viewModel.loadModelForPurpose(purpose, path, requireContext())
                        flashInfo("Loading ${purpose.displayName}...")
                    }
                }
            }
        }

        // Setup SHA input
        val shaInput = card.findViewById<TextInputEditText>(R.id.sha_input)
        shaInput.setText(viewModel.getModelSha256(purpose).orEmpty())
        shaInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.saveModelSha256(purpose, shaInput.text?.toString())
            }
        }

        return card
    }

    /**
     * Update all model purpose cards with current states
     */
    private fun updateModelPurposeCards(
        container: android.view.ViewGroup,
        states: Map<ModelPurpose, AiSettingsViewModel.ModelPurposeState>
    ) {
        for (i in 0 until container.childCount) {
            val card = container.getChildAt(i)
            val purposeName = card.tag as? String ?: continue
            val purpose = ModelPurpose.valueOf(purposeName)
            val state = states[purpose] ?: continue

            val modelStatus = card.findViewById<TextView>(R.id.model_status)
            val modelPath = card.findViewById<TextView>(R.id.selected_model_path)
            val loadSavedButton = card.findViewById<Button>(R.id.btn_load_saved)
            val browseButton = card.findViewById<Button>(R.id.btn_browse)

            // Update status text
            when (purpose) {
                ModelPurpose.SPEECH_TO_TEXT -> {
                    // STT doesn't load into engine, just show if path is configured
                    if (state.savedPath != null) {
                        modelStatus.text = "✅ Directory configured"
                    } else {
                        modelStatus.text = "No directory selected"
                    }
                }
                else -> {
                    when (state.loadingState) {
                        is ModelLoadingState.Idle -> {
                            modelStatus.text = "No model loaded"
                        }
                        is ModelLoadingState.Loading -> {
                            modelStatus.text = "Loading..."
                        }
                        is ModelLoadingState.Loaded -> {
                            modelStatus.text = "✅ ${state.loadingState.modelName}"
                        }
                        is ModelLoadingState.Error -> {
                            modelStatus.text = "❌ ${state.loadingState.message}"
                        }
                    }
                }
            }

            // Update saved path display
            if (state.savedPath != null) {
                modelPath.visibility = View.VISIBLE
                val fileName = state.savedPath.toUri().getFileName(requireContext())
                modelPath.text = "Saved: $fileName"
            } else {
                modelPath.visibility = View.GONE
            }

            // Update button states
            // Always enable browse button - browsing files doesn't need engine
            // Enable load button if there's a saved path - engine will initialize on-demand
            when (purpose) {
                ModelPurpose.SPEECH_TO_TEXT -> {
                    // STT doesn't need engine, just needs path selection
                    loadSavedButton.isEnabled = state.savedPath != null
                    browseButton.isEnabled = true
                }
                else -> {
                    // Other models: allow loading if path exists (engine init on-demand)
                    loadSavedButton.isEnabled = state.savedPath != null
                    browseButton.isEnabled = true
                }
            }
        }
    }

    // Track which purpose is currently being browsed
    private var currentBrowsingPurpose: ModelPurpose? = null

    private fun updateLocalLlmUi(view: View) {
        val modelPathTextView = view.findViewById<TextView>(R.id.selected_model_path)
        val browseButton = view.findViewById<Button>(R.id.btn_browse_model)
        val loadSavedButton = view.findViewById<Button>(R.id.loadSavedButton)
        val modelStatusTextView = view.findViewById<TextView>(R.id.model_status_text_view)
        val engineStatusTextView = view.findViewById<TextView>(R.id.engine_status_text) // <-- NEW: Get reference to the new TextView
        val simplePromptSwitch = view.findViewById<MaterialSwitch>(R.id.switch_simple_local_prompt)
        val shaInput = view.findViewById<TextInputEditText>(R.id.local_model_sha_input)

        browseButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        loadSavedButton.setOnClickListener { loadFromSaved() }

        shaInput?.apply {
            setText(viewModel.getLocalModelSha256().orEmpty())
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    viewModel.saveLocalModelSha256(text?.toString())
                }
            }
        }

        simplePromptSwitch?.apply {
            isChecked = viewModel.isUseSimpleLocalPromptEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                viewModel.setUseSimpleLocalPrompt(isChecked)
            }
        }
        viewModel.engineState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is EngineState.Initializing, EngineState.Uninitialized -> {
                    engineStatusTextView.text = getString(R.string.ai_setting_initializing_engine)
                    browseButton.isEnabled = false
                    loadSavedButton.isEnabled = viewModel.savedModelPath.value != null
                }
                is EngineState.Initialized -> {
                    engineStatusTextView.text = getString(R.string.ai_setting_engine_ready)
                    browseButton.isEnabled = true
                    loadSavedButton.isEnabled = viewModel.savedModelPath.value != null
                }
                is EngineState.Error -> {
                    engineStatusTextView.text = state.message
                    browseButton.isEnabled = false
                    loadSavedButton.isEnabled = false
                }
            }
        }

        viewModel.savedModelPath.observe(viewLifecycleOwner) { uri ->
            loadSavedButton.isEnabled = uri != null && viewModel.engineState.value is EngineState.Initialized

            if (uri != null) {
                modelPathTextView.visibility = View.VISIBLE
                context?.let {
                    modelPathTextView.text =
                        getString(R.string.ai_setting_saved, uri.toUri().getFileName(it))
                }
            } else {
                modelPathTextView.visibility = View.GONE
            }
        }

        viewModel.modelLoadingState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ModelLoadingState.Idle -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text =
                        getString(R.string.ai_setting_no_model_is_currently_loaded)
                }
                is ModelLoadingState.Loading -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text =
                        getString(R.string.ai_setting_loading_model_please_wait)
                }
                is ModelLoadingState.Loaded -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text =
                        getString(R.string.ai_setting_model_loaded, state.modelName)
                }
                is ModelLoadingState.Error -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text =
                        getString(R.string.ai_setting_error_loading_model, state.message)
                }
            }
        }
    }

    private fun loadFromSaved() {
        val savedUri = viewModel.savedModelPath.value
        if (savedUri != null) {
            val hasPermission = requireActivity().contentResolver.persistedUriPermissions.any {
                it.uri == savedUri.toUri() && it.isReadPermission
            }
            if (hasPermission) {
                viewModel.loadModelFromUri(savedUri, requireContext())
            } else {
                requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    remove(SAVED_MODEL_URI_KEY)
                }
                viewModel.saveLocalModelPath("")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @SuppressLint("SetTextI18n")
    private fun setupGeminiApiUi(view: View) {
        val apiKeyLayout = view.findViewById<TextInputLayout>(R.id.gemini_api_key_layout)
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.gemini_api_key_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)
        val editButton = view.findViewById<Button>(R.id.btn_edit_api_key)
        val clearButton = view.findViewById<Button>(R.id.btn_clear_api_key)
        val statusTextView = view.findViewById<TextView>(R.id.gemini_api_key_status_text)

        fun updateUiState(isEditing: Boolean) {
            if (isEditing) {
                statusTextView.visibility = View.GONE
                apiKeyLayout.visibility = View.VISIBLE
                saveButton.visibility = View.VISIBLE
                editButton.visibility = View.GONE
                clearButton.visibility = View.GONE
            } else {
                statusTextView.visibility = View.VISIBLE
                apiKeyLayout.visibility = View.GONE
                saveButton.visibility = View.GONE
                editButton.visibility = View.VISIBLE
                clearButton.visibility = View.VISIBLE
            }
        }

        val savedApiKey = viewModel.getGeminiApiKey()
        if (savedApiKey.isNullOrBlank()) {
            updateUiState(isEditing = true)
            apiKeyInput.setText("")
        } else {
            updateUiState(isEditing = false)
            val timestamp = viewModel.getGeminiApiKeySaveTimestamp()
            if (timestamp > 0) {
                val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                val savedDate = sdf.format(Date(timestamp))
                statusTextView.text = getString(R.string.gemini_api_key_saved_on, savedDate)
            } else {
                statusTextView.text = getString(R.string.api_key_is_saved)
            }
        }

        saveButton.setOnClickListener {
            val apiKey = apiKeyInput.text.toString()
            if (apiKey.isNotBlank()) {
                viewModel.saveGeminiApiKey(apiKey)
                flashInfo(getString(R.string.api_key_saved_securely))

                updateUiState(isEditing = false)
                val timestamp = viewModel.getGeminiApiKeySaveTimestamp()
                val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                val savedDate = sdf.format(Date(timestamp))
                statusTextView.text = getString(R.string.gemini_api_key_saved_on, savedDate)

            } else {
                flashInfo(getString(R.string.api_key_cannot_be_empty))
            }
        }

        editButton.setOnClickListener {
            updateUiState(isEditing = true)
            apiKeyInput.setText(getString(R.string.obfuscated_api_key))
            apiKeyInput.requestFocus()
        }

        clearButton.setOnClickListener {
            viewModel.clearGeminiApiKey()
            flashInfo(getString(R.string.api_key_cleared))
            updateUiState(isEditing = true)
            apiKeyInput.setText("")
        }
    }
}
