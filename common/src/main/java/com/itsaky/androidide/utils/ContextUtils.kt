/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources.Theme
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

@PublishedApi
internal val logger = LoggerFactory.getLogger("ContextUtils")

/**
 * Check if the given accessibility service is enabled.
 */
inline fun <reified T> Context.isAccessibilityEnabled(): Boolean {
	try {
		val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
		if (enabled != 1) return false

		val name = ComponentName(applicationContext, T::class.java)
		val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
		return services?.contains(name.flattenToString()) ?: false
	} catch (e: Settings.SettingNotFoundException) {
		logger.warn("Failed to check if accessibility service is enabled", e)
		return false
	}
}

fun Context.isSystemInDarkMode(): Boolean = this.resources.configuration.isSystemInDarkMode()

fun Configuration.isSystemInDarkMode(): Boolean = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

@JvmOverloads
fun Context.resolveAttr(
	id: Int,
	resolveRefs: Boolean = true,
): Int = theme.resolveAttr(id, resolveRefs)

@JvmOverloads
fun Theme.resolveAttr(
	id: Int,
	resolveRefs: Boolean = true,
): Int =
	TypedValue().let {
		resolveAttribute(id, it, resolveRefs)
		it.data
	}

fun Activity.restartApp() {
	val intent = packageManager.getLaunchIntentForPackage(packageName)
	intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
	intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
	startActivity(intent)
	finishAffinity()
	exitProcess(0)
}

/**
 * Converts a dp value to pixels, using this context's display metrics.
 */
fun Context.dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

/**
 * Converts an sp value to pixels, using this context's display metrics.
 */
fun Context.spToPx(sp: Float): Int = (sp * resources.displayMetrics.scaledDensity + 0.5f).toInt()

/**
 * Copies [text] to the system clipboard, under the given [label].
 */
fun Context.copyToClipboard(
	text: CharSequence,
	label: CharSequence = "",
) {
	val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
	cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * Checks whether this device currently has network connectivity with internet access.
 */
fun Context.isNetworkConnected(): Boolean {
	val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
	val network = cm.activeNetwork ?: return false
	val capabilities = cm.getNetworkCapabilities(network) ?: return false
	return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * Returns this app's version code, or -1 if it could not be determined.
 */
@Suppress("DEPRECATION")
fun Context.getAppVersionCode(): Int =
	try {
		packageManager.getPackageInfo(packageName, 0).versionCode
	} catch (e: PackageManager.NameNotFoundException) {
		logger.warn("Failed to get app version code", e)
		-1
	}

/**
 * Checks whether the soft (on-screen) keyboard is currently visible in this activity's window.
 *
 * `WindowInsetsCompat`'s IME visibility bit is only reliable from API 30 onward; below that
 * (down to `MIN_SDK = 28`) it can misreport, so we fall back to a decor visible-frame heuristic
 * that works regardless of API level or the window's soft-input-adjust mode.
 */
fun Activity.isSoftInputVisible(): Boolean {
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		val insets = ViewCompat.getRootWindowInsets(window.decorView) ?: return false
		return insets.isVisible(WindowInsetsCompat.Type.ime())
	}
	return isSoftInputVisibleByDecorFrame()
}

private fun Activity.isSoftInputVisibleByDecorFrame(): Boolean {
	val decorView = window.decorView
	val visibleFrame = Rect()
	decorView.getWindowVisibleDisplayFrame(visibleFrame)
	val heightDiff = decorView.height - visibleFrame.height()
	// A keyboard eats a large chunk of the screen; smaller diffs come from status/nav bar
	// insets rather than the IME, so require the gap to be a meaningful fraction of the screen.
	return heightDiff > decorView.height / 4
}
