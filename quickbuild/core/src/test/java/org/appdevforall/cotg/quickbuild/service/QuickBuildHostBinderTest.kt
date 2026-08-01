package org.appdevforall.cotg.quickbuild.service

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The uid trust boundary of [QuickBuildHostService.HostBinder], against a real
 * [ProxyAppConnections]. On the JVM the stubbed `Binder.getCallingUid()` reports uid 0,
 * so a session begun for uid 0 stands in for the matching proxy app and any other
 * `expectedUid` stands in for a foreign caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickBuildHostBinderTest {
	private val connections = ProxyAppConnections()
	private val binder = QuickBuildHostService.HostBinder(connections)

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

	private fun beginMatchingSession() = connections.beginSession("com.example.quickbuild", uid = 0)

	@Test
	fun `every op is rejected when no session is live`() {
		assertThrows(SecurityException::class.java) { binder.connect(target, "com.example.quickbuild", 0) }
		assertThrows(SecurityException::class.java) { binder.reportReloaded(1, 40) }
		assertThrows(SecurityException::class.java) { binder.reportCrash(1, "boom") }
		assertThrows(SecurityException::class.java) { binder.disconnect("com.example.quickbuild") }
		assertThat(connections.target.value).isNull()
	}

	@Test
	fun `a foreign uid is rejected and named in the error`() {
		connections.beginSession("com.example.quickbuild", uid = 10123)

		val error =
			assertThrows(SecurityException::class.java) {
				binder.reportReloaded(1, 40)
			}

		assertThat(error.message).contains("uid 0")
		assertThat(error.message).contains("10123")
	}

	@Test
	fun `a matching connect registers the target at its running generation`() {
		beginMatchingSession()

		binder.connect(target, "com.example.quickbuild", runningGeneration = 7)

		val connected = connections.target.value
		assertThat(connected).isNotNull()
		assertThat(connected!!.packageName).isEqualTo("com.example.quickbuild")
		assertThat(connected.runningGeneration).isEqualTo(7)
	}

	@Test
	fun `connect without a target or package is rejected even from the right uid`() {
		beginMatchingSession()

		assertThrows(SecurityException::class.java) { binder.connect(null, "com.example.quickbuild", 0) }
		assertThrows(SecurityException::class.java) { binder.connect(target, null, 0) }
		assertThat(connections.target.value).isNull()
	}

	@Test
	fun `reports from the session's uid reach the report flow`() =
		runTest {
			beginMatchingSession()
			val reports = recordReports()

			binder.reportReloaded(3, 42)
			binder.reportCrash(4, "NPE at MainActivity")

			assertThat(reports)
				.containsExactly(
					TargetReport.Reloaded(generation = 3, reloadMillis = 42),
					TargetReport.Crashed(generation = 4, stackSummary = "NPE at MainActivity"),
				).inOrder()
		}

	@Test
	fun `a crash report without a summary reads as an unknown crash`() =
		runTest {
			beginMatchingSession()
			val reports = recordReports()

			binder.reportCrash(5, null)

			assertThat(reports)
				.containsExactly(TargetReport.Crashed(generation = 5, stackSummary = "unknown crash"))
		}

	/** Collects the zero-replay report flow eagerly, so emissions land synchronously. */
	private fun kotlinx.coroutines.test.TestScope.recordReports(): List<TargetReport> {
		val seen = mutableListOf<TargetReport>()
		backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
			connections.reports.collect { seen += it }
		}
		return seen
	}

	@Test
	fun `disconnect clears the registered target`() {
		beginMatchingSession()
		binder.connect(target, "com.example.quickbuild", 0)

		binder.disconnect("com.example.quickbuild")

		assertThat(connections.target.value).isNull()
	}
}
