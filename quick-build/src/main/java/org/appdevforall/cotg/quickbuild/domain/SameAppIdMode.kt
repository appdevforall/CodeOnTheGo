package org.appdevforall.cotg.quickbuild.domain

/**
 * What CoGo knows about the app currently installed under the project's real
 * applicationId, read ONCE at mode entry (same-app-id design contract, section 2).
 */
data class InstalledRealApp(
	/** PackageInfo.longVersionCode of the installed app. */
	val versionCode: Long,
	/** Lowercase hex SHA-256 of its signing certificate, or null when unreadable. */
	val certSha256: String?,
)

/** Why a same-app-id mode entry was refused (analytics `reason` param, contract section 6). */
enum class SameAppIdRefusalReason {
	SIGNATURE_MISMATCH,
	USER_DECLINED,
}

/** Outcome of the mode-entry decision (contract section 2). */
sealed interface SameAppIdEntryDecision {
	/**
	 * Entry may proceed once the clobber warning is confirmed. [versionCode] is the
	 * pinned override for the whole episode; [updateInstall] distinguishes the
	 * data-preserving update path from a plain fresh install.
	 */
	data class Proceed(
		val versionCode: Int,
		val updateInstall: Boolean,
		/**
		 * False when either certificate was unreadable at entry time. The provisioner
		 * re-verifies authoritatively against the built APK before any install, so an
		 * unverified Proceed can still end in a refusal - but never in an uninstall.
		 */
		val signatureVerified: Boolean,
	) : SameAppIdEntryDecision

	/** Entry is refused outright; [message] names the reason for the user. */
	data class Refuse(
		val message: String,
	) : SameAppIdEntryDecision
}

/**
 * Pure decision logic for same-app-id mode entry (contract section 2): fresh install
 * vs data-preserving update vs refuse-by-default, plus the pinned-versionCode strategy
 * `max(installedVersionCode + 1, project versionCode)`.
 */
object SameAppIdEntry {
	/**
	 * @param installed facts about the app installed under [realApplicationId], or null
	 *   when nothing is installed.
	 * @param projectVersionCode the project's own versionCode when known.
	 * @param cogoCertSha256 SHA-256 of the certificate CoGo's on-device builds sign
	 *   with, when known.
	 */
	fun decide(
		realApplicationId: String,
		installed: InstalledRealApp?,
		projectVersionCode: Int?,
		cogoCertSha256: String?,
	): SameAppIdEntryDecision {
		val floor = (projectVersionCode ?: 1).coerceAtLeast(1).toLong()
		if (installed == null) {
			return SameAppIdEntryDecision.Proceed(
				versionCode = floor.toInt(),
				updateInstall = false,
				signatureVerified = true,
			)
		}

		val bothCertsKnown = installed.certSha256 != null && cogoCertSha256 != null
		if (bothCertsKnown && !installed.certSha256.equals(cogoCertSha256, ignoreCase = true)) {
			return SameAppIdEntryDecision.Refuse(refusalMessage(realApplicationId))
		}

		val candidate = maxOf(installed.versionCode + 1, floor)
		if (candidate > Int.MAX_VALUE) {
			return SameAppIdEntryDecision.Refuse(
				"The installed $realApplicationId has versionCode ${installed.versionCode}; " +
					"a higher versionCode cannot be represented, so an update install is impossible.",
			)
		}
		return SameAppIdEntryDecision.Proceed(
			versionCode = candidate.toInt(),
			updateInstall = true,
			signatureVerified = bothCertsKnown,
		)
	}

	/** The contract's refusal wording: names the reason and the manual way forward. */
	fun refusalMessage(realApplicationId: String): String =
		"The installed $realApplicationId was not built by this device's CoGo - " +
			"same-app-id Quick Build would have to delete it and its data. " +
			"Back up and uninstall it manually first if you really want this mode."
}
