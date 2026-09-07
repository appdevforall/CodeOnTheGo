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
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.clearLongPressHelp
import com.itsaky.androidide.utils.longPressHelpTimeoutMillis
import com.itsaky.androidide.utils.performOnHold
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * How long a press has to last before help replaces the control (ADFA-5554).
 *
 * The platform fires a long press at 400ms, which is a brisk tap, so the carousel's buttons were
 * answering with a tooltip instead of doing their job. The interesting case is neither the long
 * press nor the short one -- it is the press in between. At 500ms the framework has already
 * decided the gesture is a long press and cancelled the click, so a fix that merely defers the
 * tooltip leaves that press doing nothing whatsoever: no help, and no button either. That is the
 * first test here, and it is why the timing is this code's rather than the framework's.
 *
 * The hold's payload is a lambda rather than a real tooltip because `TooltipManager` reads the
 * docs database from device storage in its static initialiser and cannot be loaded off-device --
 * the same reason the renderer separates deciding a help tag from showing one.
 */
@RunWith(RobolectricTestRunner::class)
class LongPressHelpTimingTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private var holds = 0

	private var clicks = 0

	private fun target(): Button =
		Button(context).apply {
			setOnClickListener { clicks++ }
			performOnHold { holds++ }
		}

	private fun send(
		view: View,
		action: Int,
		x: Float = 0f,
	) {
		val event = MotionEvent.obtain(0L, 0L, action, x, 0f, 0)
		view.dispatchTouchEvent(event)
		event.recycle()
	}

	/** Runs the main looper forward by [millis] of virtual time. */
	private fun elapse(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

	@Test
	fun `a press past the platform timeout but short of the hold still clicks`() {
		// The regression the obvious fix introduces, and the reason this class exists. The
		// framework's long press is 400ms and the hold is 800ms; everything between the two would
		// otherwise be dead.
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(ViewConfiguration.getLongPressTimeout() + 100L)
		send(view, MotionEvent.ACTION_UP)

		assertThat(clicks).isEqualTo(1)
		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `a quick tap clicks`() {
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(50L)
		send(view, MotionEvent.ACTION_UP)

		assertThat(clicks).isEqualTo(1)
		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `a press held past the hold shows help and does not click`() {
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(longPressHelpTimeoutMillis() + 50L)
		send(view, MotionEvent.ACTION_UP)

		assertThat(holds).isEqualTo(1)
		assertThat(clicks).isEqualTo(0)
	}

	@Test
	fun `a press that wanders off the control does neither`() {
		val view = target()
		val slop = ViewConfiguration.get(context).scaledTouchSlop

		send(view, MotionEvent.ACTION_DOWN)
		elapse(100L)
		send(view, MotionEvent.ACTION_MOVE, x = slop + 10f)
		elapse(longPressHelpTimeoutMillis())
		send(view, MotionEvent.ACTION_UP)

		// The framework treats a drag out of a view as neither, so taking the touch over means
		// saying so rather than inventing a third behaviour.
		assertThat(clicks).isEqualTo(0)
		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `a cancelled gesture does neither`() {
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(100L)
		send(view, MotionEvent.ACTION_CANCEL)
		elapse(longPressHelpTimeoutMillis())

		assertThat(clicks).isEqualTo(0)
		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `the hold is longer than the platform's, and never shorter`() {
		// The floor matters: the platform value is exposed as an accessibility "touch and hold
		// delay", and someone who lengthened it meant to.
		assertThat(longPressHelpTimeoutMillis()).isAtLeast(800L)
		assertThat(longPressHelpTimeoutMillis()).isAtLeast(ViewConfiguration.getLongPressTimeout().toLong())
	}

	@Test
	fun `clearing the help stops the timing`() {
		val view = target()
		view.clearLongPressHelp()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(longPressHelpTimeoutMillis() + 50L)
		send(view, MotionEvent.ACTION_UP)

		// Left installed, the listener would go on timing holds -- and swallowing every touch --
		// for help the view no longer offers.
		assertThat(holds).isEqualTo(0)
		assertThat(view.isLongClickable).isFalse()
	}
}
