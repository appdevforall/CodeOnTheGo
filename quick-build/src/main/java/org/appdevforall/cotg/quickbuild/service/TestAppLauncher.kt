package org.appdevforall.cotg.quickbuild.service

/**
 * Relaunches the test app after a restart deploy (design contract section 4, step 3):
 * the runtime persisted the payload and exited; CoGo, being the foreground app while
 * the user edits, starts the launcher proxy activity via an explicit intent and the
 * fresh process boots on the newest persisted generation.
 *
 * Implemented in the app module (needs a Context); an interface here keeps the
 * executor JVM-testable.
 */
fun interface TestAppLauncher {
	/**
	 * Relaunches [packageName]. [activityClass] is the launcher proxy FQN from the
	 * transformed manifest when one is known; null when the launcher is an
	 * `<activity-alias>` (no proxied activity carries it), in which case the
	 * implementation launches the package's default launch intent instead.
	 *
	 * @return false when the launch could not even be started.
	 */
	fun launch(
		packageName: String,
		activityClass: String?,
	): Boolean
}
