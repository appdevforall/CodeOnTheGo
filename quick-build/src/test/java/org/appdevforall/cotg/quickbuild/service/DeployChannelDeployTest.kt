@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.service

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The real [DeployChannel.deploy] verdict machinery against a real
 * [ProxyAppConnections] and a scripted [IQuickBuildTarget]: report matching by
 * generation, the disconnect and binder-failure verdicts, and the timeout fallback.
 * (The Android `ParcelFileDescriptor` stubs no-op on the JVM, so payload files stay
 * null-or-ignored here; fd plumbing is device territory.)
 */
class DeployChannelDeployTest {
	private val connections = ProxyAppConnections()
	private val channel = DeployChannel(connections, timeoutMillis = 5_000)

	/** Records payload calls; can be scripted to throw at the binder boundary. */
	private class ScriptedTarget(
		private val onPayloadThrow: (() -> Throwable)? = null,
	) : IQuickBuildTarget {
		val payloads = mutableListOf<Pair<Long, String?>>()
		val statuses = mutableListOf<String?>()
		var statusThrow: (() -> Throwable)? = null

		override fun asBinder(): IBinder? = null

		override fun onPayload(
			generation: Long,
			dexPayload: ParcelFileDescriptor?,
			resourcesPayload: ParcelFileDescriptor?,
			assetsPayload: ParcelFileDescriptor?,
			metadataJson: String?,
		) {
			onPayloadThrow?.let { throw it() }
			payloads += generation to metadataJson
		}

		override fun onBuildStatus(statusJson: String?) {
			statusThrow?.let { throw it() }
			statuses += statusJson
		}
	}

	private fun connect(
		target: ScriptedTarget,
		generation: Long = 0,
	) = connections.onConnected(ConnectedTarget(target, "com.example.quickbuild", generation))

	@Test
	fun `deploy without a connected proxy app reports NotConnected`() =
		runTest {
			val result = channel.deploy(1, null, null, null, "{}")

			assertThat(result).isEqualTo(DeployResult.NotConnected)
		}

	@Test
	fun `a reload report for the deployed generation completes the deploy`() =
		runTest {
			val target = ScriptedTarget()
			connect(target)

			val deploy = async { channel.deploy(7, null, null, null, """{"gen":7}""") }
			runCurrent()
			connections.report(TargetReport.Reloaded(generation = 7, reloadMillis = 42))

			assertThat(deploy.await()).isEqualTo(DeployResult.Reloaded(42))
			assertThat(target.payloads).containsExactly(7L to """{"gen":7}""")
		}

	@Test
	fun `reports for other generations are ignored, not misattributed`() =
		runTest {
			val target = ScriptedTarget()
			connect(target)

			val deploy = async { channel.deploy(7, null, null, null, "{}") }
			runCurrent()
			// Late reports from a superseded generation must not complete this deploy.
			connections.report(TargetReport.Reloaded(generation = 6, reloadMillis = 5))
			connections.report(TargetReport.Crashed(generation = 6, stackSummary = "old crash"))
			runCurrent()
			assertThat(deploy.isCompleted).isFalse()

			connections.report(TargetReport.Reloaded(generation = 7, reloadMillis = 99))
			assertThat(deploy.await()).isEqualTo(DeployResult.Reloaded(99))
		}

	@Test
	fun `a crash report for the deployed generation reports Crashed with the stack`() =
		runTest {
			val target = ScriptedTarget()
			connect(target)

			val deploy = async { channel.deploy(3, null, null, null, "{}") }
			runCurrent()
			connections.report(TargetReport.Crashed(generation = 3, stackSummary = "NPE at MainActivity"))

			assertThat(deploy.await()).isEqualTo(DeployResult.Crashed("NPE at MainActivity"))
		}

	@Test
	fun `a disconnect while awaiting the verdict reports Disconnected`() =
		runTest {
			val target = ScriptedTarget()
			connect(target)

			val deploy = async { channel.deploy(3, null, null, null, "{}") }
			runCurrent()
			connections.onDisconnected()

			assertThat(deploy.await()).isEqualTo(DeployResult.Disconnected)
		}

	@Test
	fun `a binder failure during onPayload reports Failed naming the binder`() =
		runTest {
			connect(ScriptedTarget(onPayloadThrow = { RemoteException("binder gone") }))

			val result = channel.deploy(3, null, null, null, "{}")

			assertThat(result).isInstanceOf(DeployResult.Failed::class.java)
			assertThat((result as DeployResult.Failed).message).contains("Binder call failed")
		}

	@Test
	fun `an unopenable payload reports Failed naming the payload`() =
		runTest {
			connect(ScriptedTarget(onPayloadThrow = { java.io.IOException("fd refused") }))

			val result = channel.deploy(3, null, null, null, "{}")

			assertThat(result).isInstanceOf(DeployResult.Failed::class.java)
			assertThat((result as DeployResult.Failed).message).contains("Cannot open payload")
		}

	@Test
	fun `a proxy app that never answers times out with the configured timeout`() =
		runTest {
			val target = ScriptedTarget()
			connect(target)

			val result = channel.deploy(3, null, null, null, "{}")

			// runTest's virtual clock skips the 5s wait; no report ever arrives.
			assertThat(result).isEqualTo(DeployResult.TimedOut(5_000))
			assertThat(target.payloads).hasSize(1)
		}

	@Test
	fun `notifyBuildStatus reaches the connected proxy app`() =
		runTest {
			val target = ScriptedTarget()
			connect(target)

			channel.notifyBuildStatus("""{"state":"building"}""")

			assertThat(target.statuses).containsExactly("""{"state":"building"}""")
		}

	@Test
	fun `notifyBuildStatus without a connection is a silent no-op`() =
		runTest {
			// Nothing to assert beyond "did not throw": the contract is fire-and-forget.
			channel.notifyBuildStatus("""{"state":"building"}""")
		}

	@Test
	fun `a throwing status stub stays best-effort`() =
		runTest {
			val target = ScriptedTarget()
			target.statusThrow = { RemoteException("stub predates onBuildStatus") }
			connect(target)

			channel.notifyBuildStatus("""{"state":"building"}""")

			assertThat(target.statuses).isEmpty()
		}
}
