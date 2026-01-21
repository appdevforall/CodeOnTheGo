package org.appdevforall.codeonthego.computervision.utils

import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import org.appdevforall.codeonthego.computervision.utils.CvAnalyticsUtil.EventNames.CV_DETECTION_COMPLETED
import org.appdevforall.codeonthego.computervision.utils.CvAnalyticsUtil.EventNames.CV_DETECTION_STARTED
import org.appdevforall.codeonthego.computervision.utils.CvAnalyticsUtil.EventNames.CV_IMAGE_SELECTED
import org.appdevforall.codeonthego.computervision.utils.CvAnalyticsUtil.EventNames.CV_SCREEN_OPENED
import org.appdevforall.codeonthego.computervision.utils.CvAnalyticsUtil.EventNames.CV_XML_EXPORTED
import org.appdevforall.codeonthego.computervision.utils.CvAnalyticsUtil.EventNames.CV_XML_GENERATED

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
        logEvent(CV_SCREEN_OPENED, Bundle().apply {
            putLong("timestamp", System.currentTimeMillis())
        })
    }
    fun trackImageSelected(fromCamera: Boolean) {
        logEvent(CV_IMAGE_SELECTED, Bundle().apply {
            putString("source", if (fromCamera) "camera" else "gallery")
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackDetectionStarted() {
        logEvent(CV_DETECTION_STARTED, Bundle().apply {
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackDetectionCompleted(success: Boolean, detectionCount: Int, durationMs: Long) {
        logEvent(CV_DETECTION_COMPLETED, Bundle().apply {
            putBoolean("success", success)
            putInt("detection_count", detectionCount)
            putLong("duration_ms", durationMs)
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackXmlGenerated(componentCount: Int) {
        logEvent(CV_XML_GENERATED, Bundle().apply {
            putInt("component_count", componentCount)
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    fun trackXmlExported(toDownloads: Boolean) {
        logEvent(CV_XML_EXPORTED, Bundle().apply {
            putString("export_method", if (toDownloads) "save_downloads" else "update_layout")
            putLong("timestamp", System.currentTimeMillis())
        })
    }

    private object EventNames {
        const val CV_SCREEN_OPENED = "cv_screen_opened"
        const val CV_IMAGE_SELECTED = "cv_image_selected"
        const val CV_DETECTION_STARTED = "cv_detection_started"
        const val CV_DETECTION_COMPLETED = "cv_detection_completed"
        const val CV_XML_GENERATED = "cv_xml_generated"
        const val CV_XML_EXPORTED = "cv_xml_exported"
    }
}