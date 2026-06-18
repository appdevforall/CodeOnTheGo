package com.itsaky.androidide.agent.repository

import android.content.Context
import androidx.core.net.toUri
import com.itsaky.androidide.llamacpp.api.ILlamaController
import com.itsaky.androidide.utils.DynamicLibraryLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Manages multiple LLM contexts for different purposes (chat, embeddings, STT, etc.).
 * Each purpose gets its own llama context with appropriate configuration.
 */
class MultiModelManager(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val log = LoggerFactory.getLogger(LOG_TAG)

    // Map of purpose -> llama controller instance
    private val controllers = mutableMapOf<ModelPurpose, ILlamaController>()

    // Map of purpose -> loaded model info
    private val loadedModels = mutableMapOf<ModelPurpose, LoadedModelInfo>()

    private var llamaAndroidClass: Class<*>? = null
    private var isInitialized = false
    private val initMutex = Mutex()
    private val loadMutex = Mutex()
    private var cachedContext: Context? = null

    data class LoadedModelInfo(
        val modelName: String,
        val modelPath: String,
        val sourceUri: String,
        val modelFamily: ModelFamily
    )

    companion object {
        private const val LOG_TAG = "MultiModelManager"

        /**
         * Get cache filename for a specific purpose
         */
        private fun getCacheFileName(purpose: ModelPurpose): String {
            return "local_model_${purpose.name.lowercase()}.gguf"
        }
    }

    /**
     * Initialize the llama library. Must be called once before loading models.
     */
    suspend fun initialize(context: Context): Boolean = initMutex.withLock {
        if (isInitialized) return true

        cachedContext = context.applicationContext
        val classLoader = DynamicLibraryLoader.getLlamaClassLoader(context)
        if (classLoader == null) {
            log.error("Failed to create Llama ClassLoader")
            return false
        }

        try {
            llamaAndroidClass = classLoader.loadClass("android.llama.cpp.LLamaAndroid")
            log.info("Llama Android class loaded successfully")
            isInitialized = true
            true
        } catch (e: Exception) {
            log.error("Failed to initialize Llama class", e)
            false
        }
    }

    /**
     * Load a model for a specific purpose.
     */
    suspend fun loadModel(
        context: Context,
        purpose: ModelPurpose,
        modelUriString: String,
        expectedSha256: String? = null
    ): Boolean = loadMutex.withLock {
        if (!isInitialized && !initialize(context)) {
            log.error("Cannot load model - initialization failed")
            return false
        }

        // Unload existing model for this purpose if any
        unloadModel(purpose)

        return withContext(ioDispatcher) {
            try {
                val modelUri = modelUriString.toUri()
                val displayName = resolveModelDisplayName(context, modelUri)
                val cacheFile = File(context.cacheDir, getCacheFileName(purpose))

                // Copy model to cache
                if (!copyModelToCache(context, modelUri, cacheFile)) {
                    return@withContext false
                }

                // Verify hash if provided
                if (!verifyModelHash(cacheFile, expectedSha256)) {
                    return@withContext false
                }

                // Create a new llama controller for this purpose
                val controller = createControllerForPurpose(purpose)
                if (controller == null) {
                    log.error("Failed to create controller for purpose: $purpose")
                    return@withContext false
                }

                // Load the model
                controller.load(cacheFile.path)
                controllers[purpose] = controller

                loadedModels[purpose] = LoadedModelInfo(
                    modelName = displayName,
                    modelPath = cacheFile.path,
                    sourceUri = modelUriString,
                    modelFamily = detectModelFamily(displayName)
                )

                log.info("Successfully loaded model for purpose $purpose: $displayName")
                true
            } catch (e: Exception) {
                log.error("Failed to load model for purpose $purpose", e)
                controllers.remove(purpose)
                loadedModels.remove(purpose)
                false
            }
        }
    }

    /**
     * Create a new llama controller instance configured for the specific purpose.
     */
    private fun createControllerForPurpose(purpose: ModelPurpose): ILlamaController? {
        val klass = llamaAndroidClass ?: return null

        return try {
            // Create a new instance via reflection
            val instanceMethod = klass.getMethod("createInstance")
            val instance = instanceMethod.invoke(null) as ILlamaController

            // Configure based on purpose
            when (purpose) {
                ModelPurpose.EMBEDDINGS -> {
                    // For embeddings, we need special configuration
                    // This will be handled at the native layer when context is created
                    log.info("Created controller for EMBEDDINGS purpose")
                }
                ModelPurpose.CHAT -> {
                    // Default configuration for chat
                    log.info("Created controller for CHAT purpose")
                }
                else -> {
                    log.info("Created controller for ${purpose.name} purpose")
                }
            }

            instance
        } catch (e: Exception) {
            log.error("Failed to create controller instance for purpose $purpose", e)
            null
        }
    }

    /**
     * Unload a model for a specific purpose.
     */
    suspend fun unloadModel(purpose: ModelPurpose) {
        controllers[purpose]?.let { controller ->
            withContext(ioDispatcher) {
                try {
                    controller.unload()
                } catch (e: Exception) {
                    log.error("Error unloading model for purpose $purpose", e)
                }
            }
        }
        controllers.remove(purpose)
        loadedModels.remove(purpose)
    }

    /**
     * Get the controller for a specific purpose.
     * Returns null if no model is loaded for that purpose.
     */
    fun getController(purpose: ModelPurpose): ILlamaController? {
        return controllers[purpose]
    }

    /**
     * Check if a model is loaded for the given purpose.
     */
    fun isModelLoaded(purpose: ModelPurpose): Boolean {
        return controllers.containsKey(purpose) && loadedModels.containsKey(purpose)
    }

    /**
     * Get loaded model info for a purpose.
     */
    fun getModelInfo(purpose: ModelPurpose): LoadedModelInfo? {
        return loadedModels[purpose]
    }

    /**
     * Get all loaded models.
     */
    fun getAllLoadedModels(): Map<ModelPurpose, LoadedModelInfo> {
        return loadedModels.toMap()
    }

    private fun resolveModelDisplayName(context: Context, modelUri: android.net.Uri): String {
        val nameFromProvider = context.contentResolver.query(modelUri, null, null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }

        if (!nameFromProvider.isNullOrBlank()) {
            return nameFromProvider
        }

        val fileNameFromPath = modelUri.path?.let { File(it).name }
        return fileNameFromPath ?: "model.gguf"
    }

    private fun copyModelToCache(
        context: Context,
        modelUri: android.net.Uri,
        destinationFile: File
    ): Boolean {
        val inputStream = when (modelUri.scheme) {
            "file" -> modelUri.path?.let { File(it).inputStream() }
            "content" -> context.contentResolver.openInputStream(modelUri)
            else -> context.contentResolver.openInputStream(modelUri)
        }

        inputStream?.use { input ->
            FileOutputStream(destinationFile).use { output ->
                input.copyTo(output)
            }
        } ?: run {
            log.error("Failed to open input stream for model URI: $modelUri")
            return false
        }

        return true
    }

    private fun verifyModelHash(file: File, expectedSha256: String?): Boolean {
        val normalized = expectedSha256?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return true

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(normalized, ignoreCase = true)) {
            log.error("Hash verification failed. expected=$normalized, actual=$actual")
            return false
        }

        return true
    }

    private fun detectModelFamily(path: String): ModelFamily {
        val lower = path.lowercase()
        return when {
            lower.contains("h2o") || lower.contains("danube") -> ModelFamily.H2O
            lower.contains("qwen") -> ModelFamily.QWEN
            lower.contains("gemma-3") || lower.contains("gemma3") -> ModelFamily.GEMMA3
            lower.contains("gemma") -> ModelFamily.GEMMA2
            lower.contains("llama") -> ModelFamily.LLAMA3
            else -> ModelFamily.UNKNOWN
        }
    }
}
