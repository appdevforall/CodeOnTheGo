package com.appdevforall.keygen.plugin

import android.app.AlertDialog
import android.view.LayoutInflater
import com.appdevforall.keygen.plugin.R
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import com.appdevforall.keygen.plugin.fragments.KeystoreGeneratorFragment
import androidx.fragment.app.Fragment
import android.widget.EditText
import java.io.File

/**
 * AndroidIDE plugin for generating Android keystores for release builds
 */
class KeystoreGeneratorPlugin : IPlugin, UIExtension, EditorTabExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("KeystoreGeneratorPlugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("KeystoreGeneratorPlugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("KeystoreGeneratorPlugin: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("KeystoreGeneratorPlugin: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("KeystoreGeneratorPlugin: Disposing plugin")
    }

    private fun showKeystoreGenerationDialog() {
        context.logger.info("Showing keystore generation dialog")

        val uiService = context.services.get(IdeUIService::class.java) ?: run {
            context.logger.error("UI service not available")
            return
        }

        if (!uiService.isUIAvailable()) {
            context.logger.error("UI not available")
            return
        }

        val activity = uiService.getCurrentActivity() ?: run {
            context.logger.error("No current activity")
            return
        }

        // Check if activity is valid and not finishing
        if (activity.isFinishing || activity.isDestroyed) {
            context.logger.error("Activity is finishing or destroyed")
            return
        }

        activity.runOnUiThread {
            try {
                // Double-check activity state inside UI thread
                if (activity.isFinishing || activity.isDestroyed) {
                    context.logger.error("Activity became invalid before showing dialog")
                    return@runOnUiThread
                }

                createKeystoreDialog(activity)
            } catch (e: Exception) {
                context.logger.error("Error in UI thread", e)
            }
        }
    }

    private fun createKeystoreDialog(activity: android.app.Activity) {
        try {
            // Use the plugin's resource context for proper layout inflation
            val pluginContext = PluginFragmentHelper.getPluginContext(context.pluginId)
                ?: context.androidContext

            val inflater = PluginFragmentHelper.getPluginInflater(
                context.pluginId,
                LayoutInflater.from(pluginContext)
            )

            val dialogView = inflater.inflate(R.layout.dialog_keystore_generation_simple, null)

            // Get references to input fields
            val keystoreNameInput = dialogView.findViewById<EditText>(R.id.et_keystore_name)
            val keystorePasswordInput = dialogView.findViewById<E87ditText>(R.id.et_keystore_password)
            val keyAliasInput = dialogView.findViewById<EditText>(R.id.et_key_alias)
            val keyPasswordInput = dialogView.findViewById<EditText>(R.id.et_key_password)
            val certificateNameInput = dialogView.findViewById<EditText>(R.id.et_certificate_name)
            val organizationalUnitInput = dialogView.findViewById<EditText>(R.id.et_organizational_unit)
            val organizationInput = dialogView.findViewById<EditText>(R.id.et_organization)
            val cityInput = dialogView.findViewById<EditText>(R.id.et_city)
            val stateInput = dialogView.findViewById<EditText>(R.id.et_state)
            val countryInput = dialogView.findViewById<EditText>(R.id.et_country)

            AlertDialog.Builder(activity)
                .setTitle("Generate Keystore")
                .setView(dialogView)
                .setPositiveButton("Create") { _, _ ->
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

                    generateKeystore(config)
                }
                .setNegativeButton("Cancel", null)
                .show()

        } catch (e: Exception) {
            context.logger.error("Error creating keystore dialog", e)
        }
    }

    private fun generateKeystore(config: KeystoreConfig) {
        context.logger.info("Starting keystore generation")

        // Validate configuration
        val validationErrors = KeystoreGenerator.validateConfig(config)
        if (validationErrors.isNotEmpty()) {
            context.logger.error("Validation errors: ${validationErrors.joinToString(", ")}")
            return
        }

        // Get project directory
        val projectService = context.services.get(IdeProjectService::class.java)
        if (projectService == null) {
            context.logger.error("IdeProjectService not available")
            return
        }

        val currentProject = projectService.getCurrentProject()
        if (currentProject == null) {
            context.logger.warn("No current project available")
            return
        }

        try {
            // Create keystore in the project's app directory
            val appDirectory = File(currentProject.rootDir, "app")
            if (!appDirectory.exists()) {
                appDirectory.mkdirs()
            }

            // Generate the keystore
            when (val result = KeystoreGenerator.generateKeystore(config, appDirectory)) {
                is KeystoreGenerationResult.Success -> {
                    context.logger.info("Keystore generated successfully: ${result.keystoreFile.absolutePath}")
                }
                is KeystoreGenerationResult.Error -> {
                    if (result.exception != null) {
                        context.logger.error("Keystore generation failed: ${result.message}", result.exception)
                    } else {
                        context.logger.error("Keystore generation failed: ${result.message}")
                    }
                }
            }

        } catch (e: SecurityException) {
            context.logger.error("Permission denied accessing project: ${e.message}")
        } catch (e: Exception) {
            context.logger.error("Error during keystore generation", e)
        }
    }

    // EditorTabExtension interface methods - for main editor tabs
    override fun getMainEditorTabs(): List<EditorTabItem> {
        context.logger.debug("getMainEditorTabs() called - returning keystore generator main tab")

        val tabItem = EditorTabItem(
            id = "keystore_generator_main",
            title = "Keystore Generator",
            icon = android.R.drawable.ic_lock_lock,
            fragmentFactory = {
                context.logger.debug("Creating KeystoreGeneratorFragment instance")
                KeystoreGeneratorFragment()
            },
            isCloseable = true,
            isPersistent = false,
            order = 0,
            isEnabled = true,
            isVisible = true,
            tooltip = "Generate Android keystore for release builds"
        )

        context.logger.debug("Returning tab item: id={}, title={}, enabled={}, visible={}",
            tabItem.id, tabItem.title, tabItem.isEnabled, tabItem.isVisible)

        return listOf(tabItem)
    }

    // UIExtension interface methods - for bottom sheet tabs
    override fun getEditorTabs(): List<TabItem> {
        return listOf(
            TabItem(
                id = "keystore_generator_bottom",
                title = "Keystore Gen",
                fragmentFactory = { KeystoreGeneratorFragment() },
                isEnabled = true,
                isVisible = true,
                order = 0
            )
        )
    }

    override fun getMainMenuItems(): List<MenuItem> {
        return listOf(
            MenuItem(
                id = "keystore_generator_menu",
                title = "Generate Keystore",
                isEnabled = true,
                action = { showKeystoreGenerationDialog() }
            )
        )
    }

    // Override sidebar action to open main editor tab instead of dialog
    private fun openKeystoreGeneratorTab() {
        context.logger.info("Opening keystore generator in main editor tab")

        val editorTabService = context.services.get(IdeEditorTabService::class.java) ?: run {
            context.logger.error("Editor tab service not available")
            showKeystoreGenerationDialog()
            return
        }

        context.logger.debug("Editor tab service available: {}", editorTabService.javaClass.name)

        if (!editorTabService.isTabSystemAvailable()) {
            context.logger.error("Editor tab system not available")
            showKeystoreGenerationDialog()
            return
        }

        val tabId = "keystore_generator_main"
        context.logger.debug("Attempting to select plugin tab with ID: {}", tabId)

        // Check if this is actually a plugin tab
        val isPluginTab = editorTabService.isPluginTab(tabId)
        context.logger.debug("Is '{}' registered as plugin tab: {}", tabId, isPluginTab)

        // Get all available plugin tab IDs
        val allPluginTabIds = editorTabService.getAllPluginTabIds()
        context.logger.debug("All registered plugin tab IDs: {}", allPluginTabIds)

        try {
            if (editorTabService.selectPluginTab(tabId)) {
                context.logger.info("Successfully opened keystore generator tab")
            } else {
                context.logger.warn("Keystore generator tab not found or could not be selected - falling back to dialog")
                showKeystoreGenerationDialog()
            }
        } catch (e: Exception) {
            context.logger.error("Error opening keystore generator tab", e)
            showKeystoreGenerationDialog()
        }
    }

    // Update the sidebar items to use the new tab-opening behavior
    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "generate_keystore",
                title = "Generate Keystore",
                icon = android.R.drawable.ic_lock_lock,
                isEnabled = true,
                isVisible = true,
                group = "build",
                order = 0,
                action = { openKeystoreGeneratorTab() }
            )
        )
    }

    // DocumentationExtension interface methods
    override fun getTooltipCategory(): String = "plugin_keystore_generator"

    override fun getTooltipEntries(): List<PluginTooltipEntry> {
        return listOf(
            // Main feature documentation
            PluginTooltipEntry(
                tag = "keystore_generator.main_feature",
                summary = "<b>Keystore Generator</b><br>Generate Android keystores for release builds",
                detail = """
                    <h3>Android Keystore Generator</h3>
                    <p>This plugin allows you to generate Android keystores (.jks files) directly within your project for signing release builds.</p>

                    <h4>How to use:</h4>
                    <ol>
                        <li><b>Sidebar</b>: Click "Generate Keystore" in the sidebar to open the main editor tab</li>
                        <li><b>Main Menu</b>: Use Tools → Generate Keystore for a dialog interface</li>
                        <li><b>Bottom Sheet</b>: Access via the "Keystore Gen" tab in the editor bottom sheet</li>
                        <li>Fill in the keystore details and certificate information</li>
                        <li>Click "Generate Keystore" to create the file in your project's app directory</li>
                    </ol>

                    <h4>Features:</h4>
                    <ul>
                        <li>RSA 2048-bit key generation with SHA256 signature</li>
                        <li>X.509 certificate creation with customizable details</li>
                        <li>Automatic project integration (saves to app/ directory)</li>
                        <li>Form validation and error handling</li>
                        <li>Multiple UI integration points</li>
                    </ul>

                    <p><b>💡 Tip:</b> Keep your keystore and passwords secure - you'll need them to update your app!</p>
                """.trimIndent(),
                buttons = listOf(
                    PluginTooltipButton(
                        description = "Android Signing Guide",
                        uri = "https://developer.android.com/studio/publish/app-signing",
                        order = 0
                    )
                )
            ),

            // Plugin overview
            PluginTooltipEntry(
                tag = "keystore_generator.overview",
                summary = "<b>Keystore Generator Plugin</b><br>Complete plugin integration showcase",
                detail = """
                    <h3>Plugin Integration Showcase</h3>
                    <p>This plugin demonstrates comprehensive integration with the CodeOnTheGo plugin system.</p>

                    <h4>Integration Points:</h4>
                    <ul>
                        <li><b>Main Editor Tab</b> - Full keystore generator interface in main tab bar</li>
                        <li><b>Bottom Sheet Tab</b> - Same interface accessible in editor bottom sheet</li>
                        <li><b>Main Menu Integration</b> - Dialog-based keystore generation</li>
                        <li><b>Sidebar Action</b> - Quick access button that opens main editor tab</li>
                        <li><b>Documentation System</b> - Integrated help and tooltips</li>
                    </ul>

                    <h4>Technical Features:</h4>
                    <ul>
                        <li>Common fragment reused across all integration points</li>
                        <li>Plugin resource loading with proper inflaters</li>
                        <li>Asynchronous keystore generation</li>
                        <li>IDE service integration for project access</li>
                        <li>Comprehensive error handling and validation</li>
                    </ul>

                    <p>This showcases how a single plugin can provide multiple ways to access its functionality.</p>
                """.trimIndent(),
                buttons = listOf(
                    PluginTooltipButton(
                        description = "Plugin Development Guide",
                        uri = "plugin/development/guide",
                        order = 0
                    )
                )
            ),

            // Editor tab documentation
            PluginTooltipEntry(
                tag = "keystore_generator.editor_tab",
                summary = "<b>Keystore Generator Tab</b><br>Full-featured keystore generation interface",
                detail = """
                    <h3>Editor Tab Interface</h3>
                    <p>The main editor tab provides the complete keystore generation interface with real-time validation and progress tracking.</p>

                    <h4>Interface Features:</h4>
                    <ul>
                        <li>Structured form with keystore and certificate sections</li>
                        <li>Real-time form validation with error display</li>
                        <li>Progress bar during keystore generation</li>
                        <li>Success/error status display</li>
                        <li>Clear button to reset the form</li>
                    </ul>

                    <h4>Form Fields:</h4>
                    <ul>
                        <li><b>Keystore Name</b> - Filename for the .jks file</li>
                        <li><b>Keystore Password</b> - Password to protect the keystore</li>
                        <li><b>Key Alias</b> - Alias for the signing key</li>
                        <li><b>Key Password</b> - Password for the signing key</li>
                        <li><b>Certificate Details</b> - Distinguished name information</li>
                    </ul>

                    <p>Access from sidebar → Generate Keystore or bottom sheet → Keystore Gen tab.</p>
                """.trimIndent()
            ),

            // Keystore security documentation
            PluginTooltipEntry(
                tag = "keystore_generator.security",
                summary = "<b>Keystore Security</b><br>Best practices for keystore management",
                detail = """
                    <h3>Keystore Security Best Practices</h3>
                    <p>Your keystore is critical for app signing. Follow these security guidelines:</p>

                    <h4>Password Security:</h4>
                    <ul>
                        <li>Use strong, unique passwords (12+ characters)</li>
                        <li>Include uppercase, lowercase, numbers, and symbols</li>
                        <li>Never reuse passwords from other accounts</li>
                        <li>Store passwords securely (password manager recommended)</li>
                    </ul>

                    <h4>File Security:</h4>
                    <ul>
                        <li>Keep multiple secure backups of your keystore file</li>
                        <li>Never commit keystores to version control</li>
                        <li>Store in secure, encrypted locations</li>
                        <li>Limit access to essential team members only</li>
                    </ul>

                    <h4>Certificate Fields:</h4>
                    <ul>
                        <li><b>CN (Common Name)</b> - Your name or organization</li>
                        <li><b>OU (Organizational Unit)</b> - Department (optional)</li>
                        <li><b>O (Organization)</b> - Company name (optional)</li>
                        <li><b>L (Locality)</b> - City (optional)</li>
                        <li><b>ST (State)</b> - State/Province (optional)</li>
                        <li><b>C (Country)</b> - Two-letter country code</li>
                    </ul>

                    <p><b>⚠️ Warning:</b> Losing your keystore means you cannot update your published app!</p>
                """.trimIndent()
            )
        )
    }

    override fun onDocumentationInstall(): Boolean {
        context.logger.info("Installing Keystore Generator Plugin documentation")
        return true
    }

    override fun onDocumentationUninstall() {
        context.logger.info("Removing Keystore Generator Plugin documentation")
    }
}