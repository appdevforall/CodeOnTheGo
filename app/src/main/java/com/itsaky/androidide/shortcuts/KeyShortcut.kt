package com.itsaky.androidide.shortcuts

import android.view.KeyEvent

data class KeyShortcut(
	val keyCode: Int,
	val ctrl: Boolean = false,
	val shift: Boolean = false,
	val alt: Boolean = false,
) {
	fun matches(event: KeyEvent): Boolean {
		if (event.action != KeyEvent.ACTION_DOWN) return false
		if (event.repeatCount > 0) return false

		return event.keyCode == keyCode &&
			event.isCtrlPressed == ctrl &&
			event.isShiftPressed == shift &&
			event.isAltPressed == alt
	}

	companion object {
		fun ctrl(keyCode: Int) = KeyShortcut(
			keyCode = keyCode,
			ctrl = true,
		)

		fun ctrlShift(keyCode: Int) = KeyShortcut(
			keyCode = keyCode,
			ctrl = true,
			shift = true,
		)

		fun ctrlAlt(keyCode: Int) = KeyShortcut(
			keyCode = keyCode,
			ctrl = true,
			alt = true,
		)

		fun esc() = KeyShortcut(
			keyCode = KeyEvent.KEYCODE_ESCAPE,
		)
	}
}
