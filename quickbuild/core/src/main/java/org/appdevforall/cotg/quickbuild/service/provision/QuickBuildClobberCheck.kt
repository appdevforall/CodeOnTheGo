package org.appdevforall.cotg.quickbuild.service.provision

import org.appdevforall.cotg.quickbuild.domain.reload.RealIdInstall

/**
 * Decides whether tapping Quick Build or Standard Run should ask the user to confirm a
 * clobber first.
 *
 * Both build types install under the project's real applicationId, so switching between them
 * overwrites the installed app. The installed package's component factory says which build
 * occupies the slot; [RealIdInstall] holds the rules. Stateless, so an install or uninstall
 * outside CoGo cannot leave it stale.
 *
 * @property packages read on every call, never cached, which is what keeps this stateless
 */
class QuickBuildClobberCheck(
	private val packages: InstalledPackages,
) {
	/**
	 * True when a Quick Build tap for [realApplicationId] would clobber a different build.
	 *
	 * @param realApplicationId the project's own applicationId, not the proxy app's
	 * @return true only when the slot holds something a Quick Build would overwrite; an
	 *   empty slot needs no confirmation
	 */
	fun quickBuildNeedsConfirm(realApplicationId: String): Boolean =
		RealIdInstall.quickBuildNeedsClobberConfirm(
			realAppInstalled = packages.uid(realApplicationId) != null,
			installedFactory = packages.appComponentFactory(realApplicationId),
		)

	/**
	 * True when a Standard Run for [realApplicationId] would clobber a Quick Build proxy app.
	 *
	 * @param realApplicationId the project's own applicationId, the slot both builds share
	 * @return true only when the installed app carries the Quick Build runtime factory
	 */
	fun standardRunNeedsConfirm(realApplicationId: String): Boolean =
		RealIdInstall.standardRunNeedsClobberConfirm(
			packages.appComponentFactory(realApplicationId),
		)
}
