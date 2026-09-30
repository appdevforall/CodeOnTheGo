package com.itsaky.androidide.plugins.ai.ui

import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.google.android.material.textfield.TextInputLayout

/**
 * What the reveal control shows in one state: the plugin's own icon and content description.
 *
 * @property icon the end icon's drawable
 * @property description the end icon's content description, which is what tells TalkBack the state
 */
data class RevealToggle(
	@DrawableRes val icon: Int,
	@StringRes val description: Int,
)

/**
 * The reveal control for a plugin's masked credential input.
 *
 * The control is the field's own [TextInputLayout] end icon rather than a loose `ImageButton`,
 * which is what gives it a real touch target wherever the field is shown. Toggle behaviour, masking
 * and accessibility are decided here once, so the AI plugins' credential fields cannot drift apart
 * (ADFA-5491).
 *
 * The toggles' resources resolve against [box]'s context, which carries the plugin's resources,
 * not the host's.
 *
 * @param box the field's own layout, whose end icon becomes the control
 * @param input the masked field
 * @param reveal what the control shows while the secret is masked
 * @param hide what the control shows while the secret stands in clear text
 * @param onLegibleChanged called with true when the secret comes to stand in clear text and false once
 *   it is masked again, so the caller can flag its window secure - which window that is depends on
 *   the screen, not on this control. Not called by [attach], which leaves the secret masked.
 */
@MainThread
class SecretRevealController(
	private val box: TextInputLayout,
	private val input: EditText,
	private val reveal: RevealToggle,
	private val hide: RevealToggle,
	private val onLegibleChanged: (legible: Boolean) -> Unit,
) {
	/** Whether the secret currently stands in clear text. */
	var isRevealed: Boolean = false
		private set(value) {
			field = value
			render()
			report()
		}

	/**
	 * Take over [box]'s end icon and mask the input.
	 *
	 * The drawable is set here rather than in the layout because an end icon declared as
	 * `app:endIconDrawable` draws blank inside the host.
	 */
	fun attach() {
		box.endIconMode = TextInputLayout.END_ICON_CUSTOM
		// Not announced as a toggle: with END_ICON_CUSTOM nothing ever moves the icon's checked
		// state, so TalkBack would read "not checked" over a legible secret. The content
		// description carries the state instead.
		box.isEndIconCheckable = false
		box.setEndIconOnClickListener { isRevealed = !isRevealed }
		render()
	}

	/**
	 * Re-mask the secret and report it illegible.
	 *
	 * Call it when the screen leaves the foreground and when a new secret is loaded, so neither a
	 * screenshot nor the recents thumbnail can catch a revealed credential.
	 */
	fun mask() {
		if (isRevealed) isRevealed = false
	}

	/** Dress the field and its icon for [isRevealed]. */
	private fun render() {
		val revealed = isRevealed
		// Swapping the transformation drops the cursor to the start; put back wherever the user had it.
		val start = input.selectionStart
		val end = input.selectionEnd
		input.transformationMethod =
			if (revealed) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
		if (start >= 0 && end >= 0) input.setSelection(start, end)
		val toggle = if (revealed) hide else reveal
		box.setEndIconDrawable(toggle.icon)
		box.setEndIconContentDescription(toggle.description)
	}

	/** Tell the caller what is now legible; masking only invalidates, so false waits for the redraw. */
	private fun report() {
		if (isRevealed) onLegibleChanged(true) else afterNextDraw { if (!isRevealed) onLegibleChanged(false) }
	}

	/**
	 * Run [action] once the next frame has been drawn, or right away if the input is already gone:
	 * an animation callback runs before that frame's traversal, so a message posted from it lands
	 * after the field has been redrawn.
	 */
	private fun afterNextDraw(action: () -> Unit) {
		if (input.isAttachedToWindow) {
			input.postOnAnimation { input.post(action) }
		} else {
			action()
		}
	}
}
