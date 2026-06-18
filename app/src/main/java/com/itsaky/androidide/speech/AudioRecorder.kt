package com.itsaky.androidide.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val TAG = "AudioRecorder"

/**
 * Audio recorder for capturing voice for speech-to-text.
 *
 * Captures 16-bit PCM audio at 16kHz mono, which is the standard for
 * modern speech recognition models including Moonshine.
 *
 * @param context Android context for permissions
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000 // 16kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2 // 16-bit = 2 bytes
        private const val BUFFER_SIZE_MULTIPLIER = 2
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val audioBuffer = ByteArrayOutputStream()

    /**
     * Initialize audio recorder and check permissions.
     */
    fun initialize(): Boolean {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            Log.d(TAG, "Min buffer size: $bufferSize bytes")

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * BUFFER_SIZE_MULTIPLIER
            )

            Log.d(TAG, "AudioRecord initialized (state: ${audioRecord?.state})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord", e)
            false
        }
    }

    /**
     * Start recording audio.
     * Launches a background coroutine to continuously read audio data.
     */
    suspend fun startRecording(): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                require(audioRecord != null) { "AudioRecord not initialized" }

                // Clear previous audio data
                audioBuffer.reset()

                // Start AudioRecord
                audioRecord?.startRecording()
                isRecording = true
                Log.d(TAG, "Recording started")

                // Start reading audio data in background
                recordingJob = launch {
                    val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                    val buffer = ByteArray(bufferSize)

                    Log.d(TAG, "Started audio capture loop (buffer size: $bufferSize)")

                    while (isActive && isRecording) {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                        if (bytesRead > 0) {
                            synchronized(audioBuffer) {
                                audioBuffer.write(buffer, 0, bytesRead)
                            }
                        } else if (bytesRead < 0) {
                            Log.w(TAG, "Error reading audio: $bytesRead")
                        }
                    }

                    Log.d(TAG, "Audio capture loop ended")
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                isRecording = false
                false
            }
        }
    }

    /**
     * Stop recording and return captured audio.
     *
     * @return PCM audio bytes (16-bit, 16kHz mono) or empty array on error
     */
    suspend fun stopRecording(): ByteArray {
        return withContext(Dispatchers.Default) {
            try {
                require(audioRecord != null) { "AudioRecord not initialized" }
                require(isRecording) { "Not recording" }

                // Stop recording flag (stops the capture loop)
                isRecording = false

                // Wait for recording job to finish
                recordingJob?.join()
                recordingJob = null

                // Stop AudioRecord
                audioRecord?.stop()

                // Get captured audio
                val capturedAudio = synchronized(audioBuffer) {
                    audioBuffer.toByteArray()
                }

                Log.d(TAG, "Recording stopped, captured ${capturedAudio.size} bytes")

                capturedAudio
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop recording", e)
                byteArrayOf()
            }
        }
    }

    /**
     * Record audio for a specified duration.
     *
     * @param durationMs Duration to record in milliseconds
     * @return PCM audio bytes
     */
    suspend fun recordAudio(durationMs: Int): ByteArray {
        return withContext(Dispatchers.Default) {
            try {
                require(audioRecord != null) { "AudioRecord not initialized" }

                startRecording()

                // Calculate expected number of samples
                val numSamples = (SAMPLE_RATE * durationMs / 1000)
                val buffer = ByteArray(numSamples * BYTES_PER_SAMPLE)

                Log.d(TAG, "Recording ${durationMs}ms (~$numSamples samples)")

                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                Log.d(TAG, "Read $bytesRead bytes")

                stopRecording()

                if (bytesRead > 0) {
                    buffer.copyOf(bytesRead)
                } else {
                    byteArrayOf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
                byteArrayOf()
            }
        }
    }

    /**
     * Release resources.
     */
    fun release() {
        try {
            // Stop recording if active
            if (isRecording) {
                isRecording = false
                recordingJob?.cancel()
                recordingJob = null
                audioRecord?.stop()
            }

            // Release AudioRecord
            audioRecord?.release()
            audioRecord = null

            // Clear buffer
            audioBuffer.reset()

            Log.d(TAG, "AudioRecord released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
    }
}
