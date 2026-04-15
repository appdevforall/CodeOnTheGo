package com.itsaky.androidide.helper

import android.content.Context
import androidx.test.uiautomator.UiSelector
import com.kaspersky.kaspresso.device.Device

/**
 * UiAutomator flows for system Settings / permission dialogs after tapping "Grant" on the onboarding
 * permission list. Used by [grantAllRequiredPermissionsThroughOnboardingUi].
 */
fun Device.grantPostNotificationsUi() {
    val d = uiDevice
    d.waitForIdle(1500)
    val labels = listOf("Allow", "While using the app", "Only this time", "Allow notifications")
    for (label in labels) {
        val o = d.findObject(UiSelector().text(label))
        if (o.waitForExists(4000) && o.exists() && o.isEnabled) {
            o.click()
            d.waitForIdle(1500)
            return
        }
    }
}

fun Device.grantStorageManageAllFilesUi() {
    val d = uiDevice
    d.waitForIdle(2000)
    val texts =
        listOf(
            "Allow access to manage all files",
            "Files and media",
            "Access all files",
            "Allow",
            "Allow file management",
            "Allow permission",
            "Use USB storage",
            "Storage",
            "Files",
        )
    for (t in texts) {
        val o = d.findObject(UiSelector().text(t))
        if (o.waitForExists(3500) && o.exists() && o.isEnabled) {
            o.click()
            d.waitForIdle(2000)
            d.pressBack()
            d.waitForIdle(1500)
            return
        }
    }
}

fun Device.grantInstallUnknownAppsUi() {
    val d = uiDevice
    d.waitForIdle(2000)
    val texts =
        listOf(
            "Allow from this source",
            "Allow install of apps",
            "Allow",
        )
    for (t in texts) {
        val o = d.findObject(UiSelector().text(t))
        if (o.waitForExists(3500) && o.exists() && o.isEnabled) {
            o.click()
            d.waitForIdle(2000)
            d.pressBack()
            d.waitForIdle(1500)
            return
        }
    }
}

fun Device.grantDisplayOverOtherAppsUi(candidates: List<String>, context: Context) {
    val d = uiDevice
    d.waitForIdle(2000)
    for (label in candidates) {
        if (label.isBlank()) continue
        val row = d.findObject(UiSelector().text(label))
        if (row.waitForExists(6000) && row.exists()) {
            row.click()
            d.waitForIdle(2000)
            val switchNode = d.findObject(UiSelector().className("android.widget.Switch"))
            if (switchNode.waitForExists(4000) && switchNode.exists()) {
                if (!switchNode.isChecked) {
                    switchNode.click()
                }
            } else {
                val toggle =
                    d.findObject(UiSelector().resourceId("android:id/switch_widget"))
                if (toggle.waitForExists(3000) && toggle.exists() && !toggle.isChecked) {
                    toggle.click()
                }
            }
            d.waitForIdle(1500)
            d.pressBack()
            d.waitForIdle(1500)
            return
        }
    }
}
