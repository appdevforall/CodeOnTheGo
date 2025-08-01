/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.sampleplugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginMetadata
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeUIService
import android.widget.EditText
import android.widget.Button
import android.widget.Toast
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import com.example.sampleplugin.R
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException

class HelloWorldPlugin : IPlugin, UIExtension {
    

    override val metadata = PluginMetadata(
        id = "com.example.helloworld",
        name = "Hello World Plugin",
        version = "1.0.0",
        description = "A simple hello world plugin that demonstrates COGO plugin capabilities",
        author = "COGO Team",
        minIdeVersion = "2.1.0",
        dependencies = listOf("com.itsaky.androidide.base"),
        permissions = listOf("IDE_SETTINGS", "FILESYSTEM_READ")
    )
    
    private lateinit var context: PluginContext
    
    /**
     * Initialize the plugin with the provided context.
     * 
     * Available during initialization:
     * - context.pluginId: The unique ID of this plugin
     * - context.logger: Logger for this plugin (info, warn, error, debug)
     * - context.androidContext: Android Context for system services
     * - context.services: Service registry for accessing IDE services
     * 
     * Services that may be available (check for null):
     * - IdeProjectService: Access to project information
     * - IdeEditorService: Access to editor state and open files
     * - IdeUIService: Access to UI context for dialogs
     * 
     * What you can do in initialize():
     * - Store the context for later use
     * - Initialize plugin-specific data structures
     * - Register internal services with context.services.register()
     * - Validate plugin requirements and permissions
     * - Set up configuration or preferences
     * 
     * What NOT to do in initialize():
     * - Access IDE services (they may not be ready yet)
     * - Show UI dialogs or toasts
     * - Perform heavy computations or I/O operations
     * - Access project files or editor content
     * 
     * @param context The plugin context provided by AndroidIDE
     * @return true if initialization succeeded, false otherwise
     */
    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("HelloWorldPlugin initialized")
            true
        } catch (e: Exception) {
            context.logger.error("HelloWorldPlugin initialization failed", e)
            false
        }
    }
    
    override fun activate(): Boolean {
        context.logger.info("HelloWorldPlugin: Activating plugin")
        
        // Register a simple service
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
    
    // UIExtension implementation
    override fun contributeToMainMenu(): List<MenuItem> {
        return listOf(
            MenuItem(
                id = "json_to_kotlin",
                title = "JSON to Kotlin Data Class",
                action = {
                    showJsonToKotlinDialog()
                }
            )
        )
    }
    
    override fun contributeToContextMenu(context: ContextMenuContext): List<MenuItem> {
        return emptyList()
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
            ), TabItem(
                id = "go_bot_tab",
                title = "GoBot",
                fragmentFactory = { GoBotFragment() },
                isEnabled = true,
                isVisible = true,
                order = 1
            )
        )
    }
    
    
    private fun showToast(message: String) {
        try {
            Toast.makeText(context.androidContext, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // Fallback to logging if toast fails
            context.logger.info("Toast: $message (${e.message})")
        }
    }
    
    private fun showJsonToKotlinDialog() {
        try {
            context.logger.info("Attempting to show JSON to Kotlin dialog")
            val uiService = context.services.get(IdeUIService::class.java)
            
            if (uiService == null) {
                context.logger.warn("IdeUIService is null - using fallback")
                showToast("UI service not available, using sample conversion...")
                showSampleJsonConversion()
                return
            }
            
            context.logger.info("IdeUIService found, checking if UI is available")
            if (!uiService.isUIAvailable()) {
                context.logger.warn("UI is not available according to service")
                showToast("UI not ready, using sample conversion...")
                showSampleJsonConversion()
                return
            }
            
            context.logger.info("UI is available, getting current activity")
            val activity = uiService.getCurrentActivity()
            if (activity == null) {
                context.logger.warn("Current activity is null")
                showToast("No activity available, using sample conversion...")
                showSampleJsonConversion()
                return
            }
            
            context.logger.info("Got activity: ${activity.javaClass.simpleName}, showing dialog")
            showToast("Opening JSON to Kotlin converter...")
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
        try {
            // Create dialog layout programmatically
            val dialogLayout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
            }
            
            // JSON input section
            dialogLayout.addView(android.widget.TextView(activity).apply {
                text = "Paste your JSON here"
                textSize = 16f
                setPadding(0, 0, 0, 8)
            })
            
            val jsonInput = android.widget.EditText(activity).apply {
                hint = "{\n  \"example\": \"value\",\n  \"number\": 123\n}"
                minLines = 8
                maxLines = 15
                gravity = android.view.Gravity.TOP
                isVerticalScrollBarEnabled = true
            }
            dialogLayout.addView(jsonInput)
            
            // Class name section
            dialogLayout.addView(android.widget.TextView(activity).apply {
                text = "Class Name:"
                textSize = 16f
                setPadding(0, 16, 0, 8)
            })
            
            val classNameInput = android.widget.EditText(activity).apply {
                hint = "e.g., User"
                setText("User")
                setSingleLine(true)
            }
            dialogLayout.addView(classNameInput)
            
            // Package name section
            dialogLayout.addView(android.widget.TextView(activity).apply {
                text = "Package Name:"
                textSize = 16f
                setPadding(0, 16, 0, 8)
            })
            
            val packageNameInput = android.widget.EditText(activity).apply {
                hint = "e.g., com.example.model"
                setText("com.example.model")
                setSingleLine(true)
            }
            dialogLayout.addView(packageNameInput)
            
            // Create and show the dialog
            val dialog = AlertDialog.Builder(activity)
                .setTitle("JSON to Kotlin Data Class")
                .setView(dialogLayout)
                .setPositiveButton("Convert") { _, _ ->
                    val jsonText = jsonInput.text.toString().trim()
                    val className = classNameInput.text.toString().trim()
                    val packageName = packageNameInput.text.toString().trim()
                    
                    // Validate inputs
                    when {
                        jsonText.isEmpty() -> {
                            showToast("Please enter JSON data")
                        }
                        className.isEmpty() -> {
                            showToast("Please enter a class name")
                        }
                        packageName.isEmpty() -> {
                            showToast("Please enter a package name")
                        }
                        else -> {
                            convertJsonToKotlin(jsonText, className, packageName)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Use Sample") { _, _ ->
                    // Populate the dialog fields with sample data
                    val sampleJson = """
                    {
                        "name": "John Doe",
                        "age": 30,
                        "isActive": true
                    }
                    """.trimIndent()
                    
                    jsonInput.setText(sampleJson)
                    classNameInput.setText("User")
                    packageNameInput.setText("com.example.model")
                }
                .create()

            dialog.show()
            
        } catch (e: Exception) {
            context.logger.error("Error creating JSON to Kotlin dialog", e)
            showToast("Error creating dialog: ${e.message}")
            // Fallback to sample conversion
            showSampleJsonConversion()
        }
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
            val jsonObject = JSONObject(jsonString)
            val kotlinCode = generateKotlinDataClass(jsonObject, className, packageName)
            
            val suggestedPath = if (packageName.isNotEmpty()) {
                "app/src/main/java/${packageName.replace('.', '/')}/$className.kt"
            } else {
                "app/src/main/java/$className.kt"
            }
            
            saveKotlinFileToProject(kotlinCode, suggestedPath, className)
            
        } catch (e: JSONException) {
            context.logger.error("Invalid JSON format", e)
            showToast("Invalid JSON: ${e.message}")
        } catch (e: Exception) {
            context.logger.error("Conversion error", e)
            showToast("Error: ${e.message}")
        }
    }
    
    private fun generateKotlinDataClass(jsonObject: JSONObject, className: String, packageName: String): String {
        val builder = StringBuilder()
        
        if (packageName.isNotEmpty()) {
            builder.appendLine("package $packageName")
            builder.appendLine()
        }
        
        builder.appendLine("import kotlinx.serialization.Serializable")
        builder.appendLine("import com.google.gson.annotations.SerializedName")
        builder.appendLine()
        builder.appendLine("@Serializable")
        builder.append("data class $className(")
        
        val properties = mutableListOf<String>()
        val keys = jsonObject.keys()
        
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            val kotlinType = getKotlinType(value)
            val propertyName = key.toCamelCase()
            
            val property = buildString {
                appendLine()
                append("    @SerializedName(\"$key\")")
                appendLine()
                append("    val $propertyName: $kotlinType")
                
                when (value) {
                    is String -> append(" = \"\"")
                    is Boolean -> append(" = false")
                    is Int -> append(" = 0")
                    is Double -> append(" = 0.0")
                    is JSONArray -> append(" = emptyList()")
                    is JSONObject -> append(" = $kotlinType()")
                    else -> append("?")
                }
            }
            
            properties.add(property)
        }
        
        builder.append(properties.joinToString(","))
        builder.appendLine()
        builder.appendLine(")")
        
        return builder.toString()
    }
    
    private fun getKotlinType(value: Any): String {
        return when (value) {
            is String -> "String"
            is Boolean -> "Boolean"
            is Int -> "Int"
            is Double -> "Double"
            is JSONArray -> {
                if (value.length() > 0) {
                    val firstItem = value.get(0)
                    "List<${getKotlinType(firstItem)}>"
                } else {
                    "List<Any>"
                }
            }
            is JSONObject -> "Any" // Could be made more sophisticated
            else -> "Any"
        }
    }
    
    private fun String.toCamelCase(): String {
        return this.split("_", "-", " ")
            .mapIndexed { index, word ->
                if (index == 0) word.lowercase()
                else word.replaceFirstChar { it.uppercase() }
            }
            .joinToString("")
    }
    
    
    private fun saveKotlinFileToProject(kotlinCode: String, filePath: String, className: String) {
        try {
            val projectRoot = findProjectRoot()
            if (projectRoot == null) {
                showToast("Could not find project root")
                return
            }
            
            val targetFile = File(projectRoot, filePath)
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(kotlinCode)
            
            showToast("✅ Saved: $className.kt")
            context.logger.info("Saved: ${targetFile.absolutePath}")
            
        } catch (e: Exception) {
            context.logger.error("Error saving file", e)
            showToast("Failed to save: ${e.message}")
        }
    }
    
    /**
     * Finds the project root directory using AndroidIDE's plugin services.
     * 
     * Available Plugin Services:
     * - IdeProjectService: Provides access to project information
     *   • getCurrentProject(): Gets the currently active project
     *   • getAllProjects(): Gets all loaded projects  
     *   • getProjectByPath(path): Finds project by root directory path
     * 
     * - IdeEditorService: Provides access to editor state and files
     *   • getCurrentFile(): Gets the currently active file
     *   • getOpenFiles(): Gets all open files
     *   • isFileOpen(file): Checks if a file is open
     *   • getCurrentSelection(): Gets selected text in editor
     * 
     * - IdeUIService: Provides access to UI context for dialogs
     *   • getCurrentActivity(): Gets current Activity for dialogs
     *   • isUIAvailable(): Checks if UI operations are possible
     * 
     * @return The project root directory, or null if no project is available
     */
    private fun findProjectRoot(): File? {
        context.logger.info("Attempting to find project root...")
        
        val projectService = context.services.get(IdeProjectService::class.java)
        if (projectService == null) {
            context.logger.error("IdeProjectService not available - ensure AndroidIDE has initialized plugin services")
            showToast("Debug: Project service not available")
            return null
        }
        
        context.logger.info("Project service found, getting current project...")
        
        return try {
            val currentProject = projectService.getCurrentProject()
            if (currentProject != null) {
                context.logger.info("Found current project: ${currentProject.name} at ${currentProject.rootDir.absolutePath}")
                showToast("Debug: Found project: ${currentProject.name}")
                currentProject.rootDir
            } else {
                context.logger.warn("No current project available - ensure a project is open in AndroidIDE")
                showToast("Debug: No current project returned from service")
                
                // Try alternative approach - check if we can get project via AndroidIDE's project manager directly
                tryDirectProjectAccess()
            }
        } catch (e: SecurityException) {
            context.logger.error("Permission denied accessing project: ${e.message}")
            showToast("Debug: Permission denied - ${e.message}")
            null
        } catch (e: Exception) {
            context.logger.error("Error accessing project service", e)
            showToast("Debug: Service error - ${e.message}")
            null
        }
    }
    
    /**
     * Alternative method to access project directly when service approach fails
     */
    private fun tryDirectProjectAccess(): File? {
        return try {
            context.logger.info("Trying direct project manager access...")
            
            // Use reflection to access ProjectManagerImpl directly (same as AndroidIdeProjectProvider)
            val projectManagerClass = Class.forName("com.itsaky.androidide.projects.ProjectManagerImpl")
            val getInstanceMethod = projectManagerClass.getMethod("getInstance")
            val projectManager = getInstanceMethod.invoke(null)
            
            // Check if project is initialized
            val projectInitializedField = projectManagerClass.getField("projectInitialized")
            val isInitialized = projectInitializedField.getBoolean(projectManager)
            
            if (!isInitialized) {
                context.logger.warn("Project not yet initialized")
                showToast("Debug: Project not initialized yet")
                return null
            }
            
            val projectDirMethod = projectManagerClass.getMethod("getProjectDir")
            val rootProjectMethod = projectManagerClass.getMethod("getRootProject")
            
            val projectDir = projectDirMethod.invoke(projectManager) as? File
            val rootProject = rootProjectMethod.invoke(projectManager)
            
            context.logger.info("Direct access - projectDir: ${projectDir?.absolutePath}, rootProject: $rootProject")
            
            if (projectDir != null && projectDir.exists() && rootProject != null) {
                context.logger.info("Direct access found project at: ${projectDir.absolutePath}")
                showToast("Debug: Direct access found project")
                projectDir
            } else {
                context.logger.warn("Direct access failed - projectDir exists: ${projectDir?.exists()}, rootProject: $rootProject")
                showToast("Debug: Direct access failed - missing data")
                null
            }
        } catch (e: Exception) {
            context.logger.error("Direct project access failed", e)
            showToast("Debug: Direct access exception - ${e.message}")
            null
        }
    }
}

