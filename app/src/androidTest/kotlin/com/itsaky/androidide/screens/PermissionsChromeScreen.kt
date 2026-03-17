package com.itsaky.androidide.screens

import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.text.KButton

object PermissionsChromeScreen : KScreen<PermissionsChromeScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val finishInstallationButton = KButton { withId(R.id.finish_installation_button) }
    val appIntroBackButton = KButton { withId(R.id.back) }
    val appIntroDoneButton = KButton { withId(R.id.done) }
}

