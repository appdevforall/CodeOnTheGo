package org.appdevforall.codeonthego.computervision.utils

import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase

object CvAnalyticsUtil {
    private const val TAG = "CvAnalyticsUtil"
    private val analytics by lazy {
        try {
            Firebase.analytics
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Analytics not available", e)
            null
        }
    }

    private fun logEvent(eventName: String, params: Bundle) {
        try {
            analytics?.logEvent(eventName, params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log event: $eventName", e)
        }
    }

    fun trackScreenOpened() {
        logEvent("cv_screen_opened", Bundle().apply {
            putLong("timestamp", System.currentTimeMillis())
        })
    }
    fun trackImageSelected(fromCamera: Boolean) {
        logEvent("cv_image_selected", Bundle().apply {
            putString("source", if (fromCamera) "camera" else "gallery")
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackDetectionStarted() {
        logEvent("cv_detection_started", Bundle().apply {
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackDetectionCompleted(success: Boolean, detectionCount: Int, durationMs: Long) {
        logEvent("cv_detection_completed", Bundle().apply {
            putBoolean("success", success)
            putInt("detection_count", detectionCount)
            putLong("duration_ms", durationMs)
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackXmlGenerated(componentCount: Int) {
        logEvent("cv_xml_generated", Bundle().apply {
            putInt("component_count", componentCount)
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackXmlExported(toDownloads: Boolean) {
        logEvent("cv_xml_exported", Bundle().apply {
            putString("export_method", if (toDownloads) "save_downloads" else "update_layout")
            putLong("timestamp", System.currentTimeMillis())
        })
    }

}