
package com.itsaky.androidide.app

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.preferences.internal.TelemetryConsent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryConsentMigrationTest {
	@Before
	fun setUp() {
		System.setProperty("androidide.test.mode", "true")
	}

	@Test
	fun `unset consent with legacy acceptance migrates`() {
		assertThat(
			DeviceProtectedApplicationLoader.shouldMigrateLegacyConsent(
				currentConsent = TelemetryConsent.UNSET,
				legacyDisclosureShown = true,
			),
		).isTrue()
	}

	@Test
	fun `unset consent without legacy acceptance does not migrate`() {
		assertThat(
			DeviceProtectedApplicationLoader.shouldMigrateLegacyConsent(
				currentConsent = TelemetryConsent.UNSET,
				legacyDisclosureShown = false,
			),
		).isFalse()
	}

	@Test
	fun `granted consent never re-migrates`() {
		assertThat(
			DeviceProtectedApplicationLoader.shouldMigrateLegacyConsent(
				currentConsent = TelemetryConsent.GRANTED,
				legacyDisclosureShown = true,
			),
		).isFalse()
	}

	@Test
	fun `declined consent is never overridden by legacy acceptance`() {
		assertThat(
			DeviceProtectedApplicationLoader.shouldMigrateLegacyConsent(
				currentConsent = TelemetryConsent.DECLINED,
				legacyDisclosureShown = true,
			),
		).isFalse()
	}
}
