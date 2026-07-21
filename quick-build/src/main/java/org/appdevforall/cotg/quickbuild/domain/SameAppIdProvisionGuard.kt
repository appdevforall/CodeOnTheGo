package org.appdevforall.cotg.quickbuild.domain

/** Decision from the same-app-id provision gate (design contract sections 2 + 7). */
sealed interface SameAppIdProvisionDecision {
	/** The install may proceed. */
	object Proceed : SameAppIdProvisionDecision

	/**
	 * Refuse the provision; [message] is user-facing. [signatureMismatch] is true only
	 * for the built-vs-installed cert refusal, which the caller reports to analytics
	 * (`reason=signature_mismatch`); other refusals are internal-state errors.
	 */
	data class Refuse(
		val message: String,
		val signatureMismatch: Boolean = false,
	) : SameAppIdProvisionDecision
}

/**
 * Pure decision logic for the provisioner's same-app-id gates - the last line of
 * defense before installing over the user's real app (design contract sections 2 + 7).
 *
 * Extracted out of `GradleQuickBuildProvisioner` so these rules are JVM-tested rather
 * than trapped behind that class's Android `Context` and global-singleton setup path
 * (`Lookup`, `IProjectManager`), which its `provision()` cannot exercise in a unit test.
 * The provisioner keeps all I/O - reading signing certs, emitting analytics, logging;
 * this object is only the boolean logic between those reads.
 */
object SameAppIdProvisionGuard {

	/**
	 * Runs BEFORE the setup build: same-app-id mode must be a confirmed episode (a
	 * pinned versionCode exists) and the override must not decrease within the episode
	 * (contract section 2's downgrade guard). Returns null to proceed, else the message.
	 */
	fun preflight(
		sameAppId: Boolean,
		pinnedVersionCode: Int?,
		guard: SameAppIdGuard,
	): String? {
		if (!sameAppId) return null
		val pinned =
			pinnedVersionCode
				?: return "Same-app-id mode is enabled but its warning was never confirmed; " +
					"toggle the mode again to confirm"
		return try {
			guard.checkVersionCodeOverride(pinned)
			null
		} catch (e: SameAppIdGuard.Violation) {
			e.message ?: "Quick Build safety guard violation"
		}
	}

	/**
	 * Runs BETWEEN the setup build and the install: the plugin honored the mode, the
	 * pinned versionCode was applied, the signature-mismatch refusal (contract section 2
	 * - refuse loud, never uninstall), and the [SameAppIdGuard] assertions (section 7).
	 *
	 * Certs are read by the caller (I/O); an unreadable cert on either side counts as a
	 * mismatch - by this point the APK exists and API 28+ can read an installed cert, so
	 * "unreadable" means "cannot prove the update install would preserve data".
	 *
	 * @param realAppInstalled a package already exists under [realApplicationId].
	 */
	fun installGate(
		modeSameAppId: Boolean,
		pinnedVersionCode: Int?,
		setupSameAppId: Boolean,
		setupVersionCode: Int?,
		testAppPackage: String,
		realApplicationId: String,
		realAppInstalled: Boolean,
		installedCertSha256: String?,
		builtCertSha256: String?,
		guard: SameAppIdGuard,
	): SameAppIdProvisionDecision {
		if (setupSameAppId != modeSameAppId) {
			return SameAppIdProvisionDecision.Refuse(
				"The setup build did not honor same-app-id mode (setup.json says " +
					"sameAppId=$setupSameAppId); is the Quick Build Gradle plugin up to date?",
			)
		}
		if (modeSameAppId) {
			if (setupVersionCode != pinnedVersionCode) {
				return SameAppIdProvisionDecision.Refuse(
					"The setup build applied versionCode $setupVersionCode instead of the " +
						"pinned $pinnedVersionCode; refusing to install",
				)
			}
			signatureRefusal(
				realApplicationId,
				realAppInstalled,
				installedCertSha256,
				builtCertSha256,
			)?.let { return it }
		}
		return try {
			guard.checkInstall(modeSameAppId, testAppPackage, realApplicationId)
			SameAppIdProvisionDecision.Proceed
		} catch (e: SameAppIdGuard.Violation) {
			SameAppIdProvisionDecision.Refuse(e.message ?: "Quick Build safety guard violation")
		}
	}

	private fun signatureRefusal(
		realApplicationId: String,
		realAppInstalled: Boolean,
		installedCertSha256: String?,
		builtCertSha256: String?,
	): SameAppIdProvisionDecision.Refuse? {
		if (!realAppInstalled) return null
		if (installedCertSha256 != null &&
			builtCertSha256 != null &&
			installedCertSha256.equals(builtCertSha256, ignoreCase = true)
		) {
			return null
		}
		return SameAppIdProvisionDecision.Refuse(
			SameAppIdEntry.refusalMessage(realApplicationId),
			signatureMismatch = true,
		)
	}
}
