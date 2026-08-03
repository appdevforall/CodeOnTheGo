
package com.itsaky.androidide.analytics

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import com.itsaky.androidide.preferences.internal.StatPreferences
import com.itsaky.androidide.preferences.internal.TelemetryConsent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnalyticsManagerConsentTest {
	private lateinit var firebaseAnalytics: FirebaseAnalytics

	@Before
	fun setUp() {
		System.setProperty("androidide.test.mode", "true")

		firebaseAnalytics = mockk(relaxed = true)
		mockkStatic("com.google.firebase.analytics.ktx.AnalyticsKt")
		every { Firebase.analytics } returns firebaseAnalytics
		mockkObject(StatPreferences)
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `track call with consent declined keeps collection disabled`() {
		every { StatPreferences.telemetryConsent } returns TelemetryConsent.DECLINED

		AnalyticsManager().trackFeatureUsed("editor")

		verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
		verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
	}

	@Test
	fun `track call with consent unset keeps collection disabled`() {
		every { StatPreferences.telemetryConsent } returns TelemetryConsent.UNSET

		AnalyticsManager().trackFeatureUsed("editor")

		verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
		verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
	}

	@Test
	fun `initialize with consent granted enables collection`() {
		every { StatPreferences.telemetryConsent } returns TelemetryConsent.GRANTED

		AnalyticsManager().initialize()

		verify(atLeast = 1) { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
		verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
	}

	@Test
	fun `initialize re-enables collection on an instance created while consent was unset`() {
		every { StatPreferences.telemetryConsent } returns TelemetryConsent.UNSET
		val manager = AnalyticsManager()
		manager.trackFeatureUsed("editor")
		verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }

		every { StatPreferences.telemetryConsent } returns TelemetryConsent.GRANTED
		manager.initialize()

		verify { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
	}
}
