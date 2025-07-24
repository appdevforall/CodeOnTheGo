package com.itsaky.androidide.helper

import androidx.test.uiautomator.UiDevice
import com.itsaky.androidide.BuildConfig

const val SERVICE = "${BuildConfig.APPLICATION_ID}/com.itsaky.androidide.services.debug.ForegroundDetectionService"


fun grantOverlayPermission(device: UiDevice) {

    device.executeShellCommand(
        "appops set ${BuildConfig.APPLICATION_ID} SYSTEM_ALERT_WINDOW allow"
    )

    val result: String? = device.executeShellCommand(
        "appops get ${BuildConfig.APPLICATION_ID} SYSTEM_ALERT_WINDOW"
    )
    println("Overlay AppOp status: $result")
}

fun grantAccessibilityPermission(device: UiDevice) {
    // Grant the ability to write secure settings (requires debuggable build)
    device.executeShellCommand(
        "appops set ${BuildConfig.APPLICATION_ID} WRITE_SECURE_SETTINGS allow")
    // Enable the service
    device.executeShellCommand(
        "settings put secure enabled_accessibility_services $SERVICE"
    )
    device.executeShellCommand(
        "settings put secure accessibility_enabled 1")
}

fun grantStoragePermissions(device: UiDevice) {
    device.executeShellCommand(
        "pm grant ${BuildConfig.APPLICATION_ID} android.permission.READ_EXTERNAL_STORAGE");
    device.executeShellCommand(
        "pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_EXTERNAL_STORAGE");
    // for Android 11+ all‑files
    device.executeShellCommand(
        "appops set ${BuildConfig.APPLICATION_ID} MANAGE_EXTERNAL_STORAGE allow");
}