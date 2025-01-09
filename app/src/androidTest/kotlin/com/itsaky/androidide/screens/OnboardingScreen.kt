package com.itsaky.androidide.screens

import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.text.KButton
import io.github.kakaocup.kakao.text.KTextView

object OnboardingScreen : KScreen<OnboardingScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val greetingTitle = KTextView { withText(R.string.greeting_title) }
    val greetingSubtitle = KTextView { withText(R.string.greeting_subtitle) }
    val nextButton = KButton { withId(R.id.next) }
}