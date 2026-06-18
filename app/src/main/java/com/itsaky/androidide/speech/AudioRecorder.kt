package com.itsaky.androidide.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

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
                bufferSize * 2 // Double for safety
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
     */
    fun startRecording(): Boolean {
        return try {
            require(audioRecord != null) { "AudioRecord not initialized" }
            audioRecord?.startRecording()
            isRecording = true
            Log.d(TAG, "Recording started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            false
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

                audioRecord?.stop()
                isRecording = false

                Log.d(TAG, "Recording stopped")
                byteArrayOf() // TODO: Return captured audio
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
            if (isRecording) {
                audioRecord?.stop()
                isRecording = false
            }
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "AudioRecord released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
    }
}
