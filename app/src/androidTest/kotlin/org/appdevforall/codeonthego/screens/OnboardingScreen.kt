package org.appdevforall.codeonthego.screens

import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.text.KButton
import io.github.kakaocup.kakao.text.KTextView
import org.appdevforall.codeonthego.R

object OnboardingScreen : KScreen<OnboardingScreen>() {
	override val layoutId: Int? = null
	override val viewClass: Class<*>? = null

	val greetingTitle =
		KTextView {
			withId(R.id.title)
			isDisplayed()
		}
	val greetingSubtitle =
		KTextView {
			withId(R.id.description)
			isDisplayed()
		}
	val nextButton = KButton { withId(R.id.next) }
}
