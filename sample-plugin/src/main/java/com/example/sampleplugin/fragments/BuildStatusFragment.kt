package com.example.sampleplugin.fragments

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.IdeBuildService
import java.text.SimpleDateFormat
import java.util.*

class BuildStatusFragment : Fragment(), BuildStatusListener {
    
    private lateinit var buildStatusText: TextView
    private lateinit var toolingServerStatusText: TextView
    private lateinit var buildLogContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private var buildService: IdeBuildService? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }
        
        val titleView = TextView(context).apply {
            text = "🔨 Build Status Monitor"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 24
            }
        }
        
        val statusContainer = createStatusContainer(context)
        val descriptionView = createDescriptionView(context)
        val logHeaderView = createLogHeaderView(context)
        
        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            background = GradientDrawable().apply {
                cornerRadius = 8f
                setColor(Color.parseColor("#FAFAFA"))
                setStroke(1, Color.parseColor("#E0E0E0"))
            }
        }
        
        buildLogContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(12, 12, 12, 12)
        }
        
        scrollView.addView(buildLogContainer)
        
        rootLayout.addView(titleView)
        rootLayout.addView(statusContainer)
        rootLayout.addView(descriptionView)
        rootLayout.addView(logHeaderView)
        rootLayout.addView(scrollView)
        
        return rootLayout
    }
    
    private fun createStatusContainer(context: android.content.Context): LinearLayout {
        val statusContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.parseColor("#F5F5F5"))
            }
        }
        
        buildStatusText = TextView(context).apply {
            text = "Build Status: Checking..."
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
        }
        
        toolingServerStatusText = TextView(context).apply {
            text = "Tooling Server: Checking..."
            textSize = 14f
            setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        statusContainer.addView(buildStatusText)
        statusContainer.addView(toolingServerStatusText)
        
        return statusContainer
    }
    
    private fun createDescriptionView(context: android.content.Context): TextView {
        return TextView(context).apply {
            text = "This tab demonstrates the IdeBuildService API. It shows:\n\n• Current build status (running/idle)\n• Tooling server availability\n• Real-time build notifications\n• Build event history"
            textSize = 14f
            setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }
    }
    
    private fun createLogHeaderView(context: android.content.Context): TextView {
        return TextView(context).apply {
            text = "Build Events:"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
        }
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeBuildService()
        addLogEntry("Build Status Monitor initialized", Color.parseColor("#2196F3"))
        startStatusUpdates()
    }
    
    private fun initializeBuildService() {
        try {

            addLogEntry("Attempting to access IdeBuildService...", Color.GRAY)
            addLogEntry("Note: This demo shows the UI. Connect to plugin context for real monitoring.", Color.parseColor("#FF9800"))
        } catch (e: Exception) {
            addLogEntry("Error initializing build service: ${e.message}", Color.parseColor("#F44336"))
        }
    }
    
    private fun startStatusUpdates() {
        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                updateBuildStatus()
                Handler(Looper.getMainLooper()).postDelayed(this, 2000)
            }
        })
    }
    
    private fun updateBuildStatus() {
        val buildInProgress = buildService?.isBuildInProgress() ?: false
        val toolingServerStarted = buildService?.isToolingServerStarted() ?: false
        
        buildStatusText.text = if (buildInProgress) {
            "Build Status: 🔄 Building..."
        } else {
            "Build Status: ✅ Idle"
        }
        
        buildStatusText.setTextColor(
            if (buildInProgress) Color.parseColor("#FF9800") else Color.parseColor("#4CAF50")
        )
        
        toolingServerStatusText.text = if (toolingServerStarted) {
            "Tooling Server: ✅ Available"
        } else {
            "Tooling Server: ❌ Not Available"
        }
        
        toolingServerStatusText.setTextColor(
            if (toolingServerStarted) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        )
    }
    
    private fun addLogEntry(message: String, color: Int = Color.BLACK) {
        val context = requireContext()
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        
        val logEntry = TextView(context).apply {
            text = "[$timestamp] $message"
            textSize = 12f
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4
            }
        }
        
        buildLogContainer.addView(logEntry)
        
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
        
        if (buildLogContainer.childCount > 50) {
            buildLogContainer.removeViewAt(0)
        }
    }
    
    override fun onBuildStarted() {
        activity?.runOnUiThread {
            addLogEntry("Build started! 🚀", Color.parseColor("#2196F3"))
            updateBuildStatus()
        }
    }
    
    override fun onBuildFinished() {
        activity?.runOnUiThread {
            addLogEntry("Build finished successfully! ✅", Color.parseColor("#4CAF50"))
            updateBuildStatus()
        }
    }
    
    override fun onBuildFailed(error: String?) {
        activity?.runOnUiThread {
            val message = if (error != null) {
                "Build failed: $error ❌"
            } else {
                "Build was cancelled ⚠️"
            }
            addLogEntry(message, Color.parseColor("#F44336"))
            updateBuildStatus()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        buildService?.removeBuildStatusListener(this)
    }
}