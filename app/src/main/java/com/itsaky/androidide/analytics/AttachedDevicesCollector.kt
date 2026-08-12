package com.itsaky.androidide.analytics

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.InputDevice

enum class AttachedDeviceClass {
	MOUSE,
	EXTERNAL_KEYBOARD,
	TOUCHPAD,
	STYLUS,
	GAMEPAD,
}

data class AttachedDevicesSnapshot(
	val mouseCount: Int,
	val externalKeyboardCount: Int,
	val touchpadCount: Int,
	val stylusCount: Int,
	val gamepadCount: Int,
	val externalDisplayCount: Int,
)

object AttachedDevicesCollector {
	private val DEVICE_CLASS_BY_SOURCE =
		mapOf(
			InputDevice.SOURCE_MOUSE to AttachedDeviceClass.MOUSE,
			InputDevice.SOURCE_TOUCHPAD to AttachedDeviceClass.TOUCHPAD,
			InputDevice.SOURCE_STYLUS to AttachedDeviceClass.STYLUS,
			InputDevice.SOURCE_BLUETOOTH_STYLUS to AttachedDeviceClass.STYLUS,
			InputDevice.SOURCE_GAMEPAD to AttachedDeviceClass.GAMEPAD,
			InputDevice.SOURCE_JOYSTICK to AttachedDeviceClass.GAMEPAD,
		)

	fun classify(
		sources: Int,
		keyboardType: Int,
		isVirtual: Boolean,
		isExternal: Boolean?,
	): Set<AttachedDeviceClass> {
		if (isVirtual || isExternal == false) {
			return emptySet()
		}
		if (isExternal == null && sources.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) {
			return emptySet()
		}
		val matched =
			DEVICE_CLASS_BY_SOURCE
				.filterKeys { sources.supportsSource(it) }
				.values
				.toSet()
		return if (sources.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
			keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
		) {
			matched + AttachedDeviceClass.EXTERNAL_KEYBOARD
		} else {
			matched
		}
	}

	fun collect(context: Context): AttachedDevicesSnapshot {
		val classCounts =
			runCatching { countInputDeviceClasses() }.getOrDefault(emptyMap())
		val externalDisplays =
			runCatching { countExternalDisplays(context) }.getOrDefault(0)
		return AttachedDevicesSnapshot(
			mouseCount = classCounts[AttachedDeviceClass.MOUSE] ?: 0,
			externalKeyboardCount = classCounts[AttachedDeviceClass.EXTERNAL_KEYBOARD] ?: 0,
			touchpadCount = classCounts[AttachedDeviceClass.TOUCHPAD] ?: 0,
			stylusCount = classCounts[AttachedDeviceClass.STYLUS] ?: 0,
			gamepadCount = classCounts[AttachedDeviceClass.GAMEPAD] ?: 0,
			externalDisplayCount = externalDisplays,
		)
	}

	private fun countInputDeviceClasses(): Map<AttachedDeviceClass, Int> =
		InputDevice
			.getDeviceIds()
			.map { InputDevice.getDevice(it) }
			.filterNotNull()
			.flatMap { device ->
				classify(
					sources = device.sources,
					keyboardType = device.keyboardType,
					isVirtual = device.isVirtual,
					isExternal =
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							device.isExternal
						} else {
							null
						},
				)
			}.groupingBy { it }
			.eachCount()

	private fun countExternalDisplays(context: Context): Int =
		requireNotNull(context.getSystemService(DisplayManager::class.java))
			.displays
			.count { it.displayId != Display.DEFAULT_DISPLAY }

	private fun Int.supportsSource(source: Int): Boolean = (this and source) == source
}
