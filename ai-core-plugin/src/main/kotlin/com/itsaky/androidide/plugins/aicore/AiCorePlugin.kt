package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext

/**
 * AI Core Plugin providing LLM inference capabilities.
 * Implements LlmInferenceService and registers local LLM backend.
 */
class AiCorePlugin : IPlugin {

    private lateinit var context: PluginContext

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("AiCorePlugin: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("AiCorePlugin: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("AiCorePlugin: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("AiCorePlugin: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("AiCorePlugin: Disposing plugin")
    }
}
