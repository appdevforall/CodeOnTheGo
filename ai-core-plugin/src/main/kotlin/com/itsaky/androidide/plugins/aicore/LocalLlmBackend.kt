package com.itsaky.androidide.plugins.aicore

import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.provider.DocumentsContract
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture

/**
 * Local LLM backend using llama-impl for on-device inference.
 * Wraps llama-impl APIs and implements LlmBackend interface.
 */
class LocalLlmBackend(private val context: PluginContext) : LlmBackend {

    private val llama = LLamaAndroid.instance()
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var modelLoaded = false
    @Volatile private var currentModelPath: String? = null

    override fun getId(): String = "local"

    override fun getName(): String = "Local LLM"

    override fun isAvailable(): Boolean {
        // Check if model is actually configured
        val prefs = try {
            // Try to get ai-assistant plugin's preferences
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            null
        }

        val configuredPath = prefs?.getString("local_llm_model_path", null)

        context.logger.debug("LocalLlmBackend.isAvailable() - configured path: $configuredPath, modelLoaded: $modelLoaded")

        // Available if model is loaded OR if a path is configured
        return modelLoaded || !configuredPath.isNullOrBlank()
    }

    /**
     * Resolves a content URI to an actual file path that native code can access.
     * If the URI points to a real file, returns the file path. Otherwise, returns null.
     */
    private fun resolveContentUriToPath(uriString: String): String? {
        if (!uriString.startsWith("content://")) {
            return uriString // Already a file path
        }

        val uri = Uri.parse(uriString)
        context.logger.info("Resolving content URI: $uri")

        try {
            // Try to get the actual file path using DocumentsContract
            if (DocumentsContract.isDocumentUri(context.androidContext, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                context.logger.debug("Document ID: $docId")

                // For downloads provider, the path is typically in the downloads folder
                if (uri.authority == "com.android.providers.downloads.documents") {
                    // The docId for downloads provider can be in different formats
                    // Try to construct the file path
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )

                    // Try common patterns for file names in downloads
                    // The docId might be a number (media store ID) or "msf:number" or "raw:/path"
                    val filePath = when {
                        docId.startsWith("raw:") -> {
                            docId.substring(4) // Remove "raw:" prefix
                        }
                        docId.startsWith("msf:") -> {
                            // This is a media store file ID, we need to query it
                            // For now, try to find .gguf files in downloads
                            findGgufFileInDownloads(downloadsDir)
                        }
                        else -> {
                            // Might be a direct file ID, try downloads folder
                            findGgufFileInDownloads(downloadsDir)
                        }
                    }

                    if (filePath != null && File(filePath).exists()) {
                        context.logger.info("Resolved to file path: $filePath")
                        return filePath
                    }
                }
            }
        } catch (e: Exception) {
            context.logger.error("Error resolving content URI", e)
        }

        return null
    }

    /**
     * Scans the downloads directory for .gguf files and returns the first one found.
     * This is a fallback when we can't directly resolve the content URI.
     */
    private fun findGgufFileInDownloads(downloadsDir: File): String? {
        if (!downloadsDir.exists() || !downloadsDir.isDirectory) {
            context.logger.warn("Downloads directory not found: ${downloadsDir.absolutePath}")
            return null
        }

        val ggufFiles = downloadsDir.listFiles { file ->
            file.isFile && file.name.endsWith(".gguf", ignoreCase = true)
        }

        if (ggufFiles != null && ggufFiles.isNotEmpty()) {
            // Return the most recently modified .gguf file
            val latestFile = ggufFiles.maxByOrNull { it.lastModified() }
            context.logger.info("Found .gguf file in downloads: ${latestFile?.absolutePath}")
            return latestFile?.absolutePath
        }

        context.logger.warn("No .gguf files found in downloads directory")
        return null
    }

    private suspend fun ensureModelLoaded(modelPath: String) {
        // Resolve content URI to actual file path
        val resolvedPath = resolveContentUriToPath(modelPath)
        if (resolvedPath == null) {
            throw IllegalStateException("Could not resolve model path: $modelPath. Make sure the .gguf file is in the Downloads folder.")
        }

        if (modelLoaded && currentModelPath == resolvedPath) {
            return // Already loaded
        }

        // Unload old model if loaded
        if (modelLoaded) {
            context.logger.info("Unloading previous model: $currentModelPath")
            llama.unload()
            modelLoaded = false
            currentModelPath = null
        }

        // Load new model with resolved path
        context.logger.info("Loading model: $resolvedPath")
        llama.load(resolvedPath)
        modelLoaded = true
        currentModelPath = resolvedPath
        context.logger.info("Model loaded successfully")
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        context.logger.info("LocalLlmBackend.generate() called with prompt: ${prompt.take(50)}...")

        // Check if model is configured
        val prefs = try {
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            null
        }

        val configuredPath = prefs?.getString("local_llm_model_path", null)
        context.logger.info("LocalLlmBackend: configured model path = $configuredPath")

        if (configuredPath.isNullOrBlank()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("No model configured. Please go to Settings and select a .gguf model file.")
            )
        }

