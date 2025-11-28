package com.itsaky.androidide.agent.repository

import android.content.Context
import androidx.core.net.toUri
import com.itsaky.androidide.llamacpp.api.ILlamaController // Ensure you have this interface
import com.itsaky.androidide.utils.DynamicLibraryLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream

/**
 * A wrapper class for the LLamaAndroid library, loaded dynamically.
 * This class manages loading the library, the model, running inference, and releasing resources.
 * An instance of this class should be managed as a singleton by a provider.
 *
 * @param ioDispatcher The coroutine dispatcher for blocking I/O and CPU-intensive operations.
 */
class LlmInferenceEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val log = LoggerFactory.getLogger(LlmInferenceEngine::class.java)

    private var llamaController: ILlamaController? = null
    private var isInitialized = false

    var isModelLoaded: Boolean = false
        private set
    var loadedModelName: String? = null
        private set
    var loadedModelPath: String? = null
        private set
    var currentModelFamily: ModelFamily = ModelFamily.UNKNOWN
        private set

    /**
     * Initializes the Llama controller by dynamically loading the library.
     * This must be called once on the instance before any other methods are used.
     * @return true if initialization was successful, false otherwise.
     */
    suspend fun initialize(context: Context): Boolean = withContext(ioDispatcher) {
        if (isInitialized) return@withContext true

        log.info("Initializing Llama Inference Engine...")
        val classLoader = DynamicLibraryLoader.getLlamaClassLoader(context)
        if (classLoader == null) {
            log.error("Failed to create Llama ClassLoader. The library might not be installed.")
            return@withContext false
        }

        try {
            val llamaAndroidClass = classLoader.loadClass("android.llama.cpp.LLamaAndroid")
            val instanceMethod = llamaAndroidClass.getMethod("instance")
            val llamaInstance = instanceMethod.invoke(null) // 'null' for static method

            llamaController = llamaInstance as ILlamaController
            isInitialized = true
            log.info("Llama Inference Engine initialized successfully.")
            true
        } catch (e: Exception) {
            log.error("Failed to initialize Llama class via reflection", e)
            false
        }
    }

    /**
     * Copies a model from a URI to the app's cache and loads it.
     * If the engine is not initialized, it will attempt to initialize it first.
     */
    suspend fun initModelFromFile(context: Context, modelUriString: String): Boolean {
        if (!isInitialized && !initialize(context)) {
            log.error("Engine initialization failed. Cannot load model.")
            return false
        }
        if (isModelLoaded) {
            unloadModel()
        }
        return withContext(ioDispatcher) {
            try {
                val modelUri = modelUriString.toUri()
                val displayName =
                    context.contentResolver.query(modelUri, null, null, null, null)?.use { cursor ->
                        val nameIndex =
                            cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        cursor.getString(nameIndex)
                    } ?: "local_model.gguf"

                val destinationFile = File(context.cacheDir, "local_model.gguf")

                context.contentResolver.openInputStream(modelUri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                log.info("Model copied to cache at {}", destinationFile.path)

                llamaController?.load(destinationFile.path)
                isModelLoaded = true
                loadedModelPath = destinationFile.path
                loadedModelName = displayName
                currentModelFamily = detectModelFamily(displayName)
                log.info("Successfully loaded local model: {}", loadedModelName)
                true
            } catch (e: Exception) {
                log.error("Failed to initialize or load model from file", e)
                isModelLoaded = false
                loadedModelPath = null
                loadedModelName = null
                currentModelFamily = ModelFamily.UNKNOWN
                false
            }
        }
    }

    suspend fun unloadModel() {
        if (!isModelLoaded) return
        withContext(ioDispatcher) {
            llamaController?.unload()
        }
        isModelLoaded = false
        loadedModelPath = null
        loadedModelName = null
        currentModelFamily = ModelFamily.UNKNOWN
    }

    suspend fun runInference(prompt: String, stopStrings: List<String> = emptyList()): String {
        val controller = llamaController ?: throw IllegalStateException("Engine not initialized.")
        if (!isModelLoaded) throw IllegalStateException("Model is not loaded.")

        return withContext(ioDispatcher) {
            controller.clearKvCache()
            controller.send(prompt, stop = stopStrings).reduce { acc, s -> acc + s }
        }
    }

    suspend fun runStreamingInference(
        prompt: String,
        stopStrings: List<String> = emptyList()
    ): Flow<String> {
        val controller = llamaController ?: throw IllegalStateException("Engine not initialized.")
        if (!isModelLoaded) throw IllegalStateException("Model is not loaded.")

        controller.clearKvCache()
        return controller.send(prompt, stop = stopStrings).flowOn(ioDispatcher)
    }

    fun stop() {
        if (!isInitialized) return
        try {
            llamaController?.stop()
        } catch (e: Exception) {
            log.error("Error calling stop on the native library", e)
        }
    }

    private fun detectModelFamily(path: String): ModelFamily {
        val lowerPath = path.lowercase()
        return when {
            lowerPath.contains("gemma") -> ModelFamily.GEMMA2
            lowerPath.contains("llama") -> ModelFamily.LLAMA3
            else -> ModelFamily.UNKNOWN
        }
    }
}