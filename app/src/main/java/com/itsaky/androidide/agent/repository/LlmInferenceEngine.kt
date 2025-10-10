package com.itsaky.androidide.agent.repository

import android.content.Context
import android.llama.cpp.LLamaAndroid
import androidx.core.net.toUri
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
 * A wrapper class for the LLamaAndroid library to abstract away direct interactions
 * and manage model state and execution contexts.
 *
 * This class is designed to be instantiated and used as a dependency, for example,
 * within a repository. It handles threading for long-running operations.
 *
 * @param llama The underlying LLamaAndroid instance. Defaults to the singleton instance.
 * @param ioDispatcher The coroutine dispatcher for blocking I/O and CPU-intensive operations.
 */
class LlmInferenceEngine(
    private val llama: LLamaAndroid = LLamaAndroid.instance(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val log = LoggerFactory.getLogger(LlmInferenceEngine::class.java)
    var isModelLoaded: Boolean = false
        private set

    var loadedModelPath: String? = null
        private set

    suspend fun initModelFromFile(context: Context, modelUriString: String): Boolean {
        if (isModelLoaded) {
            unloadModel()
        }
        return withContext(ioDispatcher) {
            try {
                val modelUri = modelUriString.toUri()
                val destinationFile = File(context.cacheDir, "local_model.gguf")

                context.contentResolver.openInputStream(modelUri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                log.info("Model copied to cache at {}", destinationFile.path)

                llama.load(destinationFile.path)
                isModelLoaded = true
                loadedModelPath = destinationFile.path
                log.info("Successfully loaded local model: {}", loadedModelPath)
                true
            } catch (e: Exception) {
                log.error("Failed to initialize or load model from file", e)
                isModelLoaded = false
                loadedModelPath = null
                false
            }
        }
    }

    /**
     * Unloads the currently loaded model and releases its resources.
     */
    suspend fun unloadModel() {
        if (!isModelLoaded) return
        withContext(ioDispatcher) {
            llama.unload()
        }
        isModelLoaded = false
        loadedModelPath = null
    }

    /**
     * Runs a non-streaming inference, returning the complete model response as a single string.
     * This method clears the KV cache before execution.
     *
     * @param prompt The input prompt for the model.
     * @param stopStrings A list of strings that will cause the generation to stop.
     * @return The complete generated text.
     */
    suspend fun runInference(prompt: String, stopStrings: List<String> = emptyList()): String {
        if (!isModelLoaded) throw IllegalStateException("Model is not loaded.")
        llama.clearKvCache()
        return withContext(ioDispatcher) {
            llama.send(prompt, stop = stopStrings).reduce { acc, s -> acc + s }
        }
    }

    /**
     * Runs a streaming inference, returning a Flow of response chunks.
     * This method clears the KV cache before execution.
     * The Flow is configured to emit on the provided IO dispatcher.
     *
     * @param prompt The input prompt for the model.
     * @param stopStrings A list of strings that will cause the generation to stop.
     * @return A Flow<String> that emits text chunks as they are generated.
     */
    suspend fun runStreamingInference(
        prompt: String,
        stopStrings: List<String> = emptyList()
    ): Flow<String> {
        if (!isModelLoaded) throw IllegalStateException("Model is not loaded.")
        llama.clearKvCache()
        return llama.send(prompt, stop = stopStrings).flowOn(ioDispatcher)
    }

    /**
     * Runs a benchmark on the loaded model.
     *
     * @param pp Prompt processing batch size.
     * @param tg Token generation batch size.
     * @param pl Parallel decoding count.
     * @param nr Number of runs.
     * @return A string containing the benchmark results.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
        if (!isModelLoaded) throw IllegalStateException("Model is not loaded.")
        return withContext(ioDispatcher) {
            llama.bench(pp, tg, pl, nr)
        }
    }

    /**
     * @return The context size of the currently loaded model in tokens, or 0 if no model is loaded.
     */
    suspend fun getContextSize(): Int {
        return if (isModelLoaded) {
            llama.getContextSize()
        } else {
            0
        }
    }

    /**
     * Immediately stops any ongoing inference.
     */
    fun stop() {
        llama.stop()
    }
}
