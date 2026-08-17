package org.appdevforall.cotg.quickbuild.service.provision

/**
 * Restarts the proxy app after a restart deploy, so a fresh process boots on the newest
 * persisted generation.
 *
 * Implemented in the app module because it needs a Context; the interface keeps the
 * executor JVM-testable.
 */
fun interface ProxyAppLauncher {
	/**
	 * Starts [packageName] again.
	 *
	 * @param packageName the installed proxy app's applicationId
	 * @param activityClass the launcher proxy FQN from the transformed manifest, or null
	 *   when the launcher is an `<activity-alias>` that no proxied activity carries - the
	 *   implementation then uses the package's default launch intent
	 * @return false when the launch could not be started at all
	 */
	fun launch(
		packageName: String,
		activityClass: String?,
	): Boolean
}
