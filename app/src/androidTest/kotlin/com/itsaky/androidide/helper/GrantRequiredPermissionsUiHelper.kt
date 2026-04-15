package com.itsaky.androidide.helper

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.R
import com.itsaky.androidide.screens.PermissionScreen
import com.itsaky.androidide.utils.PermissionsHelper
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext

/**
 * Drives the onboarding permission list and system Settings UIs for every entry in
 * [PermissionsHelper.getRequiredPermissions]. Matches [com.itsaky.androidide.PermissionsScreenTest]
 * so scenarios like [com.itsaky.androidide.scenarios.NavigateToMainScreenScenario] stay in sync
 * with API-level permission sets (e.g. notifications + overlay on API 33+).
 */
fun TestContext<Unit>.grantAllRequiredPermissionsThroughOnboardingUi() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val required = PermissionsHelper.getRequiredPermissions(targetContext)
    val appLabel =
        targetContext.applicationInfo.loadLabel(targetContext.packageManager).toString()

    required.forEachIndexed { index, item ->
        step("Grant: ${targetContext.getString(item.title)}") {
            flakySafely(timeoutMs = 120_000) {
                PermissionScreen {
                    rvPermissions {
                        childAt<PermissionScreen.PermissionItem>(index) {
                            grantButton {
                                isVisible()
                                click()
                            }
                        }
                    }
                }
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
                        device.grantDisplayOverOtherAppsUi(
                            listOf(
                                appLabel,
                                targetContext.getString(R.string.app_name),
                                targetContext.packageName,
                            ),
                            targetContext,
                        )
                    }
                    else -> {
                        throw IllegalStateException("Unknown permission row: ${item.permission}")
                    }
                }
            }
        }
    }
}
