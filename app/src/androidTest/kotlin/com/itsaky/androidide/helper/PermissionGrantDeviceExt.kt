package com.itsaky.androidide.helper

import android.content.Context
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.screens.SystemPermissionsScreen
import com.itsaky.androidide.utils.PermissionsHelper
import com.kaspersky.kaspresso.device.Device

/**
 * System Settings automation for onboarding permission grants.
 *
 * Tuned for **Pixel 9 XL** (stock AOSP / Google Settings). Other OEMs may need extra selectors.
 */

private val overlaySwitchClassNames =
	listOf(
		"com.google.android.material.materialswitch.MaterialSwitch",
		"com.google.android.material.switchmaterial.SwitchMaterial",
		"android.widget.Switch",
		"androidx.appcompat.widget.SwitchCompat",
	)

/** One tap on the switch thumb area — each extra tap toggles again, so never multi-tap. */
private fun tapSwitchOnce(d: UiDevice, sw: UiObject) {
	val r = runCatching { sw.visibleBounds }.getOrNull() ?: return
	if (r.width() < 2 || r.height() < 2) {
		return
	}
	val y = r.centerY()
	val x = (r.left + r.width() * 3 / 4).coerceIn(r.left + 4, r.right - 4)
	runCatching { d.click(x, y) }
}

/**
 * Pixel app detail for “Display over other apps”: toggle is the **trailing** [MaterialSwitch] on the same row as
 * the **“Allow display over other apps”** label. That widget is often **not** found by generic class scans.
 * Tap once at the trailing edge — **multiple X positions each toggle** the MaterialSwitch; use a single tap.
 */
private fun tapPixelAllowDisplayOverOtherAppsRowOnce(d: UiDevice): Boolean {
	val labelSelectors =
		listOf(
			UiSelector().text("Allow display over other apps"),
			UiSelector().textContains("Allow display over other apps"),
			UiSelector().textMatches("(?i).*allow\\s+display\\s+over\\s+other\\s+apps.*"),
		)
	val scroll =
		runCatching {
			UiScrollable(UiSelector().scrollable(true)).also { it.setAsVerticalList() }
		}.getOrNull()

	for (sel in labelSelectors) {
		runCatching { scroll?.scrollIntoView(sel) }
		val label = d.findObject(sel)
		if (!label.waitForExists(4000) || !label.exists()) {
			continue
		}
		val r = runCatching { label.visibleBounds }.getOrNull() ?: continue
		if (r.height() < 2) {
			continue
		}
		val y = r.centerY()
		val w = d.displayWidth
		val x = (w - 48).coerceIn(r.left + 24, w - 16)
		runCatching { d.click(x, y) }
		d.waitForIdle(300)
		return true
	}
	return false
}

private fun findPrimaryOverlaySwitch(d: UiDevice): UiObject? {
	for (sel in
		listOf(
			UiSelector().resourceId("android:id/switch_widget"),
			UiSelector().resourceIdMatches(".*switch_widget"),
		)) {
		val sw = d.findObject(sel)
		if (sw.waitForExists(1500) && sw.exists()) {
			return sw
		}
	}
	for (className in overlaySwitchClassNames) {
		val sw = d.findObject(UiSelector().className(className).instance(0))
		if (sw.waitForExists(800) && sw.exists()) {
			return sw
		}
	}
	return null
}

/**
 * At most one enable action per call: prefer [UiObject.isChecked] so we never tap an already-on switch
 * (which would turn it off). Falls back to a single label-row tap if no switch node is found yet.
 */
private fun tryEnableOverlayOnDetailScreen(d: UiDevice, targetContext: Context): Boolean {
	if (PermissionsHelper.canDrawOverlays(targetContext)) {
		return true
	}
	val sw = findPrimaryOverlaySwitch(d)
	if (sw != null) {
		val checked = runCatching { sw.isChecked }.getOrNull()
		if (checked == true) {
			d.waitForIdle(600)
			return PermissionsHelper.canDrawOverlays(targetContext)
		}
		tapSwitchOnce(d, sw)
		d.waitForIdle(1500)
		return PermissionsHelper.canDrawOverlays(targetContext)
	}
	if (tapPixelAllowDisplayOverOtherAppsRowOnce(d)) {
		d.waitForIdle(1500)
		return PermissionsHelper.canDrawOverlays(targetContext)
	}
	return false
}

/** Selectors for the app title row on Settings → Display over other apps (Pixel / AOSP). */
private fun overlayAppRowSelectors(labels: List<String>): List<UiSelector> {
	val out = ArrayList<UiSelector>()
	for (label in labels) {
		if (label.isEmpty()) continue
		out.add(UiSelector().text(label))
		out.add(UiSelector().textContains(label))
	}
	// Title string is stable in resources; pattern helps if layout uses subtle text differences.
	out.add(UiSelector().textMatches("(?i).*code\\s+on\\s+the\\s+go.*"))
	out.add(UiSelector().descriptionContains("Code on the Go"))
	return out
}

