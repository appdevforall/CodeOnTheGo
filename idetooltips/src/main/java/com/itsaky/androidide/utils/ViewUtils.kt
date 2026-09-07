package com.itsaky.androidide.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import kotlin.math.abs

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
 */
fun longPressHelpTimeoutMillis(): Long = maxOf(ViewConfiguration.getLongPressTimeout() * 2L, 800L)

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
	val slop = ViewConfiguration.get(context).scaledTouchSlop
	// An explicit handler, not View.postDelayed: a view not attached to a window parks posted work
	// in its HandlerActionQueue and only runs it on attach, so the hold would never time out.
	val handler = Handler(Looper.getMainLooper())
	var held = false
	var holding = false
	var downX = 0f
	var downY = 0f
	val fire =
		Runnable {
			held = true
			isPressed = false
			onHold()
		}

	setOnTouchListener { view, event ->
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				held = false
				holding = true
				downX = event.x
				downY = event.y
				view.isPressed = true
				handler.postDelayed(fire, holdMillis)
			}

			MotionEvent.ACTION_MOVE -> {
				if (holding && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
					// Wandered off the control: neither a click nor help, which is how the
					// framework treats a drag out of a view. Taking the touch over means saying so.
					holding = false
					handler.removeCallbacks(fire)
					view.isPressed = false
				}
			}

			MotionEvent.ACTION_UP -> {
				handler.removeCallbacks(fire)
				view.isPressed = false
				// The click belongs to a press that stayed put and did not become a hold.
				if (holding && !held) {
					view.performClick()
				}
				holding = false
			}

			MotionEvent.ACTION_CANCEL -> {
				holding = false
				handler.removeCallbacks(fire)
				view.isPressed = false
			}
		}
		true
	}
}
