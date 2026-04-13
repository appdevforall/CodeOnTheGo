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
                var clicked = false
                try {
                    for (node in nodes) {
                        if (!clicked && node.text?.toString().equals(accept, ignoreCase = true)) {
                            clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        node.recycle()
                    }
                } finally {
                    root.recycle()
                }
                if (!clicked) {
                    throw AssertionError("Failed to click '$accept' button via accessibility")
                }
            }
            d.waitForIdle()
        }
    }
    step("Continue from permissions information slide") {
        // After dismissing the dialog, the accessibility tree transitions from 2 windows
        // to 1. Use UIAutomator's waitForExists (which handles window transitions) to
        // wait for the NEXT button to become reachable, then click via accessibility.
        val d = device.uiDevice
        val nextObj = d.findObject(UiSelector().descriptionContains("NEXT"))
        if (!nextObj.waitForExists(3_000)) {
            throw AssertionError("NEXT button not found on permissions info slide")
        }

        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = uiAutomation.rootInActiveWindow
            ?: throw AssertionError("No active window for accessibility")
        val nodes = root.findAccessibilityNodeInfosByText("NEXT")
        var clicked = false
        try {
            for (node in nodes) {
                val desc = node.contentDescription?.toString() ?: ""
                val text = node.text?.toString() ?: ""
                if (!clicked && (desc.contains("NEXT", ignoreCase = true) || text.contains("NEXT", ignoreCase = true))) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                node.recycle()
            }
        } finally {
            root.recycle()
        }
        if (!clicked) {
            throw AssertionError("NEXT button found by UIAutomator but accessibility click failed")
        }
        d.waitForIdle()
    }
}
