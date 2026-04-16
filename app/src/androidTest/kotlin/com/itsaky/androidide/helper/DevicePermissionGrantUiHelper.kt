package com.itsaky.androidide.helper

import android.Manifest
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.kaspersky.kaspresso.device.Device

/**
 * Grant permissions after tapping "Allow" on the onboarding permission list.
 * Each method handles the Settings page that the app opened, grants the permission,
 * and presses back to return to onboarding.
 *
 * Notifications uses grantRuntimePermission (standard runtime permission).
 * Storage, Install, and Overlay use appops shell commands because the Settings UI
 * varies across API levels and emulators, making UI-based toggling fragile.
 */
fun Device.grantPostNotificationsUi() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
        instrumentation.targetContext.packageName,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    val d = uiDevice
    d.waitForIdle()
    d.pressBack()
    d.waitForIdle()
}

fun Device.grantStorageManageAllFilesUi() {
    grantViaAppOpsAndBack("MANAGE_EXTERNAL_STORAGE")
}

fun Device.grantInstallUnknownAppsUi() {
    grantViaAppOpsAndBack("REQUEST_INSTALL_PACKAGES")
}

fun Device.grantDisplayOverOtherAppsUi() {
    grantViaAppOpsAndBack("SYSTEM_ALERT_WINDOW")
}

/**
 * Finds accessibility nodes matching [searchText] and clicks the first one accepted by [matchBy].
 *
 * Handles root-window acquisition, node iteration, recycling, and throws
 * [AssertionError] if no matching node was clicked.
 */
fun clickFirstAccessibilityNodeByText(
    searchText: String,
    errorLabel: String = searchText,
    matchBy: (AccessibilityNodeInfo) -> Boolean = { node ->
        node.text?.toString().equals(searchText, ignoreCase = true) == true
            && node.isClickable
            && node.isEnabled
            && node.isVisibleToUser
    },
) {
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val root = uiAutomation.rootInActiveWindow
        ?: throw AssertionError("No active window for accessibility")

    val nodes = root.findAccessibilityNodeInfosByText(searchText)
    var clicked = false
    try {
        for (node in nodes) {
            if (!clicked && matchBy(node)) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            node.recycle()
        }
    } finally {
        root.recycle()
    }

    if (!clicked) {
        throw AssertionError("No '$errorLabel' button found via accessibility")
    }
}

/**
 * Clicks a node found by [searchText] matching on [contentDescription] rather than text.
 * Useful for toolbar ImageButtons that have no text label.
 */
fun clickFirstAccessibilityNodeByDescription(
    searchText: String,
    errorLabel: String = searchText,
) {
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val root = uiAutomation.rootInActiveWindow
        ?: throw AssertionError("No active window for accessibility")

    val nodes = root.findAccessibilityNodeInfosByText(searchText)
    var clicked = false
    try {
        for (node in nodes) {
            val desc = node.contentDescription?.toString() ?: ""
            if (!clicked && desc.contains(searchText, ignoreCase = true) && node.isClickable) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            node.recycle()
        }
    } finally {
        root.recycle()
    }

    if (!clicked) {
        throw AssertionError("No '$errorLabel' button found via accessibility (by description)")
    }
}

/**
 * Finds a node by [searchText], walks up to its nearest clickable ancestor, and clicks it.
 * Useful when the click handler is on a parent container (e.g., RecyclerView item roots).
 */
fun clickFirstAccessibilityNodeParentByText(
    searchText: String,
    errorLabel: String = searchText,
) {
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val root = uiAutomation.rootInActiveWindow
        ?: throw AssertionError("No active window for accessibility")

    val nodes = root.findAccessibilityNodeInfosByText(searchText)
    var clicked = false
    try {
        for (node in nodes) {
            if (clicked) { node.recycle(); continue }
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    break
                }
                current = current.parent
            }
            node.recycle()
        }
    } finally {
        root.recycle()
    }

    if (!clicked) {
        throw AssertionError("No clickable parent found for '$errorLabel' via accessibility")
    }
}

/**
 * Sets the text of an EditText found by [searchText] to [newText] using accessibility
 * ACTION_SET_TEXT. Avoids the clear-then-retype pattern which can lose the selector.
 */
fun setAccessibilityEditText(
    searchText: String,
    newText: String,
    errorLabel: String = searchText,
) {
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val root = uiAutomation.rootInActiveWindow
        ?: throw AssertionError("No active window for accessibility")

    val nodes = root.findAccessibilityNodeInfosByText(searchText)
    var set = false
    try {
        for (node in nodes) {
            if (!set && node.className?.toString() == "android.widget.EditText") {
                val args = android.os.Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText
                )
                set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            node.recycle()
        }
    } finally {
        root.recycle()
    }

    if (!set) {
        throw AssertionError("Failed to set text on '$errorLabel' via accessibility")
    }
}

/** Appops that are granted via [grantViaAppOpsAndBack] and must be explicitly revoked in cleanup. */
private val APPOPS_PERMISSIONS = listOf(
    "MANAGE_EXTERNAL_STORAGE",
    "REQUEST_INSTALL_PACKAGES",
    "SYSTEM_ALERT_WINDOW",
)

/**
 * Resets appops permissions back to default and clears app data.
 *
 * `pm clear` / `pm reset-permissions` only handle runtime permissions;
 * appops granted via `appops set ... allow` survive those commands.
 */
fun resetAppPermissionsAndClear(pkg: String) {
    val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (op in APPOPS_PERMISSIONS) {
        ua.executeShellCommand("appops set $pkg $op default")
    }
    ua.executeShellCommand("pm clear $pkg && pm reset-permissions $pkg")
}

private fun Device.grantViaAppOpsAndBack(appOp: String) {
    val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("appops set $pkg $appOp allow")
    val d = uiDevice
    d.waitForIdle()
    d.pressBack()
    d.waitForIdle()
}
