package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppInstaller
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppRebuildOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The signing-cert check between the proxy app build and the install - the highest-stakes
 * decision the provisioner makes.
 *
 * Quick Build installs under the project's REAL applicationId, so the slot it is about to take
 * may already hold a stranger's app: the same id, built elsewhere, with the user's data in it.
 * An update-install cannot preserve that data and cannot be undone, so a wrong answer here
 * destroys something the user never offered up. Both directions therefore have to be pinned:
 * a check that refuses everything is just as broken - Quick Build could then never reinstall
 * over its own proxy app - and would be found only by a user, on a device.
 *
 * The load-bearing assertion in every refusal is that the installer was never called. A
 * refusal that returns the right sentence and installs anyway looks correct in every log.
 */
@RunWith(RobolectricTestRunner::class)
class GradleQuickBuildProvisionerSigningTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context: Context get() = ApplicationProvider.getApplicationContext()

	private val projectRoot: File get() = temp.root

	private fun moduleDir(): File = File(projectRoot, "app").apply { mkdirs() }

	@Test
	fun `a cert mismatch refuses the install - a stranger's app under the same id is not ours to replace`() =
		runTest {
			writeSetupJson(moduleDir())
			val packages = FakePackages(installedUid = 10_002, certSha256 = "installed-by-someone-else")
			val installer = recordingInstaller()

			val outcome =
				testProvisioner(
					context,
					projectRoot,
					installer = installer,
					packages = packages,
					apkCertSha256 = { "built-by-this-device" },
				).provision()

			assertThat(failure(outcome)).isEqualTo(QuickBuildMessage.ForeignAppInstalled(REAL_APPLICATION_ID))
			assertNothingInstalled(installer)
			// The two certs must come from different places. Reading the installed one for both
			// sides would make every comparison match, and this refusal would never fire.
			assertThat(packages.certReads).isEqualTo(1)
		}

	@Test
	fun `matching certs proceed - Quick Build has to be able to reinstall over its own proxy app`() =
		runTest {
			writeSetupJson(moduleDir())
			val installer = recordingInstaller()

			val outcome =
				testProvisioner(
					context,
					projectRoot,
					installer = installer,
					packages = FakePackages(installedUid = 10_002, certSha256 = "same-cert"),
					apkCertSha256 = { "same-cert" },
				).provision()

			assertThat(outcome).isInstanceOf(ProvisionOutcome.Success::class.java)
			// The uid the installer resolved becomes the deploy channel's gate, so it has to be
			// carried through rather than defaulted.
			assertThat((outcome as ProvisionOutcome.Success).proxyAppUid).isEqualTo(INSTALLED_UID)
			coVerify(exactly = 1) { installer.ensureInstalled(any(), REAL_APPLICATION_ID) }
		}

	@Test
	fun `an empty slot installs without consulting a cert at all`() =
		runTest {
			writeSetupJson(moduleDir())
			val packages = FakePackages(installedUid = null)
			var apkCertReads = 0

			val outcome =
				testProvisioner(
					context,
					projectRoot,
					packages = packages,
					apkCertSha256 = {
						apkCertReads++
						null
					},
				).provision()

			assertThat(outcome).isInstanceOf(ProvisionOutcome.Success::class.java)
			// Nothing is at risk when the slot is free, and both cert reads are expensive
			// binder/IO work - but the real point is that an unreadable cert must not be able
			// to refuse a first install onto a clean device.
			assertThat(packages.certReads).isEqualTo(0)
			assertThat(apkCertReads).isEqualTo(0)
		}

	@Test
	fun `an unreadable installed cert refuses - unverifiable is not the same as matching`() =
		runTest {
			writeSetupJson(moduleDir())
			val installer = recordingInstaller()

			val outcome =
				testProvisioner(
					context,
					projectRoot,
					installer = installer,
					packages = FakePackages(installedUid = 10_002, certSha256 = null),
					apkCertSha256 = { "built-by-this-device" },
				).provision()

			assertThat(failure(outcome)).isEqualTo(QuickBuildMessage.ForeignAppInstalled(REAL_APPLICATION_ID))
			assertNothingInstalled(installer)
		}

	@Test
	fun `an unreadable built cert refuses too - the unverifiable side does not matter`() =
		runTest {
			writeSetupJson(moduleDir())
			val installer = recordingInstaller()

			val outcome =
				testProvisioner(
					context,
					projectRoot,
					installer = installer,
					packages = FakePackages(installedUid = 10_002, certSha256 = "installed-by-someone-else"),
					apkCertSha256 = { null },
				).provision()

			assertThat(failure(outcome)).isEqualTo(QuickBuildMessage.ForeignAppInstalled(REAL_APPLICATION_ID))
			assertNothingInstalled(installer)
		}

	@Test
	fun `the refusal holds on the proxy app rebuild path, not only on the first provision`() =
		runTest {
			writeSetupJson(moduleDir())
			val installer = recordingInstaller()

			val outcome =
				testProvisioner(
					context,
					projectRoot,
					installer = installer,
					packages = FakePackages(installedUid = 10_002, certSha256 = "installed-by-someone-else"),
					apkCertSha256 = { "built-by-this-device" },
				).rebuildProxyApp()

			// A rebaseline reinstalls, so it can clobber exactly as a first provision can - and
			// a stranger's app can appear under the id between the provision and the rebuild.
			assertThat(outcome).isInstanceOf(ProxyAppRebuildOutcome.Failure::class.java)
			assertThat((outcome as ProxyAppRebuildOutcome.Failure).message)
				.isEqualTo(QuickBuildMessage.ForeignAppInstalled(REAL_APPLICATION_ID))
			assertNothingInstalled(installer)
		}

	@Test
	fun `the built cert is read from the APK the build just produced`() =
		runTest {
			writeSetupJson(moduleDir())
			var certReadFrom: File? = null

			testProvisioner(
				context,
				projectRoot,
				packages = FakePackages(installedUid = 10_002, certSha256 = "same-cert"),
				apkCertSha256 = { apk ->
					certReadFrom = apk
					"same-cert"
				},
			).provision()

			// Hashing some other APK - a stale one from a previous variant, say - would compare
			// two things that were never about to be installed, and the answer would be
			// meaningless in both directions.
			assertThat(certReadFrom)
				.isEqualTo(File(projectRoot, "app/build/outputs/apk/debug/app-debug.apk"))
		}

	private fun failure(outcome: ProvisionOutcome): QuickBuildMessage {
		assertThat(outcome).isInstanceOf(ProvisionOutcome.Failure::class.java)
		return (outcome as ProvisionOutcome.Failure).message
	}

	private fun assertNothingInstalled(installer: ProxyAppInstaller) {
		coVerify(exactly = 0) { installer.ensureInstalled(any(), any()) }
	}
}
