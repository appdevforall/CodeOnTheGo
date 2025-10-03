package com.itsaky.androidide.agent.repository

import android.content.Context
import android.database.Cursor
import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.fold
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
    var loadedModelName: String? = null
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
                val modelUri = modelUriString.toUri()
                val originalFileName = getFileNameFromUri(modelUri, context)
                val destinationFile = File(context.cacheDir, "local_model.gguf")

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
                loadedModelName = originalFileName
                Log.d(TAG, "Successfully loaded local model: $loadedModelName")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize or load model from file", e)
                isModelLoaded = false
                loadedModelName = null
                false
            }
        }
    }

    /**
     * Runs inference on the loaded model with a given prompt.
     * @param prompt The full text prompt for the model.
     * @return The model's generated response as a String.
     */
    suspend fun runInference(prompt: String, stopStrings: List<String>): String {
        if (!isModelLoaded) {
            Log.e(TAG, "Inference attempted but no model is loaded.")
            return "Error: Model not loaded."
        }
        llama.clearKvCache()
        return withContext(Dispatchers.IO) {
            try {
                val send = llama.send(prompt, stop = stopStrings)
                send.fold("") { accumulator, value -> accumulator + value }
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
                loadedModelName = null
                Log.d(TAG, "Model released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing model", e)
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri, context: Context): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val colIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (colIndex >= 0) {
                        result = it.getString(colIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result ?: "Unknown File"
    }

    suspend fun clearKvCache() {
        llama.clearKvCache()
    }

    /**
     * ✨ Immediately stops any ongoing inference in the native layer.
     * This assumes the underlying LLamaAndroid library supports this operation.
     */
    fun stopInference() {
        Log.d(TAG, "Attempting to stop inference immediately.")
        try {
            llama.stop() // Assumed method in the native library
        } catch (e: Exception) {
            Log.e(TAG, "Error calling stop on native library", e)
        }
    }
}