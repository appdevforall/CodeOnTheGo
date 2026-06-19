package android.llama.cpp

import com.itsaky.androidide.llamacpp.api.ILlamaController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LLamaAndroid : ILlamaController {

    private val log = LoggerFactory.getLogger(LLamaAndroid::class.java)

    private external fun model_n_ctx(context: Long): Int
    private external fun get_pooling_type(context: Long): Int
    private external fun get_model_desc(model: Long): String

    private external fun tokenize(context: Long, text: String, add_bos: Boolean): IntArray
    suspend fun getContextSize(): Int {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> model_n_ctx(state.context)
                else -> throw IllegalStateException("Model not loaded")
            }
        }
    }

    override suspend fun clearKvCache() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> kv_cache_clear(state.context)
                else -> {}
            }
        }
    }

    override suspend fun countTokens(text: String): Int {
        return tokenize(text).size
    }

    suspend fun tokenize(text: String): IntArray {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> tokenize(state.context, text, true)
                else -> throw IllegalStateException("Model not loaded")
            }
        }
    }

    private val threadLocalState: ThreadLocal<State> = ThreadLocal.withInitial { State.Idle }

    private val isStopped = AtomicBoolean(false)

    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            log.debug("Dedicated thread for native code: {}", Thread.currentThread().name)

            System.loadLibrary("llama-android")

            log_to_android()
            backend_init(false)

            log.debug("System Info: {}", system_info())

            it.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, exception: Throwable ->
                log.error("Unhandled exception on Llm-RunLoop thread", exception)
            }
        }
    }.asCoroutineDispatcher()

    private var nlen: Int = 256

    private fun updateMaxTokens(maxTokens: Int) {
        val clamped = maxTokens.coerceIn(64, 1024)
        nlen = clamped
    }

    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long): Long
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean)
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(): Long
    private external fun free_sampler(sampler: Long)
    private external fun bench_model(
        context: Long,
        model: Long,
        batch: Long,
        pp: Int,
        tg: Int,
        pl: Int,
        nr: Int
    ): String

    private external fun system_info(): String

    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int,
        stop: Array<String>
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: IntVar
    ): String?

    private external fun kv_cache_clear(context: Long)

    override fun stop() {
        log.info("Stop requested for current generation.")
        isStopped.set(true)
    }

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    log.debug("bench(): {}", state)
                    bench_model(state.context, state.model, state.batch, pp, tg, pl, nr)
                }

                else -> throw IllegalStateException("No model loaded")
            }
        }
    }

    override suspend fun load(pathToModel: String) {
        withContext(runLoop) {
            when (threadLocalState.get()) {
                is State.Idle -> {
                    val model = load_model(pathToModel)
                    if (model == 0L) throw IllegalStateException("load_model() failed")

                    // Log model information for diagnostics
                    val modelDesc = try {
                        get_model_desc(model)
                    } catch (e: Exception) {
                        log.warn("Failed to get model description", e)
                        "unknown"
                    }
                    log.info("Model description: {}", modelDesc)

                    val context = new_context(model)
                    if (context == 0L) {
                        free_model(model)
                        throw IllegalStateException("new_context() failed")
                    }

                    // Check if this is an embedding-only model (not suitable for text generation)
                    val poolingType = try {
                        get_pooling_type(context)
                    } catch (e: UnsatisfiedLinkError) {
                        // Function not available in pre-built AAR - will detect via decode error instead
                        log.warn("get_pooling_type() not available (old AAR), will validate during inference")
                        -1 // Unknown
                    } catch (e: Exception) {
                        log.warn("Failed to get pooling type", e)
                        -1 // Unknown
                    }

                    if (poolingType >= 0) {
                        log.info("Model pooling type: {} (0=generative, 1=mean, 2=cls, 3=last, 4=rank)", poolingType)

                        // Pooling types: NONE=0, MEAN=1, CLS=2, LAST=3, RANK=4
                        // Models with pooling (1-4) are embedding models, not suitable for chat
                        if (poolingType != 0) {
                            free_context(context)
                            free_model(model)
                            throw IllegalStateException(
                                "This model is an embedding model (pooling_type=$poolingType) and cannot be used for text generation. " +
                                "Please select a chat/instruct model instead. Embedding models are designed for " +
                                "semantic search and similarity tasks, not conversational AI."
                            )
                        }
                    } else {
                        log.warn("Could not determine pooling type - will attempt inference and catch errors")
                    }

                    val batch = new_batch(2048, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    val sampler = new_sampler()
                    if (sampler == 0L) throw IllegalStateException("new_sampler() failed")

                    // CRITICAL: Test the model with a tiny inference to validate it's not an embedding model
                    // This prevents crashes during actual user interaction
                    log.info("Validating model can perform text generation (dry run test)...")
                    try {
                        val testResult = completion_init(
                            context,
                            batch,
                            "Hi",  // Minimal test prompt
                            false,  // No chat formatting
                            1,      // Only 1 token output
                            emptyArray()  // No stop strings
                        )

                        if (testResult <= 0) {
                            throw IllegalStateException("Validation failed: model returned $testResult tokens")
                        }

                        log.info("Model validation passed - {} tokens processed", testResult)

                        // Clear the test from KV cache
                        kv_cache_clear(context)

                    } catch (e: Exception) {
                        // Model failed validation - clean up and reject
                        log.error("Model validation failed - this is likely an embedding model or incompatible architecture", e)

                        free_sampler(sampler)
                        free_batch(batch)
                        free_context(context)
                        free_model(model)

                        throw IllegalStateException(
                            "Model validation failed: Cannot perform text generation.\n\n" +
                            "This typically indicates:\n" +
                            "• An embedding model (e.g., all-MiniLM, e5, bge, mpnet)\n" +
                            "• Incompatible model architecture\n" +
                            "• Corrupted model file\n\n" +
                            "Please use a chat/instruct model instead:\n" +
                            "• Llama-3.2-1B-Instruct-Q4_K_M.gguf\n" +
                            "• Qwen2.5-0.5B-Instruct-Q4_K_M.gguf\n" +
                            "• gemma-2-2b-it-Q4_K_M.gguf\n\n" +
                            "Error: ${e.message}",
                            e
                        )
                    }

                    log.info("Loaded model {}", pathToModel)
                    threadLocalState.set(State.Loaded(model, context, batch, sampler))
                }

                else -> throw IllegalStateException("Model already loaded")
            }
        }
    }


    /*

        formatChat: Boolean = false,
        stop: List<String> = emptyList(),
        clearCache: Boolean = false
     */
    override fun send(
        message: String,
        formatChat: Boolean,
        stop: List<String>,
        clearCache: Boolean
    ): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                log.debug("Starting inference - formatChat={}, clearCache={}, nlen={}", formatChat, clearCache, nlen)

                // Defensive check: verify this is not an embedding model
                try {
                    val poolingType = get_pooling_type(state.context)
                    log.debug("Model pooling_type: {}", poolingType)
                    if (poolingType != 0) {
                        log.error("Attempted to use embedding model (pooling_type={}) for text generation", poolingType)
                        throw IllegalStateException(
                            "Cannot perform text generation with an embedding model (pooling_type=$poolingType). " +
                            "This model is designed for embeddings, not chat."
                        )
                    }
                } catch (e: UnsatisfiedLinkError) {
                    log.warn("Unable to check pooling type, proceeding with generation", e)
                } catch (e: IllegalStateException) {
                    log.error("Embedding model check failed", e)
                    throw e
                }

                // Check context size vs message length
                try {
                    val contextSize = model_n_ctx(state.context)
                    val tokenCount = tokenize(state.context, message, true).size
                    log.debug("Context size: {}, message tokens: {}, max output: {}", contextSize, tokenCount, nlen)

                    if (tokenCount + nlen > contextSize) {
                        log.error("Message too long: {} tokens + {} max output > {} context", tokenCount, nlen, contextSize)
                        throw IllegalStateException(
                            "Message is too long for the model's context window. " +
                            "Message requires $tokenCount tokens plus $nlen for output, but context is only $contextSize tokens."
                        )
                    }
                } catch (e: Exception) {
                    log.error("Failed to validate context size", e)
                    throw IllegalStateException("Failed to validate message length: ${e.message}", e)
                }

                isStopped.set(false)

                if (clearCache) {
                    log.debug("Clearing KV cache")
                    kv_cache_clear(state.context)
                }

                log.debug("Calling completion_init")
                val ncur = try {
                    val result = completion_init(
                        state.context,
                        state.batch,
                        message,
                        formatChat,
                        nlen,
                        stop.toTypedArray()
                    )

                    if (result <= 0) {
                        log.error("completion_init returned invalid token count: {}", result)
                        throw IllegalStateException(
                            "Model failed to initialize text generation. " +
                            "This may indicate an embedding model or incompatible model architecture. " +
                            "Please ensure you're using a chat/instruct model, not an embedding model."
                        )
                    }

                    log.debug("completion_init succeeded with {} tokens", result)
                    IntVar(result)
                } catch (e: IllegalStateException) {
                    // Re-throw our own exceptions
                    throw e
                } catch (e: Exception) {
                    log.error("completion_init failed", e)
                    val errorMsg = e.message ?: ""

                    // Check for embedding model indicators in error message
                    if (errorMsg.contains("embed", ignoreCase = true) ||
                        errorMsg.contains("encode", ignoreCase = true) ||
                        errorMsg.contains("pooling", ignoreCase = true)) {
                        throw IllegalStateException(
                            "This appears to be an embedding model and cannot be used for text generation. " +
                            "Please select a chat/instruct model (Llama, Qwen, Gemma, etc.) instead.",
                            e
                        )
                    }

                    throw IllegalStateException("Failed to initialize text generation: ${e.message}", e)
                }

                log.debug("Starting generation loop")
                var loopCount = 0
                var consecutiveNullOrEmpty = 0
                var totalEmitted = 0

                while (true) {
                    if (isStopped.get()) {
                        log.info("Stopping generation loop because stop flag was set.")
                        break
                    }

                    try {
                        val str = completion_loop(state.context, state.batch, state.sampler, nlen, ncur)

                        if (str == null) {
                            log.debug("Generation completed after {} iterations ({} tokens emitted)", loopCount, totalEmitted)
                            break
                        }

                        if (str.isEmpty()) {
                            consecutiveNullOrEmpty++
                            if (consecutiveNullOrEmpty > 10 && totalEmitted == 0) {
                                log.error("Model producing only empty strings - likely embedding model")
                                throw IllegalStateException(
                                    "Model is not generating text properly. This is typically caused by using " +
                                    "an embedding model for text generation. Please use a chat/instruct model."
                                )
                            }
                        } else {
                            consecutiveNullOrEmpty = 0
                            totalEmitted++
                        }

                        emit(str)
                        loopCount++

                        // Safety limit for infinite loops
                        if (loopCount > 10000) {
                            log.error("Generation loop exceeded 10000 iterations, stopping")
                            break
                        }

                    } catch (e: IllegalStateException) {
                        // Re-throw our own error messages
                        throw e
                    } catch (e: Exception) {
                        log.error("Error during generation loop at iteration {} ({} tokens emitted)", loopCount, totalEmitted, e)

                        val errorMsg = e.message ?: ""
                        if (totalEmitted == 0 || errorMsg.contains("decode", ignoreCase = true)) {
                            throw IllegalStateException(
                                "Text generation failed before producing output. " +
                                "This often indicates an embedding model or incompatible architecture. " +
                                "Please use a chat/instruct model, not an embedding model.",
                                e
                            )
                        }

                        throw IllegalStateException(
                            "Text generation failed: ${e.message}",
                            e
                        )
                    }
                }
            }

            else -> {}
        }
    }.flowOn(runLoop)

    override suspend fun unload() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    free_context(state.context)
                    free_model(state.model)
                    free_batch(state.batch)
                    free_sampler(state.sampler)

                    threadLocalState.set(State.Idle)
                }

                else -> {}
            }
        }
    }

    companion object {
        private val nativeLog = LoggerFactory.getLogger("llama.cpp")

        @JvmStatic
        external fun configureThreads(nThreads: Int, nThreadsBatch: Int)

        @JvmStatic
        external fun configureSampling(temperature: Float, topP: Float, topK: Int)

        @JvmStatic
        external fun configureContext(nCtx: Int)

        @JvmStatic
        external fun configureKvCacheReuse(enabled: Boolean)

        @JvmStatic
        fun configureMaxTokens(maxTokens: Int) {
            _instance.updateMaxTokens(maxTokens)
        }

        @JvmStatic
        fun logFromNative(level: Int, message: String) {
            val cleanMessage = message.trim()
            when (level) {
                2 -> nativeLog.error(cleanMessage) // GGML_LOG_LEVEL_ERROR = 2
                3 -> nativeLog.warn(cleanMessage)  // GGML_LOG_LEVEL_WARN  = 3
                4 -> nativeLog.info(cleanMessage)   // GGML_LOG_LEVEL_INFO  = 4
                else -> nativeLog.debug(cleanMessage)
            }
        }

        private class IntVar(value: Int) {
            @Volatile
            var value: Int = value
                private set

            fun inc() {
                synchronized(this) {
                    value += 1
                }
            }
        }

        private sealed interface State {
            data object Idle : State
            data class Loaded(
                val model: Long,
                val context: Long,
                val batch: Long,
                val sampler: Long
            ) : State
        }

        private val _instance: LLamaAndroid = LLamaAndroid()

        @JvmStatic
        fun instance(): LLamaAndroid = _instance
    }
}
