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

    /**
     * Subdirectory inside the plugin APK's assets/ that contains a Tier 3
     * documentation bundle (HTML/CSS/JS/images/etc.). Return null to skip Tier 3.
     *
     * Every file under this directory is inserted into documentation.db under
     * the reserved path namespace "plugin/<pluginId>/<relative-path>". The
     * plugin owns collision-avoidance within its own subtree.
     *
     * Link from a Tier 1/2 [PluginTooltipButton] using the relative path of
     * the asset (e.g. uri = "index.html"). The documentation manager scopes it
     * into the plugin's namespace at install time and the tooltip system
     * prefixes the final URL with "http://localhost:6174/".
     *
     * Example: returning "docs" walks the assets/docs/ tree and inserts each file.
     */
    fun getTier3DocsAssetPath(): String? = null
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
     * URI for the button action. Resolved at install time:
     *  - Relative paths ("index.html", "docs/foo.html") are scoped into
     *    the plugin's own Tier 3 namespace → "plugin/<pluginId>/<path>".
     *  - Paths with a leading "/" ("/some/shared/page") are treated as
     *    absolute within the local web server (slash stripped).
     *  - Anything containing "://" is passed through unchanged.
     * The stored path is then prefixed with "http://localhost:6174/" by the
     * tooltip system when the button is rendered.
     */
    val uri: String,

    /**
     * Order of this button (lower numbers appear first).
     */
    val order: Int = 0
)