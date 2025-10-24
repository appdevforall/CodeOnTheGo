# Sample Plugin for CodeOnTheGo

A comprehensive sample plugin demonstrating all the capabilities available in the CodeOnTheGo plugin system.

## Features Demonstrated

###  Core Plugin Capabilities
- **Main Editor Tabs** - Full-featured tabs alongside code editor tabs
- **Bottom Sheet Integration** - Multiple tabs in the editor bottom sheet
- **Sidebar Navigation** - Actions and navigation items in the side menu
- **Main Menu Integration** - Menu items in the application menu bar
- **Documentation System** - Integrated help and tooltips using IdeTooltipService
- **Service Integration** - Full access to all IDE services

###  Plugin Interfaces Implemented
- `IPlugin` - Core plugin interface
- `UIExtension` - UI contributions (bottom sheet, sidebar, menus)
- `EditorTabExtension` - Main editor tab integration
- `DocumentationExtension` - Tooltip and help system integration

## Documentation & Tooltip Support

This plugin demonstrates comprehensive **documentation support** through the **IdeTooltipService**:

###  How Documentation Works
1. **DocumentationExtension Interface** - Plugin implements this to provide tooltips
2. **IdeTooltipService** - Service that displays interactive help tooltips
3. **PluginTooltipEntry** - Individual help topics with HTML content
4. **Long-press Tooltips** - UI elements support long-press for contextual help

###  Documentation Features
- **Category System** - Plugin documentation organized by category (`plugin_sample`)
- **Rich HTML Content** - Support for formatted text, lists, and styling
- **Context-Aware Help** - Different help content based on usage context
- **Interactive Buttons** - Tooltip buttons for additional resources
- **Multi-level Content** - Summary and detailed explanations

###  Tooltip Usage Examples
```kotlin
// Show tooltip on any view
tooltipService?.showTooltip(
    anchorView = button,
    category = "plugin_sample",
    tag = "sample_plugin.overview"
)

// Long-press tooltip setup
view.setOnLongClickListener {
    tooltipService?.showTooltip(
        anchorView = it,
        category = "plugin_sample",
        tag = "sample_plugin.main_tab"
    )
    true
}
```

## Architecture

###  Project Structure
```
sample-plugin/
├── build.gradle.kts              # Build configuration
├── proguard-rules.pro           # ProGuard rules
├── src/main/
│   ├── AndroidManifest.xml      # Plugin metadata
│   ├── kotlin/com/example/sampleplugin/
│   │   ├── SamplePlugin.kt      # Main plugin class
│   │   └── fragments/
│   │       └── SampleFragment.kt # Reusable fragment
│   └── res/
│       ├── layout/
│       │   └── fragment_sample.xml # Fragment layout
│       └── values/
│           └── strings.xml      # String resources
└── README.md                    # This file
```

###  Plugin Metadata (AndroidManifest.xml)
- **plugin.id** - Unique plugin identifier
- **plugin.name** - Display name
- **plugin.version** - Version string
- **plugin.description** - Plugin description
- **plugin.author** - Author information
- **plugin.min_ide_version** - Minimum IDE version
- **plugin.permissions** - Required permissions
- **plugin.main_class** - Main plugin class path

### Service Integration
The plugin demonstrates usage of all available IDE services:
- `IdeProjectService` - Project information and manipulation
- `IdeUIService` - UI interactions (toasts, dialogs)
- `IdeTooltipService` - **Documentation and help tooltips**
- `IdeFileService` - File system operations
- `IdeBuildService` - Build status monitoring
- `IdeEditorTabService` - Editor tab management

## Building the Plugin

### Build Commands
```bash
# Build release plugin
./gradlew assemblePlugin

# Build debug plugin
./gradlew assemblePluginDebug
```

### Build Output
- Release: `build/plugin/sample-plugin.cgp`
- Debug: `build/plugin/sample-plugin-debug.cgp`

### Installation
1. Copy the `.cgp` file to CodeOnTheGo's plugins directory
2. Restart the IDE or use the plugin manager to reload
3. The plugin will be automatically loaded and activated

## Usage

### Main Editor Tab
1. Use sidebar → "Open Sample Plugin" to open the main tab
2. Full-screen interface with all plugin features
3. Tab can be closed and reopened as needed

### Bottom Sheet Integration
1. Open any file in the editor
2. Access bottom sheet tabs: "Sample Tab" and "Tools"
3. Compact interface optimized for quick access

### Sidebar Navigation
1. Open sidebar menu
2. Look for "Tools" group
3. Find "Open Sample Plugin" and "Sample Action" items

### Documentation & Help
1. **Long-press** any UI element for contextual help
2. Click "Show Help" button for immediate tooltip
3. Different help content based on current context
4. Rich HTML tooltips with interactive elements

## Customization

### Adapting the Sample
1. Change package name in all files
2. Update plugin metadata in AndroidManifest.xml
3. Modify fragment layout and functionality
4. Add your own service integrations
5. Customize documentation entries

### Adding Documentation
1. Implement `DocumentationExtension`
2. Define `getTooltipCategory()` with unique identifier
3. Create `PluginTooltipEntry` objects with:
   - **tag** - Unique identifier within category
   - **summary** - Brief HTML description
   - **detail** - Comprehensive HTML content
   - **buttons** - Optional action buttons
4. Use `IdeTooltipService` to display tooltips

### 🔧 Service Integration
```kotlin
// Get services in fragment
val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
val tooltipService = serviceRegistry?.get(IdeTooltipService::class.java)

// Use services
tooltipService?.showTooltip(anchorView, category, tag)
```

## Dependencies

### Required Dependencies
- `plugin-api` - CodeOnTheGo Plugin API (compileOnly)
- `androidx.fragment` - Fragment support
- `androidx.appcompat` - AppCompat library
- `material` - Material Design components

### Plugin API Requirements
- Minimum IDE version: 1.0.0
- Required permissions: `filesystem.read`, `filesystem.write`, `project.structure`
- Android API: 26+ (Android 8.0)
- Kotlin version: 2.1.21+

## License

This sample plugin is provided as-is for educational and development purposes. Use it as a foundation for creating your own CodeOnTheGo plugins.