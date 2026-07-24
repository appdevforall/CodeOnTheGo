package org.appdevforall.cotg.quickbuild.domain

/**
 * Hard safety assertions for install/uninstall targets (same-app-id design contract,
 * section 7) - the last line, independent of UI flow bugs. The provisioner consults
 * this immediately before every install; nothing in Quick Build may touch the real
 * applicationId without a clobber token minted by a confirmed warning dialog for the
 * current episode.
 *
 * The token is episode-scoped and in-memory; on a CoGo restart mid-episode the mode
 * controller re-mints it from the persisted, already-confirmed episode state. Minting
 * stays centralized in the controller so no other code path can self-authorize.
 */
class SameAppIdGuard {
	/** A violated assertion: abort loud, touch nothing (never-stale spirit). */
	class Violation(
		message: String,
	) : IllegalStateException(message)

	/** The real applicationId the current episode's clobber confirmation covers. */
	private var tokenFor: String? = null

	/** Highest versionCode override sent this episode; overrides must never decrease. */
	private var versionCodeFloor: Int? = null

	/**
	 * Records the confirmed clobber warning for [realApplicationId]. Only the mode
	 * controller calls this, on dialog confirmation or on restoring a persisted
	 * confirmed episode.
	 */
	fun mintClobberToken(realApplicationId: String) {
		tokenFor = realApplicationId
		versionCodeFloor = null
	}

	/** Ends the episode: the token and the versionCode floor are gone. */
	fun endEpisode() {
		tokenFor = null
		versionCodeFloor = null
	}

	fun isEpisodeConfirmed(): Boolean = tokenFor != null

	/**
	 * Rule 1 + rule 3: suffix mode may only install a `.quickbuild`-suffixed package
	 * that differs from the real applicationId; same-app-id mode may only install the
	 * real applicationId, and only under this episode's confirmed token.
	 *
	 * @throws Violation when the install must not proceed.
	 */
	fun checkInstall(
		sameAppId: Boolean,
		targetPackage: String,
		realApplicationId: String,
	) {
		if (!sameAppId) {
			if (!targetPackage.endsWith(TEST_APP_ID_SUFFIX) || targetPackage == realApplicationId) {
				throw Violation(
					"Quick Build tried to install applicationId '$targetPackage', which is not a " +
						"'$TEST_APP_ID_SUFFIX'-suffixed test package distinct from the project's " +
						"applicationId '$realApplicationId'. This is an internal Quick Build bug, " +
						"not a project misconfiguration - rebaseline the project (edit any Gradle " +
						"file) and try again; if it recurs, please report a bug.",
				)
			}
			return
		}
		if (targetPackage != realApplicationId) {
			throw Violation(
				"Quick Build tried to install applicationId '$targetPackage', but the project's " +
					"applicationId is now '$realApplicationId'. Turn same-app-id mode off and back " +
					"on to re-confirm it against the current applicationId.",
			)
		}
		if (tokenFor != realApplicationId) {
			throw Violation(
				"No confirmed clobber warning for '$realApplicationId' in this episode; " +
					"refusing to install over the real app",
			)
		}
	}

	/**
	 * Rule 2: no code path may uninstall the real applicationId without this episode's
	 * token. Install failures must surface, never "resolve" themselves by uninstalling.
	 *
	 * v1 wires **no** uninstall path (the API-28 guided restore of design contract
	 * section 4 is a followup), so this method currently has no caller. It is kept and
	 * JVM-tested as the tripwire that stops that future path from being added without the
	 * token check - do not delete it as "dead code".
	 *
	 * @throws Violation when the uninstall must not proceed.
	 */
	fun checkUninstall(
		packageName: String,
		realApplicationId: String,
	) {
		if (packageName == realApplicationId && tokenFor != realApplicationId) {
			throw Violation(
				"Refusing to uninstall '$packageName': no confirmed warning covers the " +
					"real applicationId in this episode",
			)
		}
	}

	/**
	 * The provisioner's downgrade guard (contract section 2): the pinned versionCode
	 * override may never decrease within an episode. The floor resets when the episode
	 * changes ([mintClobberToken] / [endEpisode]).
	 *
	 * @throws Violation when [versionCode] is below a previously sent override.
	 */
	fun checkVersionCodeOverride(versionCode: Int) {
		val floor = versionCodeFloor
		if (floor != null && versionCode < floor) {
			throw Violation(
				"versionCode override decreased within an episode ($floor -> $versionCode); " +
					"refusing to build an uninstallable APK",
			)
		}
		versionCodeFloor = maxOf(floor ?: versionCode, versionCode)
	}

	companion object {
		/** The applicationId suffix the setup build appends in (default) suffix mode. */
		const val TEST_APP_ID_SUFFIX = ".quickbuild"
	}
}
