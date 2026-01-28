package com.itsaky.androidide.agent.repository

import android.content.Context
import androidx.core.net.toUri
import com.itsaky.androidide.agent.utils.DynamicLibraryLoader
import com.itsaky.androidide.llamacpp.api.ILlamaController
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

        android.util.Log.i("LlmEngine", "Initializing Llama Inference Engine...")
        val classLoader = DynamicLibraryLoader.getLlamaClassLoader(context)
        if (classLoader == null) {
            android.util.Log.e(
                "LlmEngine",
                "Failed to create Llama ClassLoader. The library might not be installed."
            )
            return@withContext false
        }
        android.util.Log.d("LlmEngine", "ClassLoader obtained successfully")

        try {
            android.util.Log.d("LlmEngine", "Loading class android.llama.cpp.LLamaAndroid...")
            val llamaAndroidClass = classLoader.loadClass("android.llama.cpp.LLamaAndroid")
            android.util.Log.d("LlmEngine", "Class loaded, getting instance method...")
            val hasInstanceMethod = llamaAndroidClass.methods.any { it.name == "instance" }
            android.util.Log.d(
                "LlmEngine",
                "LLamaAndroid loader=${llamaAndroidClass.classLoader} " +
                        "ILlamaController loader=${ILlamaController::class.java.classLoader} " +
                        "assignable=${
                            ILlamaController::class.java.isAssignableFrom(
                                llamaAndroidClass
                            )
                        } " +
                        "hasInstance=$hasInstanceMethod"
            )
            configureNativeDefaults(llamaAndroidClass)

            val llamaInstance =
                try {
                    val instanceMethod = llamaAndroidClass.getMethod("instance")
                    android.util.Log.d("LlmEngine", "Invoking instance method...")
                    instanceMethod.invoke(null)
                } catch (noMethod: NoSuchMethodException) {
                    android.util.Log.w(
                        "LlmEngine",
                        "No static instance() found, trying Companion/field fallback",
                        noMethod
                    )

                    val companionInstance =
                        try {
                            val companionField = llamaAndroidClass.getField("Companion")
                            companionField.get(null)
                        } catch (companionError: Exception) {
                            android.util.Log.w(
                                "LlmEngine",
                                "Companion field not accessible, falling back to _instance field",
                                companionError
                            )
                            null
                        }

                    if (companionInstance != null) {
                        val companionClass = companionInstance.javaClass
                        val companionInstanceMethod = companionClass.getMethod("instance")
                        companionInstanceMethod.invoke(companionInstance)
                    } else {
                        val instanceField = llamaAndroidClass.getDeclaredField("_instance")
                        instanceField.isAccessible = true
                        instanceField.get(null)
                    }
                }

            llamaController = llamaInstance as ILlamaController
            isInitialized = true
            android.util.Log.i("LlmEngine", "Llama Inference Engine initialized successfully.")
            true
        } catch (e: Exception) {
            android.util.Log.e("LlmEngine", "Failed to initialize Llama class via reflection", e)
            false
        }
    }

    private fun configureNativeDefaults(llamaAndroidClass: Class<*>) {
        try {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val targetThreads = (cores - 2).coerceIn(2, 6)
            llamaAndroidClass.methods.firstOrNull { it.name == "configureThreads" }
                ?.invoke(null, targetThreads, targetThreads)

            val temperature = 0.7f
            val topP = 0.9f
            val topK = 40
            llamaAndroidClass.methods.firstOrNull { it.name == "configureSampling" }
                ?.invoke(null, temperature, topP, topK)
        } catch (e: Exception) {
            android.util.Log.w("LlmEngine", "Failed to configure native defaults", e)
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
                    } ?: run {
                        val fileNameFromPath = modelUri.path?.let { File(it).name }
                        fileNameFromPath ?: "local_model.gguf"
                    }

                val destinationFile = File(context.cacheDir, "local_model.gguf")

                val inputStream =
                    when (modelUri.scheme) {
                        "file" -> modelUri.path?.let { path -> File(path).inputStream() }
                        "content" -> context.contentResolver.openInputStream(modelUri)
                        else -> context.contentResolver.openInputStream(modelUri)
                    }

                inputStream?.use { input ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        input.copyTo(outputStream)
                    }
                } ?: run {
                    android.util.Log.e(
                        "LlmEngine",
                        "Failed to open input stream for model URI: $modelUri"
                    )
                    return@withContext false
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
                android.util.Log.e("LlmEngine", "Failed to initialize or load model from file", e)
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
