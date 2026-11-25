package com.itsaky.androidide.sqlitedebugger

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DebuggerEndpointAnnounceTest {
	@Test
	fun `the request goes only to the paired app`() {
		val app = ApplicationProvider.getApplicationContext<Application>()

		DebuggerEndpointAnnounce.send(app, "com.example")

		val intent = shadowOf(app).broadcastIntents.single()
		assertThat(intent.action).isEqualTo("org.appdevforall.sqlitedebugger.ANNOUNCE")
		assertThat(intent.`package`).isEqualTo("com.example")
	}
}
