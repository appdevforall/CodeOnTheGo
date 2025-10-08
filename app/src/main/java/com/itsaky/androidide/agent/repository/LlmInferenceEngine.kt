package com.itsaky.androidide.agent.repository

import android.content.Context
import android.llama.cpp.LLamaAndroid
import androidx.core.net.toUri
import com.itsaky.androidide.utils.DynamicLibraryLoader
import com.itsaky.androidide.utils.getFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream

/**
 * A singleton wrapper for the LLamaAndroid library.
 * This object manages loading, running, and releasing the local LLM.
 */
object LlmInferenceEngine {
    private val log = LoggerFactory.getLogger(LlmInferenceEngine::class.java)

    private var llamaController: LLamaAndroid? = null
    private var isInitialized = false

    var isModelLoaded = false
        private set
    var loadedModelName: String? = null
        private set

    /**
     * Initializes the Llama controller by dynamically loading the AAR.
     * This must be called once before any other methods are used.
     * @return true if initialization was successful, false otherwise.
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
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

            llamaController = llamaInstance as LLamaAndroid
            isInitialized = true
            log.info("Llama Inference Engine initialized successfully.")
            true
        } catch (e: Exception) {
            log.error("Failed to initialize Llama class via reflection", e)
            false
        }
    }

    suspend fun initModelFromFile(context: Context, modelUriString: String): Boolean {
        if (!isInitialized) {
            log.error("Engine not initialized. Call initialize() first.")
            return false
        }
        if (isModelLoaded) {
            releaseModel()
        }
        return withContext(Dispatchers.IO) {
            try {
                val modelUri = modelUriString.toUri()
                val originalFileName = modelUri.getFileName(context)
                val destinationFile = File(context.cacheDir, "local_model.gguf")

                context.contentResolver.openInputStream(modelUri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                log.info("Model copied to cache at {}", destinationFile.path)

                llamaController?.load(destinationFile.path)
                isModelLoaded = true
                loadedModelName = originalFileName
                log.info("Successfully loaded local model: {}", loadedModelName)
                true
            } catch (e: Exception) {
                log.error("Failed to initialize or load model from file", e)
                isModelLoaded = false
                loadedModelName = null
                false
            }
        }
    }

    suspend fun runInference(prompt: String, stopStrings: List<String>): String {
        val controller = llamaController ?: run {
            log.error("Inference attempted but engine is not initialized.")
            return "Error: Llama engine not initialized."
        }
        if (!isModelLoaded) {
            log.error("Inference attempted but no model is loaded.")
            return "Error: Model not loaded."
        }

        controller.clearKvCache()
        return withContext(Dispatchers.IO) {
            try {
                val sendFlow: Flow<String> = controller.send(prompt, stop = stopStrings)
                sendFlow.fold("") { accumulator, value -> accumulator + value }
            } catch (e: Exception) {
                log.error("Error during model inference", e)
                "Error during inference: ${e.message}"
            }
        }
    }

    suspend fun releaseModel() {
        if (isModelLoaded) {
            try {
                llamaController?.unload()
                isModelLoaded = false
                loadedModelName = null
                log.info("Model released.")
            } catch (e: Exception) {
                log.error("Error releasing model", e)
            }
        }
    }

    suspend fun clearKvCache() {
        llamaController?.clearKvCache()
    }

    fun stopInference() {
        log.info("Attempting to stop inference immediately.")
        try {
            llamaController?.stop()
        } catch (e: Exception) {
            log.error("Error calling stop on native library", e)
        }
    }
}