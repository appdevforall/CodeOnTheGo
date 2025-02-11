package com.itsaky.androidide.screens

import com.itsaky.androidide.R
import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.spinner.KSpinner
import io.github.kakaocup.kakao.spinner.KSpinnerItem
import io.github.kakaocup.kakao.text.KButton

object ProjectSettingsScreen : KScreen<ProjectSettingsScreen>() {

    override val layoutId: Int? = null

    override val viewClass: Class<*>? = null

    val createProjectButton = KButton {
        withText(R.string.create_project)
        withId(R.id.finish)
    }

    val spinner = KSpinner(
        builder = { withHint("Project Language") },
        itemTypeBuilder = { itemType(::KSpinnerItem) }
    )
}