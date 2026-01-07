package org.appdevforall.codeonthego.computervision.utils

import android.os.Bundle
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase

object CvAnalyticsUtil {
    private val analytics by lazy { Firebase.analytics }

    fun trackScreenOpened() {
        analytics.logEvent("cv_screen_opened", Bundle().apply {
            putLong("timestamp", System.currentTimeMillis())
        })
    }
    fun trackImageSelected(fromCamera: Boolean) {
        analytics.logEvent("cv_image_selected", Bundle().apply {
            putString("source", if (fromCamera) "camera" else "gallery")
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackDetectionStarted() {
        analytics.logEvent("cv_detection_started", Bundle().apply {
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackDetectionCompleted(success: Boolean, detectionCount: Int, durationMs: Long) {
        analytics.logEvent("cv_detection_completed", Bundle().apply {
            putBoolean("success", success)
            putInt("detection_count", detectionCount)
            putLong("duration_ms", durationMs)
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackXmlGenerated(componentCount: Int) {
        analytics.logEvent("cv_xml_generated", Bundle().apply {
            putInt("component_count", componentCount)
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackXmlExported(toDownloads: Boolean) {
        analytics.logEvent("cv_xml_exported", Bundle().apply {
            putString("export_method", if (toDownloads) "save_downloads" else "update_layout")
            putLong("timestamp", System.currentTimeMillis())
        })
    }

}