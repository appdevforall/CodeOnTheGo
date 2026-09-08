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
import com.itsaky.androidide.utils.displayTooltipOnLongPress
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

	/**
	 * A control with a real size, which the move cases need: whether a touch is still on the view
	 * is measured against the view's bounds, so an unmeasured one collapses every position onto
	 * the same answer.
	 */
	private fun target(): Button =
		Button(context).apply {
			layout(0, 0, WIDTH, HEIGHT)
			setOnClickListener { clicks++ }
			performOnHold { holds++ }
		}

	private fun send(
		view: View,
		action: Int,
		x: Float = CENTRE_X,
		y: Float = CENTRE_Y,
	) {
		val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
		view.dispatchTouchEvent(event)
		event.recycle()
	}

	/** Runs the main looper forward by [millis] of virtual time. */
	private fun elapse(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

	/**
	 * Runs whatever is already due on the main looper without advancing the clock.
	 *
	 * The click is posted rather than performed inside the touch dispatch, as the framework does
	 * it, so nothing has clicked until the looper turns.
	 */
	private fun drain() = shadowOf(Looper.getMainLooper()).idle()

	@Test
	fun `a press past the platform timeout but short of the hold still clicks`() {
		// The regression the obvious fix introduces, and the reason this class exists. The
		// framework's long press is 400ms and the hold is 800ms; everything between the two would
		// otherwise be dead.
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(ViewConfiguration.getLongPressTimeout() + 100L)
		send(view, MotionEvent.ACTION_UP)
		drain()

		assertThat(clicks).isEqualTo(1)
		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `a quick tap clicks`() {
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(50L)
		send(view, MotionEvent.ACTION_UP)
		drain()

		assertThat(clicks).isEqualTo(1)
		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `a press held past the hold shows help and does not click`() {
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(longPressHelpTimeoutMillis() + 50L)
		send(view, MotionEvent.ACTION_UP)
		drain()

		assertThat(holds).isEqualTo(1)
		assertThat(clicks).isEqualTo(0)
	}

	@Test
	fun `the click is posted, not run inside the touch that ended it`() {
		// View.onTouchEvent posts its click so the pressed state is drawn before the action runs,
		// and these actions open dialogs and re-page the carousel from inside the dispatch of the
		// event that triggered them. Taking the touch over means taking that over too.
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(50L)
		send(view, MotionEvent.ACTION_UP)

		assertThat(clicks).isEqualTo(0)
		drain()
		assertThat(clicks).isEqualTo(1)
	}

	@Test
	fun `a press that rolls but stays on the control still clicks`() {
		// The framework gives up on a press when the finger leaves the view grown by the slop --
		// not when it has travelled slop from where it went down. Measured from the down point
		// instead, an ordinary thumb tap on a large target rolls far enough to cancel its own
		// click without ever leaving the control, and every one of these targets is large: the
		// carousel strip is the full width of the editor.
		val view = target()
		val slop = ViewConfiguration.get(context).scaledTouchSlop

		send(view, MotionEvent.ACTION_DOWN)
		elapse(50L)
		send(view, MotionEvent.ACTION_MOVE, x = CENTRE_X + slop + 10f)
		send(view, MotionEvent.ACTION_UP)
		drain()

		assertThat(clicks).isEqualTo(1)
		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `a press that leaves the control does neither`() {
		val view = target()
		val slop = ViewConfiguration.get(context).scaledTouchSlop

		send(view, MotionEvent.ACTION_DOWN)
		elapse(100L)
		send(view, MotionEvent.ACTION_MOVE, x = WIDTH + slop + 10f)
		elapse(longPressHelpTimeoutMillis())
		send(view, MotionEvent.ACTION_UP)
		drain()

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
	fun `a lengthened touch-and-hold delay is doubled, not ignored`() {
		// Asserting isAtLeast against the live platform value pins nothing: maxOf(x * 2, 800) is
		// at least 800 and at least x for every x by construction, so the whole rule could be
		// deleted and such a test would still pass. Named values, and each of the two terms
		// decides one of them.
		//
		// The delay is exposed as an accessibility setting, and someone who lengthened it meant
		// to -- so the hold has to grow with it rather than staying at the floor.
		assertThat(longPressHelpTimeoutMillis(platformTimeoutMillis = 1_000L)).isEqualTo(2_000L)
	}

	@Test
	fun `a shortened touch-and-hold delay still gets the floor`() {
		// Doubling alone would put help back inside a brisk tap, which is the defect.
		assertThat(longPressHelpTimeoutMillis(platformTimeoutMillis = 100L)).isEqualTo(800L)
	}

	@Test
	fun `the platform default lands on the floor`() {
		// 400ms doubled is exactly the floor, so the two terms agree at the value almost every
		// device reports -- which is why neither can be tested at it.
		assertThat(longPressHelpTimeoutMillis(platformTimeoutMillis = 400L)).isEqualTo(800L)
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

	@Test
	fun `clearing the help cancels a hold already counting down`() {
		// The teardown runs while a finger is down -- the carousel unbinds, the strip is replaced,
		// the sheet is torn down. The timer is on the main thread's queue rather than on the view,
		// so clearing the listeners does not reach it: held in a closure it was unreachable
		// altogether, and the tooltip appeared over a control that had just been unwired.
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(100L)
		view.clearLongPressHelp()
		elapse(longPressHelpTimeoutMillis())

		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `re-wiring with a blank tag takes the previous tag's help away`() {
		// A blank tag says this view offers no help, which has to replace whatever was wired here
		// before. Returning early instead left the previous listeners in place, still timing holds
		// and still swallowing every touch.
		val view = target()
		view.displayTooltipOnLongPress(context, tooltipTag = "")

		send(view, MotionEvent.ACTION_DOWN)
		elapse(longPressHelpTimeoutMillis() + 50L)
		send(view, MotionEvent.ACTION_UP)
		drain()

		assertThat(holds).isEqualTo(0)
		assertThat(view.isLongClickable).isFalse()
	}

	private companion object {
		/** Big enough that a roll of one touch slop is still well inside it. */
		const val WIDTH = 400

		const val HEIGHT = 200

		const val CENTRE_X = WIDTH / 2f

		const val CENTRE_Y = HEIGHT / 2f
	}
}
