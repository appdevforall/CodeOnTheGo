package org.appdevforall.cotg.quickbuild.domain

/**
 * Pure decision logic for installing under the project's real applicationId (ADFA-4128).
 *
 * Quick Build and Standard Run share one package slot - the project's real applicationId.
 * There is no `.quickbuild` suffix and no separate install id, so installing one build type
 * overwrites whatever occupies the slot. The UI therefore confirms a switch before it
 * clobbers the other build.
 *
 * Which build currently occupies the slot is read statelessly from the installed package's
 * `android:appComponentFactory` (no persisted marker): the Quick Build setup build stamps
 * [QUICK_BUILD_APP_COMPONENT_FACTORY] into the test app's manifest, so an installed factory
 * equal to it means "a Quick Build test app"; anything else means "the user's Standard-Run
 * build" (or a third-party app of the same id, caught by [signatureRefusal] before any
 * clobber).
 */
object RealIdInstall {
	/**
	 * FQN of the Quick Build runtime's AppComponentFactory. The setup build sets exactly this
	 * as the test app's `android:appComponentFactory`, making it the marker that identifies an
	 * installed package as a Quick Build test app. MUST stay in sync with the runtime class
	 * `com.itsaky.androidide.quickbuild.runtime.QuickBuildAppComponentFactory` and the value the
	 * Gradle plugin writes into the manifest (`QuickBuildPlugin.APP_COMPONENT_FACTORY`).
	 */
	const val QUICK_BUILD_APP_COMPONENT_FACTORY =
		"com.itsaky.androidide.quickbuild.runtime.QuickBuildAppComponentFactory"

	/** True when the package installed under the real id is a Quick Build test app. */
	fun isQuickBuildTestApp(installedAppComponentFactory: String?): Boolean =
		installedAppComponentFactory == QUICK_BUILD_APP_COMPONENT_FACTORY

	/**
	 * Whether tapping Quick Build should confirm a clobber first. Confirm only when a
	 * DIFFERENT build already occupies the slot (the Standard-Run app, or a third-party app);
	 * a fresh slot or Quick Build's own test app installs without a prompt.
	 */
	fun quickBuildNeedsClobberConfirm(
		realAppInstalled: Boolean,
		installedAppComponentFactory: String?,
	): Boolean = realAppInstalled && !isQuickBuildTestApp(installedAppComponentFactory)

	/**
	 * Whether a Standard Run should confirm a clobber first. Confirm only when a Quick Build
	 * test app occupies the slot; over a normal app (or nothing) Standard Run behaves as always.
	 */
	fun standardRunNeedsClobberConfirm(installedAppComponentFactory: String?): Boolean =
		isQuickBuildTestApp(installedAppComponentFactory)

	/**
	 * The provisioner's authoritative safety check before installing the test app over an
	 * existing real-id package: returns a refusal message when the occupant was NOT built by
	 * this device's CoGo (its signing cert differs from the freshly built test APK's), else null
	 * to proceed. Refusing here prevents clobbering a third-party install of the same id, whose
	 * data an update-install cannot preserve; the only way past it is a manual uninstall.
	 *
	 * An unreadable cert on either side counts as "cannot prove same origin" and refuses - by
	 * this point the APK exists and API 28+ can read an installed cert, so unreadable means we
	 * cannot guarantee a data-preserving update.
	 */
	fun signatureRefusal(
		realApplicationId: String,
		realAppInstalled: Boolean,
		installedCertSha256: String?,
		builtCertSha256: String?,
	): String? {
		if (!realAppInstalled) return null
		if (installedCertSha256 != null &&
			builtCertSha256 != null &&
			installedCertSha256.equals(builtCertSha256, ignoreCase = true)
		) {
			return null
		}
		return refusalMessage(realApplicationId)
	}

	/** The refusal wording: names the reason and the manual way forward. */
	fun refusalMessage(realApplicationId: String): String =
		"The installed $realApplicationId was not built by this device's CoGo - " +
			"Quick Build would have to delete it and its data to install. " +
			"Back up and uninstall it manually first if you want to run it with Quick Build."
}
