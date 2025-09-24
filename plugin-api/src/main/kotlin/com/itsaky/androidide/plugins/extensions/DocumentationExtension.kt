package com.itsaky.androidide.plugins.extensions

import com.itsaky.androidide.plugins.IPlugin

/**
 * Interface for plugins that provide documentation and tooltips.
 * Plugins implementing this interface can contribute their own documentation
 * that will be integrated into the IDE's tooltip system.
 */
interface DocumentationExtension : IPlugin {

    /**
     * Get the tooltip category for this plugin.
     * This should be a unique identifier for the plugin's documentation category.
     * Example: "plugin_sampleplugin"
     */
    fun getTooltipCategory(): String

    /**
     * Provide tooltip entries for the plugin.
     * These will be inserted into the documentation database when the plugin is installed.
     */
    fun getTooltipEntries(): List<PluginTooltipEntry>

    /**
     * Called when the plugin's documentation is being installed.
     * Return true if installation should proceed, false to skip.
     */
    fun onDocumentationInstall(): Boolean = true

    /**
     * Called when the plugin's documentation is being removed.
     */
    fun onDocumentationUninstall() {}
}

/**
 * Represents a single tooltip entry provided by a plugin.
 */
data class PluginTooltipEntry(
    /**
     * Unique tag for this tooltip within the plugin's category.
     * Example: "json_converter.help"
     */
    val tag: String,

    /**
     * Brief HTML summary shown initially (level 0).
     * Keep this concise - ideally 1-2 sentences.
     */
    val summary: String,

    /**
     * Detailed HTML description shown when "See More" is clicked (level 1).
     * Can include more comprehensive information.
     */
    val detail: String = "",

    /**
     * Optional action buttons for the tooltip.
     * Each pair is (label, uri) where uri is a relative path.
     */
    val buttons: List<PluginTooltipButton> = emptyList()
)

/**
 * Represents an action button in a plugin tooltip.
 */
data class PluginTooltipButton(
    /**
     * Display label for the button.
     * Example: "View Documentation"
     */
    val description: String,

    /**
     * URI path for the button action.
     * This will be prefixed with "http://localhost:6174/" by the tooltip system.
     * Example: "plugin/sampleplugin/docs/json-converter"
     */
    val uri: String,

    /**
     * Order of this button (lower numbers appear first).
     */
    val order: Int = 0
)