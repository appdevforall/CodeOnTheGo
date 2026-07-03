package com.itsaky.androidide.agent.repository

import android.content.Context
import androidx.core.net.toUri
import com.itsaky.androidide.agent.model.ModelLoadResult
import com.itsaky.androidide.llamacpp.api.ILlamaController
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.DynamicLibraryLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException

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
    private val log = LoggerFactory.getLogger(LOG_TAG)

    private var llamaController: ILlamaController? = null
    private var llamaAndroidClass: Class<*>? = null
    private var isInitialized = false
    private var samplingDefaults = SamplingConfig(temperature = 0.7f, topP = 0.9f, topK = 40)
    private var configuredContextSize = 4096
    private var configuredMaxTokens = 512
    private var defaultMaxTokens = 512
    private val modelLoadMutex = Mutex()

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
    var loadedModelSourceUri: String? = null
        private set
    @Volatile
    var currentModelFamily: ModelFamily = ModelFamily.UNKNOWN
        private set

    companion object {
        private const val LOG_TAG = "LlmEngine"
        private const val DEFAULT_TOTAL_MEM_GB = 6.0
        private const val LOW_MEM_GB = 4.5
        private const val MID_MEM_GB = 8.5
        private const val HIGH_MEM_GB = 12.5

        private const val CONTEXT_SIZE_LOW_MEM = 1024
        private const val CONTEXT_SIZE_MID_MEM = 2048
        private const val CONTEXT_SIZE_HIGH_MEM = 3072
        private const val CONTEXT_SIZE_MAX = 4096

        private const val EXT_ONNX = ".onnx"
        private const val EXT_PT = ".pt"
        private const val EXT_PTH = ".pth"
        private const val EXT_BIN = ".bin"
        private const val EXT_SAFETENSORS = ".safetensors"
        private const val EXT_PB = ".pb"
        private const val EXT_TFLITE = ".tflite"
        private const val EXT_GGML = ".ggml"
        private const val EXT_GGUF = ".gguf"

        private const val KEYWORD_TENSORFLOW = "tensorflow"
        private const val KEYWORD_ALL_MINI = "all-mini"
        private const val KEYWORD_ALL_MPNET = "all-mpnet"
        private const val KEYWORD_E5 = "e5-"
        private const val KEYWORD_EMBED = "embed"
        private const val KEYWORD_LLAMA = "llama"
        private const val KEYWORD_H2O = "h2o"
        private const val KEYWORD_DANUBE = "danube"
        private const val KEYWORD_QWEN = "qwen"
        private const val KEYWORD_GEMMA3 = "gemma3"
        private const val KEYWORD_GEMMA_3 = "gemma-3"
        private const val KEYWORD_GEMMA = "gemma"
    }

    /**
     * Initializes the Llama controller by dynamically loading the library.
     * This must be called once on the instance before any other methods are used.
     * @return true if initialization was successful, false otherwise.
     */
    suspend fun initialize(context: Context): Boolean = withContext(ioDispatcher) {
        if (isInitialized) return@withContext true

        cachedContext = context.applicationContext
        val classLoader = DynamicLibraryLoader.getLlamaClassLoader(context)
        if (classLoader == null) {
            log.error("Failed to create Llama ClassLoader. The library might not be installed.")
            return@withContext false
        }

        try {
            val llamaAndroidClass = classLoader.loadClass("android.llama.cpp.LLamaAndroid")
            this@LlmInferenceEngine.llamaAndroidClass = llamaAndroidClass
            val hasInstanceMethod = llamaAndroidClass.methods.any { it.name == "instance" }
            log.info(
                "Llama Android class loaded. assignable={} hasInstance={}",
                ILlamaController::class.java.isAssignableFrom(llamaAndroidClass),
                hasInstanceMethod
            )
            configureNativeDefaults(context, llamaAndroidClass)

            val llamaInstance = resolveLlamaInstance(llamaAndroidClass)

            llamaController = llamaInstance as ILlamaController
            isInitialized = true
            log.info("Llama Inference Engine initialized successfully.")
            true
        } catch (e: Exception) {
            log.error("Failed to initialize Llama class via reflection", e)
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
            configuredMaxTokens = maxTokens
            defaultMaxTokens = maxTokens
            llamaAndroidClass.methods.firstOrNull { it.name == "configureMaxTokens" }
                ?.invoke(null, maxTokens)

            llamaAndroidClass.methods.firstOrNull { it.name == "configureKvCacheReuse" }
                // Disable KV reuse until native can safely trim KV cache.
                ?.invoke(null, false)
        } catch (e: Exception) {
            log.warn("Failed to configure native defaults", e)
        }
    }

    private fun resolveLlamaInstance(llamaAndroidClass: Class<*>): Any? {
        val instance = runCatching {
            val instanceMethod = llamaAndroidClass.getMethod("instance")
            instanceMethod.invoke(null)
        }.getOrElse { error ->
            if (error is NoSuchMethodException) {
                log.warn("No static instance() found, trying Companion/field fallback", error)
            } else {
                log.warn("Instance() invocation failed", error)
            }
            null
        }

        if (instance != null) return instance

        val companionInstance = runCatching {
            val companionField = llamaAndroidClass.getField("Companion")
            companionField.get(null)
        }.getOrElse { error ->
            log.warn("Companion field not accessible, falling back to _instance field", error)
            null
        }

        val companionResolved = companionInstance?.let { companion ->
            runCatching {
                val companionClass = companion.javaClass
                val companionInstanceMethod = companionClass.getMethod("instance")
                companionInstanceMethod.invoke(companion)
            }.getOrElse { error ->
                log.warn("Companion instance() invocation failed", error)
                null
            }
        }

        if (companionResolved != null) return companionResolved

        return runCatching {
            val instanceField = llamaAndroidClass.getDeclaredField("_instance")
            instanceField.isAccessible = true
            instanceField.get(null)
        }.getOrElse { error ->
            log.warn("Fallback _instance field access failed", error)
            null
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
            log.warn("Failed to update sampling", e)
        }
    }

    fun updateMaxTokens(maxTokens: Int) {
        val klass = llamaAndroidClass ?: return
        try {
            configuredMaxTokens = maxTokens
            klass.methods.firstOrNull { it.name == "configureMaxTokens" }
                ?.invoke(null, maxTokens)
        } catch (e: Exception) {
            log.warn("Failed to update max tokens", e)
        }
    }

    fun resetMaxTokens() {
        updateMaxTokens(defaultMaxTokens)
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
                log.warn("Token counting failed; falling back to estimate.", e)
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
            DEFAULT_TOTAL_MEM_GB
        }
        return when {
            totalMemGb <= LOW_MEM_GB -> CONTEXT_SIZE_LOW_MEM
            totalMemGb <= MID_MEM_GB -> CONTEXT_SIZE_MID_MEM
            totalMemGb <= HIGH_MEM_GB -> CONTEXT_SIZE_HIGH_MEM
            else -> CONTEXT_SIZE_MAX
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
    ): ModelLoadResult {
        return modelLoadMutex.withLock {
            if (!ensureInitialized(context)) {
                return@withLock ModelLoadResult.Failed(
                    message = context.getString(R.string.model_error_engine_init)
                )
            }

            if (isModelLoaded) unloadModel()

            withContext(ioDispatcher) {
                loadModelFromUri(context, modelUriString, expectedSha256)
            }
        }
    }

    private suspend fun ensureInitialized(context: Context): Boolean {
        if (isInitialized) return true
        if (!initialize(context)) {
            log.error("Engine initialization failed. Cannot load model.")
            return false
        }
        return true
    }

    private suspend fun loadModelFromUri(
        context: Context,
        modelUriString: String,
        expectedSha256: String?
    ): ModelLoadResult {
        val modelUri = modelUriString.toUri()
        val displayName = resolveModelDisplayName(context, modelUri)

        return try {
            validateModelFormat(context, displayName)

            val destinationFile = File(context.cacheDir, "local_model.gguf")

            if (!copyModelToCache(context, modelUri, destinationFile)) {
                return ModelLoadResult.Failed(
                    message = context.getString(R.string.model_error_copy_failed)
                )
            }

            if (!verifyModelHash(destinationFile, expectedSha256)) {
                return ModelLoadResult.Failed(
                    message = context.getString(R.string.model_error_verification_failed)
                )
            }

            llamaController?.load(destinationFile.path)

            isModelLoaded = true
            loadedModelPath = destinationFile.path
            loadedModelSourceUri = modelUriString
            loadedModelName = displayName
            currentModelFamily = detectModelFamily(displayName)

            ModelLoadResult.Loaded(displayName)
        } catch (e: CancellationException) {
            resetLoadedModelState()
            throw e
        } catch (e: IllegalStateException) {
            resetLoadedModelState()

            if (e.message?.contains("embedding model", ignoreCase = true) == true) {
                log.error("Cannot use embedding model for chat: {}", displayName, e)

                ModelLoadResult.Rejected(
                    context.getString(R.string.model_error_embedding, displayName)
                )
            } else {
                log.error("Failed to load model: {}", displayName, e)

                ModelLoadResult.Failed(
                    message = context.getString(R.string.model_error_load_failed, displayName),
                    cause = e
                )
            }
        } catch (e: IllegalArgumentException) {
            log.error("Model validation failed: {}", displayName, e)
            resetLoadedModelState()

            ModelLoadResult.Rejected(
                e.message ?: context.getString(R.string.model_error_format_unsupported)
            )
        } catch (e: Exception) {
            log.error("Failed to initialize or load model from file", e)
            resetLoadedModelState()

            ModelLoadResult.Failed(
                message = context.getString(R.string.model_error_load_failed, displayName),
                cause = e
            )
        }
    }

    private fun resolveModelDisplayName(context: Context, modelUri: android.net.Uri): String {
        val nameFromProvider =
            context.contentResolver.query(modelUri, null, null, null, null)
                ?.use { cursor ->
                    val nameIndex =
                        cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
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
        return fileNameFromPath ?: "local_model.gguf"
    }

    private fun copyModelToCache(
        context: Context,
        modelUri: android.net.Uri,
        destinationFile: File
    ): Boolean {
        val inputStream = openModelInputStream(context, modelUri)
        inputStream?.use { input ->
            FileOutputStream(destinationFile).use { outputStream ->
                input.copyTo(outputStream)
            }
        } ?: run {
            log.error("Failed to open input stream for model URI: {}", modelUri)
            return false
        }
        return true
    }

    private fun openModelInputStream(
        context: Context,
        modelUri: android.net.Uri
    ) = when (modelUri.scheme) {
        "file" -> modelUri.path?.let { path -> File(path).inputStream() }
        "content" -> context.contentResolver.openInputStream(modelUri)
        else -> context.contentResolver.openInputStream(modelUri)
    }

    private fun verifyModelHash(destinationFile: File, expectedSha256: String?): Boolean {
        val normalizedExpected = expectedSha256?.trim()?.lowercase().orEmpty()
        if (normalizedExpected.isBlank()) return true
        val actual = sha256(destinationFile)
        if (!actual.equals(normalizedExpected, ignoreCase = true)) {
            log.error(
                "Model hash verification failed. expected={}, actual={}",
                normalizedExpected,
                actual
            )
            return false
        }
        return true
    }

    private fun resetLoadedModelState() {
        isModelLoaded = false
        loadedModelPath = null
        loadedModelSourceUri = null
        loadedModelName = null
        currentModelFamily = ModelFamily.UNKNOWN
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
        loadedModelSourceUri = null
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
        clearCache: Boolean = true,
        formatChat: Boolean = false
    ): Flow<String> {
        val controller = llamaController ?: throw IllegalStateException("Engine not initialized.")
        if (!isModelLoaded) throw IllegalStateException("Model is not loaded.")

        if (clearCache) {
            controller.clearKvCache()
        }
        return controller.send(prompt, formatChat = formatChat, stop = stopStrings)
            .flowOn(ioDispatcher)
    }

    fun stop() {
        if (!isInitialized) return
        try {
            llamaController?.stop()
        } catch (e: Exception) {
            log.error("Error calling stop on the native library", e)
        }
    }

    /**
     * Validates that the model file format is supported.
     * This app uses llama.cpp which only supports GGUF format.
     *
     * @throws IllegalArgumentException if the model format is not supported
     */
    private fun validateModelFormat(context: Context, filename: String) {
        val lowerName = filename.lowercase()

        when {
            lowerName.endsWith(EXT_ONNX) -> {
                throw IllegalArgumentException(context.getString(R.string.model_error_format_onnx))
            }
            lowerName.endsWith(EXT_PT) || lowerName.endsWith(EXT_PTH) || lowerName.endsWith(EXT_BIN) -> {
                throw IllegalArgumentException(context.getString(R.string.model_error_format_pytorch))
            }
            lowerName.endsWith(EXT_SAFETENSORS) -> {
                throw IllegalArgumentException(context.getString(R.string.model_error_format_safetensors))
            }
            lowerName.endsWith(EXT_PB) || lowerName.contains(KEYWORD_TENSORFLOW) -> {
                throw IllegalArgumentException(context.getString(R.string.model_error_format_tensorflow))
            }
            lowerName.endsWith(EXT_TFLITE) -> {
                throw IllegalArgumentException(context.getString(R.string.model_error_format_tflite))
            }
            lowerName.endsWith(EXT_GGML) -> {
                throw IllegalArgumentException(context.getString(R.string.model_error_format_ggml))
            }
            !lowerName.endsWith(EXT_GGUF) -> {
                log.warn("Model file '{}' doesn't have $EXT_GGUF extension. May fail to load.", filename)
            }
        }

        if (lowerName.contains(KEYWORD_ALL_MINI) ||
            lowerName.contains(KEYWORD_ALL_MPNET) ||
            lowerName.contains(KEYWORD_E5) ||
            (lowerName.contains(KEYWORD_EMBED) && !lowerName.contains(KEYWORD_LLAMA))) {
            log.error("Rejecting embedding model based on filename: {}", filename)
            throw IllegalArgumentException(
                context.getString(R.string.model_error_embedding, filename)
            )
        }
    }

    private fun detectModelFamily(path: String): ModelFamily {
        val lowerPath = path.lowercase()
        return when {
            lowerPath.contains(KEYWORD_H2O) || lowerPath.contains(KEYWORD_DANUBE) -> ModelFamily.H2O
            lowerPath.contains(KEYWORD_QWEN) -> ModelFamily.QWEN
            lowerPath.contains(KEYWORD_GEMMA_3) || lowerPath.contains(KEYWORD_GEMMA3) -> ModelFamily.GEMMA3
            lowerPath.contains(KEYWORD_GEMMA) -> ModelFamily.GEMMA2
            lowerPath.contains(KEYWORD_LLAMA) -> ModelFamily.LLAMA3
            else -> ModelFamily.UNKNOWN
        }
    }

}
