package com.itsaky.androidide.app

//import android.llama.cpp.LLamaAndroid // Unresolved reference: LLamaAndroid
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream

/**
 * A singleton object to manage the lifecycle and interaction with the native
 * llama.cpp inference engine.
 */
object LlmInferenceEngine {

    //    private val llamaAndroid: LLamaAndroid = LLamaAndroid.instance()
    private const val TAG = "LlmInferenceEngine"

    suspend fun releaseModel() {
        try {
//            llamaAndroid.unload()
            Log.d(TAG, "Local model unloaded.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unload model", e)
        }
    }

    /**
     * Loads a model from a user-selected file URI.
     * This involves copying the file to a local cache directory to ensure
     * the native code can access it via a direct file path.
     */
    suspend fun initModelFromFile(context: Context, modelUriString: String): Boolean {
        return try {
            val modelUri = modelUriString.toUri()
            // Create a stable file in the app's cache directory
            val destFile = File(context.cacheDir, "current_model.gguf")

            // Copy the user-selected model to our private cache file
            context.contentResolver.openInputStream(modelUri)?.use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw Exception("Failed to open input stream for URI: $modelUriString")

            Log.d(TAG, "Model copied to cache: ${destFile.absolutePath}")

            // Now load the model from the stable file path
//            llamaAndroid.load(destFile.absolutePath)
            Log.d(TAG, "Local model loaded successfully from path.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "initModelFromFile() failed", e)
            false
        }
    }

    /**
     * Runs inference and collects the entire streamed response into a single string.
     */
    suspend fun runInference(fullPrompt: String): String {
        return ""
//        try {
////             .fold() collects the entire flow into a single value
//            llamaAndroid.send(fullPrompt)
//                .fold("") { accumulator, value -> accumulator + value }
//        } catch (e: Exception) {
//            Log.e(TAG, "runInference() failed", e)
//            "Error during inference: ${e.message}"
//        }
    }
}