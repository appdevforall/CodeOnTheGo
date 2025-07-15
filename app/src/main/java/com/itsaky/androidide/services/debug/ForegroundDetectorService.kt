package com.itsaky.androidide.services.debug

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.itsaky.androidide.buildinfo.BuildInfo
import org.slf4j.LoggerFactory

class ForegroundDetectionService : AccessibilityService() {

    /**
     * The package name of the application that is currently in foreground.
     */
    private var currentForegroundApp = ""

    companion object {
        const val ACTION_FOREGROUND_APP_CHANGED = "${BuildInfo.PACKAGE_NAME}.foreground_app"
        const val EXTRA_PACKAGE_NAME = "$ACTION_FOREGROUND_APP_CHANGED.package_name"
        const val EXTRA_CLASS_NAME = "$ACTION_FOREGROUND_APP_CHANGED.class_name"

        const val PERMISSION_RECEIVE_FOREGROUND_WINDOW_UPDATES = "org.adfa.cogo.permission.RECEIVE_FOREGROUND_WINDOW_UPDATES"

        private val logger = LoggerFactory.getLogger(ForegroundDetectionService::class.java)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = if (event.packageName != null) event.packageName.toString() else ""
            val className = if (event.className != null) event.className.toString() else ""
            if (packageName != currentForegroundApp) {
                currentForegroundApp = packageName
                notifyListeners(packageName, className)
            }
        }
    }

    private fun notifyListeners(
        packageName: String,
        className: String
    ) {
        sendOrderedBroadcast(Intent().apply {
            action = ACTION_FOREGROUND_APP_CHANGED
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_CLASS_NAME, className)
        }, PERMISSION_RECEIVE_FOREGROUND_WINDOW_UPDATES)
    }

    override fun onInterrupt() {
        // ignored
    }
}