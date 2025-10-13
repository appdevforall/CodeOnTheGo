package com.itsaky.androidide.screens

import com.kaspersky.components.kautomator.component.common.views.UiView
import com.kaspersky.components.kautomator.screen.UiScreen

object SystemPermissionsScreen : UiScreen<SystemPermissionsScreen>() {
    override val packageName: String = "com.android.settings"

    val storagePermissionView = UiView { withText("Allow access to manage all files") }
    val storagePermissionViewAlt1 = UiView { withText("Files and media") }
    val storagePermissionViewAlt2 = UiView { withText("Access all files") }
    val storagePermissionViewAlt3 = UiView { withText("Allow") }
    val storagePermissionViewAlt4 = UiView { withText("Allow file management") }
    val storagePermissionViewAlt5 = UiView { withText("Allow permission") }
    val storagePermissionViewAlt6 = UiView { withText("Use USB storage") }
    val storagePermissionViewAlt7 = UiView { withText("Storage") }
    val storagePermissionViewAlt8 = UiView { withText("Files") }

    val installPackagesPermission = UiView { withText("Allow from this source") }
    val installPackagesPermissionAlt1 = UiView { withText("Allow install of apps") }
    val installPackagesPermissionAlt2 = UiView { withText("Allow") }
}