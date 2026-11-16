package org.appdevforall.sqlitedebugger

import android.app.Application
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The literals below are Code on the Go's contract: the same values are pinned by
 * DebuggerEndpointContractTest in :app (DebuggerEndpointValidation). Change both together.
 */
@RunWith(RobolectricTestRunner::class)
class EndpointBroadcastTest {
	@Test
	fun `the constants match the receiver in Code on the Go`() {
		assertThat(EndpointBroadcast.ACTION).isEqualTo("com.itsaky.androidide.SQLITE_DEBUGGER_ENDPOINT")
		assertThat(EndpointBroadcast.COGO_PACKAGE).isEqualTo("com.itsaky.androidide")
		assertThat(EndpointBroadcast.EXTRA_TOKEN).isEqualTo("token")
		assertThat(EndpointBroadcast.EXTRA_PORT).isEqualTo("port")
		assertThat(EndpointBroadcast.EXTRA_SENDER).isEqualTo("sender")
	}

	@Test
	fun `send broadcasts the endpoint to Code on the Go with the sender identity`() {
		val app = ApplicationProvider.getApplicationContext<Application>()

		EndpointBroadcast.send(app, "tok-en_1", 5555)

		val intent = shadowOf(app).broadcastIntents.single()
		assertThat(intent.action).isEqualTo("com.itsaky.androidide.SQLITE_DEBUGGER_ENDPOINT")
		assertThat(intent.`package`).isEqualTo("com.itsaky.androidide")
		assertThat(intent.getStringExtra("token")).isEqualTo("tok-en_1")
		assertThat(intent.getIntExtra("port", 0)).isEqualTo(5555)
		@Suppress("DEPRECATION")
		val sender = intent.getParcelableExtra<PendingIntent>("sender")
		assertThat(sender).isNotNull()
		assertThat(sender!!.creatorPackage).isEqualTo(app.packageName)
	}
}
