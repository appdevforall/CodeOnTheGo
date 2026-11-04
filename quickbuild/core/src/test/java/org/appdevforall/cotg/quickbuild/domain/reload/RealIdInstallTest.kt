package org.appdevforall.cotg.quickbuild.domain.reload

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.junit.jupiter.api.Test

class RealIdInstallTest {
	private val ourFactory = RealIdInstall.QUICK_BUILD_APP_COMPONENT_FACTORY

	@Test
	fun `isQuickBuildProxyApp is true only for the runtime factory`() {
		assertThat(RealIdInstall.isQuickBuildProxyApp(ourFactory)).isTrue()
	}

	@Test
	fun `isQuickBuildProxyApp is false for a null, empty, or foreign factory`() {
		assertThat(RealIdInstall.isQuickBuildProxyApp(null)).isFalse()
		assertThat(RealIdInstall.isQuickBuildProxyApp("")).isFalse()
		assertThat(RealIdInstall.isQuickBuildProxyApp("androidx.core.app.CoreComponentFactory")).isFalse()
	}

	@Test
	fun `Quick Build needs no confirm when nothing is installed`() {
		assertThat(
			RealIdInstall.quickBuildNeedsClobberConfirm(
				realAppInstalled = false,
				installedFactory = null,
			),
		).isFalse()
	}

	@Test
	fun `Quick Build needs no confirm when its own proxy app already occupies the slot`() {
		assertThat(
			RealIdInstall.quickBuildNeedsClobberConfirm(
				realAppInstalled = true,
				installedFactory = ourFactory,
			),
		).isFalse()
	}

	@Test
	fun `Quick Build confirms when a different build occupies the slot`() {
		// The Standard-Run app (no runtime factory) - or any non-QB occupant.
		assertThat(
			RealIdInstall.quickBuildNeedsClobberConfirm(
				realAppInstalled = true,
				installedFactory = null,
			),
		).isTrue()
		assertThat(
			RealIdInstall.quickBuildNeedsClobberConfirm(
				realAppInstalled = true,
				installedFactory = "com.example.OtherFactory",
			),
		).isTrue()
	}

	@Test
	fun `Standard Run confirms only when a Quick Build proxy app occupies the slot`() {
		assertThat(RealIdInstall.standardRunNeedsClobberConfirm(ourFactory)).isTrue()
		assertThat(RealIdInstall.standardRunNeedsClobberConfirm(null)).isFalse()
		assertThat(RealIdInstall.standardRunNeedsClobberConfirm("com.example.OtherFactory")).isFalse()
	}

	@Test
	fun `signatureRefusal proceeds when nothing is installed`() {
		assertThat(
			RealIdInstall.signatureRefusal(
				realApplicationId = "com.example.app",
				realAppInstalled = false,
				installedCertSha256 = null,
				builtCertSha256 = "abc",
			),
		).isNull()
	}

	@Test
	fun `signatureRefusal proceeds when the installed cert matches the built cert`() {
		assertThat(
			RealIdInstall.signatureRefusal(
				realApplicationId = "com.example.app",
				realAppInstalled = true,
				installedCertSha256 = "ABC123",
				builtCertSha256 = "abc123",
			),
		).isNull()
	}

	@Test
	fun `signatureRefusal refuses when the installed cert differs`() {
		val message =
			RealIdInstall.signatureRefusal(
				realApplicationId = "com.example.app",
				realAppInstalled = true,
				installedCertSha256 = "aaa",
				builtCertSha256 = "bbb",
			)
		assertThat(message).isEqualTo(QuickBuildMessage.ForeignAppInstalled("com.example.app"))
	}

	@Test
	fun `signatureRefusal refuses when either cert is unreadable`() {
		assertThat(
			RealIdInstall.signatureRefusal("com.example.app", true, installedCertSha256 = null, builtCertSha256 = "bbb"),
		).isNotNull()
		assertThat(
			RealIdInstall.signatureRefusal("com.example.app", true, installedCertSha256 = "aaa", builtCertSha256 = null),
		).isNotNull()
	}

	@Test
	fun `refusalMessage names the app and the manual way forward`() {
		// The applicationId travels as data so the host's copy can name it; the sentence
		// around it belongs to the app module's resources, not here.
		val message = RealIdInstall.refusalMessage("com.example.app")
		assertThat(message).isEqualTo(QuickBuildMessage.ForeignAppInstalled("com.example.app"))
	}
}
