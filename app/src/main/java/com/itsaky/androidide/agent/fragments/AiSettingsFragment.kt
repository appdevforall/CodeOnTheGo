package com.itsaky.androidide.agent.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.itsaky.androidide.R
import com.itsaky.androidide.agent.repository.AiBackend
import com.itsaky.androidide.agent.viewmodel.AiSettingsViewModel
import com.itsaky.androidide.databinding.FragmentAiSettingsBinding
import com.itsaky.androidide.databinding.LayoutSettingsGeminiApiBinding
import com.itsaky.androidide.databinding.LayoutSettingsLocalLlmBinding
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
            settingsViewModel.saveLocalModelPath(uriString)

            // Re-render the backend-specific UI to reflect the change.
            // This is more robust than trying to update individual views.
            updateBackendSpecificUi(settingsViewModel.getCurrentBackend())
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
            settingsViewModel.saveBackend(selectedBackend)
            updateBackendSpecificUi(selectedBackend)
        }
    }

    private fun updateBackendSpecificUi(backend: AiBackend) {
        val container = binding.backendSpecificSettingsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        when (backend) {
            AiBackend.LOCAL_LLM -> {
                // Inflate using the generated binding class
                val localLlmBinding = LayoutSettingsLocalLlmBinding.inflate(inflater, container, true)
                updateLocalLlmUi(localLlmBinding)
            }
            AiBackend.GEMINI -> {
                // Inflate using the generated binding class
                val geminiApiBinding = LayoutSettingsGeminiApiBinding.inflate(inflater, container, true)
                setupGeminiApiUi(geminiApiBinding)
            }
        }
    }

    /**
     * Updates the Local LLM settings UI using its specific binding class.
     */
    private fun updateLocalLlmUi(localBinding: LayoutSettingsLocalLlmBinding) {
        val savedPath = settingsViewModel.getLocalModelPath()
        // Display the URI string to the user.
        val displayName = savedPath?.let { Uri.parse(it).lastPathSegment } ?: "No model selected"
        localBinding.selectedModelPath.text = displayName

        localBinding.btnBrowseModel.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    /**
     * Sets up the Gemini API settings UI using its specific binding class.
     */
    private fun setupGeminiApiUi(geminiBinding: LayoutSettingsGeminiApiBinding) {
        if (!settingsViewModel.getGeminiApiKey().isNullOrBlank()) {
            geminiBinding.geminiApiKeyInput.setText("••••••••••••••••••••")
        }

        geminiBinding.btnSaveApiKey.setOnClickListener {
            val apiKey = geminiBinding.geminiApiKeyInput.text.toString()
            if (apiKey.isNotBlank() && apiKey != "••••••••••••••••••••") {
                settingsViewModel.saveGeminiApiKey(apiKey)
                flashInfo("API Key saved securely.")
                geminiBinding.geminiApiKeyInput.setText("••••••••••••••••••••")
            } else if (apiKey.isBlank()) {
                flashInfo("API Key cannot be empty.")
            } else {
                flashInfo("Please enter a new API Key to save.")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}