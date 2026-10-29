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
import android.widget.HorizontalScrollView
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

	/**
	 * The same control inside a container that delays its children's pressed state.
	 *
	 * A `HorizontalScrollView` because that is the real case: the bottom sheet's output-action
	 * buttons, which this ticket wired for help, sit in one. Not any container -- `ViewGroup`
	 * defaults to true but `FrameLayout` and `LinearLayout` both override it to false, so the
	 * choice here has to be a container that actually scrolls.
	 */
	private fun targetInScrollingContainer(): Button {
		val button = target()
		HorizontalScrollView(context).addView(button)
		button.layout(0, 0, WIDTH, HEIGHT)
		return button
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
	fun `a control with no scrolling ancestor lights up the moment the finger lands`() {
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)

		assertThat(view.isPressed).isTrue()
	}

	@Test
	fun `a control inside a scrolling container waits out the tap timeout first`() {
		val view = targetInScrollingContainer()

		send(view, MotionEvent.ACTION_DOWN)

		// View.onTouchEvent does not light a control up straight away when it can be scrolled:
		// it waits a tap timeout, so a flick that happens to start on a button scrolls without
		// flashing it. Taking the touch over means taking that over too, and this listener did
		// not -- every drag off one of these controls blinked it first.
		assertThat(view.isPressed).isFalse()
	}

	@Test
	fun `a flick off a control in a scrolling container never lights it up`() {
		val view = targetInScrollingContainer()

		send(view, MotionEvent.ACTION_DOWN)
		send(view, MotionEvent.ACTION_MOVE, x = WIDTH * 4f, y = HEIGHT * 4f)
		elapse(ViewConfiguration.getTapTimeout().toLong())

		// The pressed state is on the queue when the finger leaves, so dropping the hold is not
		// enough: the flash arrives after the gesture that cancelled it.
		assertThat(view.isPressed).isFalse()
		assertThat(holds).isEqualTo(0)
	}

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

	@Test
	fun `a control that does not answer taps is not clicked`() {
		// View.onTouchEvent performs a click only for a clickable view, and taking the touch over
		// means taking that test over too. The carousel dims the arrow at either end by clearing
		// isClickable rather than isEnabled -- deliberately, so it still answers a hold -- so
		// without this a tap on the dimmed arrow played the click sound and announced a click for
		// a control the screen reader is being told is unavailable.
		val view = target()
		view.isClickable = false

		send(view, MotionEvent.ACTION_DOWN)
		elapse(50L)
		send(view, MotionEvent.ACTION_UP)
		drain()

		assertThat(clicks).isEqualTo(0)
		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `a control that does not answer taps still answers a hold`() {
		// The other half, and the reason isClickable was chosen over isEnabled in the first place.
		val view = target()
		view.isClickable = false

		send(view, MotionEvent.ACTION_DOWN)
		elapse(longPressHelpTimeoutMillis() + 50L)
		send(view, MotionEvent.ACTION_UP)
		drain()

		assertThat(holds).isEqualTo(1)
		assertThat(clicks).isEqualTo(0)
	}

	@Test
	fun `a second finger gives up the press`() {
		// The carousel undocks on a two-finger tap anywhere in the strip, and one of those fingers
		// lands on a control. Counting it as a press meant the gesture both undocked the strip and
		// paged it, or held long enough to open that button's help over a strip on its way out.
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(50L)
		send(view, MotionEvent.ACTION_POINTER_DOWN)
		elapse(longPressHelpTimeoutMillis())
		send(view, MotionEvent.ACTION_UP)
		drain()

		assertThat(clicks).isEqualTo(0)
		assertThat(holds).isEqualTo(0)
	}

	@Test
	fun `clearing the help takes back a click that has not run yet`() {
		// The click is posted, so there is a turn of the looper between the finger lifting and the
		// action running. A teardown landing in it -- the sheet detaching, the carousel unbinding
		// -- would otherwise still click a control it has just unwired.
		val view = target()

		send(view, MotionEvent.ACTION_DOWN)
		elapse(50L)
		send(view, MotionEvent.ACTION_UP)
		view.clearLongPressHelp()
		drain()

		assertThat(clicks).isEqualTo(0)
	}

	private companion object {
		/** Big enough that a roll of one touch slop is still well inside it. */
		const val WIDTH = 400

		const val HEIGHT = 200

		const val CENTRE_X = WIDTH / 2f

		const val CENTRE_Y = HEIGHT / 2f
	}
}
