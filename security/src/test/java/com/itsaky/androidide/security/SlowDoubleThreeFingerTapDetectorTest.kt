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

package com.itsaky.androidide.security

import android.content.Context
import android.view.MotionEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SlowDoubleThreeFingerTapDetectorTest {
	private val context: Context = RuntimeEnvironment.getApplication()

	/** A full three-finger tap whose up-event lands at [upTime] (start/down at [upTime] - 50ms). */
	private fun feedTap(
		detector: GestureDetectorHelper.SlowDoubleThreeFingerTapDetector,
		upTime: Long,
	) {
		val down = upTime - 50
		val p1 = 100f to 100f
		val p2 = 200f to 100f
		val p3 = 300f to 100f
		detector.onTouchEvent(event(MotionEvent.ACTION_DOWN, down, down, listOf(p1)))
		detector.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, down, down + 5, listOf(p1, p2), actionIndex = 1))
		detector.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, down, down + 10, listOf(p1, p2, p3), actionIndex = 2))
		detector.onTouchEvent(event(MotionEvent.ACTION_UP, down, upTime, listOf(p1)))
	}

	@Test
	fun `two taps within the gap window are detected as an exit gesture`() {
		var detected = false
		val detector = GestureDetectorHelper.SlowDoubleThreeFingerTapDetector(context) { detected = true }
		feedTap(detector, upTime = 1_000)
		feedTap(detector, upTime = 1_800) // gap 800ms, in [150, 1600]
		assertThat(detected).isTrue()
	}

	@Test
	fun `second tap too soon does not trigger`() {
		var detected = false
		val detector = GestureDetectorHelper.SlowDoubleThreeFingerTapDetector(context) { detected = true }
		feedTap(detector, upTime = 1_000)
		feedTap(detector, upTime = 1_000 + GestureDetectorHelper.MIN_DOUBLE_TAP_GAP_MS - 1)
		assertThat(detected).isFalse()
	}

	@Test
	fun `second tap too late does not trigger`() {
		var detected = false
		val detector = GestureDetectorHelper.SlowDoubleThreeFingerTapDetector(context) { detected = true }
		feedTap(detector, upTime = 1_000)
		feedTap(detector, upTime = 1_000 + GestureDetectorHelper.MAX_DOUBLE_TAP_GAP_MS + 1)
		assertThat(detected).isFalse()
	}

	@Test
	fun `a late second tap becomes the new first tap and a follow-up completes the gesture`() {
		var detections = 0
		val detector = GestureDetectorHelper.SlowDoubleThreeFingerTapDetector(context) { detections++ }
		feedTap(detector, upTime = 1_000)
		feedTap(detector, upTime = 5_000) // too late: becomes the new first tap
		feedTap(detector, upTime = 5_800) // 800ms after the new first tap -> detected
		assertThat(detections).isEqualTo(1)
	}
}
