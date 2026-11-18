package com.itsaky.androidide.ui

import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat

/**
 * Makes the editor's status line a button while its text asks for a tap, and plain text again
 * when it does not.
 *
 * Plain text the rest of the time, so a tap on the line falls through to the header under it,
 * which expands the bottom sheet. The line's size is left alone: the collapsed header is measured
 * from it, and at a 2x font scale it needs every row it takes.
 *
 * @param statusText the status line.
 * @param actionLabel what a tap does, which TalkBack reads as "double-tap to [actionLabel]".
 *   Ignored without [onTap].
 * @param onTap what a tap does, or null to make the line plain text again.
 */
internal fun bindStatusLineTap(
	statusText: TextView,
	actionLabel: CharSequence?,
	onTap: (() -> Unit)?,
) {
	if (onTap == null) {
		statusText.setOnClickListener(null)
		statusText.isClickable = false
		statusText.isFocusable = false
		ViewCompat.removeAccessibilityAction(statusText, AccessibilityActionCompat.ACTION_CLICK.id)
		return
	}
	statusText.setOnClickListener { onTap() }
	// Focusable so a keyboard or switch user can reach it, not only a finger.
	statusText.isFocusable = true
	// A null command keeps the default click; only the spoken label is replaced.
	ViewCompat.replaceAccessibilityAction(statusText, AccessibilityActionCompat.ACTION_CLICK, actionLabel, null)
}
