package com.itsaky.androidide.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.itsaky.androidide.idetooltips.R
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager

/**
 * Shows [tag]'s tooltip (under [category]) anchored to [anchor], or does nothing if [tag] is
 * blank.
 *
 * [playHapticFeedback] defaults to `true` for callers driving this from a mechanism (e.g. a
 * [GestureDetector]-based long-press) that doesn't already get the platform's own long-press
 * haptic. Pass `false` when calling this from a [View.OnLongClickListener] or
 * `AdapterView.OnItemLongClickListener` that returns `true` - the platform already fires the
 * identical feedback for those, and a manual call here would double-buzz.
 */
fun showTooltipIfPresent(
	context: Context,
	anchor: View,
	category: String,
	tag: String,
	playHapticFeedback: Boolean = true,
) {
	if (tag.isNotBlank()) {
		if (playHapticFeedback) {
			anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
		}
		TooltipManager.showTooltip(context, anchor, category, tag)
	}
}

/** Shows [tag]'s IDE-category tooltip anchored to [anchor]. See [showTooltipIfPresent]. */
fun showIdeCategoryTooltipIfPresent(
	context: Context,
	anchor: View,
	tag: String,
	playHapticFeedback: Boolean = true,
) = showTooltipIfPresent(context, anchor, TooltipCategory.CATEGORY_IDE, tag, playHapticFeedback)

/**
 * How long a press has to be held before help appears, in milliseconds.
 *
 * Twice the platform's own long-press timeout, floored at 800ms. The platform default is 400ms,
 * which is a brisk tap, so help was appearing instead of the control activating (ADFA-5554).
 *
 * Never *shorter* than the platform's value: that setting is exposed as an accessibility
 * "touch and hold delay", and someone who has lengthened it did so deliberately.
 *
 * [platformTimeoutMillis] is a parameter only so a test can name one. Asserted against the live
 * value, both the doubling and the floor are implied by the expression itself and a test of them
 * pins nothing.
 */
fun longPressHelpTimeoutMillis(platformTimeoutMillis: Long = ViewConfiguration.getLongPressTimeout().toLong()): Long =
	maxOf(platformTimeoutMillis * PLATFORM_TIMEOUT_MULTIPLE, MIN_HOLD_MILLIS)

/** The shortest hold that will ever be asked for, whatever the platform's own timeout. */
private const val MIN_HOLD_MILLIS = 800L

/** How much longer than the platform's long press a hold is, above the floor. */
private const val PLATFORM_TIMEOUT_MULTIPLE = 2L

/**
 * Shows [tooltipTag]'s tooltip (under [tooltipCategory]) when this view is held for
 * [holdMillis], and lets a shorter press through as an ordinary click.
 *
 * The timing is this function's rather than the framework's, and that is the whole point.
 * `setOnLongClickListener` fires at [ViewConfiguration.getLongPressTimeout] -- 400ms by default --
 * and returning `true` from it sets `mHasPerformedLongPress`, which cancels the click. So simply
 * deferring the tooltip would leave a 500ms press doing nothing at all: no help, and no button
 * press either. Instead the touch is taken over outright, and the click is performed here only
 * when no tooltip was shown.
 *
 * The long-click listener stays installed for accessibility. Touch never reaches
 * [View.onTouchEvent], so the framework cannot fire it from a finger; TalkBack's own long-press
 * calls [View.performLongClick] directly, and that path shows help immediately, as it should --
 * it is already a deliberate gesture.
 *
 * On a [android.view.ViewGroup] this only sees touches its children did not take, which is what
 * makes it safe to install on a container for the gaps between its controls.
 */
fun View.displayTooltipOnLongPress(
	context: Context,
	tooltipTag: String,
	tooltipCategory: String = TooltipCategory.CATEGORY_IDE,
	holdMillis: Long = longPressHelpTimeoutMillis(),
) {
	if (tooltipTag.isBlank()) {
		// Not a no-op. This call replaces whatever help was wired here before, and a blank tag
		// says there is none now; returning early would leave the previous tag's listeners
		// answering holds -- and swallowing every touch -- for help this view no longer offers.
		clearLongPressHelp()
		return
	}

	setOnLongClickListener {
		showTooltipIfPresent(context, this, tooltipCategory, tooltipTag, playHapticFeedback = false)
		true
	}

	// Haptic feedback on, unlike the long-click path above: nothing else buzzes here, because the
	// framework's own long press never runs for this view.
	performOnHold(holdMillis) { showTooltipIfPresent(context, this, tooltipCategory, tooltipTag) }
}

/**
 * Runs [onHold] when this view is held for [holdMillis], and lets a shorter press through as an
 * ordinary click.
 *
 * Separated from [displayTooltipOnLongPress] so the timing can be tested: `TooltipManager` reads
 * the docs database from device storage in its static initialiser and cannot be loaded off-device,
 * so a test that showed a real tooltip could not run at all.
 */
