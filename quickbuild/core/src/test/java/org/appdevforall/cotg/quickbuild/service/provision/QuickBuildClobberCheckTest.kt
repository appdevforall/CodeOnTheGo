package org.appdevforall.cotg.quickbuild.service.provision

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.reload.RealIdInstall
import org.junit.jupiter.api.Test
import java.io.File

class QuickBuildClobberCheckTest {
	private val realAppId = "com.example.app"
	private val quickBuildFactory = RealIdInstall.QUICK_BUILD_APP_COMPONENT_FACTORY

	/** Scripted [InstalledPackages]: only the two fields the clobber check reads matter. */
	private class FakePackages(
		private val installedUid: Int?,
		private val factory: String?,
	) : InstalledPackages {
		override fun uid(packageName: String): Int? = installedUid

		override fun lastUpdateTime(packageName: String): Long? = null

		override fun apkFile(packageName: String): File? = null

		override fun signingCertSha256(packageName: String): String? = null

		override fun appComponentFactory(packageName: String): String? = factory
	}

	private fun check(
		installed: Boolean,
		factory: String?,
	) = QuickBuildClobberCheck(FakePackages(if (installed) 10_123 else null, factory))

	@Test
	fun `Quick Build tap needs no confirm when the slot is empty`() {
		assertThat(check(installed = false, factory = null).quickBuildNeedsConfirm(realAppId)).isFalse()
	}

	@Test
	fun `Quick Build tap needs no confirm over its own proxy app`() {
		assertThat(check(installed = true, factory = quickBuildFactory).quickBuildNeedsConfirm(realAppId)).isFalse()
	}

	@Test
	fun `Quick Build tap confirms over the Standard Run build`() {
		assertThat(check(installed = true, factory = null).quickBuildNeedsConfirm(realAppId)).isTrue()
	}

	@Test
	fun `Standard Run confirms over a Quick Build proxy app`() {
		assertThat(check(installed = true, factory = quickBuildFactory).standardRunNeedsConfirm(realAppId)).isTrue()
	}

	@Test
	fun `Standard Run needs no confirm over a normal app or an empty slot`() {
		assertThat(check(installed = true, factory = null).standardRunNeedsConfirm(realAppId)).isFalse()
		assertThat(check(installed = false, factory = null).standardRunNeedsConfirm(realAppId)).isFalse()
	}
}
