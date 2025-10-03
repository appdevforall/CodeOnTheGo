package com.itsaky.androidide.agent.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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


class AiSettingsFragment : Fragment(R.layout.fragment_ai_settings) {

    private var _binding: FragmentAiSettingsBinding? = null
    private val binding get() = _binding!!
    private val settingsViewModel: AiSettingsViewModel by viewModels()

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

            val uriString = it.toString()
            // The fragment's only job is to save the path via the ViewModel.
            settingsViewModel.saveLocalModelPath(uriString)
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
        val backends = settingsViewModel.getAvailableBackends()
        val backendNames = backends.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, backendNames)
        binding.backendAutocomplete.setAdapter(adapter)

        val currentBackend = settingsViewModel.getCurrentBackend()
        binding.backendAutocomplete.setText(currentBackend.name, false)
        updateBackendSpecificUi(currentBackend)

        binding.backendAutocomplete.setOnItemClickListener { _, _, position, _ ->
            val selectedBackend = backends[position]
            // Its only job is to save the backend selection.
            settingsViewModel.saveBackend(selectedBackend)
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

        val savedPath = settingsViewModel.getLocalModelPath()
        // Display the URI string to the user.
        val displayName = savedPath?.let { Uri.parse(it).lastPathSegment } ?: "No model selected"
        modelPathTextView.text = displayName


        browseButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupGeminiApiUi(view: View) {
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.gemini_api_key_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)

        if (!settingsViewModel.getGeminiApiKey().isNullOrBlank()) {
            apiKeyInput.setText("••••••••••••••••••••")
        }

        saveButton.setOnClickListener {
            val apiKey = apiKeyInput.text.toString()
            if (apiKey.isNotBlank() && apiKey != "••••••••••••••••••••") {
                settingsViewModel.saveGeminiApiKey(apiKey)
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