package com.itsaky.androidide.helper

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

/**
 * Second onboarding slide ([com.itsaky.androidide.fragments.onboarding.PermissionsInfoFragment]):
 * dismiss the privacy disclosure dialog if shown, then continue to the permission list slide.
 */
fun TestContext<Unit>.passPermissionsInfoSlideWithPrivacyDialog() {
    step("Permissions info: accept privacy dialog if shown") {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val accept =
            ctx.getString(com.itsaky.androidide.resources.R.string.privacy_disclosure_accept)
        val d = device.uiDevice
        val btn = d.findObject(UiSelector().text(accept))
        if (btn.waitForExists(2_000)) {
            // Use accessibility click -- button may be in the gesture exclusion zone
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation
                .rootInActiveWindow
            if (root != null) {
                val nodes = root.findAccessibilityNodeInfosByText(accept)
                for (node in nodes) {
                    if (node.text?.toString().equals(accept, ignoreCase = true)) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node.recycle()
                        break
                    }
                    node.recycle()
                }
                root.recycle()
            }
            d.waitForIdle()
        }
    }
    step("Continue from permissions information slide") {
        flakySafely(timeoutMs = 3_000) {
            // The Next button is in the system gesture exclusion zone. Use accessibility
            // ACTION_CLICK to bypass coordinate-based touch injection.
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation
                .rootInActiveWindow
                ?: throw AssertionError("No active window for accessibility")
            val nodes = root.findAccessibilityNodeInfosByText("NEXT")
            var clicked = false
            for (node in nodes) {
                if (node.contentDescription?.toString()?.contains("NEXT", ignoreCase = true) == true) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    break
                }
                node.recycle()
            }
            root.recycle()
            if (!clicked) {
                throw AssertionError("NEXT button not found on permissions info slide")
            }
            device.uiDevice.waitForIdle()
        }
    }
}
