

package org.appdevforall.codeonthego.preferences

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.app.BaseApplication
import org.appdevforall.codeonthego.preferences.internal.StatPreferences
import org.appdevforall.codeonthego.preferences.internal.TelemetryConsent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatPreferencesTest {
	@Before
	fun setUp() {
		System.setProperty("androidide.test.mode", "true")
	}

	@Test
	fun `consent defaults to UNSET when nothing is stored`() {
		assertThat(StatPreferences.telemetryConsent).isEqualTo(TelemetryConsent.UNSET)
	}

	@Test
	fun `GRANTED round-trips through device-protected storage`() {
		StatPreferences.telemetryConsent = TelemetryConsent.GRANTED
		assertThat(StatPreferences.telemetryConsent).isEqualTo(TelemetryConsent.GRANTED)
	}

	@Test
	fun `DECLINED round-trips through device-protected storage`() {
		StatPreferences.telemetryConsent = TelemetryConsent.DECLINED
		assertThat(StatPreferences.telemetryConsent).isEqualTo(TelemetryConsent.DECLINED)
	}

	@Test
	fun `corrupt stored value degrades to UNSET`() {
		BaseApplication.baseInstance
			.createDeviceProtectedStorageContext()
			.getSharedPreferences("ide.stats", Context.MODE_PRIVATE)
			.edit()
			.putString(StatPreferences.TELEMETRY_CONSENT, "garbage")
			.commit()

		assertThat(StatPreferences.telemetryConsent).isEqualTo(TelemetryConsent.UNSET)
	}
}
