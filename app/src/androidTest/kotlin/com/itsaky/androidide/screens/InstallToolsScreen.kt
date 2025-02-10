package com.itsaky.androidide.screens

import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.text.KButton

object InstallToolsScreen : KScreen<InstallToolsScreen>() {

    override val layoutId: Int? = null
    override val viewClass: Class<*>? = null

    val doneButton = KButton { withId(R.id.done) }
}