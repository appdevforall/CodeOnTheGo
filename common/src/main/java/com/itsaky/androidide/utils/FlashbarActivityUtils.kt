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
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuff.Mode.SRC_ATOP
import android.widget.ImageView.ScaleType
import android.widget.ImageView.ScaleType.FIT_CENTER
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.annotation.StringRes
import com.itsaky.androidide.flashbar.Flashbar
import com.itsaky.androidide.flashbar.Flashbar.Gravity.TOP
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.FlashType.ERROR
import com.itsaky.androidide.utils.FlashType.INFO
import com.itsaky.androidide.utils.FlashType.SUCCESS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val DURATION_SHORT = 2000L
const val DURATION_LONG = 3500L
const val DURATION_INDEFINITE = Flashbar.DURATION_INDEFINITE

/** Safety net for [Flashbar.OnBarShowListener.onShown] never firing - callers awaiting it are
 * never blocked indefinitely (e.g. if there's no foreground activity to actually show a bar). */
private const val FLASH_SHOWN_TIMEOUT_MS = 3000L

val COLOR_SUCCESS = Color.parseColor("#4CAF50")
val COLOR_ERROR = Color.parseColor("#f44336")
const val COLOR_INFO = Color.DKGRAY

enum class IconType { SUCCESS, ERROR, INFO }

private fun Flashbar.Builder.applyIcon(iconType: IconType): Flashbar.Builder =
	when (iconType) {
		IconType.SUCCESS -> this.successIcon()
		IconType.ERROR -> this.errorIcon()
		IconType.INFO -> this.infoIcon()
	}

/**
 * Builds and configures a Flashbar for [msg]/[iconType] (icon, and - for an indefinite error - the
 * dismiss button plus tap/swipe dismissal), without showing it yet. Shared by [showFlashBar] and
 * [showFlashBarAwaitShown]
 * so their setup can't silently diverge. Returns `null` for a `null` [msg] (nothing to show).
 */
private fun Activity.configureFlashbar(
	msg: Any?,
	iconType: IconType,
	gravity: Flashbar.Gravity,
	duration: Long,
): Flashbar.Builder? {
	if (msg == null) return null
	if (msg !is Int && msg !is String) {
		throw IllegalArgumentException("Message must be String or Int resource")
	}

	val builder = flashbarBuilder(gravity, duration).applyIcon(iconType)

	// Add a close button if the flashbar is an indefinite error
	if (duration == DURATION_INDEFINITE && iconType == IconType.ERROR) {
		builder.positiveActionText(getString(R.string.dismiss))
		builder.positiveActionTapListener { it.dismiss() }

		// An indefinite bar is drawn OVER the activity, and the error variant is tall enough
		// (message + action row) to cover the editor toolbar. Until it goes away the Run and
		// Quick Build buttons cannot be reached at all: a tap on them lands on the bar, so both
		// read as dead with nothing on screen saying why. Measured on an a56: the bar occupied
		// y 236-371 while the toolbar buttons sat at y 261-383.
		// So any touch on the bar, and any swipe, gets rid of it - not just the Dismiss button.
		if (indefiniteErrorBarDismissesOnTouch()) {
			builder.listenBarTaps { it.dismiss() }
			builder.enableSwipeToDismiss()
		}
	}

	when (msg) {
		is Int -> builder.message(msg)
		is String -> builder.message(msg)
	}

	return builder
}

private fun Activity.showFlashBar(
	msg: Any?,
	iconType: IconType,
	gravity: Flashbar.Gravity = TOP,
	duration: Long = Flashbar.DURATION_SHORT,
) {
	configureFlashbar(msg, iconType, gravity, duration)?.showOnUiThread()
}

@JvmOverloads
fun Activity.flashbarBuilder(
	gravity: Flashbar.Gravity = TOP,
	duration: Long = DURATION_SHORT,
	backgroundColor: Int = resolveAttr(R.attr.colorPrimaryContainer),
	messageColor: Int = resolveAttr(R.attr.colorOnPrimaryContainer),
): Flashbar.Builder =
	Flashbar
		.Builder(this)
		.gravity(gravity)
		.duration(duration)
		.backgroundColor(backgroundColor)
		.messageColor(messageColor)

fun Activity.flashMessage(
	msg: String?,
	type: FlashType,
) {
	msg ?: return
	when (type) {
		ERROR -> flashError(msg)
		INFO -> flashInfo(msg)
		SUCCESS -> flashSuccess(msg)
	}
}

fun Activity.flashMessage(
	@StringRes msg: Int,
	type: FlashType,
) {
	when (type) {
		ERROR -> flashError(msg)
		INFO -> flashInfo(msg)
		SUCCESS -> flashSuccess(msg)
	}
}

