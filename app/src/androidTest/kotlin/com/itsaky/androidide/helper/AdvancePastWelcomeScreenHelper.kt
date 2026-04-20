package com.itsaky.androidide.helper

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

/**
 * Clicks the Next button on the welcome screen using accessibility ACTION_CLICK.
 * The button is in the system gesture exclusion zone so coordinate-based clicks fail.
 */
fun TestContext<Unit>.advancePastWelcomeScreen() {
    step("Click continue button on the Welcome Screen") {
        flakySafely(timeoutMs = 3_000) {
            device.uiDevice.waitForIdle()

            val clicked = accessibilityClickByDescription("NEXT")
                || accessibilityClickByText("Next")
                || accessibilityClickByText("Continue")

            if (!clicked) {
                throw AssertionError("Next/Continue button not found on welcome screen")
            }
            device.uiDevice.waitForIdle()
        }
    }
}

private fun accessibilityClickByDescription(desc: String): Boolean {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation
        .rootInActiveWindow ?: return false
    val nodes = root.findAccessibilityNodeInfosByText(desc)
    for (node in nodes) {
        if (node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            root.recycle()
            return result
        }
        node.recycle()
    }
    root.recycle()
    return false
}

private fun accessibilityClickByText(text: String): Boolean {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation
        .rootInActiveWindow ?: return false
    val nodes = root.findAccessibilityNodeInfosByText(text)
    for (node in nodes) {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            root.recycle()
            return result
        }
        node.recycle()
    }
    root.recycle()
    return false
}
