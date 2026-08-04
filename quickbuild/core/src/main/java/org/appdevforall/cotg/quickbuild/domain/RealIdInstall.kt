package org.appdevforall.cotg.quickbuild.domain

/**
 * Decides when installing under the project's real applicationId needs the user's confirmation.
 *
 * Quick Build and Standard Run share one package slot - the real applicationId, with no
 * `.quickbuild` suffix - so installing one overwrites the other. Which build occupies the slot
 * is read statelessly from the installed package's `android:appComponentFactory`: a factory
 * equal to [QUICK_BUILD_APP_COMPONENT_FACTORY] means a Quick Build proxy app, anything else
 * means the user's Standard-Run build or a third-party app of the same id.
 */
object RealIdInstall {
	/**
	 * FQN of the Quick Build runtime's AppComponentFactory, the marker identifying an installed
	 * package as a Quick Build proxy app.
	 *
	 * Must stay in sync with the runtime class of the same name and with the value the Gradle
	 * plugin writes into the manifest (`QuickBuildPlugin.APP_COMPONENT_FACTORY`).
	 */
	const val QUICK_BUILD_APP_COMPONENT_FACTORY =
		"com.itsaky.androidide.quickbuild.runtime.QuickBuildAppComponentFactory"

	/**
	 * True when the package installed under the real id is a Quick Build proxy app.
	 *
	 * @param installedFactory the installed package's `android:appComponentFactory`, or null when
	 *   nothing is installed or the manifest declares none.
	 * @return true only on an exact match with [QUICK_BUILD_APP_COMPONENT_FACTORY]; null and any
	 *   other factory both mean "not ours".
	 */
	fun isQuickBuildProxyApp(installedFactory: String?): Boolean = installedFactory == QUICK_BUILD_APP_COMPONENT_FACTORY

	/**
	 * Whether tapping Quick Build must confirm a clobber first.
	 *
	 * Only when a different build occupies the slot; a fresh slot or Quick Build's own proxy
	 * app installs without a prompt.
	 *
	 * @param realAppInstalled whether anything is installed under the project's real applicationId.
	 * @param installedFactory that package's `android:appComponentFactory`, or null when unreadable
	 *   or undeclared - an unreadable one counts as somebody else's build.
	 * @return true when the user must confirm overwriting a non-Quick-Build package.
	 */
	fun quickBuildNeedsClobberConfirm(
		realAppInstalled: Boolean,
		installedFactory: String?,
	): Boolean = realAppInstalled && !isQuickBuildProxyApp(installedFactory)

	/**
	 * Whether a Standard Run must confirm a clobber first.
	 *
	 * Only when a Quick Build proxy app occupies the slot; over a normal app or nothing,
	 * Standard Run behaves as always.
	 *
	 * @param installedFactory the installed package's `android:appComponentFactory`, or null when
	 *   nothing is installed.
	 * @return true when a Quick Build proxy app is about to be overwritten, which also ends its
	 *   session.
	 */
	fun standardRunNeedsClobberConfirm(installedFactory: String?): Boolean = isQuickBuildProxyApp(installedFactory)

	/**
	 * Refuses to install the proxy app over a real-id package this device's CoGo did not build.
	 *
	 * The provisioner's authoritative safety check: an update-install cannot preserve a
	 * third-party app's data, so the only way past a refusal is a manual uninstall. An
	 * unreadable cert on either side counts as "cannot prove same origin" and refuses.
	 *
	 * @param realApplicationId the project's real applicationId, named back to the user in the
	 *   refusal.
	 * @param realAppInstalled whether anything occupies that slot; an empty slot always proceeds.
	 * @param installedCertSha256 signing-cert SHA-256 of the installed package, or null when it
	 *   cannot be read - which refuses.
	 * @param builtCertSha256 signing-cert SHA-256 of the proxy app about to be installed, or null
	 *   when it cannot be read - which also refuses.
	 * @return the refusal message, or null to proceed.
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

	/**
	 * The refusal wording: names the reason and the manual way forward.
	 *
	 * @param realApplicationId the applicationId to name in the message.
	 * @return the user-facing wording, ready to show unchanged.
	 */
	fun refusalMessage(realApplicationId: String): String =
		"The installed $realApplicationId was not built by this device's CoGo - " +
			"Quick Build would have to delete it and its data to install. " +
			"Back up and uninstall it manually first if you want to run it with Quick Build."
}