/**
 * Scroll the settings list until the row is visible, then tap **coordinates** at the label’s bounds.
 * On Pixel the primary text is often not `clickable`; `.click()` on the UiObject is a no-op.
 */
private fun openDisplayOverAppsAppDetail(d: UiDevice, selectors: List<UiSelector>): Boolean {
	val scroll =
		runCatching {
			UiScrollable(UiSelector().scrollable(true)).also { it.setAsVerticalList() }
		}.getOrNull()

	for (sel in selectors) {
		runCatching { scroll?.scrollIntoView(sel) }
		val node = d.findObject(sel)
		if (!node.waitForExists(8000) || !node.exists()) {
			continue
		}
		val r =
			runCatching { node.visibleBounds }.getOrNull()
				?: continue
		if (r.width() <= 1 || r.height() <= 1) {
			continue
		}
		val x = r.centerX()
		val y = r.centerY()
		runCatching { d.click(x, y) }
		d.waitForIdle(2500)
		return true
	}
	return false
}

private fun navigateBackFromOverlaySettings(d: UiDevice) {
	d.waitForIdle(500)
	d.pressBack()
	d.waitForIdle(1200)
	d.pressBack()
	d.waitForIdle(2000)
}

/** Notification app-settings screen: enable the primary toggle, then return. */
fun Device.grantPostNotificationsUi() {
	uiDevice.waitForIdle(2000)
	val sw = uiDevice.findObject(UiSelector().className("android.widget.Switch"))
	if (sw.waitForExists(10_000)) {
		runCatching { if (!sw.isChecked) sw.click() }
			.recoverCatching { sw.click() }
	}
	uiDevice.waitForIdle(500)
	uiDevice.pressBack()
	uiDevice.waitForIdle(2000)
}

/**
 * "Display over other apps" on Pixel: **first** screen is a **scrolling app list** (tap the app name),
 * **second** screen has the **MaterialSwitch**. Uses **single** taps only — multi-tap coordinate sweeps toggle on/off repeatedly.
 */
fun Device.grantDisplayOverOtherAppsUi(rowSearchTexts: List<String>, targetContext: Context) {
	val labels =
		rowSearchTexts
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.distinct()
	val rowSelectors = overlayAppRowSelectors(labels)
	uiDevice.waitForIdle(2500)

	repeat(4) {
		if (openDisplayOverAppsAppDetail(uiDevice, rowSelectors)) {
			repeat(24) {
				if (PermissionsHelper.canDrawOverlays(targetContext)) {
					navigateBackFromOverlaySettings(uiDevice)
					return
				}
				tryEnableOverlayOnDetailScreen(uiDevice, targetContext)
				uiDevice.waitForIdle(400)
			}
		}
		uiDevice.waitForIdle(1200)
	}

	check(PermissionsHelper.canDrawOverlays(targetContext)) {
		"Display over other apps was not granted — overlay switch on Pixel detail screen did not take effect."
	}
	navigateBackFromOverlaySettings(uiDevice)
}

fun Device.grantStorageManageAllFilesUi() {
	uiDevice.waitForIdle(3000)
	SystemPermissionsScreen {
		val fallbacks =
			listOf(
				{ storagePermissionView { isDisplayed(); click() } },
				{ storagePermissionViewAlt1 { isDisplayed(); click() } },
				{ storagePermissionViewAlt2 { isDisplayed(); click() } },
				{ storagePermissionViewAlt3 { isDisplayed(); click() } },
				{ storagePermissionViewAlt4 { isDisplayed(); click() } },
				{ storagePermissionViewAlt5 { isDisplayed(); click() } },
				{ storagePermissionViewAlt6 { isDisplayed(); click() } },
				{ storagePermissionViewAlt7 { isDisplayed(); click() } },
				{ storagePermissionViewAlt8 { isDisplayed(); click() } },
			)
		var granted = false
		for (attempt in fallbacks) {
			try {
				attempt()
				granted = true
				break
			} catch (_: Exception) {
			}
		}
		check(granted) { "Could not confirm storage / all-files access in system UI" }
	}
	uiDevice.waitForIdle(2000)
	uiDevice.pressBack()
	uiDevice.waitForIdle(2000)
}

fun Device.grantInstallUnknownAppsUi() {
	uiDevice.waitForIdle(3000)
	SystemPermissionsScreen {
		val installFallbacks =
			listOf(
				{ installPackagesPermission { isDisplayed(); click() } },
				{ installPackagesPermissionAlt1 { isDisplayed(); click() } },
				{ installPackagesPermissionAlt2 { isDisplayed(); click() } },
			)
		var granted = false
		for (attempt in installFallbacks) {
			try {
				attempt()
				granted = true
				break
			} catch (_: Exception) {
			}
		}
		check(granted) { "Could not allow install-unknown-apps in system UI" }
	}
	uiDevice.waitForIdle(2000)
	uiDevice.pressBack()
	uiDevice.waitForIdle(2000)
}
