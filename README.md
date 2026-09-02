 // MainActivity.kt

package com.friday.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestMicrophonePermission()
    }

    private fun requestMicrophonePermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )

        } else {
            startFriday()
        }
    }

    private fun startFriday() {

        val intent = Intent(this, FridayService::class.java)

        ContextCompat.startForegroundService(
            this,
            intent
        )
    }
}// FridayVoice.kt

package com.friday.ai

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class FridayVoice(
    private val context: Context
) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    fun startListening() {

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            speak("Speech recognition is not available")
            return
        }

        recognizer?.destroy()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        recognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(params: android.os.Bundle?) {}

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    // Listen again
                    startListening()
                }

                override fun onResults(
                    results: android.os.Bundle?
                ) {

                    val commands =
                        results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                    val command =
                        commands?.firstOrNull() ?: return

                    handleCommand(command)
                }

                override fun onPartialResults(
                    partialResults: android.os.Bundle?
                ) {}

                override fun onEvent(
                    eventType: Int,
                    params: android.os.Bundle?
                ) {}
            }
        )

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            Locale.getDefault()
        )

        intent.putExtra(
            RecognizerIntent.EXTRA_PARTIAL_RESULTS,
            true
        )

        recognizer?.startListening(intent)
    }

    private fun handleCommand(command: String) {

        val text = command.lowercase(Locale.getDefault())

        when {

            text.contains("hello") -> {
                speak("Hello. FRIDAY is online.")
            }

            text.contains("who are you") -> {
                speak("I am your personal AI assistant.")
            }

            text.contains("open youtube") -> {

                val intent = context.packageManager
                    .getLaunchIntentForPackage(
                        "com.google.android.youtube"
                    )

                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    speak("Opening YouTube.")
                } else {
                    speak("YouTube is not installed.")
                }
            }

            text.contains("open chrome") -> {

                val intent = context.packageManager
                    .getLaunchIntentForPackage(
                        "com.android.chrome"
                    )

                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    speak("Opening Chrome.")
                } else {
                    speak("Chrome is not installed.")
                }
            }

            else -> {
                speak("I heard you say $command")
            }
        }
    }

    private fun speak(text: String) {

        if (tts == null) {

            tts = TextToSpeech(
                context
            ) { status ->

                if (status == TextToSpeech.SUCCESS) {

                    tts?.language =
                        Locale.US

                    tts?.setSpeechRate(1.0f)

                    tts?.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "FRIDAY_RESPONSE"
                    )
                }
            }

        } else {

            tts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "FRIDAY_RESPONSE"
            )
        }
    }

    fun destroy() {

        recognizer?.destroy()
        recognizer = null

        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}// FridayService.kt
// Add inside startWakeWordDetection()

private fun startVoiceCommand() {

    val fridayVoice = FridayVoice(this)

    fridayVoice.startListening()
}// Temporary activation
// Call this when your wake-word detector detects:
//
// "Hey Friday"

private fun onWakeWordDetected() {

    startVoiceCommand()
}<!-- AndroidManifest.xml -->

<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />// Next part:
//
// "HEY FRIDAY" wake-word detection
// + screen OFF support
// + AI API
// + natural FRIDAY voice
// + commands such as:
// "Open YouTube"
// "Call Dad"
// "Turn on Bluetooth"
// "What's the time?"
// "Search Google"
//
// These need to be connected to the service above.
