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
        val transcription: String,
        val command: String,
        val code: String,
        val confidence: Float,
        val totalLatencyMs: Long
    )

    companion object {
        private const val TRANSCRIPTION_TIMEOUT_CLOUD_MS = 5000L // Cloud STT needs more time
        private const val TRANSCRIPTION_TIMEOUT_MOONSHINE_MS = 1000L
        private const val INTENT_TIMEOUT_MS = 200L
        // On-device generation includes prompt prefill (~1s+) plus up to n_len tokens
        // at ~30ms each, so it can take ~10s. 2s killed generation almost immediately.
        private const val GENERATION_TIMEOUT_MS = 30000L
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

            val transcription: AndroidSpeechRecognizer.TranscriptionResult = when {
                VoicePreferences.isUsingCloudStt(context) -> {
                    Log.d(TAG, "Using Cloud STT (Android SpeechRecognizer)")
                    withTimeout(TRANSCRIPTION_TIMEOUT_CLOUD_MS) {
                        cloudStt.transcribe(audioBytes, sampleRate = 16000)
                    }
                }
                VoicePreferences.isUsingMoonshineStt(context) -> {
                    Log.d(TAG, "Using Moonshine offline STT")
                    withTimeout(TRANSCRIPTION_TIMEOUT_MOONSHINE_MS) {
                        val result = moonshineStt.transcribe(audioBytes, sampleRate = 16000)
                        // Convert MoonshineSTT.TranscriptionResult to AndroidSpeechRecognizer.TranscriptionResult
                        AndroidSpeechRecognizer.TranscriptionResult(
                            text = result.text,
                            confidence = result.confidence,
                            latencyMs = result.latencyMs
                        )
                    }
                }
                else -> {
                    Log.e(TAG, "Unknown STT mode")
                    AndroidSpeechRecognizer.TranscriptionResult("", 0f, 0L)
                }
            }

            val sttDurationMs = System.currentTimeMillis() - sttStart
            Log.d(TAG, "STT: '${transcription.text}' in ${sttDurationMs}ms")

            generateCodeFromTranscription(transcription, pipelineStart)
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            GenerationResult(
                transcription = "",
                command = "error",
                code = "// Error: ${e.message}",
                confidence = 0f,
                totalLatencyMs = System.currentTimeMillis() - pipelineStart
            )
        }
    }

    /**
     * Start live Android SpeechRecognizer capture for cloud mode.
     */
    fun startCloudTranscription(
        onResult: (AndroidSpeechRecognizer.TranscriptionResult) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (!VoicePreferences.isUsingCloudStt(context)) {
            onError("Cloud STT is not selected")
            return false
        }

        return cloudStt.startListening(onResult, onError)
    }

    /**
     * Request final cloud recognition results for the active live capture.
     */
    fun stopCloudTranscription() {
        cloudStt.stopListening()
    }

    /**
     * Generate code from a transcript that has already been produced by live STT.
     */
    suspend fun generateCodeFromTranscription(
        transcription: AndroidSpeechRecognizer.TranscriptionResult
    ): GenerationResult {
        return try {
            val pipelineStart = System.currentTimeMillis() - transcription.latencyMs
            generateCodeFromTranscription(transcription, pipelineStart)
        } catch (e: Exception) {
            Log.e(TAG, "Generation error", e)
            GenerationResult(
                transcription = transcription.text,
                command = "error",
                code = "// Error: ${e.message}",
                confidence = 0f,
                totalLatencyMs = transcription.latencyMs
            )
        }
    }

    private suspend fun generateCodeFromTranscription(
        transcription: AndroidSpeechRecognizer.TranscriptionResult,
        pipelineStart: Long
    ): GenerationResult {
        if (transcription.text.isEmpty()) {
            return GenerationResult(
                transcription = "",
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
                val rawOutput = llamaController.send(
                    message = prompt,
                    formatChat = false,
                    // Prompt is primed with an opening ```kotlin fence, so stop at the
                    // closing fence. Also stop if the model starts echoing the prompt.
                    // (The previous "fun "/"class " stops truncated valid Kotlin instantly.)
                    stop = listOf("```", "\nRequest:", "\nCommand:", "\nIntent:"),
                    clearCache = true
                ).collectToString()

                Pair(intent?.name ?: "custom", cleanGeneratedCode(rawOutput))
            }
        }
        val codeDurationMs = System.currentTimeMillis() - codeStart
        Log.d(TAG, "Code generation: ${code.take(50)}... in ${codeDurationMs}ms")

        val totalLatencyMs = System.currentTimeMillis() - pipelineStart
        Log.d(TAG, "Pipeline complete: ${totalLatencyMs}ms total")

        return GenerationResult(
            transcription = transcription.text,
            command = command,
            code = code,
            confidence = transcription.confidence,
            totalLatencyMs = totalLatencyMs
        )
    }

    /**
     * Build LLM prompt from transcribed text.
     *
     * The prompt ends with an opening ```kotlin fence so the model continues by writing
     * code (in raw-completion mode an instruction alone makes the model reply with prose).
     * Generation is stopped at the closing fence; see the `stop` list at the call site.
     */
    private fun buildPrompt(transcribedText: String, intent: VoiceIntent?): String {
        return """
            |You are a Kotlin coding assistant. Write Kotlin code that fulfills the request.
            |Output only valid Kotlin code — no explanations, comments, or prose.
            |
            |Request: "$transcribedText"
            |
            |```kotlin
            |""".trimMargin()
    }

    /**
     * Clean up raw model output: keep only the code, dropping any markdown fences and any
     * echoed-prompt / prose the model appended after the code.
     */
    private fun cleanGeneratedCode(raw: String): String {
        var text = raw.trim()

        // If the model emitted its own fenced block, keep only the first block's contents.
        val fenceStart = text.indexOf("```")
        if (fenceStart >= 0) {
            val afterOpen = text.indexOf('\n', fenceStart)
            if (afterOpen >= 0) {
                val body = text.substring(afterOpen + 1)
                val closeIdx = body.indexOf("```")
                text = if (closeIdx >= 0) body.substring(0, closeIdx) else body
            }
        }

        // Truncate at the first sign the model started echoing the prompt or adding prose.
        val cutMarkers = listOf(
            "Request:", "Command:", "Intent:", "Explanation:",
            "Commands are always", "What I have so far", "Resulting text file"
        )
        var cutIndex = text.length
        for (marker in cutMarkers) {
            val idx = text.indexOf(marker)
            if (idx in 0 until cutIndex) {
                cutIndex = idx
            }
        }
        text = text.substring(0, cutIndex)

        return text.trim()
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
