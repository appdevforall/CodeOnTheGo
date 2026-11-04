package com.itsaky.androidide.quickbuild

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.io.File

/**
 * Pins the digest the same-applicationId clobber refusal compares on both sides.
 *
 * The refusal fails open: a digest that is null or simply wrong reads as "cannot tell" and the
 * install proceeds, so a wrong answer here silently overwrites a third-party app holding the
 * same applicationId. The two ways to get a wrong answer are reading the oldest rotation entry
 * instead of the newest, and reading the rotation history of a package that has multiple
 * signers - where Android reports no history at all. Both produce a plausible-looking String.
 *
 * The expected digests are SHA-256 of the literal certificate bytes, computed outside this
 * code, so a test cannot agree with the implementation by recomputing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ApkSigningCertTest {
	private val context: Context = ApplicationProvider.getApplicationContext()

	private fun signature(content: String) = Signature(content.toByteArray())

	/**
	 * @param signers what `getApkContentsSigners` reports; more than one makes
	 *   `hasMultipleSigners` true, which is the state in which Android reports no history.
	 * @param history the rotation history, oldest first and the current cert last.
	 */
	private fun signingInfo(
		signers: Array<Signature>,
		history: Array<Signature>? = null,
	): SigningInfo =
		SigningInfo().also { info ->
			val shadow = Shadow.extract<ShadowSigningInfo>(info)
			shadow.setSignatures(signers)
			history?.let(shadow::setPastSigningCertificates)
		}

	private fun install(signingInfo: SigningInfo?) {
		val info =
			PackageInfo().apply {
				packageName = PACKAGE
				applicationInfo =
					ApplicationInfo().apply {
						packageName = PACKAGE
						sourceDir = APK_PATH
						publicSourceDir = APK_PATH
					}
				this.signingInfo = signingInfo
			}
		shadowOf(context.packageManager).installPackage(info)
	}

	private fun installedDigest() = AndroidInstalledPackages(context).signingCertSha256(PACKAGE)

	@Test
	fun `a rotated signer digests to its newest cert, not the one it rotated away from`() {
		install(signingInfo(signers = arrayOf(signature(CURRENT)), history = arrayOf(signature(OLDER), signature(CURRENT))))

		assertThat(installedDigest()).isEqualTo(CURRENT_SHA256)
	}

	@Test
	fun `a multiply-signed package is read from its contents signers, where Android reports no history`() {
		install(signingInfo(signers = arrayOf(signature(FIRST_SIGNER), signature(SECOND_SIGNER))))

		assertThat(installedDigest()).isEqualTo(SECOND_SIGNER_SHA256)
	}

	@Test
	fun `a package with no signing information yields no digest rather than a value that would compare equal`() {
		install(signingInfo = null)

		assertThat(installedDigest()).isNull()
	}

	@Test
	fun `a package that is not installed yields no digest`() {
		assertThat(AndroidInstalledPackages(context).signingCertSha256("com.example.absent")).isNull()
	}

	@Test
	fun `the built APK digests to the same value as the installed package, so the two sides compare like for like`() {
		install(signingInfo(signers = arrayOf(signature(CURRENT)), history = arrayOf(signature(OLDER), signature(CURRENT))))

		val fromApk = ApkSigningCert.sha256(context, File(APK_PATH))

		assertThat(fromApk).isEqualTo(CURRENT_SHA256)
		assertThat(fromApk).isEqualTo(installedDigest())
	}

	@Test
	fun `an unreadable APK yields no digest rather than throwing into the install path`() {
		assertThat(ApkSigningCert.sha256(context, File("/nonexistent/never-built.apk"))).isNull()
	}

	private companion object {
		private const val PACKAGE = "com.example.proxy"
		private const val APK_PATH = "/data/app/com.example.proxy/base.apk"

		private const val CURRENT = "current-cert"
		private const val OLDER = "older-cert"
		private const val FIRST_SIGNER = "first-signer"
		private const val SECOND_SIGNER = "second-signer"

		private const val CURRENT_SHA256 = "786c68c627804c37ca625b44ef2387e7a4bdb4fe6c6577853fac2edab8c6c707"
		private const val SECOND_SIGNER_SHA256 = "66a25a4bde11573a05b154b1dc6c7edcc546677e24fc75afa57e16e360452401"
	}
}
