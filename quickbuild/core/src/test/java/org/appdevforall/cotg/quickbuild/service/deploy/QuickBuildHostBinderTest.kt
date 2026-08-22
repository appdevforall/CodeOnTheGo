package org.appdevforall.cotg.quickbuild.service.deploy

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.service.telemetry.report
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The uid trust boundary of [QuickBuildHostService.HostBinder], against a real
 * [ProxyAppConnections], and the death-watch wiring that keeps the registered target and
 * the watched binder in step. On the JVM the stubbed `Binder.getCallingUid()` reports
 * uid 0, so a session begun for uid 0 stands in for the matching proxy app and any other
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

	@Test
	fun `a death from a superseded binder leaves the live registration alone`() {
		beginMatchingSession()
		val dead = fakeBinder()
		val live = fakeBinder()
		binder.connect(targetOn(dead), "com.example.quickbuild", 0)
		binder.connect(targetOn(live), "com.example.quickbuild", 0)

		connections.onDisconnected(dead)

		// The superseded process's death notification arrives after its replacement has bound.
		// Clearing here would deploy the next save into NotConnected against a healthy app.
		assertThat(
			connections.target.value
				?.target
				?.asBinder(),
		).isSameInstanceAs(live)
	}

	@Test
	fun `a death from the registered binder clears the target`() {
		beginMatchingSession()
		val live = fakeBinder()
		binder.connect(targetOn(live), "com.example.quickbuild", 0)

		connections.onDisconnected(live)

		assertThat(connections.target.value).isNull()
	}

	@Test
	fun `a binder that is dead at connect is not left registered`() {
		beginMatchingSession()
		val dead = WatchableBinder(onLink = { _, _ -> throw RemoteException("already dead") })

		binder.connect(targetOn(dead.binder), "com.example.quickbuild", 0)

		// A dead target no death notification can ever clear would turn every deploy into
		// a full timeout; the failed link must fail fast to no registration instead.
		assertThat(connections.target.value).isNull()
	}

	@Test
	fun `a dead binder's stale connect retry does not clobber a live registration`() {
		beginMatchingSession()
		val live = WatchableBinder()
		binder.connect(targetOn(live.binder), "com.example.quickbuild", 0)
		val dead = WatchableBinder(onLink = { _, _ -> throw RemoteException("already dead") })

		binder.connect(targetOn(dead.binder), "com.example.quickbuild", 0)

		// The superseded process's retry lost the race to the fresh process's bind; the
		// fresh registration must survive it, and stay watched.
		assertThat(
			connections.target.value
				?.target
				?.asBinder(),
		).isSameInstanceAs(live.binder)
		assertThat(live.watching()).hasSize(1)
	}

	@Test
	fun `a death delivered while connect is registering still clears the target`() {
		beginMatchingSession()
		// linkToDeath delivers the death on another thread immediately, the way a proxy app
		// crashing right after its connect() call does. The helper returns once that
		// delivery has either completed or parked against the binder's registration lock,
		// so both orderings are exercised deterministically rather than raced.
		var death: Thread? = null
		val dying =
			WatchableBinder(
				onLink = { _, recipient ->
					val delivery = Thread { recipient.binderDied() }.also { it.start() }
					death = delivery
					while (delivery.state != Thread.State.TERMINATED && delivery.state != Thread.State.BLOCKED) {
						Thread.sleep(1)
					}
				},
			)

		binder.connect(targetOn(dying.binder), "com.example.quickbuild", 0)
		death!!.join(5_000)

		// Registration and death watch are one atomic step, so the death lands after the
		// registration and clears it - a dead target must never stay registered.
		assertThat(connections.target.value).isNull()
	}

	@Test
	fun `a reconnect moves the watch, and firing it clears the registration`() {
		beginMatchingSession()
		val first = WatchableBinder()
		val second = WatchableBinder()
		binder.connect(targetOn(first.binder), "com.example.quickbuild", 0)
		binder.connect(targetOn(second.binder), "com.example.quickbuild", 1)

		assertThat(first.watching()).isEmpty()
		val recipient = second.watching().single()

		recipient.binderDied()

		assertThat(connections.target.value).isNull()
	}

	@Test
	fun `a graceful disconnect unlinks the death watch`() {
		beginMatchingSession()
		val live = WatchableBinder()
		binder.connect(targetOn(live.binder), "com.example.quickbuild", 0)

		binder.disconnect("com.example.quickbuild")

		// No stale recipient stays linked to fire on the process's eventual death.
		assertThat(live.watching()).isEmpty()
	}

	/**
	 * An [IBinder] that records link/unlink traffic and can script [IBinder.linkToDeath],
	 * so the death-watch wiring is exercised for real. Only the two death-watch methods
	 * are live; everything else no-ops through the reflection proxy.
	 */
	private class WatchableBinder(
		private val onLink: (WatchableBinder, IBinder.DeathRecipient) -> Unit = { _, _ -> },
	) {
		val linked = mutableListOf<IBinder.DeathRecipient>()
		val unlinked = mutableListOf<IBinder.DeathRecipient>()

		val binder: IBinder =
			java.lang.reflect.Proxy.newProxyInstance(
				IBinder::class.java.classLoader,
				arrayOf(IBinder::class.java),
			) { _, method, args ->
				when (method.name) {
					"linkToDeath" -> {
						val recipient = args!![0] as IBinder.DeathRecipient
						onLink(this, recipient)
						linked += recipient
						null
					}

					"unlinkToDeath" -> {
						unlinked += args!![0] as IBinder.DeathRecipient
						true
					}

					else -> {
						null
					}
				}
			} as IBinder

		/** The recipients still linked, in link order. */
		fun watching(): List<IBinder.DeathRecipient> = linked.filterNot { it in unlinked }
	}

	/**
	 * A distinct [IBinder] identity. Only reference identity is exercised, so a reflection
	 * proxy is enough and avoids stubbing the whole interface against an unmocked android.jar.
	 */
	private fun fakeBinder(): IBinder =
		java.lang.reflect.Proxy.newProxyInstance(
			IBinder::class.java.classLoader,
			arrayOf(IBinder::class.java),
		) { _, _, _ -> null } as IBinder

	private fun targetOn(binder: IBinder): IQuickBuildTarget =
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
