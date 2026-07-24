@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TestAppInstallerTest {
	private companion object {
		const val PKG = "com.example.quickbuild"
	}

	@TempDir lateinit var dir: File

	private lateinit var apk: File

	/** Scripted [InstalledPackages]: a mutable picture of what is installed. */
	private class FakePackages : InstalledPackages {
		var uid: Int? = null
		var stamp: Long? = null
		var installedApk: File? = null

		override fun uid(packageName: String): Int? = uid

		override fun lastUpdateTime(packageName: String): Long? = stamp

		override fun apkFile(packageName: String): File? = installedApk

		override fun versionCode(packageName: String): Long? = null

		override fun signingCertSha256(packageName: String): String? = null
	}

	private val packages = FakePackages()
	private val broadcasts = MutableSharedFlow<InstallBroadcast>(extraBufferCapacity = 16)
	private val installLaunches = mutableListOf<File>()
	private var launchResult = true

	/** What the scripted launch does to the fake package state, if anything. */
	private var onLaunch: () -> Unit = {}

	private fun installer(timeoutMillis: Long = 180_000L) =
		TestAppInstaller(
			packages = packages,
			launchInstall = { file ->
				installLaunches += file
				onLaunch()
				launchResult
			},
			broadcasts = broadcasts,
			timeoutMillis = timeoutMillis,
			pollMillis = 1_000L,
		)

	@BeforeEach
	fun setUp() {
		apk = File(dir, "test-app.apk").apply { writeText("apk-bytes-v1") }
	}

	@Test
	fun `installed package with identical bytes is skipped - no dialog, no reinstall`() =
		runTest {
			packages.uid = 10123
			packages.stamp = 111L
			packages.installedApk = File(dir, "installed.apk").apply { writeText("apk-bytes-v1") }

			val outcome = installer().ensureInstalled(apk, PKG)

			assertThat(outcome).isEqualTo(InstallOutcome.Installed(10123, reinstalled = false))
			assertThat(installLaunches).isEmpty()
		}

	@Test
	fun `changed bytes reinstall and resolve via the success broadcast`() =
		runTest {
			packages.uid = 10123
			packages.stamp = 111L
			packages.installedApk = File(dir, "installed.apk").apply { writeText("apk-bytes-v0") }

			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()
			assertThat(installLaunches).containsExactly(apk)

			// Non-terminal statuses are ignored; the user confirms, then success.
			broadcasts.emit(InstallBroadcast(null, InstallBroadcast.Status.PENDING_USER_ACTION))
			broadcasts.emit(InstallBroadcast(PKG, InstallBroadcast.Status.OTHER))
			broadcasts.emit(InstallBroadcast(PKG, InstallBroadcast.Status.SUCCESS))
			advanceUntilIdle()

			assertThat(result.await()).isEqualTo(InstallOutcome.Installed(10123, reinstalled = true))
		}

	@Test
	fun `fresh install resolves via the success broadcast and the new uid`() =
		runTest {
			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()

			packages.uid = 10456
			packages.stamp = 222L
			broadcasts.emit(InstallBroadcast(PKG, InstallBroadcast.Status.SUCCESS))
			advanceUntilIdle()

			assertThat(result.await()).isEqualTo(InstallOutcome.Installed(10456, reinstalled = true))
		}

	@Test
	fun `failure broadcast surfaces the real installer message fast`() =
		runTest {
			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()

			broadcasts.emit(
				InstallBroadcast(null, InstallBroadcast.Status.FAILURE, "INSTALL_FAILED_INVALID_APK"),
			)
			advanceUntilIdle()

			assertThat(result.await())
				.isEqualTo(InstallOutcome.Failed("INSTALL_FAILED_INVALID_APK"))
		}

	@Test
	fun `broadcast for a DIFFERENT package is not ours`() =
		runTest {
			val result = async { installer(timeoutMillis = 10_000L).ensureInstalled(apk, PKG) }
			runCurrent()

			broadcasts.emit(
				InstallBroadcast("com.other.app", InstallBroadcast.Status.FAILURE, "other app failed"),
			)
			advanceUntilIdle()

			// Ignored: we time out instead of misreporting the other app's failure.
			val outcome = result.await()
			assertThat(outcome).isInstanceOf(InstallOutcome.Failed::class.java)
			assertThat((outcome as InstallOutcome.Failed).message).contains("not confirmed")
		}

	@Test
	fun `intent-fallback installs (no broadcast) complete via the lastUpdateTime poll`() =
		runTest {
			// MIUI's intent-based fallback never fires InstallationResultReceiver.
			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()

			packages.uid = 10789
			packages.stamp = 333L
			advanceTimeBy(2_000L)
			runCurrent()

			assertThat(result.await()).isEqualTo(InstallOutcome.Installed(10789, reinstalled = true))
		}

	@Test
	fun `reinstall via poll needs the stamp to CHANGE - the old install does not count`() =
		runTest {
			packages.uid = 10123
			packages.stamp = 111L
			packages.installedApk = File(dir, "installed.apk").apply { writeText("apk-bytes-v0") }

			val result = async { installer(timeoutMillis = 30_000L).ensureInstalled(apk, PKG) }
			runCurrent()
			advanceTimeBy(5_000L)
			runCurrent()

			// Still waiting: the pre-existing install must not read as completion.
			assertThat(result.isCompleted).isFalse()

			packages.stamp = 444L
			advanceTimeBy(2_000L)
			runCurrent()

			assertThat(result.await()).isEqualTo(InstallOutcome.Installed(10123, reinstalled = true))
		}

	@Test
	fun `launch failure fails immediately`() =
		runTest {
			launchResult = false

			val outcome = installer().ensureInstalled(apk, PKG)

			assertThat(outcome)
				.isEqualTo(InstallOutcome.Failed("Could not start the test app installation"))
		}

	@Test
	fun `timeout produces a visible failure, never a false success`() =
		runTest {
			val result = async { installer(timeoutMillis = 10_000L).ensureInstalled(apk, PKG) }
			advanceUntilIdle()

			val outcome = result.await()
			assertThat(outcome).isInstanceOf(InstallOutcome.Failed::class.java)
			assertThat((outcome as InstallOutcome.Failed).message).contains("not confirmed")
		}

	@Test
	fun `unreadable installed apk is treated as a content mismatch - reinstall`() =
		runTest {
			packages.uid = 10123
			packages.stamp = 111L
			packages.installedApk = File(dir, "does-not-exist.apk")

			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()

			assertThat(installLaunches).containsExactly(apk)
			broadcasts.emit(InstallBroadcast(PKG, InstallBroadcast.Status.SUCCESS))
			advanceUntilIdle()
			assertThat(result.await()).isEqualTo(InstallOutcome.Installed(10123, reinstalled = true))
		}

	@Test
	fun `success broadcast but unresolvable uid fails visibly after retries`() =
		runTest {
			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()

			// Success reported, but PackageManager never resolves the package.
			broadcasts.emit(InstallBroadcast(PKG, InstallBroadcast.Status.SUCCESS))
			advanceUntilIdle()

			val outcome = result.await()
			assertThat(outcome).isInstanceOf(InstallOutcome.Failed::class.java)
			assertThat((outcome as InstallOutcome.Failed).message).contains("cannot resolve")
		}

	@Test
	fun `uid appearing after a retry still resolves`() =
		runTest {
			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()

			broadcasts.emit(InstallBroadcast(PKG, InstallBroadcast.Status.SUCCESS))
			runCurrent()
			// The uid becomes visible between the broadcast and the first retry.
			packages.uid = 10999
			advanceTimeBy(1_500L)
			runCurrent()

			assertThat(result.await()).isEqualTo(InstallOutcome.Installed(10999, reinstalled = true))
		}

	@Test
	fun `failure broadcast without a message falls back to a generic one`() =
		runTest {
			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()

			broadcasts.emit(InstallBroadcast(PKG, InstallBroadcast.Status.FAILURE, message = null))
			advanceUntilIdle()

			assertThat(result.await())
				.isEqualTo(InstallOutcome.Failed("Test app installation failed"))
		}

	@Test
	fun `a throwing launch is treated as could-not-start, never as a crash`() =
		runTest {
			onLaunch = { throw IllegalStateException("installer exploded") }

			val outcome = installer().ensureInstalled(apk, PKG)

			assertThat(outcome)
				.isEqualTo(InstallOutcome.Failed("Could not start the test app installation"))
		}

	@Test
	fun `unreadable CANDIDATE apk is a content mismatch - reinstall, not a false skip`() =
		runTest {
			packages.uid = 10123
			packages.stamp = 111L
			packages.installedApk = File(dir, "installed.apk").apply { writeText("apk-bytes-v1") }
			val missingCandidate = File(dir, "not-built.apk")

			val result = async { installer().ensureInstalled(missingCandidate, PKG) }
			runCurrent()

			assertThat(installLaunches).containsExactly(missingCandidate)
			broadcasts.emit(InstallBroadcast(PKG, InstallBroadcast.Status.SUCCESS))
			advanceUntilIdle()
			assertThat(result.await()).isEqualTo(InstallOutcome.Installed(10123, reinstalled = true))
		}

	@Test
	fun `installed package without a resolvable apk file reinstalls`() =
		runTest {
			packages.uid = 10123
			packages.stamp = 111L
			packages.installedApk = null

			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()

			assertThat(installLaunches).containsExactly(apk)
			broadcasts.emit(InstallBroadcast(PKG, InstallBroadcast.Status.SUCCESS))
			advanceUntilIdle()
			assertThat(result.await()).isEqualTo(InstallOutcome.Installed(10123, reinstalled = true))
		}

	@Test
	fun `sha256 digests real content and returns null for a missing file`() {
		assertThat(TestAppInstaller.sha256OrNull(apk))
			.isEqualTo(TestAppInstaller.sha256OrNull(File(dir, "copy.apk").apply { writeText("apk-bytes-v1") }))
		assertThat(TestAppInstaller.sha256OrNull(File(dir, "missing.apk"))).isNull()
	}
}
