
package com.itsaky.androidide.analytics

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import io.mockk.every
import io.mockk.mockk
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
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `track call before initialize keeps collection disabled`() {
		AnalyticsManager().trackFeatureUsed("editor")

		verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
		verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
	}

	@Test
	fun `metric call before initialize keeps collection disabled`() {
		AnalyticsManager().trackProjectOpened("/sdcard/project")

		verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
		verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
	}

	@Test
	fun `initialize enables collection`() {
		AnalyticsManager().initialize()

		verify(atLeast = 1) { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
		verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
	}

	@Test
	fun `initialize re-enables collection on an instance that already tracked`() {
		val manager = AnalyticsManager()
		manager.trackFeatureUsed("editor")
		verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }

		manager.initialize()

		verify { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
	}
}
