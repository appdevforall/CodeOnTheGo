package com.example.sampleplugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import androidx.fragment.app.Fragment
import android.widget.Toast
import com.example.sampleplugin.fragments.ApkAnalyzerFragment

/**
 * Sample plugin demonstrating all CodeOnTheGo plugin capabilities:
 * - Bottom sheet tabs
 * - Side menu navigation
 * - Main editor tabs
 * - Documentation system integration
 * - Service usage
 */
class SamplePlugin : IPlugin, UIExtension, EditorTabExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("SamplePlugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("SamplePlugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("SamplePlugin: Activating plugin")

        // Example: Access IDE services
        val projectService = context.services.get(IdeProjectService::class.java)
        val uiService = context.services.get(IdeUIService::class.java)

        if (projectService != null) {
            context.logger.info("Project service available")
        }

        if (uiService != null) {
            context.logger.info("UI service available")
        }

        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("SamplePlugin: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("SamplePlugin: Disposing plugin")
    }

    // UIExtension implementation - for bottom sheet tabs
    override fun getEditorTabs(): List<TabItem> {
        context.logger.debug("getEditorTabs() called - returning bottom sheet tabs")

        return listOf(
            TabItem(
                id = "sample_plugin_tools_tab",
                title = "Tools",
                fragmentFactory = {
                    context.logger.debug("Creating tools SampleFragment for bottom sheet")
                    ApkAnalyzerFragment.newInstance("tools")
                },
                isEnabled = true,
                isVisible = true,
                order = 1
            )
        )
    }

    override fun getMainMenuItems(): List<MenuItem> {
        return listOf(
            MenuItem(
                id = "sample_plugin_menu",
                title = "Sample Plugin Action",
                isEnabled = true,
                isVisible = true,
                action = {
                    context.logger.info("Sample plugin menu item clicked!")
                    val uiService = context.services.get(IdeUIService::class.java)
                    val activity = uiService?.getCurrentActivity()
                    activity?.runOnUiThread {
                        Toast.makeText(activity, "Sample Plugin: Menu item clicked!", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        )
    }

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "sample_plugin_nav",
                title = "Open Sample Plugin",
                icon = android.R.drawable.ic_menu_info_details,
                isEnabled = true,
                isVisible = true,
                group = "tools",
                order = 0,
                action = {
                    openSamplePluginTab()
                }
            ),

        )
    }

    // EditorTabExtension implementation - for main editor tabs
    override fun getMainEditorTabs(): List<EditorTabItem> {
        context.logger.debug("getMainEditorTabs() called - returning main editor tabs")

        return listOf(
            EditorTabItem(
                id = "sample_plugin_main_tab",
                title = "Sample Plugin",
                icon = android.R.drawable.ic_menu_info_details,
                fragmentFactory = {
                    context.logger.debug("Creating SampleFragment for main editor tab")
                    ApkAnalyzerFragment.newInstance("main_editor")
                },
                isCloseable = true,
                isPersistent = false,
                order = 0,
                isEnabled = true,
                isVisible = true,
                tooltip = "Sample plugin main interface"
            )
        )
    }

    override fun onEditorTabSelected(tabId: String, fragment: Fragment) {
        context.logger.info("Editor tab selected: $tabId")
    }

    override fun onEditorTabClosed(tabId: String) {
        context.logger.info("Editor tab closed: $tabId")
    }

    override fun canCloseEditorTab(tabId: String): Boolean {
        context.logger.debug("Can close editor tab: $tabId")
        return true
    }

    // DocumentationExtension implementation
    override fun getTooltipCategory(): String = "plugin_sample"

    override fun getTooltipEntries(): List<PluginTooltipEntry> {
        return listOf(
            // Main plugin overview
            PluginTooltipEntry(
                tag = "sample_plugin.overview",
                summary = "<b>Sample Plugin</b><br>Comprehensive plugin showcasing all CodeOnTheGo capabilities",
                detail = """
                    <h3>Sample Plugin Overview</h3>
                    <p>This sample plugin demonstrates all the integration capabilities available in the CodeOnTheGo plugin system.</p>

                    <h4>Features Demonstrated:</h4>
                    <ul>
                        <li><b>Main Editor Tabs</b> - Full-featured tabs in the main editor tab bar</li>
                        <li><b>Bottom Sheet Tabs</b> - Multiple tabs in the editor bottom sheet</li>
                        <li><b>Sidebar Navigation</b> - Actions and navigation items in the side menu</li>
                        <li><b>Main Menu Integration</b> - Menu items in the main application menu</li>
                        <li><b>Documentation System</b> - Integrated help and tooltips</li>
                        <li><b>Service Usage</b> - Accessing IDE services for project manipulation</li>
                    </ul>

                    <h4>Plugin Architecture:</h4>
                    <ul>
                        <li>Implements UIExtension for UI contributions</li>
                        <li>Implements EditorTabExtension for main editor integration</li>
                        <li>Implements DocumentationExtension for help system</li>
                        <li>Uses shared fragments across different integration points</li>
                        <li>Demonstrates proper resource loading and service access</li>
                    </ul>

                    <p>This serves as a reference implementation for plugin developers.</p>
                """.trimIndent(),
                buttons = listOf(
                    PluginTooltipButton(
                        description = "Plugin Development Guide",
                        uri = "plugin/development/guide",
                        order = 0
                    )
                )
            ),

            // Bottom sheet documentation
            PluginTooltipEntry(
                tag = "sample_plugin.bottom_sheet",
                summary = "<b>Bottom Sheet Integration</b><br>Tabs in the editor bottom sheet panel",
                detail = """
                    <h3>Bottom Sheet Tabs</h3>
                    <p>This plugin contributes multiple tabs to the editor's bottom sheet panel.</p>

                    <h4>Available Tabs:</h4>
                    <ul>
                        <li><b>Sample Tab</b> - Main plugin interface</li>
                        <li><b>Tools</b> - Additional tools and utilities</li>
                    </ul>

                    <h4>Implementation:</h4>
                    <ul>
                        <li>Uses UIExtension.getEditorTabs() method</li>
                        <li>Returns TabItem instances with fragment factories</li>
                        <li>Shared fragment implementation across different contexts</li>
                    </ul>

                    <p>Access via the bottom sheet tabs when a file is open in the editor.</p>
                """.trimIndent()
            ),

            // Main editor tab documentation
            PluginTooltipEntry(
                tag = "sample_plugin.main_tab",
                summary = "<b>Main Editor Tab</b><br>Full interface alongside code editor tabs",
                detail = """
                    <h3>Main Editor Tab</h3>
                    <p>This plugin provides a main editor tab that appears alongside file editor tabs.</p>

                    <h4>Features:</h4>
                    <ul>
                        <li>Full-screen interface with comprehensive controls</li>
                        <li>Tab can be closed and reopened via sidebar</li>
                        <li>Persistent state management</li>
                        <li>Custom icon and tooltip</li>
                    </ul>

                    <h4>Implementation:</h4>
                    <ul>
                        <li>Uses EditorTabExtension.getMainEditorTabs() method</li>
                        <li>Returns EditorTabItem with fragment factory</li>
                        <li>Lifecycle callbacks for tab events</li>
                    </ul>

                    <p>Open via sidebar → "Open Sample Plugin" or if tab is closed.</p>
                """.trimIndent()
            ),

            // Sidebar integration documentation
            PluginTooltipEntry(
                tag = "sample_plugin.sidebar",
                summary = "<b>Sidebar Integration</b><br>Navigation items and actions in the side menu",
                detail = """
                    <h3>Sidebar Navigation</h3>
                    <p>This plugin contributes multiple items to the side navigation menu.</p>

                    <h4>Navigation Items:</h4>
                    <ul>
                        <li><b>Open Sample Plugin</b> - Opens the main editor tab</li>
                        <li><b>Sample Action</b> - Executes a sample action with toast feedback</li>
                    </ul>

                    <h4>Implementation:</h4>
                    <ul>
                        <li>Uses UIExtension.getSideMenuItems() method</li>
                        <li>Returns NavigationItem instances with action callbacks</li>
                        <li>Groups items logically ("tools" group)</li>
                        <li>Custom icons for visual identification</li>
                    </ul>

                    <p>Access via the hamburger menu → Tools section.</p>
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

    // Private helper methods
    private fun openSamplePluginTab() {
        context.logger.info("Opening sample plugin in main editor tab")

        val editorTabService = context.services.get(IdeEditorTabService::class.java) ?: run {
            context.logger.error("Editor tab service not available")
            return
        }

        if (!editorTabService.isTabSystemAvailable()) {
            context.logger.error("Editor tab system not available")
            return
        }

        val tabId = "sample_plugin_main_tab"
        try {
            if (editorTabService.selectPluginTab(tabId)) {
                context.logger.info("Successfully opened sample plugin tab")
            } else {
                context.logger.warn("Failed to open sample plugin tab")
            }
        } catch (e: Exception) {
            context.logger.error("Error opening sample plugin tab", e)
        }
    }
}