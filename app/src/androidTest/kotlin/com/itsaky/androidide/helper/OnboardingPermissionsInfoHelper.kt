package com.itsaky.androidide.helper

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
            clickFirstAccessibilityNodeByText(accept)
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

        clickFirstAccessibilityNodeByText(
            searchText = "NEXT",
            errorLabel = "NEXT",
            matchBy = { node ->
                val desc = node.contentDescription?.toString() ?: ""
                val text = node.text?.toString() ?: ""
                desc.contains("NEXT", ignoreCase = true) || text.contains("NEXT", ignoreCase = true)
            },
        )
        d.waitForIdle()
    }
}
