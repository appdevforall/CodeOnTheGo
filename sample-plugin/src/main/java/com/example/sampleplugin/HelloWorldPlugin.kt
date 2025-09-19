package com.example.sampleplugin

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.example.sampleplugin.R
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.example.sampleplugin.fragments.BuildStatusFragment
import com.example.sampleplugin.fragments.GoBotFragment
import com.example.sampleplugin.fragments.HelloPluginFragment
import com.example.sampleplugin.services.HelloService
import com.example.sampleplugin.services.HelloServiceImpl
import com.example.sampleplugin.utils.JsonToKotlinConverter
import com.example.sampleplugin.utils.JsonToKotlinConverter.Language
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService
import org.json.JSONException
import java.io.File

class HelloWorldPlugin : IPlugin, UIExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    private lateinit var buildService: IdeBuildService


    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            buildService = context.services.get(IdeBuildService::class.java)!!
            context.logger.info("HelloWorldPlugin initialized")
            true
        } catch (e: Exception) {
            context.logger.error("HelloWorldPlugin initialization failed", e)
            false
        }
    }
    
    override fun activate(): Boolean {
        context.logger.info("HelloWorldPlugin: Activating plugin")
        context.services.register(HelloService::class.java, HelloServiceImpl(context))
        return true
    }
    
    override fun deactivate(): Boolean {
        context.logger.info("HelloWorldPlugin: Deactivating plugin")
        return true
    }
    
    override fun dispose() {
        context.logger.info("HelloWorldPlugin: Disposing plugin")
    }
    
    override fun getMainMenuItems(): List<MenuItem> {
        return listOf(
            MenuItem(
                isEnabled = buildService.isBuildInProgress().not(),
                id = "json_to_kotlin",
                title = "JSON to Kotlin Data Class",
                action = { showJsonToKotlinDialog() }
            )
        )
    }
    
    override fun getContextMenuItems(context: ContextMenuContext): List<MenuItem> {
        return listOf(
            MenuItem(
                isEnabled = buildService.isBuildInProgress().not(),
                id = "json_to_kotlin",
                title = "JSON to Kotlin Data Class",
                action = { showJsonToKotlinDialog() }
            )
        )
    }
    
    override fun getEditorTabs(): List<TabItem> {
        return listOf(
            TabItem(
                id = "hello_plugin_tab",
                title = "Hello Plugin",
                fragmentFactory = { HelloPluginFragment() },
                isEnabled = true,
                isVisible = true,
                order = 0
            ),
        )
    }

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "json_converter",
                title = "JSON Converter",
                icon = android.R.drawable.ic_menu_edit,
                isEnabled = true,
                isVisible = true,
                order = 0,
                action = { showJsonToKotlinDialog() }
            ),
            NavigationItem(
                id = "plugin_info",
                title = "Plugin Info",
                icon = android.R.drawable.ic_menu_info_details,
                isEnabled = true,
                isVisible = true,
                order = 1,
                action = { showPluginInfo() }
            )
        )
    }
    
    private fun showToast(message: String) {
        Toast.makeText(context.androidContext, message, Toast.LENGTH_LONG).show()
    }

    private fun showPluginInfo() {
        // Show plugin overview tooltip when info button is clicked
        val uiService = context.services.get(IdeUIService::class.java)
        val tooltipService = context.services.get(IdeTooltipService::class.java)

        val activity = uiService?.getCurrentActivity()
        if (activity != null && tooltipService != null) {
            // Find any view to anchor the tooltip
            val anchorView = activity.window.decorView.rootView
            tooltipService.showTooltip(anchorView, "plugin_sampleplugin", "sampleplugin.overview")
        } else {
            // Fallback to toast if tooltip service is not available
            showToast("Sample Plugin - JSON Converter & Demo")
        }
    }
    
    private fun showJsonToKotlinDialog() {
        context.logger.info("Showing JSON to Kotlin dialog")
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

        activity.runOnUiThread {
            showInputDialog(activity)
        }
    }

    private fun showInputDialog(activity: android.app.Activity) {
        // Use the plugin's resource context for proper layout inflation
        val pluginContext = PluginFragmentHelper.getPluginContext("com.example.sampleplugin")
            ?: context.androidContext

        val inflater = PluginFragmentHelper.getPluginInflater(
            "com.example.sampleplugin",
            LayoutInflater.from(pluginContext)
        )
        val dialogView = inflater.inflate(R.layout.dialog_json_to_kotlin, null)

        // Get references to views from XML
        val jsonInput = dialogView.findViewById<EditText>(R.id.et_json_input)
        val classNameInput = dialogView.findViewById<EditText>(R.id.et_class_name)
        val packageNameInput = dialogView.findViewById<EditText>(R.id.et_package_name)

        // Add click listener to show help tooltip when long-pressing on the dialog view
        val tooltipService = context.services.get(IdeTooltipService::class.java)
        dialogView.setOnLongClickListener {
            tooltipService?.showTooltip(dialogView, "plugin_sampleplugin", "sampleplugin.json_converter")
            true
        }

        AlertDialog.Builder(activity)
            .setTitle("JSON to Data Class Converter")
            .setView(dialogView)
            .setPositiveButton("Convert") { _, _ ->
                val jsonText = jsonInput.text.toString().trim()
                val className = classNameInput.text.toString().trim()
                val packageName = packageNameInput.text.toString().trim()

                when {
                    jsonText.isEmpty() -> showToast("Please enter JSON data")
                    className.isEmpty() -> showToast("Please enter a class name")
                    packageName.isEmpty() -> showToast("Please enter a package name")
                    else -> {
                        try {
                            val generatedCode = JsonToKotlinConverter.convertToKotlinDataClass(jsonText, className, packageName)
                            val filePath = JsonToKotlinConverter.getSuggestedFilePath(className, packageName, JsonToKotlinConverter.Language.KOTLIN)
                            saveFileToProject(generatedCode, filePath, className, JsonToKotlinConverter.Language.KOTLIN)
                        } catch (e: Exception) {
                            showToast("Conversion failed: ${e.message}")
                            context.logger.error("JSON conversion error", e)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Use Sample") { dialog, which ->
                val sampleJson = """
            {
                "name": "John Doe",
                "age": 30,
                "isActive": true,
                "address": {
                    "street": "123 Main St",
                    "city": "New York"
                },
                "hobbies": ["reading", "coding"],
                "friends": [
                    {
                        "name": "Jane",
                        "age": 25
                    }
                ]
            }
            """.trimIndent()

                jsonInput.setText(sampleJson)
                classNameInput.setText("User")
                packageNameInput.setText("com.example.model")
            }
            .create()
            .show()
    }

    private fun convertJsonToKotlin(jsonString: String, className: String, packageName: String) {
        try {
            val kotlinCode = JsonToKotlinConverter.convertToKotlinDataClass(jsonString, className, packageName)
            val suggestedPath = JsonToKotlinConverter.getSuggestedFilePath(className, packageName, Language.KOTLIN)
            
            saveFileToProject(kotlinCode, suggestedPath, className, Language.KOTLIN)
            
        } catch (e: JSONException) {
            context.logger.error("Invalid JSON format", e)
            showToast("Invalid JSON: ${e.message}")
        } catch (e: Exception) {
            context.logger.error("Conversion error", e)
            showToast("Error: ${e.message}")
        }
    }
    
    private fun saveFileToProject(code: String, filePath: String, className: String, language: Language) {
        try {
            val projectRoot = findProjectRoot()
            if (projectRoot == null) {
                showToast("Could not find project root")
                return
            }
            
            val targetFile = File(projectRoot, filePath)
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(code)
            
            val extension = when (language) {
                Language.KOTLIN -> "kt"
                Language.JAVA -> "java"
            }
            showToast("✅ Saved: $className.$extension")
            context.logger.info("Saved: ${targetFile.absolutePath}")
            
        } catch (e: Exception) {
            context.logger.error("Error saving file", e)
            showToast("Failed to save: ${e.message}")
        }
    }
    
    private fun findProjectRoot(): File? {
        context.logger.info("Attempting to find project root...")

        val projectService = context.services.get(IdeProjectService::class.java)
        if (projectService == null) {
            context.logger.error("IdeProjectService not available")
            return null
        }

        return try {
            val currentProject = projectService.getCurrentProject()
            if (currentProject != null) {
                context.logger.info("Found current project: ${currentProject.name} at ${currentProject.rootDir.absolutePath}")
                currentProject.rootDir
            } else {
                context.logger.warn("No current project available")
                null
            }
        } catch (e: SecurityException) {
            context.logger.error("Permission denied accessing project: ${e.message}")
            null
        } catch (e: Exception) {
            context.logger.error("Error accessing project service", e)
            null
        }
    }

    override fun getTooltipCategory(): String = "plugin_sampleplugin"

    override fun getTooltipEntries(): List<PluginTooltipEntry> {
        return listOf(
            // Main feature documentation
            PluginTooltipEntry(
                tag = "sampleplugin.json_converter",
                summary = "<b>JSON to Kotlin Converter</b><br>Convert JSON data to Kotlin data classes instantly",
                detail = """
                    <h3>JSON to Kotlin Data Class Converter</h3>
                    <p>This feature allows you to quickly convert JSON data into properly formatted Kotlin data classes.</p>

                    <h4>How to use:</h4>
                    <ol>
                        <li>Access via Main Menu → JSON to Kotlin Data Class</li>
                        <li>Or use the sidebar button</li>
                        <li>Paste your JSON data</li>
                        <li>Enter class name and package</li>
                        <li>Click Convert to generate the data class</li>
                    </ol>

                    <h4>Features:</h4>
                    <ul>
                        <li>Automatic type inference</li>
                        <li>Nested object support</li>
                        <li>Array handling</li>
                        <li>Nullable field detection</li>
                        <li>Direct file creation in project</li>
                    </ul>

                    <p><i>Tip: Use the "Use Sample" button to try it out with example data!</i></p>
                """.trimIndent(),
                buttons = listOf(
                    PluginTooltipButton(
                        description = "View Examples",
                        uri = "plugin/sampleplugin/examples/json-converter",
                        order = 0
                    )
                )
            ),

            // Plugin overview
            PluginTooltipEntry(
                tag = "sampleplugin.overview",
                summary = "<b>Sample Plugin</b><br>Demonstration of CodeOnTheGo plugin capabilities",
                detail = """
                    <h3>Sample Plugin Overview</h3>
                    <p>This plugin demonstrates the capabilities of the CodeOnTheGo plugin system.</p>

                    <h4>Features Included:</h4>
                    <ul>
                        <li><b>JSON to Kotlin Converter</b> - Convert JSON to data classes</li>
                        <li><b>UI Extensions</b> - Custom tabs in editor bottom sheet</li>
                        <li><b>Menu Integration</b> - Main menu and context menu items</li>
                        <li><b>Sidebar Actions</b> - Quick access buttons in navigation</li>
                        <li><b>Service Integration</b> - Access to IDE services</li>
                        <li><b>Documentation</b> - Integrated help system (you're reading it!)</li>
                    </ul>

                    <h4>Technical Details:</h4>
                    <ul>
                        <li>APK-based plugin with full resource support</li>
                        <li>XML layouts with Android widgets</li>
                        <li>Custom fragments and dialogs</li>
                        <li>IDE service integration</li>
                    </ul>
                """.trimIndent(),
                buttons = listOf(
                    PluginTooltipButton(
                        description = "GitHub",
                        uri = "plugin/sampleplugin/github",
                        order = 0
                    ),
                    PluginTooltipButton(
                        description = "Report Issue",
                        uri = "plugin/sampleplugin/issues",
                        order = 1
                    )
                )
            ),

            // Editor tab documentation
            PluginTooltipEntry(
                tag = "sampleplugin.editor_tab",
                summary = "<b>Hello Plugin Tab</b><br>Sample editor bottom sheet integration",
                detail = """
                    <h3>Editor Tab Integration</h3>
                    <p>The Hello Plugin tab demonstrates how plugins can add custom tabs to the editor's bottom sheet.</p>

                    <h4>What it shows:</h4>
                    <ul>
                        <li>Custom fragment with XML layout</li>
                        <li>Resource loading from plugin APK</li>
                        <li>Interactive UI elements</li>
                        <li>Integration with IDE services</li>
                    </ul>

                    <p>Access it from the editor's bottom sheet tabs.</p>
                """.trimIndent()
            ),

            // Quick help for JSON format
            PluginTooltipEntry(
                tag = "sampleplugin.json_format_help",
                summary = "<b>JSON Format Help</b><br>Supported JSON structures for conversion",
                detail = """
                    <h3>Supported JSON Formats</h3>

                    <h4>Basic Types:</h4>
                    <pre>
                    {
                        "string": "text",
                        "number": 123,
                        "decimal": 45.67,
                        "boolean": true,
                        "nullable": null
                    }
                    </pre>

                    <h4>Nested Objects:</h4>
                    <pre>
                    {
                        "user": {
                            "name": "John",
                            "profile": {
                                "bio": "Developer"
                            }
                        }
                    }
                    </pre>

                    <h4>Arrays:</h4>
                    <pre>
                    {
                        "items": [1, 2, 3],
                        "users": [
                            {"name": "Alice"},
                            {"name": "Bob"}
                        ]
                    }
                    </pre>
                """.trimIndent()
            )
        )
    }

    override fun onDocumentationInstall(): Boolean {
        context.logger.info("Installing Sample Plugin documentation")
        return true
    }

    override fun onDocumentationUninstall() {
        context.logger.info("Removing Sample Plugin documentation")
    }
}