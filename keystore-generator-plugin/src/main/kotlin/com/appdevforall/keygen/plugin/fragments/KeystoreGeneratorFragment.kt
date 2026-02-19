package com.appdevforall.keygen.plugin.fragments

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
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.BuildStatusListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reusable fragment for keystore generation that can be used in editor tabs, bottom sheet, etc.
 */
class KeystoreGeneratorFragment : Fragment(), BuildStatusListener {

    companion object {
        private const val PLUGIN_ID = "com.appdevforall.keygen.plugin"
    }

    private var projectService: IdeProjectService? = null
    private var tooltipService: IdeTooltipService? = null
    private var fileService: IdeFileService? = null
    private var buildService: IdeBuildService? = null

    // Build status tracking
    private var isBuildRunning = false
    private var lastBuildFailed = false

    // UI Components
    private lateinit var statusContainer: LinearLayout
    private lateinit var headerContainer: LinearLayout
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
            fileService = serviceRegistry?.get(IdeFileService::class.java)
            buildService = serviceRegistry?.get(IdeBuildService::class.java)
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
        updateButtonStates()
    }

    override fun onResume() {
        super.onResume()
        // Register for build status updates
        buildService?.addBuildStatusListener(this)
    }

    override fun onPause() {
        super.onPause()
        // Unregister from build status updates
        buildService?.removeBuildStatusListener(this)
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
        statusContainer.setOnLongClickListener { view ->
            tooltipService?.showTooltip(
                anchorView = view,
                category = "plugin_keystore_generator",
                tag = "keystore_generator.editor_tab"
            ) ?: run {
                showToast("Long press detected! Documentation not available.")
            }
            true
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
        // Check if action should be disabled
        if (!isKeystoreGenerationEnabled()) {
            showActionDisabledMessage()
            return
        }
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
                            // Update build file on main thread
                            val currentProject = projectService?.getCurrentProject()
                            if (currentProject != null) {
                                addSigningConfigToBuildFile(currentProject.rootDir, config, result.keystoreFile)
                            }

                            showSuccess("✅ Keystore generated successfully!\nLocation: ${result.keystoreFile.absolutePath}\n📝 Build file updated with signing configuration")
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
                fileService = serviceRegistry?.get(IdeFileService::class.java)
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
            val result = KeystoreGenerator.generateKeystore(config, appDirectory)

            result

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
        val ctx = statusContainer.context
        statusText.setTextColor(ContextCompat.getColor(ctx, R.color.status_text))
        statusContainer.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_background))
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
    }

    private fun showSuccess(message: String) {
        statusContainer.visibility = View.VISIBLE
        statusText.text = message
        val ctx = statusContainer.context
        statusText.setTextColor(ContextCompat.getColor(ctx, R.color.status_success_text))
        statusContainer.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_success_background))
    }

    private fun showError(message: String) {
        statusContainer.visibility = View.VISIBLE
        statusText.text = message
        val ctx = statusContainer.context
        statusText.setTextColor(ContextCompat.getColor(ctx, R.color.status_error_text))
        statusContainer.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_error_background))
    }

    private fun hideStatus() {
        statusContainer.visibility = View.GONE
    }

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun addSigningConfigToBuildFile(projectDir: File, config: KeystoreConfig, keystoreFile: File) {
        if (fileService == null) {
            // Try to get it again in case it's available now
            try {
                val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
                fileService = serviceRegistry?.get(IdeFileService::class.java)
            } catch (e: Exception) {
                showToast("ERROR: IdeFileService not available")
                return
            }
        }

        val buildFiles = listOf(
            File(projectDir, "app/build.gradle"),
            File(projectDir, "app/build.gradle.kts")
        )

        val buildFile = buildFiles.find { it.exists() } ?: run {
            showToast("ERROR: No build.gradle found in app directory")
            return
        }

        try {
            val isKotlinDsl = buildFile.name.endsWith(".kts")
            val keystoreRelativePath = "${keystoreFile.name}"
            val currentContent = fileService?.readFile(buildFile) ?: ""

            val hasReleaseConfig = if (isKotlinDsl) {
                currentContent.contains("create(\"release\")") ||
                currentContent.contains("getByName(\"release\")")
            } else {
                currentContent.contains("release {")
            }

            if (hasReleaseConfig) {
                handleExistingReleaseConfig(buildFile, currentContent, config, keystoreRelativePath, isKotlinDsl)
            } else {
                handleNewReleaseConfig(buildFile, currentContent, config, keystoreRelativePath, isKotlinDsl)
            }

        } catch (e: Exception) {
            showToast("Error modifying build file: ${e.message}")
        }
    }

    private fun handleExistingReleaseConfig(
        buildFile: File,
        currentContent: String,
        config: KeystoreConfig,
        keystoreRelativePath: String,
        isKotlinDsl: Boolean
    ) {
        val updatedContent = updateExistingSigningConfig(currentContent, config, keystoreRelativePath, isKotlinDsl)

        when {
            updatedContent == currentContent -> showToast("Could not update signing config - pattern not found")
            fileService?.writeFile(buildFile, updatedContent) == true -> showToast("Updated existing release signing config")
            else -> showToast("Failed to update signing config")
        }
    }

    private fun handleNewReleaseConfig(
        buildFile: File,
        currentContent: String,
        config: KeystoreConfig,
        keystoreRelativePath: String,
        isKotlinDsl: Boolean
    ) {
        val signingConfig = when (isKotlinDsl) {
            true -> generateKotlinSigningConfig(config, keystoreRelativePath)
            false -> generateGroovySigningConfig(config, keystoreRelativePath)
        }

        when (currentContent.contains("signingConfigs")) {
            true -> insertIntoExistingSigningConfigs(buildFile, config, keystoreRelativePath, isKotlinDsl)
            false -> insertNewSigningConfigsBlock(buildFile, signingConfig)
        }
    }

    private fun insertIntoExistingSigningConfigs(
        buildFile: File,
        config: KeystoreConfig,
        keystoreRelativePath: String,
        isKotlinDsl: Boolean
    ) {
        val releaseConfig = when (isKotlinDsl) {
            true -> """
        create("release") {
            storeFile = file("$keystoreRelativePath")
            storePassword = "${String(config.keystorePassword)}"
            keyAlias = "${config.keyAlias}"
            keyPassword = "${String(config.keyPassword)}"
        }"""
            false -> """
        release {
            storeFile file('$keystoreRelativePath')
            storePassword '${String(config.keystorePassword)}'
            keyAlias '${config.keyAlias}'
            keyPassword '${String(config.keyPassword)}'
        }"""
        }

        val success = fileService?.insertAfterPattern(buildFile, "signingConfigs {", releaseConfig) == true
        val message = when (success) {
            true -> "Added release signing config"
            false -> "Failed to add release config to signingConfigs block"
        }
        showToast(message)
    }

    private fun insertNewSigningConfigsBlock(buildFile: File, signingConfig: String) {
        val success = fileService?.insertAfterPattern(buildFile, "android {", signingConfig) == true
        val message = when (success) {
            true -> "Build file updated with signing config"
            false -> "Could not find android block in ${buildFile.name}"
        }
        showToast(message)
    }

    private fun updateExistingSigningConfig(
        content: String,
        config: KeystoreConfig,
        keystoreRelativePath: String,
        isKotlinDsl: Boolean
    ): String {
        var result = content

        if (isKotlinDsl) {
            // Update Kotlin DSL patterns
            // Pattern 1: create("release") { ... }
            val createPattern = """create\("release"\)\s*\{[^}]*\}""".toRegex(RegexOption.DOT_MATCHES_ALL)
            if (createPattern.containsMatchIn(result)) {
                val newConfig = """create("release") {
            storeFile = file("$keystoreRelativePath")
            storePassword = "${String(config.keystorePassword)}"
            keyAlias = "${config.keyAlias}"
            keyPassword = "${String(config.keyPassword)}"
        }"""
                result = result.replace(createPattern, newConfig)
            } else {
                // Pattern 2: getByName("release") { ... }
                val getByNamePattern = """getByName\("release"\)\s*\{[^}]*\}""".toRegex(RegexOption.DOT_MATCHES_ALL)
                if (getByNamePattern.containsMatchIn(result)) {
                    val newConfig = """getByName("release") {
            storeFile = file("$keystoreRelativePath")
            storePassword = "${String(config.keystorePassword)}"
            keyAlias = "${config.keyAlias}"
            keyPassword = "${String(config.keyPassword)}"
        }"""
                    result = result.replace(getByNamePattern, newConfig)
                }
            }
        } else {
            // Update Groovy DSL pattern
            val groovyPattern = """release\s*\{[^}]*\}""".toRegex(RegexOption.DOT_MATCHES_ALL)
            if (groovyPattern.containsMatchIn(result)) {
                val newConfig = """release {
            storeFile file('$keystoreRelativePath')
            storePassword '${String(config.keystorePassword)}'
            keyAlias '${config.keyAlias}'
            keyPassword '${String(config.keyPassword)}'
        }"""
                result = result.replace(groovyPattern, newConfig)
            }
        }

        return result
    }

    private fun generateKotlinSigningConfig(config: KeystoreConfig, keystoreRelativePath: String): String {
        return """

    signingConfigs {
        create("release") {
            storeFile = file("$keystoreRelativePath")
            storePassword = "${String(config.keystorePassword)}"
            keyAlias = "${config.keyAlias}"
            keyPassword = "${String(config.keyPassword)}"
        }
    }
"""
    }

    private fun generateGroovySigningConfig(config: KeystoreConfig, keystoreRelativePath: String): String {
        return """

    signingConfigs {
        release {
            storeFile file('$keystoreRelativePath')
            storePassword '${String(config.keystorePassword)}'
            keyAlias '${config.keyAlias}'
            keyPassword '${String(config.keyPassword)}'
        }
    }
"""
    }

    // Build status checking methods
    private fun isKeystoreGenerationEnabled(): Boolean {
        return !isBuildRunning && !lastBuildFailed
    }

    private fun showActionDisabledMessage() {
        val message = when {
            isBuildRunning -> "Cannot generate keystore while project is building or syncing"
            lastBuildFailed -> "Cannot generate keystore - please fix build errors first"
            else -> "Keystore generation is currently unavailable"
        }
        showToast(message)
    }

    private fun updateButtonStates() {
        val isEnabled = isKeystoreGenerationEnabled()
        btnGenerate.isEnabled = isEnabled
        btnGenerate.alpha = if (isEnabled) 1.0f else 0.6f
    }

    // BuildStatusListener implementation
    override fun onBuildStarted() {
        isBuildRunning = true
        lastBuildFailed = false
        activity?.runOnUiThread {
            updateButtonStates()
        }
    }

    override fun onBuildFinished() {
        isBuildRunning = false
        lastBuildFailed = false
        activity?.runOnUiThread {
            updateButtonStates()
        }
    }

    override fun onBuildFailed(error: String?) {
        isBuildRunning = false
        lastBuildFailed = true
        activity?.runOnUiThread {
            updateButtonStates()
        }
    }
}