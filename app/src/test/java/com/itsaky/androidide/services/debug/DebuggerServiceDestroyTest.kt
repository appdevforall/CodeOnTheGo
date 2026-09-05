package com.itsaky.androidide.services.debug

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.IDEApplication
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADFA-2648: the debug toolbar stayed on screen over other apps because a destroyed [DebuggerService]
 * was still reachable and still able to add its overlay window, while the collector that hides it on a
 * foreground change had died with the service's scope.
 *
 * The activity-side half of the fix (dropping the reference on unbind, since `unbindService` never
 * fires `onServiceDisconnected`) needs the whole editor activity to exercise and is covered on-device.
 * This pins the service-side half: after destroy, the instance holds no overlay manager, so a stale
 * caller's [DebuggerService.showOverlay] is a no-op rather than an orphaned window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DebuggerServiceDestroyTest {
	private var previousTestMode: String? = null

	@Before
	fun setUp() {
		previousTestMode = System.setProperty("androidide.test.mode", "true")
	}

	@After
	fun tearDown() {
		val previous = previousTestMode
		if (previous == null) System.clearProperty("androidide.test.mode") else System.setProperty("androidide.test.mode", previous)
	}

	@Test
	fun givenABoundService_whenItIsDestroyed_thenItCanNoLongerShowTheOverlay() {
		val application = ApplicationProvider.getApplicationContext<IDEApplication>()
		val intent = Intent(application, DebuggerService::class.java).putExtra(DebuggerService.EXTRA_DISPLAY_ID, -1)
		val controller = Robolectric.buildService(DebuggerService::class.java, intent).create().bind()
		val service = controller.get()
		assertThat(service.hasOverlayManager).isTrue()

		controller.destroy()

		assertThat(service.hasOverlayManager).isFalse()
		// A stale reference calling this after destroy is exactly the reported path; it must do nothing.
		service.showOverlay()
	}
}
