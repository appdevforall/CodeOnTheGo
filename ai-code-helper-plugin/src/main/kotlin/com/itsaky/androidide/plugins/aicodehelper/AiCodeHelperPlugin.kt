package com.itsaky.androidide.plugins.aicodehelper

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*

/**
 * AI Code Helper Plugin providing code generation and explanation.
 * Consumes LlmInferenceService from ai-core-plugin.
 */
class AiCodeHelperPlugin : IPlugin, UIExtension {

    private lateinit var context: PluginContext
    private var llmService: LlmInferenceService? = null

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

        try {
            // Get LLM service from ai-core-plugin
            llmService = context.services.get(LlmInferenceService::class.java)
            if (llmService == null) {
                context.logger.warn("AiCodeHelperPlugin: LlmInferenceService not available")
                return false
            }

            // Check if local backend is available
            val isAvailable = llmService!!.isBackendAvailable("local")
            context.logger.info("AiCodeHelperPlugin: Local LLM backend available: $isAvailable")

            return true
        } catch (e: Exception) {
            context.logger.error("AiCodeHelperPlugin: Activation failed", e)
            return false
        }
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
        context.logger.info("AiCodeHelperPlugin: Explain code requested")

        val service = llmService
        if (service == null) {
            context.logger.error("AiCodeHelperPlugin: LLM service not available")
            return
        }

        val prompt = "Explain the following code:\n\n$code"
        val config = LlmConfig("local")
        config.temperature = 0.3f
        config.maxTokens = 500

        service.generateCompletion(prompt, config).thenAccept { response ->
            if (response.success) {
                context.logger.info("AiCodeHelperPlugin: Explanation generated (${response.tokensGenerated} tokens)")
                // Will show in dialog in Task 4
            } else {
                context.logger.error("AiCodeHelperPlugin: Explanation failed: ${response.error}")
            }
        }
    }

    private fun generateCode(prompt: String) {
        context.logger.info("AiCodeHelperPlugin: Generate code requested")

        val service = llmService
        if (service == null) {
            context.logger.error("AiCodeHelperPlugin: LLM service not available")
            return
        }

        val fullPrompt = "Generate code for: $prompt"
        val config = LlmConfig("local")
        config.temperature = 0.5f
        config.maxTokens = 1000

        service.generateCompletion(fullPrompt, config).thenAccept { response ->
            if (response.success) {
                context.logger.info("AiCodeHelperPlugin: Code generated (${response.tokensGenerated} tokens)")
                // Will show in dialog in Task 4
            } else {
                context.logger.error("AiCodeHelperPlugin: Code generation failed: ${response.error}")
            }
        }
    }

    override fun getMainMenuItems(): List<MenuItem> = emptyList()
    override fun getEditorTabs(): List<com.itsaky.androidide.plugins.extensions.TabItem> = emptyList()
    override fun getSideMenuItems(): List<com.itsaky.androidide.plugins.extensions.NavigationItem> = emptyList()
}
