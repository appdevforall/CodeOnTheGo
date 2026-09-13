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
import androidx.annotation.StringRes
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.flashbar.Flashbar

fun flashbarBuilder(): Flashbar.Builder? = withActivity { flashbarBuilder() }

fun flashMessage(
	msg: String?,
	type: FlashType,
) {
	withActivity { flashMessage(msg, type) }
}

fun flashMessage(
	@StringRes msg: Int,
	type: FlashType,
) {
	withActivity { flashMessage(msg, type) }
}

fun flashSuccess(msg: String?) {
	withActivity { flashSuccess(msg) }
}

fun flashSuccess(
	@StringRes msg: Int,
) {
	withActivity { flashSuccess(msg) }
}

/** Suspends until the success bar has actually finished its entrance animation - see [Activity.flashSuccessAwaitShown]. */
suspend fun flashSuccessAwaitShown(msg: String?) {
	withActivitySuspend { flashSuccessAwaitShown(msg) }
}

fun flashError(msg: String?) {
	withActivity { flashError(msg) }
}

/** Suspends until the error bar has actually finished its entrance animation - see [Activity.flashErrorAwaitShown]. */
suspend fun flashErrorAwaitShown(msg: String?) {
	withActivitySuspend { flashErrorAwaitShown(msg) }
}

fun flashError(
	@StringRes msg: Int,
) {
	withActivity { flashError(msg) }
}

fun flashInfo(msg: String?) {
	withActivity { flashInfo(msg) }
}

fun flashInfo(
	@StringRes msg: Int,
) {
	withActivity { flashInfo(msg) }
}

@JvmOverloads
suspend fun flashProgress(configure: (Flashbar.Builder.() -> Unit)? = null): Flashbar? = withActivity { flashProgress(configure) }

private inline fun <T> withActivity(action: Activity.() -> T?): T? =
	BaseApplication.baseInstance.foregroundActivity?.action()
		?: run {
			ILogger.ROOT.warn("Cannot show flashbar message. Cannot get top activity.")
			null
		}

private suspend inline fun withActivitySuspend(crossinline action: suspend Activity.() -> Unit) {
	val activity = BaseApplication.baseInstance.foregroundActivity
	if (activity == null) {
		ILogger.ROOT.warn("Cannot show flashbar message. Cannot get top activity.")
		return
	}
	activity.action()
}

/** The type of flashbar message. */
enum class FlashType {
	ERROR,
	INFO,
	SUCCESS,
}
