package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

/**
 * AI Core Plugin providing LLM inference capabilities.
 * Implements LlmInferenceService and registers local LLM backend.
 */
class AiCorePlugin : IPlugin {

    private lateinit var context: PluginContext
    private lateinit var llmService: LlmInferenceServiceImpl
    private lateinit var localBackend: LocalLlmBackend

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

        try {
            // Create LlmInferenceService
            llmService = LlmInferenceServiceImpl()

            // Register in SharedServices (accessible by all plugins)
            SharedServices.register(LlmInferenceService::class.java, llmService)
            context.logger.info("AiCorePlugin: Registered LlmInferenceService in SharedServices")

            // Create and register local LLM backend
            localBackend = LocalLlmBackend(context)
            llmService.registerBackend(localBackend)
            context.logger.info("AiCorePlugin: Registered local LLM backend")

            return true
        } catch (e: Exception) {
            context.logger.error("AiCorePlugin: Activation failed", e)
            return false
        }
    }

    override fun deactivate(): Boolean {
        context.logger.info("AiCorePlugin: Deactivating plugin")

        try {
            // Unregister backend
            if (::llmService.isInitialized) {
                llmService.unregisterBackend("local")
                context.logger.info("AiCorePlugin: Unregistered local LLM backend")
            }

            // Unregister from SharedServices
            SharedServices.unregister(LlmInferenceService::class.java)
            context.logger.info("AiCorePlugin: Unregistered LlmInferenceService from SharedServices")

            return true
        } catch (e: Exception) {
            context.logger.error("AiCorePlugin: Deactivation failed", e)
            return false
        }
    }

    override fun dispose() {
        context.logger.info("AiCorePlugin: Disposing plugin")

        // Cancel any ongoing generation
        if (::llmService.isInitialized) {
            llmService.cancelGeneration()
        }
    }
}
