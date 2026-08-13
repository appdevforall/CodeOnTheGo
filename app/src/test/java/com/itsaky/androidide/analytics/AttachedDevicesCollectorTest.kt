package com.itsaky.androidide.analytics

import android.view.InputDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AttachedDevicesCollectorTest {
	@Test
	fun `external mouse is classified as mouse`() {
		val classes =
			AttachedDevicesCollector.classify(
				sources = InputDevice.SOURCE_MOUSE,
				keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
				isVirtual = false,
				isExternal = true,
			)
		assertThat(classes).containsExactly(AttachedDeviceClass.MOUSE)
	}

	@Test
	fun `external alphabetic keyboard is classified as keyboard`() {
		val classes =
			AttachedDevicesCollector.classify(
				sources = InputDevice.SOURCE_KEYBOARD,
				keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
				isVirtual = false,
				isExternal = true,
			)
		assertThat(classes).containsExactly(AttachedDeviceClass.EXTERNAL_KEYBOARD)
	}

	@Test
	fun `non alphabetic keyboard is not classified`() {
		val classes =
			AttachedDevicesCollector.classify(
				sources = InputDevice.SOURCE_KEYBOARD,
				keyboardType = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC,
				isVirtual = false,
				isExternal = true,
			)
		assertThat(classes).isEmpty()
	}

	@Test
	fun `touchpad stylus and gamepad classes are detected`() {
		assertThat(
			AttachedDevicesCollector.classify(InputDevice.SOURCE_TOUCHPAD, InputDevice.KEYBOARD_TYPE_NONE, false, true),
		).containsExactly(AttachedDeviceClass.TOUCHPAD)
		assertThat(
			AttachedDevicesCollector.classify(InputDevice.SOURCE_STYLUS, InputDevice.KEYBOARD_TYPE_NONE, false, true),
		).containsExactly(AttachedDeviceClass.STYLUS)
		assertThat(
			AttachedDevicesCollector.classify(InputDevice.SOURCE_BLUETOOTH_STYLUS, InputDevice.KEYBOARD_TYPE_NONE, false, true),
		).containsExactly(AttachedDeviceClass.STYLUS)
		assertThat(
			AttachedDevicesCollector.classify(InputDevice.SOURCE_GAMEPAD, InputDevice.KEYBOARD_TYPE_NONE, false, true),
		).containsExactly(AttachedDeviceClass.GAMEPAD)
		assertThat(
			AttachedDevicesCollector.classify(InputDevice.SOURCE_JOYSTICK, InputDevice.KEYBOARD_TYPE_NONE, false, true),
		).containsExactly(AttachedDeviceClass.GAMEPAD)
	}

	@Test
	fun `virtual devices are never classified`() {
		val classes =
			AttachedDevicesCollector.classify(
				sources = InputDevice.SOURCE_KEYBOARD,
				keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
				isVirtual = true,
				isExternal = true,
			)
		assertThat(classes).isEmpty()
	}

	@Test
	fun `internal devices are never classified on api 29 plus`() {
		val classes =
			AttachedDevicesCollector.classify(
				sources = InputDevice.SOURCE_MOUSE,
				keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
				isVirtual = false,
				isExternal = false,
			)
		assertThat(classes).isEmpty()
	}

	@Test
	fun `api 28 fallback excludes stylus capable touchscreens`() {
		val classes =
			AttachedDevicesCollector.classify(
				sources = InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN,
				keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
				isVirtual = false,
				isExternal = null,
			)
		assertThat(classes).isEmpty()
	}

	@Test
	fun `api 28 fallback still detects a mouse`() {
		val classes =
			AttachedDevicesCollector.classify(
				sources = InputDevice.SOURCE_MOUSE,
				keyboardType = InputDevice.KEYBOARD_TYPE_NONE,
				isVirtual = false,
				isExternal = null,
			)
		assertThat(classes).containsExactly(AttachedDeviceClass.MOUSE)
	}

	@Test
	fun `combo keyboard with touchpad yields both classes`() {
		val classes =
			AttachedDevicesCollector.classify(
				sources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_TOUCHPAD,
				keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
				isVirtual = false,
				isExternal = true,
			)
		assertThat(classes).containsExactly(AttachedDeviceClass.EXTERNAL_KEYBOARD, AttachedDeviceClass.TOUCHPAD)
	}
}
