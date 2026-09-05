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
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the two-finger tap that undocks the metrics carousel (ADFA-5486).
 *
 * The gesture cannot be injected on an unrooted device -- `adb input` has no multi-touch and
 * `sendevent` needs root -- so the recogniser is exercised here with the same MotionEvents it would
 * receive, including the pinch it must not mistake for a tap.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsCarouselLayoutTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private fun layout() = MetricsCarouselLayout(context)

	private var downTime = 0L

	private fun event(
		action: Int,
		vararg points: Pair<Float, Float>,
		eventTime: Long = downTime,
	): MotionEvent {
		val properties =
			Array(points.size) { index ->
				MotionEvent.PointerProperties().apply {
					id = index
					toolType = MotionEvent.TOOL_TYPE_FINGER
				}
			}
		val coords =
			Array(points.size) { index ->
				MotionEvent.PointerCoords().apply {
					x = points[index].first
					y = points[index].second
					pressure = 1f
					size = 1f
				}
			}
		return MotionEvent.obtain(
			downTime,
			eventTime,
			action,
			points.size,
			properties,
			coords,
			0,
			0,
			1f,
			1f,
			0,
			0,
			0,
			0,
		)
	}

	private fun pointerDown(index: Int): Int = MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

	private fun pointerUp(index: Int): Int = MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

	/**
	 * Drives one gesture through the layout the way the framework does.
	 *
	 * Via dispatchTouchEvent, not onInterceptTouchEvent: these tests passed against a recogniser
	 * that never fired on a device, because ViewPager2 stops the parent's onInterceptTouchEvent
	 * being called the moment a second pointer lands. Calling the method under test directly proved
	 * the logic and not the wiring.
	 */
	private fun MetricsCarouselLayout.dispatch(vararg events: MotionEvent) {
		events.forEach { event ->
			dispatchTouchEvent(event)
			event.recycle()
		}
	}

	@Test
	fun `a two-finger tap fires the callback`() {
		var taps = 0
		val layout = layout().apply { onTwoFingerTap = { taps++ } }
		downTime = SystemClock.uptimeMillis()

		layout.dispatch(
			event(MotionEvent.ACTION_DOWN, 500f to 450f),
			event(pointerDown(1), 500f to 450f, 900f to 450f),
			event(pointerUp(1), 500f to 450f, 900f to 450f, eventTime = downTime + 40L),
			event(MotionEvent.ACTION_UP, 500f to 450f, eventTime = downTime + 50L),
		)

		assertThat(taps).isEqualTo(1)
	}

	@Test
	fun `a single-finger tap does not fire it`() {
		var taps = 0
		val layout = layout().apply { onTwoFingerTap = { taps++ } }
		downTime = SystemClock.uptimeMillis()

		layout.dispatch(
			event(MotionEvent.ACTION_DOWN, 500f to 450f),
			event(MotionEvent.ACTION_UP, 500f to 450f, eventTime = downTime + 40L),
		)

		assertThat(taps).isEqualTo(0)
	}

	@Test
	fun `a pinch is not a tap`() {
		// The carousel is also meant to pinch-to-zoom, so movement has to disqualify the tap.
		var taps = 0
		val layout = layout().apply { onTwoFingerTap = { taps++ } }
		downTime = SystemClock.uptimeMillis()
		val travel = ViewConfiguration.get(context).scaledTouchSlop * 4f

		layout.dispatch(
			event(MotionEvent.ACTION_DOWN, 500f to 450f),
			event(pointerDown(1), 500f to 450f, 900f to 450f),
			event(MotionEvent.ACTION_MOVE, 500f - travel to 450f, 900f + travel to 450f, eventTime = downTime + 20L),
			event(pointerUp(1), 500f - travel to 450f, 900f + travel to 450f, eventTime = downTime + 40L),
		)

		assertThat(taps).isEqualTo(0)
	}

	@Test
	fun `a long two-finger hold is not a tap`() {
		var taps = 0
		val layout = layout().apply { onTwoFingerTap = { taps++ } }
		downTime = SystemClock.uptimeMillis()
		val tooLong = ViewConfiguration.getTapTimeout().toLong() * 5

		layout.dispatch(
			event(MotionEvent.ACTION_DOWN, 500f to 450f),
			event(pointerDown(1), 500f to 450f, 900f to 450f),
			event(pointerUp(1), 500f to 450f, 900f to 450f, eventTime = downTime + tooLong),
		)

		assertThat(taps).isEqualTo(0)
	}

	@Test
	fun `three fingers are not a two-finger tap`() {
		var taps = 0
		val layout = layout().apply { onTwoFingerTap = { taps++ } }
		downTime = SystemClock.uptimeMillis()

		layout.dispatch(
			event(MotionEvent.ACTION_DOWN, 500f to 450f),
			event(pointerDown(1), 500f to 450f, 900f to 450f),
			event(pointerDown(2), 500f to 450f, 900f to 450f, 700f to 600f),
			event(pointerUp(2), 500f to 450f, 900f to 450f, 700f to 600f, eventTime = downTime + 40L),
		)

		assertThat(taps).isEqualTo(0)
	}
}
