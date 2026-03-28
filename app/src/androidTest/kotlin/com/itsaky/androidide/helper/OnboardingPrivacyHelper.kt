package com.itsaky.androidide.helper

import com.itsaky.androidide.R
import com.itsaky.androidide.screens.OnboardingScreen
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import io.github.kakaocup.kakao.text.KButton

/**
 * After the welcome slide, onboarding shows [com.itsaky.androidide.fragments.onboarding.PermissionsInfoFragment],
 * which triggers a privacy & analytics disclosure dialog. Dismiss it and advance to the real permissions list.
 */
fun TestContext<Unit>.passPermissionsInfoSlideWithPrivacyDialog() {
	step("Accept privacy & analytics disclosure") {
		flakySafely(timeoutMs = 20_000) {
			KButton { withText(R.string.privacy_disclosure_accept) }.click()
		}
	}
	step("Continue to permissions list") {
		flakySafely(timeoutMs = 20_000) {
			OnboardingScreen.nextButton {
				isVisible()
				isClickable()
				click()
			}
		}
	}
}
