package com.itsaky.androidide.agent.repository

import android.content.Context
import androidx.core.net.toUri
import com.itsaky.androidide.agent.utils.DynamicLibraryLoader
import com.itsaky.androidide.llamacpp.api.ILlamaController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
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
    private var llamaAndroidClass: Class<*>? = null
    private var isInitialized = false
    private var samplingDefaults = SamplingConfig(temperature = 0.7f, topP = 0.9f, topK = 40)
    private var configuredContextSize = 4096

    @Volatile
    var isModelLoaded: Boolean = false
        private set
    @Volatile
    var loadedModelName: String? = null
        private set
    @Volatile
    var loadedModelPath: String? = null
        private set
    @Volatile
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
        cachedContext = context.applicationContext
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
            this@LlmInferenceEngine.llamaAndroidClass = llamaAndroidClass
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
            configureNativeDefaults(context, llamaAndroidClass)

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

    private fun configureNativeDefaults(context: Context, llamaAndroidClass: Class<*>) {
        try {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val targetThreads = (cores - 2).coerceIn(2, 6)
            llamaAndroidClass.methods.firstOrNull { it.name == "configureThreads" }
                ?.invoke(null, targetThreads, targetThreads)

            llamaAndroidClass.methods.firstOrNull { it.name == "configureSampling" }
                ?.invoke(
                    null,
                    samplingDefaults.temperature,
                    samplingDefaults.topP,
                    samplingDefaults.topK
                )

            configuredContextSize = determineContextSize(context)
            llamaAndroidClass.methods.firstOrNull { it.name == "configureContext" }
                ?.invoke(null, configuredContextSize)

            val maxTokens = (configuredContextSize * 0.25f).toInt().coerceIn(128, 512)
            llamaAndroidClass.methods.firstOrNull { it.name == "configureMaxTokens" }
                ?.invoke(null, maxTokens)

            llamaAndroidClass.methods.firstOrNull { it.name == "configureKvCacheReuse" }
                // Disable KV reuse until native can safely trim KV cache.
                ?.invoke(null, false)
        } catch (e: Exception) {
            android.util.Log.w("LlmEngine", "Failed to configure native defaults", e)
        }
    }

    private data class SamplingConfig(
        val temperature: Float,
        val topP: Float,
        val topK: Int
    )

    fun updateSampling(temperature: Float, topP: Float, topK: Int) {
        val klass = llamaAndroidClass ?: return
        try {
            klass.methods.firstOrNull { it.name == "configureSampling" }
                ?.invoke(null, temperature, topP, topK)
        } catch (e: Exception) {
            android.util.Log.w("LlmEngine", "Failed to update sampling", e)
        }
    }

    fun resetSamplingDefaults() {
        val context = cachedContext ?: return
        llamaAndroidClass?.let { configureNativeDefaults(context, it) }
    }

    fun getConfiguredContextSize(): Int = configuredContextSize

    suspend fun countTokens(text: String): Int {
        val controller = llamaController ?: return estimateTokenCount(text)
        if (!isModelLoaded) return estimateTokenCount(text)
        return withContext(ioDispatcher) {
            try {
                controller.countTokens(text)
            } catch (e: Throwable) {
                estimateTokenCount(text)
            }
        }
    }

    private fun estimateTokenCount(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return ((trimmed.length / 4.0) + 1).toInt()
    }

    private var cachedContext: Context? = null

    private fun determineContextSize(context: Context): Int {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val totalMemGb = if (memInfo.totalMem > 0) {
            memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        } else {
            6.0
        }
        return when {
            totalMemGb <= 4.5 -> 1024
            totalMemGb <= 8.5 -> 2048
            totalMemGb <= 12.5 -> 3072
            else -> 4096
        }
    }

    /**
     * Copies a model from a URI to the app's cache and loads it.
     * If the engine is not initialized, it will attempt to initialize it first.
     */
    suspend fun initModelFromFile(
        context: Context,
        modelUriString: String,
        expectedSha256: String? = null
    ): Boolean {
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

                val normalizedExpected = expectedSha256?.trim()?.lowercase().orEmpty()
                if (normalizedExpected.isNotBlank()) {
                    val actual = sha256(destinationFile)
                    if (!actual.equals(normalizedExpected, ignoreCase = true)) {
                        log.error(
                            "Model hash verification failed. expected={}, actual={}",
                            normalizedExpected,
                            actual
                        )
                        return@withContext false
                    }
                }

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

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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

    suspend fun runInference(
        prompt: String,
        stopStrings: List<String> = emptyList(),
        clearCache: Boolean = true
    ): String {
        val controller = llamaController ?: throw IllegalStateException("Engine not initialized.")
        if (!isModelLoaded) throw IllegalStateException("Model is not loaded.")

        return withContext(ioDispatcher) {
            if (clearCache) {
                controller.clearKvCache()
            }
            val builder = StringBuilder()
            controller.send(prompt, stop = stopStrings).collect { chunk ->
                builder.append(chunk)
            }
            builder.toString()
        }
    }

    suspend fun runStreamingInference(
        prompt: String,
        stopStrings: List<String> = emptyList(),
        clearCache: Boolean = true
    ): Flow<String> {
        val controller = llamaController ?: throw IllegalStateException("Engine not initialized.")
        if (!isModelLoaded) throw IllegalStateException("Model is not loaded.")

        if (clearCache) {
            controller.clearKvCache()
        }
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
            lowerPath.contains("gemma-3") || lowerPath.contains("gemma3") -> ModelFamily.GEMMA3
            lowerPath.contains("gemma") -> ModelFamily.GEMMA2
            lowerPath.contains("llama") -> ModelFamily.LLAMA3
            else -> ModelFamily.UNKNOWN
        }
    }
}
