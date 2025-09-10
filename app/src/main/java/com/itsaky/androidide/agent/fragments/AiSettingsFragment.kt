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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.R
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.repository.GeminiRepository
import com.itsaky.androidide.agent.repository.SwitchableGeminiRepository
import com.itsaky.androidide.agent.viewmodel.AiSettingsViewModel
import com.itsaky.androidide.databinding.FragmentAiSettingsBinding
import com.itsaky.androidide.utils.flashInfo
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AiSettingsFragment : Fragment(R.layout.fragment_ai_settings) {

    private var _binding: FragmentAiSettingsBinding? = null
    private val binding get() = _binding!!
    private val settingsViewModel: AiSettingsViewModel by viewModels<AiSettingsViewModel>()
    private val geminiRepository: GeminiRepository by inject()

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

            val uriString = it.toString()
            settingsViewModel.saveLocalModelPath(uriString)
            updateLocalLlmUi(binding.backendSpecificSettingsContainer)

            lifecycleScope.launch {
                if (geminiRepository is SwitchableGeminiRepository) {
                    if ((geminiRepository as SwitchableGeminiRepository).loadLocalModel(uriString)) {
                        flashInfo("Local model loaded successfully!")
                    } else {
                        flashInfo("Failed to load local model. Check logs.")
                    }
                }
            }
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
        updateBackendSpecificUi(currentBackend) // Initial UI setup

        binding.backendAutocomplete.setOnItemClickListener { _, _, position, _ ->
            val selectedBackend = backends[position]
            settingsViewModel.saveBackend(selectedBackend)

            // Switch the active repository
            (geminiRepository as? SwitchableGeminiRepository)?.setActiveBackend(selectedBackend)

            updateBackendSpecificUi(selectedBackend)
        }
    }

    private fun updateBackendSpecificUi(backend: AiBackend) {
        val container = binding.backendSpecificSettingsContainer
        container.removeAllViews() // Clear previous settings

        when (backend) {
            AiBackend.LOCAL_LLM -> {
                val localLlmView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.layout_settings_local_llm, container, true)
                updateLocalLlmUi(localLlmView)
            }
            // Add this new case for the Gemini API backend
            AiBackend.GEMINI -> {
                val geminiApiView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.layout_settings_gemini_api, container, true)
                setupGeminiApiUi(geminiApiView)
            }
            else -> {
                // Handle other cases or leave empty
            }
        }
    }

    private fun updateLocalLlmUi(view: View) {
        val modelPathTextView = view.findViewById<TextView>(R.id.selected_model_path)
        val browseButton = view.findViewById<Button>(R.id.btn_browse_model)

        val savedPath = settingsViewModel.getLocalModelPath()
        modelPathTextView.text = savedPath ?: "No model selected"

        browseButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*")) // Allow user to pick any file type
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Sets up the UI listeners and state for the Gemini API settings view.
     */
    private fun setupGeminiApiUi(view: View) {
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.gemini_api_key_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)

        // For security, show a placeholder if a key is already saved,
        // rather than displaying the key itself.
        if (!settingsViewModel.getGeminiApiKey().isNullOrBlank()) {
            apiKeyInput.setText("••••••••••••••••••••")
        }

        saveButton.setOnClickListener {
            val apiKey = apiKeyInput.text.toString()

            // Check if the user has actually entered a new key
            if (apiKey.isNotBlank() && apiKey != "••••••••••••••••••••") {
                settingsViewModel.saveGeminiApiKey(apiKey)
                flashInfo("API Key saved securely.")
                // Reset the field to the placeholder after saving
                apiKeyInput.setText("••••••••••••••••••••")
            } else if (apiKey.isBlank()) {
                flashInfo("API Key cannot be empty.")
            } else {
                flashInfo("Please enter a new API Key to save.")
            }
        }
    }
}