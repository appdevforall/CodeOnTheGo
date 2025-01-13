package com.itsaky.androidide.screens

import com.kaspersky.components.kautomator.component.common.views.UiView
import com.kaspersky.components.kautomator.screen.UiScreen

object SystemPermissionsScreen : UiScreen<SystemPermissionsScreen>() {
    override val packageName: String = "com.android.settings"

    val storagePermissionView = UiView { withText("Allow access to manage all files") }
    val installPackagesPermission = UiView { withText("Allow from this source") }
}