fun View.performOnHold(
	holdMillis: Long = longPressHelpTimeoutMillis(),
	onHold: () -> Unit,
) {
	// The hold only. [displayTooltipOnLongPress] installs its long-click listener first and this
	// second, so clearing that here would take away what the caller had just wired.
	clearOnHold()
	val listener = HoldTouchListener(this, holdMillis, onHold)
	// Tagged so [clearLongPressHelp] can tell that the listener it is about to remove is this one.
	setTag(R.id.tooltip_hold_listener, listener)
	setOnTouchListener(listener)
}

/**
 * Stops this view answering a hold or a long press with help, and cancels one already timing.
 *
 * `setOnLongClickListener(null)` alone is not enough: [View.setOnLongClickListener] sets
 * `isLongClickable` when it installs a listener but does not unset it when the listener is
 * removed, so the view goes on consuming long presses -- and showing the system's own
 * "performLongClick" feedback -- for help it no longer offers. The hold half is [clearOnHold].
 */
fun View.clearLongPressHelp() {
	setOnLongClickListener(null)
	isLongClickable = false
	clearOnHold()
}

/**
 * Stops this view timing a hold, and cancels one already counting down.
 *
 * The half of [clearLongPressHelp] that undoes [performOnHold], separately callable because a
 * caller that installed only a hold should be able to undo only a hold.
 *
 * The touch listener is removed only when [performOnHold] is the one that installed it, which the
 * tag says. Five of the six views [clearLongPressHelp] is called on are wired through the
 * framework's long click and never had one, and a blanket `setOnTouchListener(null)` there would
 * silently take away an unrelated listener the next contributor adds.
 */
fun View.clearOnHold() {
	val hold = getTag(R.id.tooltip_hold_listener) as? HoldTouchListener ?: return
	// A hold already counting down outlives its listener: the timer is on the main thread's
	// queue, not on the view. Left running it fires against a control that has just been unwired
	// -- or a carousel page that has just been replaced (ADFA-5554).
	hold.cancel()
	setTag(R.id.tooltip_hold_listener, null)
	setOnTouchListener(null)
}

/**
 * Times a hold on [view] and stands in for the framework's own press handling while it does.
 *
 * A class rather than a lambda so the pending hold can be cancelled from outside the touch stream;
 * captured in a closure it was unreachable, and a teardown could only stop the *next* hold.
 */
private class HoldTouchListener(
	private val view: View,
	private val holdMillis: Long,
	private val onHold: () -> Unit,
) : View.OnTouchListener {
	// An explicit handler, not View.postDelayed: a view not attached to a window parks posted work
	// in its HandlerActionQueue and only runs it on attach, so the hold would never time out.
	private val handler = Handler(Looper.getMainLooper())

	private val slop = ViewConfiguration.get(view.context).scaledTouchSlop

	private var held = false

	private var holding = false

	private val fire =
		Runnable {
			held = true
			view.isPressed = false
			onHold()
		}

	fun cancel() {
		holding = false
		handler.removeCallbacks(fire)
		view.isPressed = false
	}

	/**
	 * Whether a touch at ([x], [y]) is still on the view, by the framework's rule.
	 *
	 * `View.onTouchEvent` gives up on a press when `!pointInView(x, y, mTouchSlop)` -- when the
	 * finger leaves the view's bounds grown by the slop, not when it has travelled slop from
	 * where it went down. Measured from the down point instead, an ordinary thumb tap on a large
	 * target rolls far enough to cancel its own click without ever leaving the control, and the
	 * carousel strip is the full width of the editor.
	 */
	private fun isInside(
		x: Float,
		y: Float,
	): Boolean = x >= -slop && y >= -slop && x < view.width + slop && y < view.height + slop

	override fun onTouch(
		v: View,
		event: MotionEvent,
	): Boolean {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				held = false
				holding = true
				v.isPressed = true
				// The framework starts the ripple from the touch point. Without this every ripple
				// on these controls begins at the centre of the drawable instead.
				v.drawableHotspotChanged(event.x, event.y)
				handler.postDelayed(fire, holdMillis)
			}

			MotionEvent.ACTION_MOVE -> {
				if (holding && !isInside(event.x, event.y)) {
					// Left the control: neither a click nor help, which is how the framework
					// treats a drag out of a view. Taking the touch over means saying so.
					holding = false
					handler.removeCallbacks(fire)
					v.isPressed = false
				}
			}

			MotionEvent.ACTION_UP -> {
				handler.removeCallbacks(fire)
				v.isPressed = false
				// The click belongs to a press that stayed put and did not become a hold.
				if (holding && !held) {
					// Posted rather than called here, as View.onTouchEvent does, so the pressed
					// state is drawn before the action runs -- these open dialogs and re-page the
					// carousel from inside the dispatch of the event that triggered them.
					//
					// Through this handler and not View.post, which parks work on an unattached
					// view's HandlerActionQueue and returns true having run nothing. Same trap as
					// the hold timer, one method along.
					handler.post { v.performClick() }
				}
				holding = false
			}

			MotionEvent.ACTION_CANCEL -> {
				holding = false
				handler.removeCallbacks(fire)
				v.isPressed = false
			}
		}
		return true
	}
}
