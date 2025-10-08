package android.llama.cpp

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

class LLamaAndroid {

    private val log = LoggerFactory.getLogger(LLamaAndroid::class.java)

    private external fun model_n_ctx(context: Long): Int

    private external fun tokenize(context: Long, text: String, add_bos: Boolean): IntArray
    suspend fun getContextSize(): Int {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> model_n_ctx(state.context)
                else -> throw IllegalStateException("Model not loaded")
            }
        }
    }

    suspend fun clearKvCache() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> kv_cache_clear(state.context)
                else -> {}
            }
        }
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

    private val nlen: Int = 1000

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

    fun stop() {
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

    suspend fun load(pathToModel: String) {
        withContext(runLoop) {
            when (threadLocalState.get()) {
                is State.Idle -> {
                    val model = load_model(pathToModel)
                    if (model == 0L) throw IllegalStateException("load_model() failed")

                    val context = new_context(model)
                    if (context == 0L) throw IllegalStateException("new_context() failed")

                    val batch = new_batch(2048, 0, 1)
                    if (batch == 0L) throw IllegalStateException("new_batch() failed")

                    val sampler = new_sampler()
                    if (sampler == 0L) throw IllegalStateException("new_sampler() failed")

                    log.info("Loaded model {}", pathToModel)
                    threadLocalState.set(State.Loaded(model, context, batch, sampler))
                }

                else -> throw IllegalStateException("Model already loaded")
            }
        }
    }

    fun send(
        message: String,
        formatChat: Boolean = false,
        stop: List<String> = emptyList(),
        clearCache: Boolean = false
    ): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                isStopped.set(false)

                if (clearCache) {
                    kv_cache_clear(state.context)
                }

                val ncur = IntVar(
                    completion_init(
                        state.context,
                        state.batch,
                        message,
                        formatChat,
                        nlen,
                        stop.toTypedArray()
                    )
                )

                while (ncur.value <= nlen) {
                    if (isStopped.get()) {
                        log.info("Stopping generation loop because stop flag was set.")
                        break
                    }

                    val str = completion_loop(state.context, state.batch, state.sampler, nlen, ncur)
                    if (str == null) {
                        break
                    }
                    emit(str)
                }
            }

            else -> {}
        }
    }.flowOn(runLoop)

    suspend fun unload() {
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

        fun instance(): LLamaAndroid = _instance
    }
}