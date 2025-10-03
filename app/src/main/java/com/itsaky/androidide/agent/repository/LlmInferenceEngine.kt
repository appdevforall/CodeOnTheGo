package com.itsaky.androidide.agent.repository

import android.content.Context
import android.database.Cursor
import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
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
    private val llama = LLamaAndroid.instance()

    var isModelLoaded = false
        private set
    var loadedModelName: String? = null
        private set

    suspend fun initModelFromFile(context: Context, modelUriString: String): Boolean {
        if (isModelLoaded) {
            releaseModel()
        }
        return withContext(Dispatchers.IO) {
            try {
                val modelUri = modelUriString.toUri()
                val originalFileName = getFileNameFromUri(modelUri, context)
                val destinationFile = File(context.cacheDir, "local_model.gguf")

                context.contentResolver.openInputStream(modelUri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                log.info("Model copied to cache at {}", destinationFile.path)

                llama.load(destinationFile.path)
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
        if (!isModelLoaded) {
            log.error("Inference attempted but no model is loaded.")
            return "Error: Model not loaded."
        }
        llama.clearKvCache()
        return withContext(Dispatchers.IO) {
            try {
                val send = llama.send(prompt, stop = stopStrings)
                send.fold("") { accumulator, value -> accumulator + value }
            } catch (e: Exception) {
                log.error("Error during model inference", e)
                "Error during inference: ${e.message}"
            }
        }
    }

    suspend fun releaseModel() {
        if (isModelLoaded) {
            try {
                llama.unload()
                isModelLoaded = false
                loadedModelName = null
                log.info("Model released.")
            } catch (e: Exception) {
                log.error("Error releasing model", e)
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

    fun stopInference() {
        log.info("Attempting to stop inference immediately.")
        try {
            llama.stop()
        } catch (e: Exception) {
            log.error("Error calling stop on native library", e)
        }
    }
}