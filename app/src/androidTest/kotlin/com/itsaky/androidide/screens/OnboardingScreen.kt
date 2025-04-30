package com.itsaky.androidide.screens

import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.text.KButton
import io.github.kakaocup.kakao.text.KTextView

object OnboardingScreen : KScreen<OnboardingScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val greetingTitle = KTextView { withText(R.string.app_name) }
    val greetingSubtitle = KTextView { withText(R.string.msg_no_internet_no_problem) }
    val nextButton = KButton { withId(R.id.next) }
}