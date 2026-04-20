package com.itsaky.androidide.helper

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.R
import com.itsaky.androidide.screens.PermissionScreen
import com.itsaky.androidide.utils.PermissionsHelper
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

/**
 * Drives the onboarding permission list and system Settings UIs for every entry in
 * [PermissionsHelper.getRequiredPermissions].
 */
fun TestContext<Unit>.grantAllRequiredPermissionsThroughOnboardingUi() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val required = PermissionsHelper.getRequiredPermissions(targetContext)

    required.forEachIndexed { index, item ->
        step("Grant: ${targetContext.getString(item.title)}") {
            flakySafely(timeoutMs = 10_000) {
                // Scroll to the permission item and click its grant button via accessibility
                // ACTION_CLICK. The button may be in the gesture exclusion zone where
                // coordinate-based clicks (Espresso) get swallowed by the OS.
                PermissionScreen {
                    rvPermissions {
                        childAt<PermissionScreen.PermissionItem>(index) {
                            grantButton {
                                isVisible()
                            }
                        }
                    }
                }
                clickFirstGrantButton()

                when (item.permission) {
                    Manifest.permission.POST_NOTIFICATIONS -> {
                        device.grantPostNotificationsUi()
                    }
                    Manifest.permission_group.STORAGE -> {
                        device.grantStorageManageAllFilesUi()
                    }
                    Manifest.permission.REQUEST_INSTALL_PACKAGES -> {
                        device.grantInstallUnknownAppsUi()
                    }
                    Manifest.permission.SYSTEM_ALERT_WINDOW -> {
                        device.grantDisplayOverOtherAppsUi()
                    }
                    else -> {
                        throw IllegalStateException("Unknown permission row: ${item.permission}")
                    }
                }
            }
        }
    }
}

/**
 * Clicks the first visible, enabled "Allow" grant button using accessibility ACTION_CLICK.
 *
 * We always click the FIRST "Allow" button because permissions are granted in order.
 * After each grant, the button text changes (e.g. to a checkmark), so the first remaining
 * "Allow" is always the next permission to grant.
 */
private fun clickFirstGrantButton() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val grantText = ctx.getString(R.string.title_grant)
    clickFirstAccessibilityNodeByText(grantText)
}