fun Activity.flashSuccess(msg: String?) = showFlashBar(msg, IconType.SUCCESS)

fun Activity.flashError(msg: String?) = showFlashBar(msg, IconType.ERROR, duration = DURATION_INDEFINITE)

fun Activity.flashInfo(msg: String?) = showFlashBar(msg, IconType.INFO)

// A 1 s bar (the default) is gone before a sentence can be read. For an informational
// message that fires once and explains why something did NOT happen, the longer duration
// is the difference between an explanation and a flicker.
fun Activity.flashInfoLong(msg: String?) = showFlashBar(msg, IconType.INFO, duration = DURATION_LONG)

/**
 * Like [showFlashBar], but suspends until the bar's entrance animation has actually finished (or
 * [FLASH_SHOWN_TIMEOUT_MS] elapses) instead of firing-and-forgetting - for callers (e.g. a
 * one-shot screen about to finish()) that need the message to be visible before proceeding,
 * rather than guessing a fixed delay that may or may not outlast the real animation.
 */
private suspend fun Activity.showFlashBarAwaitShown(
	msg: Any?,
	iconType: IconType,
	gravity: Flashbar.Gravity = TOP,
	duration: Long = Flashbar.DURATION_SHORT,
) {
	val builder = configureFlashbar(msg, iconType, gravity, duration) ?: return

	val shown = CompletableDeferred<Unit>()
	builder.barShowListener(
		object : Flashbar.OnBarShowListener {
			override fun onShowing(bar: Flashbar) = Unit

			override fun onShowProgress(
				bar: Flashbar,
				progress: Float,
			) = Unit

			override fun onShown(bar: Flashbar) {
				shown.complete(Unit)
			}
		},
	)

	runOnUiThread { builder.build().show() }
	withTimeoutOrNull(FLASH_SHOWN_TIMEOUT_MS) { shown.await() }
}

suspend fun Activity.flashSuccessAwaitShown(msg: String?) = showFlashBarAwaitShown(msg, IconType.SUCCESS)

suspend fun Activity.flashErrorAwaitShown(msg: String?) = showFlashBarAwaitShown(msg, IconType.ERROR, duration = DURATION_INDEFINITE)

fun Activity.flashSuccess(
	@StringRes msg: Int,
) = showFlashBar(msg, IconType.SUCCESS)

fun Activity.flashError(
	@StringRes msg: Int,
) = showFlashBar(msg, IconType.ERROR)

fun Activity.flashInfo(
	@StringRes msg: Int,
) = showFlashBar(msg, IconType.INFO)

@JvmOverloads
suspend fun Activity.flashProgress(configure: (Flashbar.Builder.() -> Unit)? = null): Flashbar {
	val builder =
		flashbarBuilder(gravity = TOP, duration = DURATION_INDEFINITE)
			.showProgress(Flashbar.ProgressPosition.LEFT)

	configure?.invoke(builder)

	val flashbar =
		withContext(Dispatchers.Main.immediate) {
			builder.build().also { flashbar ->
				flashbar.show()
			}
		}

	return flashbar
}

fun Flashbar.Builder.showOnUiThread() {
	// build() may inflate layout using LayoutInflater, which may result in
	// animators being started. At that point, if we're in a non-UI thread,
	// the thread will crash with an AndroidRuntimeException.
	// See ADFA-1529
	runOnUiThread { build().show() }
}

fun Flashbar.showOnUiThread() {
	runOnUiThread { show() }
}

fun Flashbar.Builder.successIcon(): Flashbar.Builder = withIcon(R.drawable.ic_ok, colorFilter = COLOR_SUCCESS)

fun Flashbar.Builder.errorIcon(): Flashbar.Builder = withIcon(R.drawable.ic_error, colorFilter = COLOR_ERROR)

fun Flashbar.Builder.infoIcon(): Flashbar.Builder = withIcon(R.drawable.ic_info, colorFilter = COLOR_INFO)

fun Flashbar.Builder.withIcon(
	@DrawableRes icon: Int,
	@FloatRange(from = 0.0, to = 1.0) scale: Float = 1.0f,
	@ColorInt colorFilter: Int = -1,
	colorFilterMode: PorterDuff.Mode = SRC_ATOP,
	scaleType: ScaleType = FIT_CENTER,
): Flashbar.Builder =
	showIcon(scale = scale, scaleType = scaleType).icon(icon).also {
		if (colorFilter != -1) {
			iconColorFilter(colorFilter, colorFilterMode)
		}
	}
