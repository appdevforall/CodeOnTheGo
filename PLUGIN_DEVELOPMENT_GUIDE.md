# AndroidIDE Plugin Development Guide

## Table of Contents
1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [Plugin Architecture](#plugin-architecture)
4. [Plugin Development](#plugin-development)
5. [Available Services](#available-services)
6. [UI Extensions](#ui-extensions)
7. [Plugin Management](#plugin-management)
8. [Examples](#examples)
9. [Best Practices](#best-practices)

## Overview

AndroidIDE now has a fully functional plugin system that allows developers to extend the IDE's functionality. Plugins can:

- ✅ **Add custom tabs** to the editor bottom sheet
- ✅ **Contribute menu items** to the main menu and context menus
- ✅ **Access project information** and file system
- ✅ **Create beautiful UIs** programmatically
- ✅ **Be enabled/disabled** at runtime with persistent state
- ✅ **Integrate with IDE services** (Project, Editor, UI)
- ✅ **Save files to the current project**
- ✅ **Create dialogs and interactive interfaces**

## Quick Start

### Creating Your First Plugin

1. **Create a new Android Library module** in your project
2. **Add the plugin API dependency**:
```kotlin
dependencies {
    implementation project(':plugin-api')
    // Your other dependencies
}
```

3. **Create your plugin class**:
```kotlin
class MyPlugin : IPlugin, UIExtension {
    override val metadata = PluginMetadata(
        id = "com.example.myplugin",
        name = "My Awesome Plugin",
        version = "1.0.0",
        description = "A plugin that does awesome things",
        author = "Your Name",
        minIdeVersion = "2.1.0",
        dependencies = listOf("com.itsaky.androidide.base"),
        permissions = listOf("IDE_SETTINGS", "FILESYSTEM_READ")
    )
    
    private lateinit var context: PluginContext
    
    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        context.logger.info("MyPlugin initialized")
        return true
    }
    
    override fun activate(): Boolean {
        context.logger.info("MyPlugin activated")
        return true
    }
    
    override fun deactivate(): Boolean {
        context.logger.info("MyPlugin deactivated")
        return true
    }
    
    override fun dispose() {
        context.logger.info("MyPlugin disposed")
    }
    
    // Add a menu item
    override fun contributeToMainMenu(): List<MenuItem> {
        return listOf(
            MenuItem(
                id = "my_action",
                title = "My Plugin Action",
                action = {
                    context.services.get(IdeUIService::class.java)?.let { uiService ->
                        uiService.getCurrentActivity()?.runOnUiThread {
                            showMyDialog(uiService.getCurrentActivity()!!)
                        }
                    }
                }
            )
        )
    }
    
    // Add a tab to the editor bottom sheet
    override fun contributeToEditorBottomSheet(): List<TabItem> {
        return listOf(
            TabItem(
                id = "my_tab",
                title = "My Tab",
                fragmentFactory = { MyFragment() },
                isEnabled = true,
                isVisible = true,
                order = 0
            )
        )
    }
    
    override fun contributeToContextMenu(context: ContextMenuContext): List<MenuItem> {
        return emptyList()
    }
}
```

4. **Build your plugin** as an AAR and place it in AndroidIDE's plugins directory

## Plugin Architecture

### Core Components

```
Plugin System Architecture
├── PluginManager (Central management)
│   ├── Plugin loading & lifecycle
│   ├── Enable/disable functionality
│   ├── State persistence
│   └── Service integration
├── Plugin API (Interfaces & Services)
│   ├── IPlugin (Core plugin interface)
│   ├── UIExtension (UI contributions)
│   ├── IdeProjectService (Project access)
│   ├── IdeEditorService (Editor access)
│   └── IdeUIService (UI operations)
└── Plugin Runtime
    ├── PluginContext (Services & logging)
    ├── ServiceRegistry (Dependency injection)
    └── Activity integration
```

### Plugin Lifecycle

1. **Loading**: Plugin AAR is loaded by PluginManager
2. **Initialization**: `initialize(context)` called with PluginContext
3. **Activation**: `activate()` called if plugin is enabled
4. **Runtime**: Plugin contributes to UI and responds to events
5. **Deactivation**: `deactivate()` called when disabled
6. **Disposal**: `dispose()` called when unloading

## Plugin Development

### Plugin Interface

Every plugin must implement `IPlugin`:

```kotlin
interface IPlugin {
    val metadata: PluginMetadata
    fun initialize(context: PluginContext): Boolean
    fun activate(): Boolean
    fun deactivate(): Boolean
    fun dispose()
}
```

### Plugin Metadata

```kotlin
data class PluginMetadata(
    val id: String,                    // Unique identifier
    val name: String,                  // Display name
    val version: String,               // Version string
    val description: String,           // Description
    val author: String,                // Author name
    val minIdeVersion: String,         // Minimum IDE version
    val dependencies: List<String>,    // Plugin dependencies
    val permissions: List<String>      // Required permissions
)
```

### Available Permissions

```kotlin
enum class PluginPermission {
    FILESYSTEM_READ,      // Read files from project
    FILESYSTEM_WRITE,     // Write files to project
    NETWORK_ACCESS,       // Access network resources
    SYSTEM_COMMANDS,      // Execute system commands
    IDE_SETTINGS,         // Modify IDE settings
    PROJECT_STRUCTURE     // Modify project structure
}
```

## Available Services

Plugins access IDE functionality through services available in `PluginContext`:

### IdeProjectService
```kotlin
val projectService = context.services.get(IdeProjectService::class.java)

// Get current project
val currentProject = projectService?.getCurrentProject()
currentProject?.let { project ->
    println("Project: ${project.name} at ${project.rootDir}")
    
    // Get modules
    project.getModules().forEach { module ->
        println("Module: ${module.name} (${module.type})")
    }
    
    // Get build files
    project.getBuildFiles().forEach { buildFile ->
        println("Build file: ${buildFile.absolutePath}")
    }
}
```

### IdeEditorService
```kotlin
val editorService = context.services.get(IdeEditorService::class.java)

// Get current file
val currentFile = editorService?.getCurrentFile()
println("Current file: ${currentFile?.absolutePath}")

// Get all open files
val openFiles = editorService?.getOpenFiles() ?: emptyList()
println("Open files: ${openFiles.size}")

// Check if file is open
val isOpen = editorService?.isFileOpen(someFile) ?: false
```

### IdeUIService
```kotlin
val uiService = context.services.get(IdeUIService::class.java)

// Check if UI is available
if (uiService?.isUIAvailable() == true) {
    // Get current activity for dialogs
    val activity = uiService.getCurrentActivity()
    activity?.runOnUiThread {
        // Show dialogs, create UI elements
        showMyDialog(activity)
    }
}
```

## UI Extensions

### UIExtension Interface

Implement `UIExtension` to contribute to the IDE's user interface:

```kotlin
interface UIExtension {
    fun contributeToMainMenu(): List<MenuItem>
    fun contributeToContextMenu(context: ContextMenuContext): List<MenuItem>
    fun contributeToEditorBottomSheet(): List<TabItem>
}
```

### Menu Contributions

```kotlin
override fun contributeToMainMenu(): List<MenuItem> {
    return listOf(
        MenuItem(
            id = "json_to_kotlin",
            title = "JSON to Kotlin Data Class",
            action = {
                // Your action here
                showJsonToKotlinDialog()
            }
        ),
        MenuItem(
            id = "my_tool",
            title = "My Custom Tool",
            action = {
                // Another action
                launchMyTool()
            }
        )
    )
}
```

### Tab Contributions

Add custom tabs to the editor bottom sheet:

```kotlin
override fun contributeToEditorBottomSheet(): List<TabItem> {
    return listOf(
        TabItem(
            id = "my_tab",
            title = "My Tab",
            fragmentFactory = { MyFragment() },
            isEnabled = true,
            isVisible = true,
            order = 0
        )
    )
}
```

### Creating Fragments

```kotlin
class MyFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        
        // Create UI programmatically (recommended)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val titleView = TextView(context).apply {
            text = "My Custom Tab"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        
        val button = Button(context).apply {
            text = "Click Me"
            setOnClickListener {
                Toast.makeText(context, "Button clicked!", Toast.LENGTH_SHORT).show()
            }
        }
        
        layout.addView(titleView)
        layout.addView(button)
        
        return layout
    }
}
```

### Creating Dialogs

```kotlin
private fun showMyDialog(activity: Activity) {
    val dialogLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 24, 24, 24)
    }
    
    val input = EditText(activity).apply {
        hint = "Enter some text"
        setSingleLine(true)
    }
    
    dialogLayout.addView(TextView(activity).apply {
        text = "My Dialog Title"
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 16)
    })
    
    dialogLayout.addView(input)
    
    AlertDialog.Builder(activity)
        .setTitle("My Plugin Dialog")
        .setView(dialogLayout)
        .setPositiveButton("OK") { _, _ ->
            val text = input.text.toString()
            Toast.makeText(activity, "You entered: $text", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancel", null)
        .show()
}
```

## Plugin Management

### Enable/Disable Plugins

Plugins can be enabled or disabled at runtime:

```kotlin
val pluginManager = PluginManager.getInstance()

// Disable a plugin
pluginManager?.disablePlugin("com.example.myplugin")

// Enable a plugin
pluginManager?.enablePlugin("com.example.myplugin")

// Get all plugins with their status
val plugins = pluginManager?.getAllPlugins() ?: emptyList()
plugins.forEach { plugin ->
    println("${plugin.metadata.name}: enabled=${plugin.isEnabled}")
}
```

### Persistent State

Plugin enabled/disabled state is automatically persisted to `plugin_states.properties`:

```properties
# Plugin enabled/disabled states
com.example.helloworld=true
com.example.anotherplugin=false
```

## Examples

### Example 1: JSON to Kotlin Converter Plugin

This is a complete working example from our HelloWorldPlugin:

```kotlin
class HelloWorldPlugin : IPlugin, UIExtension {
    override val metadata = PluginMetadata(
        id = "com.example.helloworld",
        name = "Hello World Plugin",
        version = "1.0.0",
        description = "A plugin that demonstrates COGO plugin capabilities",
        author = "COGO Team",
        minIdeVersion = "2.1.0",
        dependencies = listOf("com.itsaky.androidide.base"),
        permissions = listOf("IDE_SETTINGS", "FILESYSTEM_READ")
    )
    
    private lateinit var context: PluginContext
    
    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        context.logger.info("HelloWorldPlugin initialized")
        return true
    }
    
    override fun activate(): Boolean {
        context.services.register(HelloService::class.java, HelloServiceImpl(context))
        return true
    }
    
    override fun deactivate(): Boolean = true
    override fun dispose() {}
    
    override fun contributeToMainMenu(): List<MenuItem> {
        return listOf(
            MenuItem(
                id = "json_to_kotlin",
                title = "JSON to Kotlin Data Class",
                action = { showJsonToKotlinDialog() }
            )
        )
    }
    
    override fun contributeToEditorBottomSheet(): List<TabItem> {
        return listOf(
            TabItem(
                id = "go_bot_tab",
                title = "GoBot",
                fragmentFactory = { GoBotFragment() },
                isEnabled = true,
                isVisible = true,
                order = 1
            )
        )
    }
    
    override fun contributeToContextMenu(context: ContextMenuContext): List<MenuItem> {
        return emptyList()
    }
    
    private fun showJsonToKotlinDialog() {
        val uiService = context.services.get(IdeUIService::class.java)
        val activity = uiService?.getCurrentActivity() ?: return
        
        activity.runOnUiThread {
            // Create dialog with JSON input, class name, package name fields
            // Convert JSON to Kotlin data class
            // Save to project using project service
        }
    }
}
```

### Example 2: AI Chat Interface

```kotlin
class GoBotFragment : Fragment() {
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputEditText: EditText
    
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
        }
        
        // Chat area with ScrollView
        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        
        chatContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(chatContainer)
        
        // Input area
        val inputLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        
        inputEditText = EditText(context).apply {
            hint = "Type a message..."
            layoutParams = LinearLayout.LayoutParams(0, 56.dpToPx(), 1f)
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#F0F0F0"))
            }
            setPadding(24, 0, 24, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val sendButton = Button(context).apply {
            text = "Send"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#4CAF50"))
            }
            setPadding(32, 0, 32, 0)
            setOnClickListener {
                val message = inputEditText.text.toString().trim()
                if (message.isNotEmpty()) {
                    addMessage(message, isUser = true)
                    inputEditText.text.clear()
                    simulateBotReply()
                }
            }
        }
        
        inputLayout.addView(inputEditText)
        inputLayout.addView(sendButton)
        
        rootLayout.addView(titleView)
        rootLayout.addView(scrollView)
        rootLayout.addView(inputLayout)
        
        return rootLayout
    }
    
    private fun addMessage(text: String, isUser: Boolean) {
        val messageView = TextView(requireContext()).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(if (isUser) Color.parseColor("#DCF8C6") else Color.parseColor("#E3F2FD"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = 16.dpToPx()
            }
        }
        
        chatContainer.addView(messageView)
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
    
    private fun simulateBotReply() {
        Handler(Looper.getMainLooper()).postDelayed({
            addMessage("I'm here to assist you!", isUser = false)
        }, 1000)
    }
    
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
```

### Example 3: File Operations

```kotlin
private fun saveToProject(content: String, fileName: String) {
    val projectService = context.services.get(IdeProjectService::class.java)
    val currentProject = projectService?.getCurrentProject()
    
    if (currentProject != null) {
        val targetFile = File(currentProject.rootDir, "app/src/main/java/$fileName")
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(content)
        
        context.logger.info("Saved file: ${targetFile.absolutePath}")
        showToast("✅ Saved: $fileName")
    } else {
        context.logger.warn("No current project available")
        showToast("❌ Could not find project")
    }
}
```

## Best Practices

### 1. Plugin Structure
```
my-plugin/
├── src/main/kotlin/
│   └── com/example/myplugin/
│       ├── MyPlugin.kt          // Main plugin class
│       ├── fragments/           // UI fragments
│       ├── services/           // Plugin services
│       └── utils/              // Utility classes
├── build.gradle.kts            // Build configuration
└── README.md                   // Documentation
```

### 2. Error Handling
```kotlin
override fun initialize(context: PluginContext): Boolean {
    return try {
        this.context = context
        // Initialize plugin
        context.logger.info("Plugin initialized successfully")
        true
    } catch (e: Exception) {
        context.logger.error("Plugin initialization failed", e)
        false
    }
}
```

### 3. Logging
```kotlin
// Use the plugin's logger
context.logger.info("Info message")
context.logger.warn("Warning message")
context.logger.error("Error message", exception)
context.logger.debug("Debug message")
```

### 4. UI Creation
- **Prefer programmatic UI** creation over XML layouts
- **Use consistent styling** with Material Design colors
- **Handle different screen sizes** appropriately
- **Test on different devices** and orientations

### 5. Service Access
```kotlin
// Always check if services are available
val projectService = context.services.get(IdeProjectService::class.java)
if (projectService != null) {
    // Use the service
} else {
    context.logger.warn("Project service not available")
}
```

### 6. Threading
```kotlin
// UI operations must be on main thread
uiService.getCurrentActivity()?.runOnUiThread {
    // Update UI here
}

// Heavy operations should be on background threads
Thread {
    // Do heavy work
    // Then update UI on main thread
}.start()
```

### 7. Resource Management
- **Clean up resources** in `deactivate()` and `dispose()`
- **Remove event listeners** when plugin is deactivated
- **Close files and streams** properly

## Testing Your Plugin

1. **Build your plugin** as an AAR
2. **Copy the AAR** to AndroidIDE's plugins directory
3. **Restart AndroidIDE** to load the plugin
4. **Test functionality** through the UI
5. **Check logs** for any errors or warnings
6. **Test enable/disable** functionality

## Troubleshooting

### Common Issues

1. **Plugin not loading**: Check the AAR is in the correct directory and manifest is valid
2. **Services not available**: Ensure AndroidIDE is fully initialized before plugin activation
3. **UI not showing**: Check if UIExtension interface is implemented correctly
4. **Crashes on enable/disable**: Implement proper cleanup in deactivate() method
5. **File operations failing**: Verify project is open and file paths are correct

### Debug Tips

- **Use extensive logging** to track plugin lifecycle
- **Check plugin enabled state** in PluginManager
- **Verify service availability** before using them
- **Test with different projects** and project states
- **Monitor memory usage** and clean up resources

This guide covers the current working plugin system in AndroidIDE. The plugin API is stable and ready for development!