package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.domain.RealIdInstall

/**
 * Decides whether tapping Quick Build or Standard Run should ask the user to confirm a
 * clobber first.
 *
 * Both build types install under the project's real applicationId, so switching between
 * them overwrites the installed app. The installed package's component factory (read via
 * [InstalledPackages]) says which build occupies the slot; [RealIdInstall] holds the
 * rules. Stateless, so an install or uninstall outside CoGo cannot leave it stale.
 */
class QuickBuildClobberCheck(
	private val packages: InstalledPackages,
) {
	/** True when a Quick Build tap for [realApplicationId] would clobber a different build. */
	fun quickBuildNeedsConfirm(realApplicationId: String): Boolean =
		RealIdInstall.quickBuildNeedsClobberConfirm(
			realAppInstalled = packages.uid(realApplicationId) != null,
			installedFactory = packages.appComponentFactory(realApplicationId),
		)

	/** True when a Standard Run for [realApplicationId] would clobber a Quick Build proxy app. */
	fun standardRunNeedsConfirm(realApplicationId: String): Boolean =
		RealIdInstall.standardRunNeedsClobberConfirm(
			packages.appComponentFactory(realApplicationId),
		)
}
