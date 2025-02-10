package com.itsaky.androidide.screens

import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.common.views.KView
import io.github.kakaocup.kakao.text.KButton

object ProjectSettingsScreen : KScreen<ProjectSettingsScreen>() {

    override val layoutId: Int? = null

    override val viewClass: Class<*>? = null

    val languageField = KView {
        withHint("Project Language")
    }

    val createProjectButton = KButton {
        withText(R.string.create_project)
        withId(R.id.finish)
    }
}