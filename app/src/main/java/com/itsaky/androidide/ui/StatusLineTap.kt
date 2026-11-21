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

/**
 * Keeps the status line's tap with the text it was bound for. [bind] remembers the text on the line,
 * and [onTextWritten] drops the tap when other text replaces it, whoever wrote that text (a refused
 * Quick Build's tap must not stay on the indexing line the view model writes next). The same text
 * written again, as the status observer does after the refusal, keeps the tap.
 */
internal class StatusLineTapBinding(
	private val statusText: TextView,
) {
	private var boundText: String? = null

	fun bind(
		actionLabel: CharSequence?,
		onTap: (() -> Unit)?,
	) {
		boundText = if (onTap == null) null else statusText.text?.toString().orEmpty()
		bindStatusLineTap(statusText, actionLabel, onTap)
	}

	fun onTextWritten(text: CharSequence) {
		val bound = boundText ?: return
		if (text.toString() != bound) bind(null, null)
	}
}
