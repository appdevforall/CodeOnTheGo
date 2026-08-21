package org.appdevforall.cotg.quickbuild.service.provision

/**
 * Restarts the proxy app after a restart deploy, so a fresh process boots on the newest
 * persisted generation - resuming the app's existing task where there is one, so the user
 * comes back to the screen and back stack they left.
 *
 * Implemented in the app module because it needs a Context; the interface keeps the
 * executor JVM-testable.
 */
fun interface ProxyAppLauncher {
	/**
	 * Starts [packageName] again.
	 *
	 * @param packageName the installed proxy app's applicationId; the implementation launches
	 *   it the way a home screen would, which is what resumes its task
	 * @param activityClass the launcher proxy FQN from the transformed manifest, or null when
	 *   the launcher is an `<activity-alias>` that no proxied activity carries. A fallback
	 *   only, for an app that declares no launcher: an explicit component intent starts a
	 *   screen rather than resuming a task.
	 * @return false when the launch could not be started at all
	 */
	fun launch(
		packageName: String,
		activityClass: String?,
	): Boolean
}
