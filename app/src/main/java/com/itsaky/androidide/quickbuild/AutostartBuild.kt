package com.itsaky.androidide.quickbuild

/**
 * The build an external harness asked the editor to fire in place of the user's first tap
 * (ADFA-4128). The harness only exists in a debug build, so a release build always sees
 * [NONE] - see the release twin of `QuickBuildBenchHooks`.
 *
 * @property suppressesPrebuild whether claiming this autostart skips the eager Quick Build prebuild
 *   on project init, which only [STANDARD] does so the build it measures has the Gradle daemon to
 *   itself.
 */
enum class AutostartBuild(
	val suppressesPrebuild: Boolean,
) {
	/** Nothing armed. The editor behaves exactly as it does for a human. */
	NONE(suppressesPrebuild = false),

	/** Fire the Quick Build lightning-bolt tap. */
	QUICK_BUILD(suppressesPrebuild = false),

	/** Fire the standard Run build, for the standard-vs-proxy-app-build comparison. */
	STANDARD(suppressesPrebuild = true),
}
