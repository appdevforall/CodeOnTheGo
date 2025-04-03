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
import android.os.Looper
import android.widget.ImageView.ScaleType
import android.widget.ImageView.ScaleType.FIT_CENTER
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.annotation.StringRes
import androidx.core.graphics.toColorInt
import com.blankj.utilcode.util.ThreadUtils
import com.itsaky.androidide.flashbar.Flashbar
import com.itsaky.androidide.flashbar.Flashbar.Gravity.TOP
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.FlashType.ERROR
import com.itsaky.androidide.utils.FlashType.INFO
import com.itsaky.androidide.utils.FlashType.SUCCESS

const val DURATION_SHORT = 2000L
const val DURATION_LONG = 3500L
const val DURATION_INDEFINITE = Flashbar.DURATION_INDEFINITE

val COLOR_SUCCESS = Color.parseColor("#4CAF50")
val COLOR_ERROR = Color.parseColor("#f44336")
const val COLOR_INFO = Color.DKGRAY


enum class IconType { SUCCESS, ERROR, INFO }

private fun Flashbar.Builder.applyIcon(iconType: IconType): Flashbar.Builder {
  return when (iconType) {
    IconType.SUCCESS -> this.successIcon()
    IconType.ERROR -> this.errorIcon()
    IconType.INFO -> this.infoIcon()
  }
}

private fun Activity.showFlashBar(
  msg: Any?,
  iconType: IconType,
  backgroundColor: Int = getColor(R.color.background),
  gravity: Flashbar.Gravity = Flashbar.Gravity.TOP,
  duration: Long = Flashbar.DURATION_SHORT
) {
  when (msg) {
    null -> return
    is Int -> flashbarBuilder(gravity, duration, backgroundColor)
      .applyIcon(iconType)
      .message(msg)
      .showOnUiThread()
    is String -> this.flashbarBuilder(gravity, duration, backgroundColor)
      .applyIcon(iconType)
      .message(msg)
      .showOnUiThread()
    else -> throw IllegalArgumentException("Message must be String or Int resource")
  }
}

@JvmOverloads
fun Activity.flashbarBuilder(
  gravity: Flashbar.Gravity = TOP,
  duration: Long = DURATION_SHORT,
  backgroundColor: Int = resolveAttr(R.attr.colorPrimaryContainer),
  messageColor: Int = resolveAttr(R.attr.colorOnPrimaryContainer)
): Flashbar.Builder {
  return Flashbar.Builder(this)
    .gravity(gravity)
    .duration(duration)
    .backgroundColor(backgroundColor)
    .messageColor(messageColor)
}

fun Activity.flashMessage(msg: String?, type: FlashType) {
  msg ?: return
  when (type) {
    ERROR -> flashError(msg)
    INFO -> flashInfo(msg)
    SUCCESS -> flashSuccess(msg)
  }
}

fun Activity.flashMessage(@StringRes msg: Int, type: FlashType) {
  when (type) {
    ERROR -> flashError(msg)
    INFO -> flashInfo(msg)
    SUCCESS -> flashSuccess(msg)
  }
}

fun Activity.flashSuccess(msg: String?) = showFlashBar(msg, IconType.SUCCESS)
fun Activity.flashError(msg: String?) = showFlashBar(msg, IconType.ERROR)
fun Activity.flashInfo(msg: String?) = showFlashBar(msg, IconType.INFO)

fun Activity.flashSuccess(@StringRes msg: Int) = showFlashBar(msg, IconType.SUCCESS)
fun Activity.flashError(@StringRes msg: Int) = showFlashBar(msg, IconType.ERROR)
fun Activity.flashInfo(@StringRes msg: Int) = showFlashBar(msg, IconType.INFO)

@JvmOverloads
fun <R : Any?> Activity.flashProgress(
  configure: (Flashbar.Builder.() -> Unit)? = null,
  action: (Flashbar) -> R
) : R {
  val builder = flashbarBuilder(gravity = TOP, duration = DURATION_INDEFINITE)
    .showProgress(Flashbar.ProgressPosition.LEFT)

  configure?.invoke(builder)

  val flashbar = builder.build()
  flashbar.show()

  return action(flashbar)
}

fun Flashbar.Builder.showOnUiThread() {
  build().showOnUiThread()
}

fun Flashbar.showOnUiThread() {
  if (Looper.myLooper() == Looper.getMainLooper()) {
    show()
  } else {
    ThreadUtils.runOnUiThread { show() }
  }
}

fun Flashbar.Builder.successIcon(): Flashbar.Builder {
  return withIcon(R.drawable.ic_ok, colorFilter = COLOR_SUCCESS)
}

fun Flashbar.Builder.errorIcon(): Flashbar.Builder {
  return withIcon(R.drawable.ic_error, colorFilter = COLOR_ERROR)
}

fun Flashbar.Builder.infoIcon(): Flashbar.Builder {
  return withIcon(R.drawable.ic_info, colorFilter = COLOR_INFO)
}

fun Flashbar.Builder.withIcon(
  @DrawableRes icon: Int,
  @FloatRange(from = 0.0, to = 1.0) scale: Float = 1.0f,
  @ColorInt colorFilter: Int = -1,
  colorFilterMode: PorterDuff.Mode = SRC_ATOP,
  scaleType: ScaleType = FIT_CENTER
): Flashbar.Builder {
  return showIcon(scale = scale, scaleType = scaleType).icon(icon).also {
    if (colorFilter != -1) {
      iconColorFilter(colorFilter, colorFilterMode)
    }
  }
}