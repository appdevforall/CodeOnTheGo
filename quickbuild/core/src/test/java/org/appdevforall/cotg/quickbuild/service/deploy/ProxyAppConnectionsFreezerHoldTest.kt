package org.appdevforall.cotg.quickbuild.service.deploy

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import org.junit.jupiter.api.Test

/**
 * When [ProxyAppConnections] takes and drops the freezer hold.
 *
 * The bug this pins: with no hold, Android freezes the backgrounded proxy app about a minute
 * after it loses the foreground, it stops answering the reload handshake, and every save then
 * fails the 15 s deploy timeout. So the hold follows the *connection*, not the session alone:
 * taken when an app is there to protect, dropped the moment it is gone or the session ends.
 */
class ProxyAppConnectionsFreezerHoldTest {
	private val connections = ProxyAppConnections()
	private val hold = RecordingHold()

	init {
		connections.installPriorityHold(hold)
	}

	/** Never called: the hold lifecycle only reads the registry's own state. */
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

	private fun connect(reportedPackage: String = "com.example.app") =
		connections.onConnected(ConnectedTarget(target, reportedPackage, runningGeneration = 1))

	@Test
	fun `a connected proxy app is held out of the freezer`() {
		connections.beginSession("com.example.app", uid = 10123)

		connect()

		assertThat(hold.held).containsExactly("com.example.app")
		assertThat(hold.releases).isEqualTo(0)
	}

	@Test
	fun `the held package comes from PackageManager, not from what the app reported`() {
		connections.beginSession("com.example.app", uid = 10123)

		connect(reportedPackage = "com.attacker.elsewhere")

		// The reported name is logging-only by contract; holding it would let a caller past
		// the uid gate start and pin an unrelated process by name.
		assertThat(hold.held).containsExactly("com.example.app")
	}

	@Test
	fun `no session means nothing is held`() {
		connect()

		assertThat(hold.held).isEmpty()
	}

	@Test
	fun `losing the proxy app drops the hold`() {
		connections.beginSession("com.example.app", uid = 10123)
		connect()

		connections.onDisconnected()

		assertThat(hold.releases).isEqualTo(1)
	}

	@Test
	fun `a relaunched proxy app is held again`() {
		connections.beginSession("com.example.app", uid = 10123)
		connect()
		connections.onDisconnected()

		connect()

		assertThat(hold.held).containsExactly("com.example.app", "com.example.app")
		assertThat(hold.releases).isEqualTo(1)
	}

	@Test
	fun `ending the session drops the hold, so a plain backgrounded app is cached normally`() {
		connections.beginSession("com.example.app", uid = 10123)
		connect()

		connections.endSession()

		assertThat(hold.releases).isEqualTo(1)
	}

	@Test
	fun `a second session holds the second app`() {
		connections.beginSession("com.example.first", uid = 10123)
		connect()
		connections.endSession()

		connections.beginSession("com.example.second", uid = 10124)
		connect()

		assertThat(hold.held).containsExactly("com.example.first", "com.example.second").inOrder()
	}

	@Test
	fun `uninstalling the hold releases it and stops driving it`() {
		connections.beginSession("com.example.app", uid = 10123)
		connect()

		connections.uninstallPriorityHold()
		connect()

		assertThat(hold.releases).isEqualTo(1)
		assertThat(hold.held).containsExactly("com.example.app")
	}

	@Test
	fun `a registry with no hold installed still connects`() {
		val bare = ProxyAppConnections()
		bare.beginSession("com.example.app", uid = 10123)

		bare.onConnected(ConnectedTarget(target, "com.example.app", runningGeneration = 1))
		bare.onDisconnected()
		bare.endSession()

		assertThat(bare.target.value).isNull()
	}

	/** Records what the registry asked for, so the assertions read as call sequences. */
	private class RecordingHold : ProxyAppPriorityHold {
		val held = mutableListOf<String>()
		var releases = 0

		override fun hold(packageName: String) {
			held += packageName
		}

		override fun release() {
			releases++
		}
	}
}
