package com.itsaky.androidide.utils

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.itsaky.androidide.agent.repository.LlmInferenceEngineProvider
import com.itsaky.androidide.agent.repository.LocalLlmRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Helper class to run Local LLM Benchmarks independently from the main app logic.
 */
class LocalBenchmarkRunner(private val activity: Activity) {

    private val log = LoggerFactory.getLogger(LocalBenchmarkRunner::class.java)

    fun attach() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Overlay container to ensure positioning works regardless of root layout type
        val overlay = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
        }

        val fab = FloatingActionButton(activity).apply {
            setImageResource(android.R.drawable.ic_media_play)
            backgroundTintList = ColorStateList.valueOf("#FF6200".toColorInt()) // Orange
            imageTintList = ColorStateList.valueOf(Color.WHITE)

            setOnClickListener {
                Toast.makeText(context, "🚀 Starting Local Benchmark...", Toast.LENGTH_SHORT).show()
                runListFilesBenchmark()
            }
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, 64, 64)
        }

        overlay.addView(fab, params)
        root.addView(overlay)
    }

    private fun runListFilesBenchmark() {
        // We use the activity's lifecycleScope to ensure coroutines die if the app closes
        (activity as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
            try {
                log.info("BenchmarkMode: Initializing Local Engine...")

                val modelFile = findAnyModelFile()

                if (modelFile == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "❌ File '.gguf' was not found", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                log.info("Benchmark: Found model file: ${modelFile.name}")

                val engine = LlmInferenceEngineProvider.instance
                val initSuccess = engine.initialize(activity.applicationContext)

                if (!initSuccess) {
                    log.error("BenchmarkMode: Failed to initialize Native Engine")
                    withContext(Dispatchers.Main) {
                         Toast.makeText(activity, "❌ Engine Init Failed", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val localRepo = LocalLlmRepositoryImpl(activity.applicationContext, engine)

                log.info("BenchmarkMode: Loading model from ${modelFile.absolutePath}")
                val loadSuccess = localRepo.loadModel(Uri.fromFile(modelFile).toString())

                if (!loadSuccess) {
                    log.error("BenchmarkMode: Failed to load model")
                     withContext(Dispatchers.Main) {
                         Toast.makeText(activity, "❌ Model Load Failed", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val userPrompt = "List files in the project root."
                log.info("BenchmarkMode: Running inference with prompt: '$userPrompt'")

                withContext(Dispatchers.Main) {
                     Toast.makeText(activity, "🧠 Thinking...", Toast.LENGTH_SHORT).show()
                }

                localRepo.generateASimpleResponse(
                    prompt = userPrompt,
                    history = emptyList()
                )

                withContext(Dispatchers.Main) {
                     Toast.makeText(activity, "✅ Done! Check Logcat.", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                log.error("BenchmarkMode: Critical error during execution", e)
                withContext(Dispatchers.Main) {
                     Toast.makeText(activity, "🔥 Crash: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun findAnyModelFile(): File? {
        // Priority 1: Samsung RTL Temp folder
        val tmpDir = File("/data/local/tmp/")
        val tmpFile = tmpDir.listFiles()?.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) }
        if (tmpFile != null) return tmpFile

        // Priority 2: Standard Downloads
        val downloadDir = File("/sdcard/Download/")
        val downloadFile = downloadDir.listFiles()?.firstOrNull { it.name.endsWith(".gguf", ignoreCase = true) }
        if (downloadFile != null) return downloadFile

        // Priority 3: Hardcoded fallback (in case listFiles fails due to permissions)
        val fallbackNames = listOf(
            "h2o-danube3-500m-chat.Q8_0.gguf",
            "qwen2.5-0.5b-instruct-q8_0.gguf",
            "qwen2.5-coder-1.5b-q4_k_m.gguf"
        )

        for (name in fallbackNames) {
            val file = File("/data/local/tmp/$name")
            if (file.exists()) return file
        }

        return null
    }
}
