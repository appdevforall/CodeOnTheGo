package com.appdevforall.keygen.plugin.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.appdevforall.keygen.plugin.KeystoreConfig
import com.appdevforall.keygen.plugin.KeystoreGenerator
import com.appdevforall.keygen.plugin.KeystoreGenerationResult
import com.appdevforall.keygen.plugin.R
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reusable fragment for keystore generation that can be used in editor tabs, bottom sheet, etc.
 */
class KeystoreGeneratorFragment : Fragment() {

    companion object {
        private const val PLUGIN_ID = "com.appdevforall.keygen.plugin"
    }

    private var projectService: IdeProjectService? = null
    private var tooltipService: IdeTooltipService? = null

    // UI Components
    private lateinit var statusContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var keystoreNameInput: EditText
    private lateinit var keystorePasswordInput: EditText
    private lateinit var keyAliasInput: EditText
    private lateinit var keyPasswordInput: EditText
    private lateinit var certificateNameInput: EditText
    private lateinit var organizationalUnitInput: EditText
    private lateinit var organizationInput: EditText
    private lateinit var cityInput: EditText
    private lateinit var stateInput: EditText
    private lateinit var countryInput: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnClear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get services from the plugin's service registry
        try {
            val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
            projectService = serviceRegistry?.get(IdeProjectService::class.java)
            tooltipService = serviceRegistry?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Services might not be available yet, we'll handle this gracefully
        }
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_keystore_generator, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupClickListeners()
    }

    private fun initializeViews(view: View) {
        statusContainer = view.findViewById(R.id.status_container)
        statusText = view.findViewById(R.id.tv_status)
        progressBar = view.findViewById(R.id.progress_bar)

        keystoreNameInput = view.findViewById(R.id.et_keystore_name)
        keystorePasswordInput = view.findViewById(R.id.et_keystore_password)
        keyAliasInput = view.findViewById(R.id.et_key_alias)
        keyPasswordInput = view.findViewById(R.id.et_key_password)
        certificateNameInput = view.findViewById(R.id.et_certificate_name)
        organizationalUnitInput = view.findViewById(R.id.et_organizational_unit)
        organizationInput = view.findViewById(R.id.et_organization)
        cityInput = view.findViewById(R.id.et_city)
        stateInput = view.findViewById(R.id.et_state)
        countryInput = view.findViewById(R.id.et_country)

        btnGenerate = view.findViewById(R.id.btn_generate)
        btnClear = view.findViewById(R.id.btn_clear)
    }

    private fun setupClickListeners() {
        btnGenerate.setOnClickListener {
            if (validateForm()) {
                generateKeystore()
            }
        }

        btnClear.setOnClickListener {
            clearForm()
        }

        // Add long press tooltips for help documentation
        setupTooltipHandlers()
    }

    private fun setupTooltipHandlers() {
        // Generate button tooltip
        btnGenerate.setOnLongClickListener { button ->
            tooltipService?.showTooltip(
                anchorView = button,
                category = "plugin_keystore_generator",
                tag = "keystore_generator.main_feature"
            ) ?: run {
                showToast("Long press detected! Tooltip service not available.")
            }
            true // Consume the long click
        }

        // Main interface tooltip (on the header area)
        val headerView = view?.findViewById<View>(android.R.id.content) // Use root view
        statusContainer.setOnLongClickListener { view ->
            tooltipService?.showTooltip(
                anchorView = view,
                category = "plugin_keystore_generator",
                tag = "keystore_generator.editor_tab"
            ) ?: run {
                showToast("Long press detected! Documentation not available.")
            }
            true // Consume the long click
        }
    }

    private fun validateForm(): Boolean {
        val errors = mutableListOf<String>()

        if (keystoreNameInput.text.toString().trim().isEmpty()) {
            errors.add("Keystore name is required")
        }

        if (keystorePasswordInput.text.toString().isEmpty()) {
            errors.add("Keystore password is required")
        }

        if (keyAliasInput.text.toString().trim().isEmpty()) {
            errors.add("Key alias is required")
        }

        if (keyPasswordInput.text.toString().isEmpty()) {
            errors.add("Key password is required")
        }

        if (certificateNameInput.text.toString().trim().isEmpty()) {
            errors.add("Certificate name is required")
        }

        if (countryInput.text.toString().trim().length != 2) {
            errors.add("Country code must be exactly 2 characters")
        }

        if (errors.isNotEmpty()) {
            showError("Validation failed:\n${errors.joinToString("\n")}")
            return false
        }

        return true
    }

    private fun generateKeystore() {
        val config = KeystoreConfig(
            keystoreName = keystoreNameInput.text.toString().trim(),
            keystorePassword = keystorePasswordInput.text.toString().toCharArray(),
            keyAlias = keyAliasInput.text.toString().trim(),
            keyPassword = keyPasswordInput.text.toString().toCharArray(),
            certificateName = certificateNameInput.text.toString().trim(),
            organizationalUnit = organizationalUnitInput.text.toString().trim().takeIf { it.isNotEmpty() },
            organization = organizationInput.text.toString().trim().takeIf { it.isNotEmpty() },
            city = cityInput.text.toString().trim().takeIf { it.isNotEmpty() },
            state = stateInput.text.toString().trim().takeIf { it.isNotEmpty() },
            country = countryInput.text.toString().trim()
        )

        // Show progress
        showProgress("Generating keystore...")
        btnGenerate.isEnabled = false

        // Generate keystore asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = performKeystoreGeneration(config)

                withContext(Dispatchers.Main) {
                    hideProgress()
                    btnGenerate.isEnabled = true

                    when (result) {
                        is KeystoreGenerationResult.Success -> {
                            showSuccess("✅ Keystore generated successfully!\nLocation: ${result.keystoreFile.absolutePath}")
                            showToast("Keystore created: ${result.keystoreFile.name}")
                        }
                        is KeystoreGenerationResult.Error -> {
                            showError("❌ Generation failed: ${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    btnGenerate.isEnabled = true
                    showError("❌ Unexpected error: ${e.message}")
                }
            }
        }
    }

    private fun performKeystoreGeneration(config: KeystoreConfig): KeystoreGenerationResult {
        // Validate configuration
        val validationErrors = KeystoreGenerator.validateConfig(config)
        if (validationErrors.isNotEmpty()) {
            return KeystoreGenerationResult.Error("Validation failed: ${validationErrors.joinToString(", ")}")
        }

        // Get project directory
        if (projectService == null) {
            // Try to get it again in case it's available now
            try {
                val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
                projectService = serviceRegistry?.get(IdeProjectService::class.java)
            } catch (e: Exception) {
                return KeystoreGenerationResult.Error("IDE project service not available")
            }
        }

        val currentProject = projectService?.getCurrentProject()
        if (currentProject == null) {
            return KeystoreGenerationResult.Error("No current project available")
        }

        return try {
            // Create keystore in the project's app directory
            val appDirectory = File(currentProject.rootDir, "app")
            if (!appDirectory.exists()) {
                appDirectory.mkdirs()
            }

            // Generate the keystore
            KeystoreGenerator.generateKeystore(config, appDirectory)

        } catch (e: SecurityException) {
            KeystoreGenerationResult.Error("Permission denied: ${e.message}", e)
        } catch (e: Exception) {
            KeystoreGenerationResult.Error("Generation error: ${e.message}", e)
        }
    }

    private fun clearForm() {
        keystoreNameInput.setText("release.jks")
        keystorePasswordInput.setText("")
        keyAliasInput.setText("release")
        keyPasswordInput.setText("")
        certificateNameInput.setText("")
        organizationalUnitInput.setText("")
        organizationInput.setText("")
        cityInput.setText("")
        stateInput.setText("")
        countryInput.setText("US")

        hideStatus()
        showToast("Form cleared")
    }

    private fun showProgress(message: String) {
        progressBar.visibility = View.VISIBLE
        statusContainer.visibility = View.VISIBLE
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.primary_text_light))
        statusContainer.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.background_light))
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
    }

    private fun showSuccess(message: String) {
        statusContainer.visibility = View.VISIBLE
        statusText.text = message
        statusText.setTextColor(Color.parseColor("#4CAF50")) // Green
        statusContainer.setBackgroundColor(Color.parseColor("#E8F5E8")) // Light green background
    }

    private fun showError(message: String) {
        statusContainer.visibility = View.VISIBLE
        statusText.text = message
        statusText.setTextColor(Color.parseColor("#F44336")) // Red
        statusContainer.setBackgroundColor(Color.parseColor("#FFF3F3")) // Light red background
    }

    private fun hideStatus() {
        statusContainer.visibility = View.GONE
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}