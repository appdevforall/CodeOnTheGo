package com.itsaky.androidide.agent.repository

import android.content.Context
import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * A singleton wrapper for the LLamaAndroid library.
 * This object manages loading, running, and releasing the local LLM.
 */
object LlmInferenceEngine {
    private const val TAG = "LlmInferenceEngine"
    private val llama = LLamaAndroid.instance()
    var isModelLoaded = false
        private set

    /**
     * Copies a model from a URI to a local cache file and loads it into memory.
     * @return True if the model was loaded successfully, false otherwise.
     */
    suspend fun initModelFromFile(context: Context, modelUriString: String): Boolean {
        if (isModelLoaded) {
            releaseModel()
        }
        return withContext(Dispatchers.IO) {
            try {
                val modelUri = Uri.parse(modelUriString)
                val fileName = "local_model.gguf" // Use a consistent cached name
                val destinationFile = File(context.cacheDir, fileName)

                // Copy the model file from the URI to the app's cache
                context.contentResolver.openInputStream(modelUri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "Model copied to cache at ${destinationFile.path}")

                // Load the model from the cached file
                llama.load(destinationFile.path)
                isModelLoaded = true
                Log.d(TAG, "Successfully loaded local model.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize or load model from file", e)
                isModelLoaded = false
                false
            }
        }
    }

    /**
     * Runs inference on the loaded model with a given prompt.
     * @param prompt The full text prompt for the model.
     * @return The model's generated response as a String.
     */
    suspend fun runInference(prompt: String): String {
        if (!isModelLoaded) {
            Log.e(TAG, "Inference attempted but no model is loaded.")
            return "Error: Model not loaded."
        }
        return withContext(Dispatchers.IO) {
            try {
                llama.send(prompt).reduce { acc, s -> acc + s }
            } catch (e: Exception) {
                Log.e(TAG, "Error during model inference", e)
                "Error during inference: ${e.message}"
            }
        }
    }

    /**
     * Unloads the current model from memory.
     */
    suspend fun releaseModel() {
        if (isModelLoaded) {
            try {
                llama.unload()
                isModelLoaded = false
                Log.d(TAG, "Model released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing model", e)
            }
        }
    }
}