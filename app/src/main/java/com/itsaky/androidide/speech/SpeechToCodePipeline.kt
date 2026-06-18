package com.itsaky.androidide.speech

import android.content.Context
import android.util.Log
import com.itsaky.androidide.llamacpp.api.ILlamaController
import kotlinx.coroutines.withTimeout

private const val TAG = "SpeechToCodePipeline"

/**
 * End-to-end pipeline: Audio → STT → Intent → Code Generation
 *
 * Latency breakdown:
 * - Audio capture: handled by caller
 * - STT (Cloud): 500-800ms | (Moonshine): 200-400ms
 * - Intent recognition: 50-100ms (pattern matching)
 * - LLM code generation: 400-800ms
 * - Total: 650-1700ms depending on STT mode
 *
 * @param context Android context for preferences
 * @param llamaController LLM for code generation
 * @param intentRecognizer Pattern matcher for intent detection
 */
class SpeechToCodePipeline(
    private val context: Context,
    private val llamaController: ILlamaController,
    private val intentRecognizer: VoiceCommandRecognizer
) {

    data class GenerationResult(
        val command: String,
        val code: String,
        val confidence: Float,
        val totalLatencyMs: Long
    )

    companion object {
        private const val TRANSCRIPTION_TIMEOUT_CLOUD_MS = 5000L // Cloud STT needs more time
        private const val TRANSCRIPTION_TIMEOUT_MOONSHINE_MS = 1000L
        private const val INTENT_TIMEOUT_MS = 200L
        private const val GENERATION_TIMEOUT_MS = 2000L
    }

    // Lazy initialization of STT engines
    private val cloudStt by lazy { AndroidSpeechRecognizer(context) }
    private val moonshineStt by lazy { MoonshineSTT(context) }

    /**
     * Initialize the selected STT engine.
     */
    suspend fun initialize(): Boolean {
        return when {
            VoicePreferences.isUsingCloudStt(context) -> {
                val language = VoicePreferences.getVoiceLanguage(context)
                cloudStt.initialize(language)
                true
            }
            VoicePreferences.isUsingMoonshineStt(context) -> {
                moonshineStt.initialize()
            }
            else -> {
                Log.e(TAG, "Unknown STT mode")
                false
            }
        }
    }

    /**
     * Process audio and generate code.
     *
     * @param audioBytes Raw PCM audio (16-bit, 16kHz mono)
     * @return GenerationResult with command, code, confidence, and timing
     */
    suspend fun processAudio(audioBytes: ByteArray): GenerationResult {
        val pipelineStart = System.currentTimeMillis()

        return try {
            // Step 1: Speech-to-Text
            Log.d(TAG, "Step 1/3: STT transcription...")
            val sttStart = System.currentTimeMillis()

            val transcription = when {
                VoicePreferences.isUsingCloudStt(context) -> {
                    Log.d(TAG, "Using Cloud STT (Android SpeechRecognizer)")
                    withTimeout(TRANSCRIPTION_TIMEOUT_CLOUD_MS) {
                        cloudStt.transcribe(audioBytes, sampleRate = 16000)
                    }
                }
                VoicePreferences.isUsingMoonshineStt(context) -> {
                    Log.d(TAG, "Using Moonshine offline STT")
                    withTimeout(TRANSCRIPTION_TIMEOUT_MOONSHINE_MS) {
                        moonshineStt.transcribe(audioBytes, sampleRate = 16000)
                    }
                }
                else -> {
                    Log.e(TAG, "Unknown STT mode")
                    AndroidSpeechRecognizer.TranscriptionResult("", 0f, 0L)
                }
            }

            val sttDurationMs = System.currentTimeMillis() - sttStart
            Log.d(TAG, "STT: '${transcription.text}' in ${sttDurationMs}ms")

            if (transcription.text.isEmpty()) {
                return GenerationResult(
                    command = "",
                    code = "// Failed to transcribe audio",
                    confidence = 0f,
                    totalLatencyMs = System.currentTimeMillis() - pipelineStart
                )
            }

            // Step 2: Intent Recognition (50-100ms)
            Log.d(TAG, "Step 2/3: Intent recognition...")
            val intentStart = System.currentTimeMillis()
            val intent = withTimeout(INTENT_TIMEOUT_MS) {
                intentRecognizer.recognize(transcription.text)
            }
            val intentDurationMs = System.currentTimeMillis() - intentStart
            Log.d(TAG, "Intent: ${intent?.name ?: "UNKNOWN"} in ${intentDurationMs}ms")

            // Step 3: Code Generation or Command Execution (400-800ms)
            Log.d(TAG, "Step 3/3: Code generation...")
            val codeStart = System.currentTimeMillis()
            val (command, code) = if (intent != null && intent.hasQuickAction) {
                // Use hardcoded command (faster)
                val cmd = intent.quickActionCommand
                Pair(cmd, cmd)
            } else {
                // Use LLM for generation
                withTimeout(GENERATION_TIMEOUT_MS) {
                    val prompt = buildPrompt(transcription.text, intent)
                    val generatedCode = llamaController.send(
                        message = prompt,
                        formatChat = false,
                        stop = listOf("\n\n", "fun ", "class "),
                        clearCache = true
                    ).collectToString()

                    Pair(intent?.name ?: "custom", generatedCode)
                }
            }
            val codeDurationMs = System.currentTimeMillis() - codeStart
            Log.d(TAG, "Code generation: ${code.take(50)}... in ${codeDurationMs}ms")

            val totalLatencyMs = System.currentTimeMillis() - pipelineStart
            Log.d(TAG, "Pipeline complete: ${totalLatencyMs}ms total")

            GenerationResult(
                command = command,
                code = code,
                confidence = transcription.confidence,
                totalLatencyMs = totalLatencyMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            GenerationResult(
                command = "error",
                code = "// Error: ${e.message}",
                confidence = 0f,
                totalLatencyMs = System.currentTimeMillis() - pipelineStart
            )
        }
    }

    /**
     * Build LLM prompt from transcribed text and intent.
     */
    private fun buildPrompt(transcribedText: String, intent: VoiceIntent?): String {
        return """
            |Generate Kotlin code for the following voice command:
            |Command: "$transcribedText"
            |Intent: ${intent?.name ?: "unknown"}
            |
            |Generate only the code, no explanation.
            |""".trimMargin()
    }

    /**
     * Release resources.
     */
    fun cleanup() {
        if (VoicePreferences.isUsingMoonshineStt(context)) {
            moonshineStt.cleanup()
        }
        if (VoicePreferences.isUsingCloudStt(context)) {
            cloudStt.release()
        }
    }
}

/**
 * Lightweight async wrapper for collecting flow results.
 */
private suspend fun kotlinx.coroutines.flow.Flow<String>.collectToString(): String {
    val builder = StringBuilder()
    this.collect { value ->
        builder.append(value)
    }
    return builder.toString()
}
