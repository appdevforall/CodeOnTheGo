package com.itsaky.androidide.plugins.aicodehelper

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.ContextMenuContext

/**
 * AI Code Helper Plugin providing code generation and explanation.
 * Consumes LlmInferenceService from ai-core-plugin.
 */
class AiCodeHelperPlugin : IPlugin, UIExtension {

    private lateinit var context: PluginContext

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aicodehelper"
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("AiCodeHelperPlugin: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("AiCodeHelperPlugin: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("AiCodeHelperPlugin: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("AiCodeHelperPlugin: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("AiCodeHelperPlugin: Disposing plugin")
    }

    // UIExtension - Context menu items
    override fun getContextMenuItems(menuContext: ContextMenuContext): List<MenuItem> {
        val selectedText = menuContext.selectedText
        if (selectedText.isNullOrBlank()) {
            return emptyList()
        }

        return listOf(
            MenuItem(
                id = "ai_explain_code",
                title = "Explain Code",
                isEnabled = true,
                isVisible = true,
                action = {
                    explainCode(selectedText)
                }
            ),
            MenuItem(
                id = "ai_generate_code",
                title = "Generate Code",
                isEnabled = true,
                isVisible = true,
                action = {
                    generateCode(selectedText)
                }
            )
        )
    }

    private fun explainCode(code: String) {
        context.logger.info("AiCodeHelperPlugin: Explain code requested for: $code")
        // Stub - will implement in Task 3
    }

    private fun generateCode(prompt: String) {
        context.logger.info("AiCodeHelperPlugin: Generate code requested for: $prompt")
        // Stub - will implement in Task 3
    }

    override fun getMainMenuItems(): List<MenuItem> = emptyList()
    override fun getEditorTabs(): List<com.itsaky.androidide.plugins.extensions.TabItem> = emptyList()
    override fun getSideMenuItems(): List<com.itsaky.androidide.plugins.extensions.NavigationItem> = emptyList()
}
