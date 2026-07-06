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

package com.itsaky.androidide.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import com.github.mikephil.charting.charts.LineChart
import org.slf4j.LoggerFactory

/**
 * A [LineChart] that guards its [onDraw] against the MPAndroidChart axis-rendering race.
 *
 * MPAndroidChart is not thread-safe: [com.github.mikephil.charting.renderer.AxisRenderer.computeAxisValues]
 * writes `mEntryCount` and reallocates the `mEntries` array in two separate statements. When the view is
 * drawn from more than one thread at once, a reader can observe the new `mEntryCount` while `mEntries` is
 * still the old (shorter) array, throwing an [IndexOutOfBoundsException] from the label renderer.
 *
 * This happens in AndroidIDE because Sentry Session Replay records the screen by drawing the view
 * hierarchy on a background thread, which races the main-thread updates of the memory-usage chart. The
 * chart is a non-critical diagnostic view, so dropping the occasional frame is preferable to crashing the
 * whole IDE. The next `invalidate()` recovers cleanly.
 *
 */
class SafeLineChart : LineChart {

  constructor(context: Context) : super(context)
  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
  constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
  ) : super(context, attrs, defStyleAttr)

  companion object {
    private val log = LoggerFactory.getLogger(SafeLineChart::class.java)
  }

  private var skippedFrames = 0L

  override fun onDraw(canvas: Canvas) {
    try {
      super.onDraw(canvas)
    } catch (e: IndexOutOfBoundsException) {
      // Transient race in MPAndroidChart's axis renderer (see class doc). Skip this frame.
      // Only log occasionally to avoid flooding logcat, since onDraw runs every frame.
      if (skippedFrames++ % 60L == 0L) {
        log.warn("Skipped {} chart frame(s) due to a transient axis-rendering race", skippedFrames, e)
      }
    }
  }
}
