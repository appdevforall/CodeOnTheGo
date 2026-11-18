package org.appdevforall.sqlitedebugger

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
		assertThat(EndpointBroadcast.ANNOUNCE_ACTION).isEqualTo("org.appdevforall.sqlitedebugger.ANNOUNCE")
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

	// 28 and 33 run both registerReceiver calls: RECEIVER_EXPORTED exists from 33.
	@Test
	@Config(sdk = [28, 33])
	fun `an announce request sends the endpoint again with the same token and port`() {
		val app = ApplicationProvider.getApplicationContext<Application>()
		EndpointBroadcast.sendAndListen(app, "tok-en_1", 5555)
		assertThat(endpointBroadcasts(app)).hasSize(1)

		app.sendBroadcast(Intent("org.appdevforall.sqlitedebugger.ANNOUNCE").setPackage(app.packageName))
		shadowOf(Looper.getMainLooper()).idle()

		val sent = endpointBroadcasts(app)
		assertThat(sent).hasSize(2)
		val again = sent.last()
		assertThat(again.`package`).isEqualTo("com.itsaky.androidide")
		assertThat(again.getStringExtra("token")).isEqualTo("tok-en_1")
		assertThat(again.getIntExtra("port", 0)).isEqualTo(5555)
	}

	private fun endpointBroadcasts(app: Application) =
		shadowOf(app).broadcastIntents.filter { it.action == "com.itsaky.androidide.SQLITE_DEBUGGER_ENDPOINT" }
}
