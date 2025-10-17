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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.viewmodel.AiSettingsViewModel
import com.itsaky.androidide.agent.viewmodel.ModelLoadingState
import com.itsaky.androidide.databinding.FragmentAiSettingsBinding
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.utils.getFileName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


const val SAVED_MODEL_URI_KEY = "saved_model_uri"
private const val PREFS_NAME = "LlamaPrefs"

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
                // The fragment's only job is to save the path via the ViewModel.
                viewModel.saveLocalModelPath(uriString)
                viewModel.loadModelFromUri(uriString, requireContext())
                // It also updates its own UI.
                updateLocalLlmUi(binding.backendSpecificSettingsContainer)
                flashInfo("Attempting to load selected model...")
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAiSettingsBinding.bind(view)

        setupToolbar()
        setupBackendSelector()
    }

    private fun setupToolbar() {
        binding.settingsToolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupBackendSelector() {
        val backends = viewModel.getAvailableBackends()
        val backendNames = backends.map { it.name }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            backendNames
        )
        binding.backendAutocomplete.setAdapter(adapter)

        val currentBackend = viewModel.getCurrentBackend()
        binding.backendAutocomplete.setText(currentBackend.name, false)
        updateBackendSpecificUi(currentBackend)

        binding.backendAutocomplete.setOnItemClickListener { _, _, position, _ ->
            val selectedBackend = backends[position]
            // Its only job is to save the backend selection.
            viewModel.saveBackend(selectedBackend)
            updateBackendSpecificUi(selectedBackend)
        }
    }

    private fun updateBackendSpecificUi(backend: AiBackend) {
        val container = binding.backendSpecificSettingsContainer
        container.removeAllViews()

        when (backend) {
            AiBackend.LOCAL_LLM -> {
                val localLlmView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.layout_settings_local_llm, container, true)
                updateLocalLlmUi(localLlmView)
            }

            AiBackend.GEMINI -> {
                val geminiApiView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.layout_settings_gemini_api, container, true)
                setupGeminiApiUi(geminiApiView)
            }
        }
    }

    private fun updateLocalLlmUi(view: View) {
        val modelPathTextView = view.findViewById<TextView>(R.id.selected_model_path)
        val browseButton = view.findViewById<Button>(R.id.btn_browse_model)
        val loadSavedButton = view.findViewById<Button>(R.id.loadSavedButton)
        val modelStatusTextView = view.findViewById<TextView>(R.id.model_status_text_view)

        viewModel.checkInitialSavedModel(requireContext())

        browseButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        loadSavedButton.setOnClickListener { loadFromSaved() }

        viewModel.savedModelPath.observe(viewLifecycleOwner) { uri ->
            if (uri != null) {
                loadSavedButton.isEnabled = true
                modelPathTextView.visibility = View.VISIBLE
                context?.let {
                    modelPathTextView.text =
                        getString(R.string.ai_setting_saved, uri.toUri().getFileName(it))
                }

            } else {
                loadSavedButton.isEnabled = false
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
                viewModel.log("Permission for saved model lost. Please select it again.")
                requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    remove(SAVED_MODEL_URI_KEY)
                }
                viewModel.onNewModelSelected(null)
            }
        } else {
            viewModel.log("No saved model found.")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @SuppressLint("SetTextI18n")
    private fun setupGeminiApiUi(view: View) {
        val modelLayout = view.findViewById<TextInputLayout>(R.id.gemini_model_layout)
        val modelInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.gemini_model_input)
        val apiKeyLayout = view.findViewById<TextInputLayout>(R.id.gemini_api_key_layout)
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.gemini_api_key_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)
        val editButton = view.findViewById<Button>(R.id.btn_edit_api_key)
        val clearButton = view.findViewById<Button>(R.id.btn_clear_api_key)
        val statusTextView = view.findViewById<TextView>(R.id.gemini_api_key_status_text)

        val geminiModels = viewModel.getAvailableGeminiModels()
        if (geminiModels.isNotEmpty()) {
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                geminiModels
            )
            modelInput.setAdapter(adapter)
            val currentModel = viewModel.getCurrentGeminiModel()
            val selectedModel = if (geminiModels.contains(currentModel)) {
                currentModel
            } else {
                geminiModels.first().also { viewModel.saveGeminiModel(it) }
            }
            modelInput.setText(selectedModel, false)
            modelInput.setOnItemClickListener { _, _, position, _ ->
                viewModel.saveGeminiModel(geminiModels[position])
            }
        } else {
            modelLayout.visibility = View.GONE
        }

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
