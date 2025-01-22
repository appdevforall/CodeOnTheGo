package com.itsaky.androidide.screens

import com.kaspersky.kaspresso.screens.KScreen
import io.github.kakaocup.kakao.dialog.KAlertDialog

object EditorScreen : KScreen<EditorScreen>() {

    override val layoutId: Int? = null

    override val viewClass: Class<*>? = null

    val firstBuildDialog = KAlertDialog()
}