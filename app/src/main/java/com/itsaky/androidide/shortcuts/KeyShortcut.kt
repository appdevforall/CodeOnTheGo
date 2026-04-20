package com.itsaky.androidide.shortcuts

import android.view.KeyEvent

data class KeyShortcut(
	val keyCode: Int,
	val ctrl: Boolean = false,
	val shift: Boolean = false,
	val alt: Boolean = false,
	val keyAction: Int = KeyEvent.ACTION_DOWN,
	val allowRepeat: Boolean = false,
) {
	fun matches(event: KeyEvent): Boolean {
		if (event.action != keyAction) return false
		if (!allowRepeat && event.repeatCount > 0) return false

		return event.keyCode == keyCode &&
			event.isCtrlPressed == ctrl &&
			event.isShiftPressed == shift &&
			event.isAltPressed == alt
	}

	companion object {
		fun ctrl(
			keyCode: Int,
			keyAction: Int = KeyEvent.ACTION_DOWN,
			allowRepeat: Boolean = false,
		) = KeyShortcut(
			keyCode = keyCode,
			ctrl = true,
			keyAction = keyAction,
			allowRepeat = allowRepeat,
		)

		fun ctrlShift(
			keyCode: Int,
			keyAction: Int = KeyEvent.ACTION_DOWN,
			allowRepeat: Boolean = false,
		) = KeyShortcut(
			keyCode = keyCode,
			ctrl = true,
			shift = true,
			keyAction = keyAction,
			allowRepeat = allowRepeat,
		)

		fun ctrlAlt(
			keyCode: Int,
			keyAction: Int = KeyEvent.ACTION_DOWN,
			allowRepeat: Boolean = false,
		) = KeyShortcut(
			keyCode = keyCode,
			ctrl = true,
			alt = true,
			keyAction = keyAction,
			allowRepeat = allowRepeat,
		)

		fun esc(
			keyAction: Int = KeyEvent.ACTION_DOWN,
			allowRepeat: Boolean = false,
		) = KeyShortcut(
			keyCode = KeyEvent.KEYCODE_ESCAPE,
			keyAction = keyAction,
			allowRepeat = allowRepeat,
		)
	}
}
