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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CircleGestureDetectorTest {
	private val context: Context = RuntimeEnvironment.getApplication()
	private val screenWidth = 1000
	private val centerX = 500f
	private val centerY = 700f

	@Before
	fun fixScreenWidth() {
		// Deterministic width so MIN_CIRCLE_WIDTH_FRACTION (0.70 -> 700px) is predictable.
		context.resources.displayMetrics.widthPixels = screenWidth
	}

	private fun newDetector(onDetected: () -> Unit) = GestureDetectorHelper.CircleGestureDetector(context, onDetected)

	private fun pointAt(
		radius: Float,
		angleDegrees: Double,
	): Pair<Float, Float> {
		val r = Math.toRadians(angleDegrees)
		return (centerX + radius * cos(r)).toFloat() to (centerY + radius * sin(r)).toFloat()
	}

	/**
	 * Feeds a single-finger stroke tracing [sweepDegrees] of a circle of [radius], as [nMove] move
	 * samples over [durationMs]. If [secondFingerAfterMove] is set, a second pointer goes down after
	 * that many moves (disqualifying the stroke).
	 */
	private fun feedCircle(
		detector: GestureDetectorHelper.CircleGestureDetector,
		radius: Float,
		sweepDegrees: Double,
		nMove: Int = 16,
		durationMs: Long = 500,
		secondFingerAfterMove: Int? = null,
	) {
		detector.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, 0, listOf(pointAt(radius, 0.0))))
		for (i in 0 until nMove) {
			val angle = sweepDegrees * i / (nMove - 1)
			val t = durationMs * (i + 1) / nMove
			val p = pointAt(radius, angle)
			if (secondFingerAfterMove != null && i == secondFingerAfterMove) {
				detector.onTouchEvent(
					event(MotionEvent.ACTION_POINTER_DOWN, 0, t, listOf(p, p.first + 20f to p.second), actionIndex = 1),
				)
			}
			detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, 0, t, listOf(p)))
		}
		detector.onTouchEvent(event(MotionEvent.ACTION_UP, 0, durationMs, listOf(pointAt(radius, sweepDegrees))))
	}

	private fun detect(block: (GestureDetectorHelper.CircleGestureDetector) -> Unit): Boolean {
		var detected = false
		block(newDetector { detected = true })
		return detected
	}

	@Test
	fun `a full-width single-finger circle is detected`() {
		assertThat(detect { feedCircle(it, radius = 400f, sweepDegrees = 360.0) }).isTrue()
	}

	@Test
	fun `a sweep just past the threshold is detected`() {
		assertThat(detect { feedCircle(it, radius = 400f, sweepDegrees = 335.0) }).isTrue()
	}

	@Test
	fun `a sweep below the threshold is rejected`() {
		assertThat(detect { feedCircle(it, radius = 400f, sweepDegrees = 300.0) }).isFalse()
	}

	@Test
	fun `too few points is rejected`() {
		assertThat(detect { feedCircle(it, radius = 400f, sweepDegrees = 360.0, nMove = 8) }).isFalse()
	}

	@Test
	fun `too short a stroke is rejected`() {
		assertThat(
			detect { feedCircle(it, radius = 400f, sweepDegrees = 360.0, durationMs = GestureDetectorHelper.MIN_CIRCLE_DURATION_MS - 1) },
		).isFalse()
	}

	@Test
	fun `a circle narrower than the width threshold is rejected`() {
		// radius 100 -> 200px wide, below 0.70 * 1000 = 700px, even though it is a full rotation.
		assertThat(detect { feedCircle(it, radius = 100f, sweepDegrees = 360.0) }).isFalse()
	}

	@Test
	fun `a second finger disqualifies the stroke`() {
		assertThat(detect { feedCircle(it, radius = 400f, sweepDegrees = 360.0, secondFingerAfterMove = 3) }).isFalse()
	}

	@Test
	fun `a straight full-width drag is rejected`() {
		val detected =
			detect { detector ->
				val y = centerY
				detector.onTouchEvent(event(MotionEvent.ACTION_DOWN, 0, 0, listOf(100f to y)))
				val n = 16
				for (i in 0 until n) {
					val x = 100f + (800f * i / (n - 1))
					detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, 0, 500L * (i + 1) / n, listOf(x to y)))
				}
				detector.onTouchEvent(event(MotionEvent.ACTION_UP, 0, 500, listOf(900f to y)))
			}
		assertThat(detected).isFalse()
	}
}