interface HelloService {
    fun sayHello(): String
    fun getPluginInfo(): String
}

class HelloServiceImpl(private val context: PluginContext) : HelloService {
    override fun sayHello(): String {
        return "Hello from HelloWorld Plugin!"
    }
    
    override fun getPluginInfo(): String {
        return "Plugin ID: ${context.pluginId}, Running in AndroidIDE"
    }
}


class HelloPluginFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Create UI programmatically instead of using XML to avoid R class issues
        val context = requireContext()

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val titleView = android.widget.TextView(context).apply {
            text = "Hello World Plugin Tab"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }

        val descriptionView = android.widget.TextView(context).apply {
            text = "This is a custom tab contributed by the Hello World plugin.\n\nThis demonstrates the plugin system's ability to extend the editor bottom sheet with custom fragments."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val capabilitiesView = android.widget.TextView(context).apply {
            text = "Plugin capabilities:\n• Add custom menu items\n• Contribute to context menus\n• Add custom tabs to the editor bottom sheet\n• Access IDE services (project, editor, UI)\n• Create programmatic UIs"
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }

        val testButton = android.widget.Button(context).apply {
            text = "Test Plugin Action"
            setOnClickListener {
                Toast.makeText(
                    context,
                    "Plugin action executed from programmatic UI!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        layout.addView(titleView)
        layout.addView(descriptionView)
        layout.addView(capabilitiesView)
        layout.addView(testButton)

        return layout
    }
}


class GoBotFragment : Fragment() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputEditText: EditText
    private var typingIndicatorView: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        // Title Bar
        val titleView = TextView(context).apply {
            text = "🤖 GoBot Chat"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 32
            }
        }

        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        chatContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(chatContainer)

        val inputLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
            setPadding(0, 8, 0, 8)
        }

        val editTextHeight = 56  // in dp

        val inputEditText = EditText(context).apply {
            hint = "Type a message..."
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(context, editTextHeight), 1f).apply {
                marginEnd = 8
            }
            setPadding(24, 0, 24, 0)  // vertical padding not needed with fixed height
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#F0F0F0"))
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        val sendButton = Button(context).apply {
            text = "Send"
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(context, editTextHeight)  // match height with EditText
            )
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#4CAF50"))
            }
            setPadding(32, 0, 32, 0)
        }
        sendButton.setOnClickListener {
            val userMessage = inputEditText.text.toString().trim()
            if (userMessage.isNotEmpty()) {
                addMessage(userMessage, isUser = true)
                inputEditText.text.clear()
                simulateBotReply()
            }
        }

        inputLayout.addView(inputEditText)
        inputLayout.addView(sendButton)

        rootLayout.addView(titleView)
        rootLayout.addView(scrollView)
        rootLayout.addView(inputLayout)

        // Initial greeting
        Handler(Looper.getMainLooper()).postDelayed({
            addMessage("Hi, I'm GoBot. How can I help you today?", isUser = false)
        }, 400)

        // Scroll adjustment for keyboard
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }

        return rootLayout
    }

    private fun addMessage(text: String, isUser: Boolean) {
        val context = requireContext()

        val messageView = TextView(context).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(if (isUser) Color.parseColor("#DCF8C6") else Color.parseColor("#E3F2FD"))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(context, 16)
                gravity = if (isUser) Gravity.END else Gravity.START
            }
            layoutParams = params
        }

        chatContainer.addView(messageView)
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun simulateBotReply() {
        showTypingIndicator()

        Handler(Looper.getMainLooper()).postDelayed({
            removeTypingIndicator()
            addMessage("I'm here to assist you with anything.", isUser = false)
        }, 1500) // Simulate thinking
    }

    private fun showTypingIndicator() {
        val context = requireContext()

        if (typingIndicatorView == null) {
            typingIndicatorView = TextView(context).apply {
                text = "GoBot is typing..."
                setTextColor(Color.GRAY)
                setTypeface(null, Typeface.ITALIC)
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.START
                    topMargin = 8
                }
            }
            chatContainer.addView(typingIndicatorView)
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun removeTypingIndicator() {
        typingIndicatorView?.let {
            chatContainer.removeView(it)
            typingIndicatorView = null
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}


