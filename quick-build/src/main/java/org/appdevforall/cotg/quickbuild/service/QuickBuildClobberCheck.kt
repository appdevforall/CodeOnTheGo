package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.domain.RealIdInstall

/**
 * Decides whether tapping Quick Build or Standard Run should confirm a clobber first
 * (ADFA-4128). Both build types install under the project's real applicationId, so switching
 * from one to the other overwrites the installed app. This queries the installed package's
 * component factory (via [InstalledPackages]) to tell which build occupies the slot and applies
 * [RealIdInstall]'s pure rules. Stateless: it reads the device each time and holds no state,
 * so an install or uninstall outside CoGo can never leave it stale.
 */
class QuickBuildClobberCheck(
	private val packages: InstalledPackages,
) {
	/** True when a Quick Build tap for [realApplicationId] would clobber a different build. */
	fun quickBuildNeedsConfirm(realApplicationId: String): Boolean =
		RealIdInstall.quickBuildNeedsClobberConfirm(
			realAppInstalled = packages.uid(realApplicationId) != null,
			installedAppComponentFactory = packages.appComponentFactory(realApplicationId),
		)

	/** True when a Standard Run for [realApplicationId] would clobber a Quick Build test app. */
	fun standardRunNeedsConfirm(realApplicationId: String): Boolean =
		RealIdInstall.standardRunNeedsClobberConfirm(
			packages.appComponentFactory(realApplicationId),
		)
}
