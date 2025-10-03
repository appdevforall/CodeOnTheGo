package com.itsaky.androidide.agent.fragments

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.R
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.viewmodel.AiSettingsViewModel
import com.itsaky.androidide.databinding.FragmentAiSettingsBinding
import com.itsaky.androidide.utils.flashInfo
import androidx.core.content.edit
import androidx.core.net.toUri
import com.itsaky.androidide.agent.viewmodel.ModelLoadingState


const val SAVED_MODEL_URI_KEY = "saved_model_uri"
private const val PREFS_NAME = "LlamaPrefs"

class AiSettingsFragment : Fragment(R.layout.fragment_ai_settings) {

    private var _binding: FragmentAiSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AiSettingsViewModel by viewModels()

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

            val uriString = it.toString()
            // The fragment's only job is to save the path via the ViewModel.
            viewModel.saveLocalModelPath(uriString)
            viewModel.loadModelFromUri(uriString, requireContext())
            // It also updates its own UI.
            updateLocalLlmUi(binding.backendSpecificSettingsContainer)
            flashInfo("Local model path saved.")
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
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, backendNames)
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
        // ✨ Get a reference to our new status TextView ✨
        val modelStatusTextView = view.findViewById<TextView>(R.id.model_status_text_view)

        viewModel.checkInitialSavedModel(requireContext())

        browseButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        loadSavedButton.setOnClickListener { loadFromSaved() }

        viewModel.savedModelPath.observe(viewLifecycleOwner) { uri ->
            if (uri != null) {
                // A model is saved
                loadSavedButton.isEnabled = true
                modelPathTextView.visibility = View.VISIBLE
                modelPathTextView.text = "💾 Saved: ${getFileNameFromUri(uri.toUri())}"

            } else {
                // No model is saved
                loadSavedButton.isEnabled = false
                modelPathTextView.visibility = View.GONE
            }
        }

        viewModel.modelLoadingState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ModelLoadingState.Idle -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = "🤷‍♀️ No model is currently loaded."
                }
                is ModelLoadingState.Loading -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = "🔄 Loading model, please wait..."
                }
                is ModelLoadingState.Loaded -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = "✅ Model loaded: ${state.modelName}"
                }
                is ModelLoadingState.Error -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = "❌ Error loading model: ${state.message}"
                }
            }
        }
    }

    private fun loadFromSaved() {
        // Now, this function can rely on the ViewModel's state
        val savedUri = viewModel.savedModelPath.value
        if (savedUri != null) {
            val hasPermission = requireActivity().contentResolver.persistedUriPermissions.any {
                it.uri == savedUri.toUri() && it.isReadPermission
            }
            if (hasPermission) {
                viewModel.loadModelFromUri(savedUri, requireContext())
            } else {
                viewModel.log("Permission for saved model lost. Please select it again.")
                // Clear the invalid preference and update the ViewModel
                requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    remove(SAVED_MODEL_URI_KEY)
                }
                viewModel.onNewModelSelected(null) // This will disable the button
            }
        } else {
            // This case should ideally not happen since the button would be disabled,
            // but it's good practice to handle it.
            viewModel.log("No saved model found.")
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = requireActivity().contentResolver.query(uri, null, null, null, null)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupGeminiApiUi(view: View) {
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.gemini_api_key_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)

        if (!viewModel.getGeminiApiKey().isNullOrBlank()) {
            apiKeyInput.setText("••••••••••••••••••••")
        }

        saveButton.setOnClickListener {
            val apiKey = apiKeyInput.text.toString()
            if (apiKey.isNotBlank() && apiKey != "••••••••••••••••••••") {
                viewModel.saveGeminiApiKey(apiKey)
                flashInfo("API Key saved securely.")
                apiKeyInput.setText("••••••••••••••••••••")
            } else if (apiKey.isBlank()) {
                flashInfo("API Key cannot be empty.")
            } else {
                flashInfo("Please enter a new API Key to save.")
            }
        }
    }
}