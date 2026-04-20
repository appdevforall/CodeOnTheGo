package com.appdevforall.keygen.plugin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class KeystoreGeneratorFragment : Fragment(), BuildStatusListener {

    companion object {
        private const val TAG = "KeystoreGenerator"
        private const val PLUGIN_ID = "com.appdevforall.keygen.plugin"
    }

    private var projectService: IdeProjectService? = null
    private var tooltipService: IdeTooltipService? = null
    private var fileService: IdeFileService? = null
    private var buildService: IdeBuildService? = null

    private var isBuildRunning = false
    private var lastBuildFailed = false

    private lateinit var statusContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tilKeystoreName: TextInputLayout
    private lateinit var tilKeystorePassword: TextInputLayout
    private lateinit var tilKeyAlias: TextInputLayout
    private lateinit var tilKeyPassword: TextInputLayout
    private lateinit var tilCertificateName: TextInputLayout
    private lateinit var tilCountry: TextInputLayout
    private lateinit var keystoreNameInput: TextInputEditText
    private lateinit var keystorePasswordInput: TextInputEditText
    private lateinit var keyAliasInput: TextInputEditText
    private lateinit var keyPasswordInput: TextInputEditText
    private lateinit var certificateNameInput: TextInputEditText
    private lateinit var organizationalUnitInput: TextInputEditText
    private lateinit var organizationInput: TextInputEditText
    private lateinit var cityInput: TextInputEditText
    private lateinit var stateInput: TextInputEditText
    private lateinit var countryInput: TextInputEditText
    private lateinit var btnGenerate: MaterialButton
    private lateinit var btnClear: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
            projectService = serviceRegistry?.get(IdeProjectService::class.java)
            tooltipService = serviceRegistry?.get(IdeTooltipService::class.java)
            fileService = serviceRegistry?.get(IdeFileService::class.java)
            buildService = serviceRegistry?.get(IdeBuildService::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Services not yet available", e)
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
        buildService?.addBuildStatusListener(this)
    }

    override fun onPause() {
        super.onPause()
        buildService?.removeBuildStatusListener(this)
    }

    private fun initializeViews(view: View) {
        statusContainer = view.findViewById(R.id.status_container)
        statusText = view.findViewById(R.id.tv_status)
        progressBar = view.findViewById(R.id.progress_bar)

        tilKeystoreName = view.findViewById(R.id.til_keystore_name)
        tilKeystorePassword = view.findViewById(R.id.til_keystore_password)
        tilKeyAlias = view.findViewById(R.id.til_key_alias)
        tilKeyPassword = view.findViewById(R.id.til_key_password)
        tilCertificateName = view.findViewById(R.id.til_certificate_name)
        tilCountry = view.findViewById(R.id.til_country)

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

        setupTooltipHandlers()
    }

    private fun setupTooltipHandlers() {
        btnGenerate.setOnLongClickListener { button ->
            tooltipService?.showTooltip(
                anchorView = button,
                category = "plugin_keystore_generator",
                tag = "keystore_generator.main_feature"
            ) ?: run {
                showToast(getString(R.string.tooltip_not_available))
            }
            true
        }

        statusContainer.setOnLongClickListener { view ->
            tooltipService?.showTooltip(
                anchorView = view,
                category = "plugin_keystore_generator",
                tag = "keystore_generator.editor_tab"
            ) ?: run {
                showToast(getString(R.string.docs_not_available))
            }
            true
        }
    }

    private fun clearValidationErrors() {
        tilKeystoreName.error = null
        tilKeystorePassword.error = null
        tilKeyAlias.error = null
        tilKeyPassword.error = null
        tilCertificateName.error = null
        tilCountry.error = null
    }

    private fun validateForm(): Boolean {
        var valid = true
        clearValidationErrors()

        if (keystoreNameInput.text.toString().trim().isEmpty()) {
            tilKeystoreName.error = getString(R.string.error_required)
            valid = false
        }

        if (keystorePasswordInput.text.toString().isEmpty()) {
            tilKeystorePassword.error = getString(R.string.error_required)
            valid = false
        }

        if (keyAliasInput.text.toString().trim().isEmpty()) {
            tilKeyAlias.error = getString(R.string.error_required)
            valid = false
        }

        if (keyPasswordInput.text.toString().isEmpty()) {
            tilKeyPassword.error = getString(R.string.error_required)
            valid = false
        }

        if (certificateNameInput.text.toString().trim().isEmpty()) {
            tilCertificateName.error = getString(R.string.error_required)
            valid = false
        }

        val countryCode = countryInput.text.toString().trim()
        if (countryCode.length != 2) {
            tilCountry.error = getString(R.string.error_country_length)
            valid = false
        }

        return valid
    }

    private fun generateKeystore() {
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

        showProgress(getString(R.string.generating_keystore))
        btnGenerate.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = performKeystoreGeneration(config)

                withContext(Dispatchers.Main) {
                    hideProgress()
                    btnGenerate.isEnabled = true

                    when (result) {
                        is KeystoreGenerationResult.Success -> {
                            val currentProject = projectService?.getCurrentProject()
                            if (currentProject != null) {
                                addSigningConfigToBuildFile(currentProject.rootDir, config, result.keystoreFile)
                            }
                            showSuccess(getString(R.string.success_message) + "\n${result.keystoreFile.absolutePath}")
                        }
                        is KeystoreGenerationResult.Error -> {
                            showError(getString(R.string.error_generation_failed) + ": ${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    btnGenerate.isEnabled = true
                    showError(getString(R.string.error_generation_failed) + (e.message?.let { ": $it" } ?: ""))
                }
            }
        }
    }

    private fun performKeystoreGeneration(config: KeystoreConfig): KeystoreGenerationResult {
        val validationErrors = KeystoreGenerator.validateConfig(config)
        if (validationErrors.isNotEmpty()) {
            return KeystoreGenerationResult.Error(getString(R.string.error_validation_failed, validationErrors.joinToString(", ")))
        }

        if (projectService == null) {
            try {
                val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
                projectService = serviceRegistry?.get(IdeProjectService::class.java)
                fileService = serviceRegistry?.get(IdeFileService::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get project service", e)
                return KeystoreGenerationResult.Error(getString(R.string.error_project_service_unavailable))
            }
        }

        val currentProject = projectService?.getCurrentProject()
            ?: return KeystoreGenerationResult.Error(getString(R.string.error_no_project))

        return try {
            val appDirectory = File(currentProject.rootDir, "app")
            if (!appDirectory.exists()) {
                appDirectory.mkdirs()
            }
            KeystoreGenerator.generateKeystore(config, appDirectory)
        } catch (e: SecurityException) {
            KeystoreGenerationResult.Error(getString(R.string.error_permission_denied, e.message ?: ""), e)
        } catch (e: Exception) {
            KeystoreGenerationResult.Error(getString(R.string.error_generation, e.message ?: ""), e)
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

        clearValidationErrors()

        hideStatus()
        showToast(getString(R.string.form_cleared))
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
            try {
                val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
                fileService = serviceRegistry?.get(IdeFileService::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get file service", e)
                showToast(getString(R.string.error_file_service_unavailable))
                return
            }
        }

        val buildFiles = listOf(
            File(projectDir, "app/build.gradle"),
            File(projectDir, "app/build.gradle.kts")
        )

        val buildFile = buildFiles.find { it.exists() } ?: run {
            showToast(getString(R.string.error_no_build_gradle))
            return
        }

        try {
            val isKotlinDsl = buildFile.name.endsWith(".kts")
            val keystoreRelativePath = keystoreFile.name
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
            showToast(getString(R.string.error_build_file, e.message ?: ""))
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
            updatedContent == currentContent -> showToast(getString(R.string.error_signing_pattern_not_found))
            fileService?.writeFile(buildFile, updatedContent) == true -> showToast(getString(R.string.success_signing_updated))
            else -> showToast(getString(R.string.error_signing_update_failed))
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
        val message = if (success) getString(R.string.success_signing_added) else getString(R.string.error_signing_add_failed)
        showToast(message)
    }

    private fun insertNewSigningConfigsBlock(buildFile: File, signingConfig: String) {
        val success = fileService?.insertAfterPattern(buildFile, "android {", signingConfig) == true
        val message = if (success) getString(R.string.success_build_file_updated) else getString(R.string.error_no_android_block, buildFile.name)
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

    private fun isKeystoreGenerationEnabled(): Boolean {
        return !isBuildRunning && !lastBuildFailed
    }

    private fun showActionDisabledMessage() {
        val message = when {
            isBuildRunning -> getString(R.string.error_build_running)
            lastBuildFailed -> getString(R.string.error_build_failed)
            else -> getString(R.string.error_unavailable)
        }
        showToast(message)
    }

    private fun updateButtonStates() {
        val isEnabled = isKeystoreGenerationEnabled()
        btnGenerate.isEnabled = isEnabled
        btnGenerate.alpha = if (isEnabled) 1.0f else 0.6f
    }

    override fun onBuildStarted() {
        isBuildRunning = true
        lastBuildFailed = false
        activity?.runOnUiThread { updateButtonStates() }
    }

    override fun onBuildFinished() {
        isBuildRunning = false
        lastBuildFailed = false
        activity?.runOnUiThread { updateButtonStates() }
    }

    override fun onBuildFailed(error: String?) {
        isBuildRunning = false
        lastBuildFailed = true
        activity?.runOnUiThread { updateButtonStates() }
    }
}
