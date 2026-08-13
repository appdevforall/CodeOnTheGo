package com.itsaky.androidide.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachedDevicesMetricTest {
	@Test
	fun `bundle carries every device count under its exact param name`() {
		val metric =
			AttachedDevicesMetric(
				AttachedDevicesSnapshot(
					mouseCount = 1,
					externalKeyboardCount = 2,
					touchpadCount = 3,
					stylusCount = 4,
					gamepadCount = 5,
					externalDisplayCount = 6,
				),
			)

		val bundle = metric.asBundle()

		assertThat(metric.eventName).isEqualTo("attached_devices")
		assertThat(bundle.getLong("mouse_count")).isEqualTo(1L)
		assertThat(bundle.getLong("external_keyboard_count")).isEqualTo(2L)
		assertThat(bundle.getLong("touchpad_count")).isEqualTo(3L)
		assertThat(bundle.getLong("stylus_count")).isEqualTo(4L)
		assertThat(bundle.getLong("gamepad_count")).isEqualTo(5L)
		assertThat(bundle.getLong("external_display_count")).isEqualTo(6L)
		assertThat(bundle.keySet()).hasSize(6)
	}
}
