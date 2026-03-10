package com.codeonthego.markdownpreviewer

import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import com.codeonthego.markdownpreviewer.fragments.MarkdownPreviewFragment
import java.io.File

class MarkdownPreviewerPlugin : IPlugin, UIExtension, EditorTabExtension {

    private lateinit var context: PluginContext
    
    companion object {
        const val PLUGIN_ID = "com.codeonthego.markdownpreviewer"
        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "mkdn")
        private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
        val SUPPORTED_EXTENSIONS = MARKDOWN_EXTENSIONS + HTML_EXTENSIONS
        
        fun isMarkdownFile(file: File): Boolean = 
            MARKDOWN_EXTENSIONS.contains(file.extension.lowercase())
        
        fun isHtmlFile(file: File): Boolean = 
            HTML_EXTENSIONS.contains(file.extension.lowercase())
        
        fun isSupportedFile(file: File): Boolean = 
            SUPPORTED_EXTENSIONS.contains(file.extension.lowercase())
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("MarkdownPreviewerPlugin: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("MarkdownPreviewerPlugin: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("MarkdownPreviewerPlugin: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("MarkdownPreviewerPlugin: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("MarkdownPreviewerPlugin: Disposing plugin")
    }

    // UIExtension - No main menu items (accessed via sidebar/context menu)
    override fun getMainMenuItems(): List<MenuItem> = emptyList()

    // UIExtension - Context menu for files
    override fun getContextMenuItems(context: ContextMenuContext): List<MenuItem> {
        val file = context.file ?: return emptyList()
        
        if (!isSupportedFile(file)) {
            return emptyList()
        }
        
        val fileType = when {
            isMarkdownFile(file) -> "Markdown"
            isHtmlFile(file) -> "HTML"
            else -> "File"
        }
        
        return listOf(
            MenuItem(
                id = "preview_${file.name}",
                title = "Preview $fileType",
                isEnabled = true,
                isVisible = true,
                action = {
                    openPreviewerTabWithFile(file)
                }
            )
        )
    }

    // UIExtension - Bottom sheet tab
    override fun getEditorTabs(): List<TabItem> = emptyList()

    // UIExtension - Sidebar navigation item
    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "markdown_preview_sidebar",
                title = "Preview",
                icon = android.R.drawable.ic_menu_gallery,
                isEnabled = true,
                isVisible = true,
                group = "tools",
                order = 10,
                action = { 
                    context.logger.info("Sidebar Preview clicked")
                    openPreviewerTab() 
                }
            )
        )
    }

    // EditorTabExtension - Main editor tab to display the preview
    override fun getMainEditorTabs(): List<EditorTabItem> {
        return listOf(
            EditorTabItem(
                id = "markdown_preview_main_tab",
                title = "Preview",
                icon = android.R.drawable.ic_menu_gallery,
                fragmentFactory = {
                    context.logger.debug("Creating MarkdownPreviewFragment")
                    MarkdownPreviewFragment()
                },
                isCloseable = true,
                isPersistent = false,
                order = 50,
                isEnabled = true,
                isVisible = true,
                tooltip = "Preview Markdown and HTML files"
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

    private fun openPreviewerTab() {
        context.logger.info("Opening Markdown Previewer tab")

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
            if (editorTabService.selectPluginTab("markdown_preview_main_tab")) {
                context.logger.info("Successfully opened Markdown Previewer tab")
            } else {
                context.logger.warn("Failed to open Markdown Previewer tab")
            }
        } catch (e: Exception) {
            context.logger.error("Error opening Markdown Previewer tab", e)
        }
    }
    
    private fun openPreviewerTabWithFile(file: File) {
        context.logger.info("Opening Markdown Previewer tab with file: ${file.absolutePath}")
        
        // Store the file path temporarily for the fragment to pick up
        PreviewState.pendingFilePath = file.absolutePath
        
        openPreviewerTab()
    }
}


object PreviewState {
    @Volatile
    var pendingFilePath: String? = null
    
    fun consumePendingFile(): String? {
        val path = pendingFilePath
        pendingFilePath = null
        return path
    }
}
