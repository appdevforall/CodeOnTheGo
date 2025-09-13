package com.example.sampleplugin

import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.example.sampleplugin.fragments.BuildStatusFragment
import com.example.sampleplugin.fragments.GoBotFragment
import com.example.sampleplugin.fragments.HelloPluginFragment
import com.example.sampleplugin.services.HelloService
import com.example.sampleplugin.services.HelloServiceImpl
import com.example.sampleplugin.utils.JsonToKotlinConverter
import com.example.sampleplugin.utils.JsonToKotlinConverter.Language
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginMetadata
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeUIService
import org.json.JSONException
import java.io.File

class HelloWorldPlugin : IPlugin, UIExtension {
    
    override val metadata = PluginMetadata(
        id = "com.example.helloworld",
        name = "Hello World Plugin",
        version = "1.0.0",
        description = "A simple plugin that demonstrates COGO plugin capabilities",
        author = "COGO Team",
        minIdeVersion = "2.1.0",
        dependencies = listOf("com.itsaky.androidide.base"),
        permissions = listOf("IDE_SETTINGS", "FILESYSTEM_READ")
    )
    
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
    
    override fun contributeToMainMenu(): List<MenuItem> {
        return listOf(
            MenuItem(
                isEnabled = buildService.isBuildInProgress().not(),
                id = "json_to_kotlin",
                title = "JSON to Kotlin Data Class",
                action = { showJsonToKotlinDialog() }
            )
        )
    }
    
    override fun contributeToContextMenu(context: ContextMenuContext): List<MenuItem> {
        return listOf(
            MenuItem(
                isEnabled = buildService.isBuildInProgress().not(),
                id = "json_to_kotlin",
                title = "JSON to Kotlin Data Class",
                action = { showJsonToKotlinDialog() }
            )
        )
    }
    
    override fun contributeToEditorBottomSheet(): List<TabItem> {
        return listOf(
            TabItem(
                id = "hello_plugin_tab",
                title = "Hello Plugin",
                fragmentFactory = { HelloPluginFragment() },
                isEnabled = true,
                isVisible = true,
                order = 0
            ),
            TabItem(
                id = "go_bot_tab",
                title = "GoBot",
                fragmentFactory = { GoBotFragment() },
                isEnabled = true,
                isVisible = true,
                order = 1
            ),
            TabItem(
                id = "build_status_tab",
                title = "Build Status",
                fragmentFactory = { BuildStatusFragment() },
                isEnabled = true,
                isVisible = true,
                order = 2
            )
        )
    }
    
    private fun showToast(message: String) {
        try {
            Toast.makeText(context.androidContext, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            context.logger.info("Toast: $message (${e.message})")
        }
    }
    
    private fun showJsonToKotlinDialog() {
        try {
            context.logger.info("Attempting to show JSON to Kotlin dialog")
            val uiService = context.services.get(IdeUIService::class.java)
            
            if (uiService == null) {
                showSampleJsonConversion()
                return
            }
            
            if (!uiService.isUIAvailable()) {
                showSampleJsonConversion()
                return
            }
            
            val activity = uiService.getCurrentActivity()
            if (activity == null) {
                showSampleJsonConversion()
                return
            }
            
            activity.runOnUiThread {
                showInputDialog(activity)
            }
            
        } catch (e: Exception) {
            context.logger.error("Error in JSON to Kotlin conversion", e)
            showToast("Error: ${e.message}")
            showSampleJsonConversion()
        }
    }

    private fun showInputDialog(activity: android.app.Activity) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_json_to_kotlin, null)

        // Get references to views from XML
        val jsonInput = dialogView.findViewById<EditText>(R.id.et_json_input)
        val classNameInput = dialogView.findViewById<EditText>(R.id.et_class_name)
        val packageNameInput = dialogView.findViewById<EditText>(R.id.et_package_name)

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
            .setNeutralButton("Use Sample") { _, _ ->
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

    private fun showSampleJsonConversion() {
        val sampleJson = """
        {
            "name": "John Doe",
            "age": 30,
            "isActive": true
        }
        
        """.trimIndent()
        
        showToast("Converting sample JSON...")
        convertJsonToKotlin(sampleJson, "User", "com.example.model")
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
}