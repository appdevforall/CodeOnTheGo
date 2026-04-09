package com.itsaky.androidide.helper

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.screens.OnboardingScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

/**
 * Second onboarding slide ([com.itsaky.androidide.fragments.onboarding.PermissionsInfoFragment]):
 * dismiss the privacy disclosure dialog if shown, then continue to the permission list slide.
 */
fun TestContext<Unit>.passPermissionsInfoSlideWithPrivacyDialog() {
    step("Permissions info: accept privacy dialog if shown") {
        flakySafely(timeoutMs = 25_000) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val accept =
                ctx.getString(com.itsaky.androidide.resources.R.string.privacy_disclosure_accept)
            val d = device.uiDevice
            val btn = d.findObject(UiSelector().text(accept))
            if (btn.waitForExists(12_000) && btn.exists()) {
                btn.click()
                d.waitForIdle(1500)
            }
        }
    }
    step("Continue from permissions information slide") {
        flakySafely(timeoutMs = 25_000) {
            OnboardingScreen.nextButton {
                isVisible()
                isClickable()
                click()
            }
        }
    }
}
