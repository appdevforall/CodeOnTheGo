@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.service.provision

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The package-less install broadcast: some OEM installer stacks omit
 * `EXTRA_PACKAGE_NAME` on session broadcasts, so a null packageName must count as
 * OURS (the installer only ever commits one session at a time) instead of being
 * filtered like a foreign package's broadcast.
 */
class ProxyAppInstallerEdgeTest {
	private companion object {
		const val PKG = "com.example.quickbuild"
	}

	@TempDir lateinit var dir: File

	private lateinit var apk: File

	private class FakePackages : InstalledPackages {
		var uid: Int? = null
		var stamp: Long? = null
		var installedApk: File? = null

		override fun uid(packageName: String): Int? = uid

		override fun lastUpdateTime(packageName: String): Long? = stamp

		override fun apkFile(packageName: String): File? = installedApk

		override fun signingCertSha256(packageName: String): String? = null

		override fun appComponentFactory(packageName: String): String? = null
	}

	private val packages = FakePackages()
	private val broadcasts = MutableSharedFlow<InstallBroadcast>(extraBufferCapacity = 16)

	private fun installer() =
		ProxyAppInstaller(
			packages = packages,
			launchInstall = { true },
			broadcasts = broadcasts,
			timeoutMillis = 10_000L,
			canShowConfirmDialog = { true },
		)

	@BeforeEach
	fun setUp() {
		apk = File(dir, "proxy-app.apk").apply { writeText("apk-bytes-v1") }
	}

	@Test
	fun `a broadcast without a package name is treated as this install's verdict`() =
		runTest {
			val result = async { installer().ensureInstalled(apk, PKG) }
			runCurrent()

			packages.uid = 10123
			broadcasts.emit(InstallBroadcast(null, InstallBroadcast.Status.SUCCESS, null))

			val outcome = result.await()
			assertThat(outcome).isInstanceOf(InstallOutcome.Installed::class.java)
			assertThat((outcome as InstallOutcome.Installed).uid).isEqualTo(10123)
		}
}
