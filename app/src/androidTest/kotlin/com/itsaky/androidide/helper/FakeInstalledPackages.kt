package com.itsaky.androidide.helper

import org.appdevforall.cotg.quickbuild.service.provision.InstalledPackages
import java.io.File

/**
 * Fake occupant of a project's real applicationId, so a test can drive the Quick Build
 * clobber gate deterministically without installing anything.
 *
 * [installed] `false` reads as "the slot is empty", which is what makes a real Quick Build
 * tap proceed straight to provisioning with no confirm. `true`, with a null component
 * factory, reads as "a Standard-Run build occupies the slot" - the state that must pop the
 * clobber confirm, per `RealIdInstall`'s rules.
 */
class FakeInstalledPackages : InstalledPackages {
	@Volatile var installed: Boolean = false

	override fun uid(packageName: String): Int? = if (installed) FAKE_UID else null

	override fun lastUpdateTime(packageName: String): Long? = null

	override fun apkFile(packageName: String): File? = null

	override fun signingCertSha256(packageName: String): String? = null

	override fun appComponentFactory(packageName: String): String? = null

	private companion object {
		/** Any non-null uid; the rules only ask whether the slot is occupied. */
		private const val FAKE_UID = 12345
	}
}
