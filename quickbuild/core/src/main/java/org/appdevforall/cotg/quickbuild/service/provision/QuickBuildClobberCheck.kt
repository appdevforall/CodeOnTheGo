package org.appdevforall.cotg.quickbuild.service.provision

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Both reads reach PackageManager, which is binder I/O, so both are suspending and hop to
 * [ioDispatcher]. The live callers are tap handlers on the main thread.
 *
 * @property packages read on every call, never cached, which is what keeps this stateless
 * @property ioDispatcher where the PackageManager reads run; injected so tests stay direct
 */
class QuickBuildClobberCheck(
	private val packages: InstalledPackages,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
	/**
	 * True when a Quick Build tap for [realApplicationId] would clobber a different build.
	 *
	 * @param realApplicationId the project's own applicationId, not the proxy app's
	 * @return true only when the slot holds something a Quick Build would overwrite; an
	 *   empty slot needs no confirmation
	 */
	suspend fun quickBuildNeedsConfirm(realApplicationId: String): Boolean =
		withContext(ioDispatcher) {
			RealIdInstall.quickBuildNeedsClobberConfirm(
				realAppInstalled = packages.uid(realApplicationId) != null,
				installedFactory = packages.appComponentFactory(realApplicationId),
			)
		}

	/**
	 * True when a Standard Run for [realApplicationId] would clobber a Quick Build proxy app.
	 *
	 * @param realApplicationId the project's own applicationId, the slot both builds share
	 * @return true only when the installed app carries the Quick Build runtime factory
	 */
	suspend fun standardRunNeedsConfirm(realApplicationId: String): Boolean =
		withContext(ioDispatcher) {
			RealIdInstall.standardRunNeedsClobberConfirm(
				packages.appComponentFactory(realApplicationId),
			)
		}
}
