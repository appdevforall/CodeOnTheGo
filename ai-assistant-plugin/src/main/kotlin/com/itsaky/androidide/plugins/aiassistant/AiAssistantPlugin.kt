package com.itsaky.androidide.plugins.aiassistant

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.aiassistant.fragments.ChatFragment

class AiAssistantPlugin : IPlugin, UIExtension {

    private lateinit var context: PluginContext
    private var llmService: LlmInferenceService? = null

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        context.logger.info("AI Assistant Plugin initializing...")
        return true
    }

    override fun activate(): Boolean {
        llmService = context.services.get(LlmInferenceService::class.java)

        if (llmService == null) {
            context.logger.warn("LlmInferenceService not available - LOCAL_LLM backend disabled")
            context.logger.warn("Install AI Core plugin to enable local LLM support")
        } else {
            context.logger.info("LlmInferenceService available - both backends enabled")
        }

        // Migrate chat history and settings on first activation
        migrateDataIfNeeded()

        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("AI Assistant Plugin deactivating...")
        return true
    }

    override fun dispose() {
        context.logger.info("AI Assistant Plugin disposing...")
    }

    // Register Agent tab
    override fun getEditorTabs(): List<TabItem> {
        return listOf(
            TabItem(
                id = "agent_chat",
                title = "Agent",
                order = 100,
                fragmentFactory = { ChatFragment() },
                isEnabled = true,
                isVisible = true,
                tooltipTag = "agent_chat_tab"
            )
        )
    }

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
                action = { context.logger.info("Explain Code clicked") }
            ),
            MenuItem(
                id = "ai_generate_code",
                title = "Generate Code",
                isEnabled = true,
                isVisible = true,
                action = { context.logger.info("Generate Code clicked") }
            )
        )
    }

    override fun getMainMenuItems(): List<MenuItem> = emptyList()

    private fun migrateDataIfNeeded() {
        // TODO: Implement in Task 8
    }
}