        val future = CompletableFuture<LlmResponse>()

        scope.launch {
            try {
                // Configure sampling (use defaults for topP and topK)
                LLamaAndroid.configureSampling(
                    config.temperature,
                    0.9f,  // topP default
                    40     // topK default
                )
                LLamaAndroid.configureMaxTokens(config.maxTokens)

                // Ensure model is loaded
                ensureModelLoaded(configuredPath)

                // Build full prompt with system message
                val fullPrompt = if (config.systemPrompt != null) {
                    "${config.systemPrompt}\n\nUser: $prompt\nAssistant:"
                } else {
                    "User: $prompt\nAssistant:"
                }

                // Collect all tokens
                val responseBuilder = StringBuilder()
                var tokenCount = 0

                llama.send(
                    message = fullPrompt,
                    formatChat = false,
                    stop = emptyList(),
                    clearCache = false
                ).collect { token ->
                    responseBuilder.append(token)
                    tokenCount++
                }

                val responseText = responseBuilder.toString()
                context.logger.info("Generated response: ${responseText.take(50)}... ($tokenCount tokens)")

                future.complete(LlmResponse.success(responseText, tokenCount, System.currentTimeMillis()))
            } catch (e: Exception) {
                context.logger.error("Error during generation", e)
                future.complete(LlmResponse.failure("Error: ${e.message}"))
            }
        }

        return future
    }

    override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
        context.logger.info("LocalLlmBackend.generateStreaming() called")

        // Check if model is configured
        val prefs = try {
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            null
        }

        val configuredPath = prefs?.getString("local_llm_model_path", null)

        if (configuredPath.isNullOrBlank()) {
            callback.onError("No model configured. Please go to Settings and select a .gguf model file.")
            return
        }

        scope.launch {
            try {
                // Configure sampling (use defaults for topP and topK)
                LLamaAndroid.configureSampling(
                    config.temperature,
                    0.9f,  // topP default
                    40     // topK default
                )
                LLamaAndroid.configureMaxTokens(config.maxTokens)

                // Ensure model is loaded
                ensureModelLoaded(configuredPath)

                // Build full prompt with system message
                val fullPrompt = if (config.systemPrompt != null) {
                    "${config.systemPrompt}\n\nUser: $prompt\nAssistant:"
                } else {
                    "User: $prompt\nAssistant:"
                }

                var tokenCount = 0
                val responseBuilder = StringBuilder()

                llama.send(
                    message = fullPrompt,
                    formatChat = false,
                    stop = emptyList(),
                    clearCache = false
                ).collect { token ->
                    callback.onToken(token)
                    responseBuilder.append(token)
                    tokenCount++
                }

                callback.onComplete(LlmResponse.success(responseBuilder.toString(), tokenCount, System.currentTimeMillis()))
            } catch (e: Exception) {
                context.logger.error("Error during streaming generation", e)
                callback.onError("Error: ${e.message}")
            }
        }
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        context.logger.info("LocalLlmBackend.generateWithHistory() called with ${history.size} messages")

        // Build conversation prompt
        val conversationBuilder = StringBuilder()

        if (config.systemPrompt != null) {
            conversationBuilder.append(config.systemPrompt).append("\n\n")
        }

        // Add history
        for (msg in history) {
            val role = when (msg.role) {
                ChatMessage.Role.USER -> "User"
                ChatMessage.Role.ASSISTANT -> "Assistant"
                ChatMessage.Role.SYSTEM -> "System"
            }
            conversationBuilder.append("$role: ${msg.content}\n")
        }

        // Add current prompt
        conversationBuilder.append("User: $prompt\nAssistant:")

        // Use regular generate with the full conversation
        return generate(conversationBuilder.toString(), config)
    }

    fun unloadModel() {
        if (modelLoaded) {
            runBlocking {
                llama.unload()
            }
            modelLoaded = false
            currentModelPath = null
            context.logger.info("Model unloaded")
        }
    }
}
