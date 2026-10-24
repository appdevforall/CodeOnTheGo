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
class ThreeFingerTapDetectorTest {
	private val context: Context = RuntimeEnvironment.getApplication()

	/** Feeds a complete three-finger tap: first pointer down, two more down, then up at [upTime]. */
	private fun feedThreeFingerTap(
		detector: GestureDetectorHelper.ThreeFingerTapDetector,
		downTime: Long,
		upTime: Long,
		moveToForFirstPointer: Pair<Float, Float>? = null,
	) {
		val p1 = 100f to 100f
		val p2 = 200f to 100f
		val p3 = 300f to 100f
		detector.onTouchEvent(event(MotionEvent.ACTION_DOWN, downTime, downTime, listOf(p1)))
		detector.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, downTime, downTime + 5, listOf(p1, p2), actionIndex = 1))
		detector.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, downTime, downTime + 10, listOf(p1, p2, p3), actionIndex = 2))
		if (moveToForFirstPointer != null) {
			detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, downTime, downTime + 15, listOf(moveToForFirstPointer, p2, p3)))
		}
		detector.onTouchEvent(event(MotionEvent.ACTION_UP, downTime, upTime, listOf(p1)))
	}

	@Test
	fun `three-finger tap within duration and drift bounds is detected`() {
		var detectedAt = -1L
		val detector = GestureDetectorHelper.ThreeFingerTapDetector(context) { detectedAt = it }
		feedThreeFingerTap(detector, downTime = 1_000, upTime = 1_300) // 300ms
		assertThat(detectedAt).isEqualTo(1_300L)
	}

	@Test
	fun `tap at exactly the max duration is still detected`() {
		var detected = false
		val detector = GestureDetectorHelper.ThreeFingerTapDetector(context) { detected = true }
		feedThreeFingerTap(detector, downTime = 0, upTime = GestureDetectorHelper.MAX_TAP_DURATION_MS)
		assertThat(detected).isTrue()
	}

	@Test
	fun `tap longer than the max duration is rejected`() {
		var detected = false
		val detector = GestureDetectorHelper.ThreeFingerTapDetector(context) { detected = true }
		feedThreeFingerTap(detector, downTime = 0, upTime = GestureDetectorHelper.MAX_TAP_DURATION_MS + 1)
		assertThat(detected).isFalse()
	}

	@Test
	fun `tap with excessive pointer drift is rejected`() {
		var detected = false
		val detector = GestureDetectorHelper.ThreeFingerTapDetector(context) { detected = true }
		// density is 1.0 under Robolectric default, so drift > MAX_TAP_DRIFT_DP px trips the guard.
		val driftedX = 100f + GestureDetectorHelper.MAX_TAP_DRIFT_DP + 10f
		feedThreeFingerTap(detector, downTime = 0, upTime = 300, moveToForFirstPointer = driftedX to 100f)
		assertThat(detected).isFalse()
	}

	@Test
	fun `fewer than three pointers is rejected`() {
		var detected = false
		val detector = GestureDetectorHelper.ThreeFingerTapDetector(context) { detected = true }
		detector.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, 0, listOf(100f to 100f)))
		detector.onTouchEvent(event(MotionEvent.ACTION_UP, 0, 100, listOf(100f to 100f)))
		assertThat(detected).isFalse()
	}
}
