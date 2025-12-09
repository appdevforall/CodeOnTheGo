package com.example.sampleplugin

import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import com.example.sampleplugin.fragments.ApkAnalyzerFragment

/**
 * APK Viewer Plugin
 * Provides APK analysis functionality via main menu toolbar and bottom sheet
 */
class ApkViewer : IPlugin, UIExtension, EditorTabExtension {

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("ApkViewer: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("ApkViewer: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("ApkViewer: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("ApkViewer: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("ApkViewer: Disposing plugin")
    }

    // UIExtension - Main menu toolbar item
    override fun getMainMenuItems(): List<MenuItem> {
        return listOf(
            MenuItem(
                id = "apk_analyzer_menu",
                title = "APK Analyzer",
                isEnabled = true,
                isVisible = true,
                action = {
                    context.logger.info("APK Analyzer menu item clicked")
                    openApkAnalyzerTab()
                }
            )
        )
    }

    // UIExtension - Bottom sheet tab
    override fun getEditorTabs(): List<TabItem> {
        context.logger.debug("ApkViewer: getEditorTabs() called")

        return listOf(
            TabItem(
                id = "apk_viewer_tab",
                title = "APK Analyzer",
                fragmentFactory = {
                    context.logger.debug("ApkViewer: Creating ApkAnalyzerFragment for bottom sheet")
                    ApkAnalyzerFragment()
                },
                isEnabled = true,
                isVisible = true,
                order = 100
            )
        )
    }

    // UIExtension - Sidebar navigation item
    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "apk_analyzer_sidebar",
                title = "APK Analyzer",
                icon = android.R.drawable.ic_menu_info_details,
                isEnabled = true,
                isVisible = true,
                group = "tools",
                order = 0,
                action = { openApkAnalyzerTab() }
            )
        )
    }

    // EditorTabExtension - Main editor tab to display the analyzer
    override fun getMainEditorTabs(): List<EditorTabItem> {
        return listOf(

            EditorTabItem(
                id = "apk_analyzer_main_tab",
                title = "APK Analyzer",
                icon = android.R.drawable.ic_menu_info_details,
                fragmentFactory = {
                    context.logger.debug("Creating ApkAnalyzerFragment")
                    ApkAnalyzerFragment()
                },
                isCloseable = true,
                isPersistent = false,
                order = 100,
                isEnabled = true,
                isVisible = true,
                tooltip = "Analyze APK structure and contents"
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
        return true
    }

    private fun openApkAnalyzerTab() {
        context.logger.info("Opening APK Analyzer tab")

        val editorTabService = context.services.get(IdeEditorTabService::class.java)
        if (editorTabService == null) {
            context.logger.error("Editor tab service not available")
            return
        }

        if (!editorTabService.isTabSystemAvailable()) {
            context.logger.error("Editor tab system not available")
            return
        }

        try {
            if (editorTabService.selectPluginTab("apk_analyzer_main_tab")) {
                context.logger.info("Successfully opened APK Analyzer tab")
            } else {
                context.logger.warn("Failed to open APK Analyzer tab")
            }
        } catch (e: Exception) {
            context.logger.error("Error opening APK Analyzer tab", e)
        }
    }
}
