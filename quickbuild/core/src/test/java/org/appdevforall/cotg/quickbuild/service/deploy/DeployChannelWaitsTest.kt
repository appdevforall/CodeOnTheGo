@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.service.deploy

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The real [DeployChannel]'s two restart-path waits, against a real
 * [ProxyAppConnections] - the executor's own suite only ever sees a fake channel, which
 * is how a disconnect that reported itself as a timeout shipped and made every
 * restart deploy fall back to a rebaseline on device (2026-07-22 QA walk).
 */
class DeployChannelWaitsTest {
	private val connections = ProxyAppConnections()
	private val channel = DeployChannel(connections)

	/** Never called: the waits only read the connection StateFlow. */
	private val target =
		object : IQuickBuildTarget {
			override fun asBinder(): IBinder? = null

			override fun onPayload(
				generation: Long,
				dexPayload: ParcelFileDescriptor?,
				resourcesPayload: ParcelFileDescriptor?,
				assetsPayload: ParcelFileDescriptor?,
				metadataJson: String?,
			) = Unit

			override fun onBuildStatus(statusJson: String?) = Unit
		}

	private fun connect(generation: Long) = connections.onConnected(ConnectedTarget(target, "com.example.quickbuild", generation))

	/**
	 * A target whose binder answers [IBinder.pingBinder] as scripted. A reflection proxy,
	 * because android.jar's [IBinder] is unmocked on the JVM and only the ping is live.
	 */
	private fun pingable(alive: Boolean): PingableTarget = PingableTarget(alive)

	private class PingableTarget(
		alive: Boolean,
	) {
		private val binder: IBinder =
			java.lang.reflect.Proxy.newProxyInstance(
				IBinder::class.java.classLoader,
				arrayOf(IBinder::class.java),
			) { _, method, _ ->
				when (method.name) {
					"pingBinder" -> alive
					else -> null
				}
			} as IBinder

		val target =
			object : IQuickBuildTarget {
				override fun asBinder(): IBinder = binder

				override fun onPayload(
					generation: Long,
					dexPayload: ParcelFileDescriptor?,
					resourcesPayload: ParcelFileDescriptor?,
					assetsPayload: ParcelFileDescriptor?,
					metadataJson: String?,
				) = Unit

				override fun onBuildStatus(statusJson: String?) = Unit
			}
	}

	@Test
	fun `awaitDisconnect reports true when the proxy app actually disconnects`() =
		runTest {
			connect(generation = 7)
			val awaited = async { channel.awaitDisconnect(5_000) }
			runCurrent()

			connections.onDisconnected()

			assertThat(awaited.await()).isTrue()
		}

	@Test
	fun `awaitDisconnect reports false when the proxy app stays connected`() =
		runTest {
			connect(generation = 7)
			val awaited = async { channel.awaitDisconnect(5_000) }

			advanceTimeBy(5_001)

			assertThat(awaited.await()).isFalse()
		}

	@Test
	fun `awaitDisconnect reports true immediately when nothing is connected`() =
		runTest {
			assertThat(channel.awaitDisconnect(5_000)).isTrue()
		}

	@Test
	fun `awaitDisconnect reports true on timeout when the process no longer answers a ping`() =
		runTest {
			// The death notification is late, not absent: the process is gone, so a ping
			// fails. Reporting false here would attribute the timeout to a runtime that
			// ignored the restart and cost a Gradle rebuild.
			val dead = pingable(alive = false)
			connections.onConnected(ConnectedTarget(dead.target, "com.example.quickbuild", 7))
			val awaited = async { channel.awaitDisconnect(5_000) }

			advanceTimeBy(5_001)

			assertThat(awaited.await()).isTrue()
			assertThat(connections.target.value).isNull()
		}

	@Test
	fun `awaitDisconnect reports false on timeout when the process still answers a ping`() =
		runTest {
			val alive = pingable(alive = true)
			connections.onConnected(ConnectedTarget(alive.target, "com.example.quickbuild", 7))
			val awaited = async { channel.awaitDisconnect(5_000) }

			advanceTimeBy(5_001)

			assertThat(awaited.await()).isFalse()
			assertThat(connections.target.value).isNotNull()
		}

	@Test
	fun `awaitReconnect returns the generation the fresh process reported`() =
		runTest {
			val awaited = async { channel.awaitReconnect(15_000) }
			runCurrent()

			connect(generation = 8)

			assertThat(awaited.await()).isEqualTo(8)
		}

	@Test
	fun `awaitReconnect returns null when nothing reconnects in time`() =
		runTest {
			val awaited = async { channel.awaitReconnect(15_000) }

			advanceTimeBy(15_001)

			assertThat(awaited.await()).isNull()
		}
}
