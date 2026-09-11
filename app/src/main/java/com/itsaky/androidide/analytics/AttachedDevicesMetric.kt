package com.itsaky.androidide.analytics

import android.os.Bundle

class AttachedDevicesMetric(
	private val snapshot: AttachedDevicesSnapshot,
) : Metric {
	override val eventName = "attached_devices"

	override fun asBundle(): Bundle =
		Bundle().apply {
			putLong("mouse_count", snapshot.mouseCount.toLong())
			putLong("external_keyboard_count", snapshot.externalKeyboardCount.toLong())
			putLong("touchpad_count", snapshot.touchpadCount.toLong())
			putLong("stylus_count", snapshot.stylusCount.toLong())
			putLong("gamepad_count", snapshot.gamepadCount.toLong())
			putLong("external_display_count", snapshot.externalDisplayCount.toLong())
		}
}
