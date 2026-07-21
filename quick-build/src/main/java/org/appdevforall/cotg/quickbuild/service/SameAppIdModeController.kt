package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.domain.InstalledRealApp
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.SameAppIdEntry
import org.appdevforall.cotg.quickbuild.domain.SameAppIdEntryDecision
import org.appdevforall.cotg.quickbuild.domain.SameAppIdGuard
import org.appdevforall.cotg.quickbuild.domain.SameAppIdRefusalReason
import org.slf4j.LoggerFactory

/**
 * Orchestrates same-app-id mode episodes (design contract sections 2, 3, 4, 6): the
 * entry decision (fresh install / data-preserving update / refuse), the clobber-warning
 * confirmation that mints the guard token and pins the versionCode, and the Standard
 * Run restore that ends an episode. The UI drives it (dialog on every mode ENTRY);
 * the provisioner reads the resulting [QuickBuildModeStore] + [SameAppIdGuard] state.
 *
 * Mode flips are a rebaseline boundary: [onModeChanged] fires on every persisted flip
 * so the app can stop a live session (nothing from the old package identity is
 * trustworthy). Not thread-safe; call from one thread (the UI/session thread).
 */
class SameAppIdModeController(
	private val store: QuickBuildModeStore,
	private val packages: InstalledPackages,
	private val guard: SameAppIdGuard,
	private val metrics: QuickBuildMetricsSink = QuickBuildMetricsSink.Noop,
	/**
	 * SHA-256 of the cert CoGo's on-device builds sign with, for the given real
	 * applicationId, when determinable at entry time (e.g. from an installed
	 * `.quickbuild` sibling). Null = unverifiable here; the provisioner re-verifies
	 * against the built APK before any install.
	 */
	private val cogoCertSha256: (String) -> String? = { null },
	/** Fired after every persisted mode flip; the app restarts the live session. */
	private val onModeChanged: () -> Unit = {},
) {
	/** What the UI should do with a mode-entry request. */
	sealed interface EntryRequest {
		/** Show the destructive-styled clobber warning (contract section 3). */
		data class ShowWarning(
			val realApplicationId: String,
			/** False when nothing is installed: drop the "existing app replaced" line. */
			val existingInstallReplaced: Boolean,
			val updateInstall: Boolean,
			val pinnedVersionCode: Int,
		) : EntryRequest

		/** Entry refused (signature mismatch); show [message], leave the toggle off. */
		data class Refused(
			val message: String,
		) : EntryRequest
	}

	/** Outcome of a Standard Run install landing while an episode may be active. */
	data class StandardRunRestore(
		/** True when this install ended a same-app-id episode (session must stop). */
		val episodeEnded: Boolean,
		/** True when the install should request a downgrade (restore below the pin). */
		val requestDowngrade: Boolean,
	)

	private data class PendingEntry(
		val realApplicationId: String,
		val proceed: SameAppIdEntryDecision.Proceed,
	)

	private var pending: PendingEntry? = null

	/**
	 * Set when a restore ended the episode: later Run installs may still need the
	 * downgrade request until versionCodes realign. Cleared on the next mode entry.
	 */
	private var restoreDowngradePending = false

	fun isModeEnabled(): Boolean = store.isSameAppIdEnabled()

	fun isEpisodeConfirmed(): Boolean = guard.isEpisodeConfirmed()

	/** True when the toggle is on but this episode's warning has not been confirmed yet. */
	fun needsEntryConfirmation(): Boolean = store.isSameAppIdEnabled() && !guard.isEpisodeConfirmed()

	/**
	 * Re-arms the guard from a persisted, already-confirmed episode (CoGo restarted
	 * mid-episode). Call at project open; the warning shows once per EPISODE, not once
	 * per process.
	 */
	fun restoreEpisode() {
		if (guard.isEpisodeConfirmed()) return
		val realAppId = store.episodeRealApplicationId() ?: return
		if (store.isSameAppIdEnabled() && store.isClobberConfirmed() && store.pinnedVersionCode() != null) {
			log.info("Restoring confirmed same-app-id episode for {}", realAppId)
			guard.mintClobberToken(realAppId)
		}
	}

	/**
	 * The user asked to enter same-app-id mode (or tapped the bolt with the toggle on
	 * but no confirmed episode). Reads the installed real app ONCE and decides; a
	 * [EntryRequest.ShowWarning] must be answered with [confirmEntry] or [declineEntry].
	 */
	fun requestEntry(
		realApplicationId: String,
		projectVersionCode: Int?,
	): EntryRequest {
		val installed =
			packages.versionCode(realApplicationId)?.let { versionCode ->
				InstalledRealApp(versionCode, packages.signingCertSha256(realApplicationId))
			}
		return when (
			val decision =
				SameAppIdEntry.decide(
					realApplicationId = realApplicationId,
					installed = installed,
					projectVersionCode = projectVersionCode,
					cogoCertSha256 = cogoCertSha256(realApplicationId),
				)
		) {
			is SameAppIdEntryDecision.Refuse -> {
				pending = null
				report { metrics.onSameAppIdRefused(SameAppIdRefusalReason.SIGNATURE_MISMATCH) }
				EntryRequest.Refused(decision.message)
			}
			is SameAppIdEntryDecision.Proceed -> {
				pending = PendingEntry(realApplicationId, decision)
				EntryRequest.ShowWarning(
					realApplicationId = realApplicationId,
					existingInstallReplaced = decision.updateInstall,
					updateInstall = decision.updateInstall,
					pinnedVersionCode = decision.versionCode,
				)
			}
		}
	}

	/**
	 * The clobber warning was accepted: pin the versionCode, mint the guard token,
	 * persist the episode, emit analytics, and signal the rebaseline boundary.
	 */
	fun confirmEntry() {
		val entry = checkNotNull(pending) { "confirmEntry without a pending requestEntry" }
		pending = null
		restoreDowngradePending = false
		store.setSameAppIdEnabled(true)
		store.setPinnedVersionCode(entry.proceed.versionCode)
		store.setEpisodeRealApplicationId(entry.realApplicationId)
		store.setClobberConfirmed(true)
		guard.mintClobberToken(entry.realApplicationId)
		report { metrics.onSameAppIdClobberConfirmed() }
		report { metrics.onSameAppIdEntered(updateInstall = entry.proceed.updateInstall) }
		onModeChanged()
	}

	/**
	 * The warning was declined: nothing was touched, the toggle stays (or returns to)
	 * off. On a re-entry decline (toggle was on from a previous episode), the flip back
	 * to off is a mode change and stops any live session.
	 */
	fun declineEntry() {
		pending = null
		report { metrics.onSameAppIdRefused(SameAppIdRefusalReason.USER_DECLINED) }
		if (store.isSameAppIdEnabled()) {
			clearMode()
			onModeChanged()
		}
	}

	/** The user flipped the toggle off; ends any episode and stops the session. */
	fun disableMode() {
		if (!store.isSameAppIdEnabled() && !guard.isEpisodeConfirmed()) return
		clearMode()
		onModeChanged()
	}

	/**
	 * A Standard Run is about to install the project's real app (contract section 4):
	 * in same-app-id mode the hand-back IS the restore - symmetric clobber, no second
	 * warning. Ends the episode (the toggle stays on; the next bolt tap re-enters via
	 * the warning) and reports whether the install should request a downgrade, since
	 * the project versionCode is typically below the pinned test versionCode.
	 *
	 * @param downgradeAvailable false on API 28, where no downgrade API exists; the
	 *   caller then owns the explicit uninstall warning (never silent).
	 */
	fun onStandardRunInstall(downgradeAvailable: Boolean): StandardRunRestore {
		if (!guard.isEpisodeConfirmed()) {
			return StandardRunRestore(
				episodeEnded = false,
				requestDowngrade = restoreDowngradePending && downgradeAvailable,
			)
		}
		guard.endEpisode()
		store.setClobberConfirmed(false)
		store.setPinnedVersionCode(null)
		store.setEpisodeRealApplicationId(null)
		restoreDowngradePending = true
		report { metrics.onSameAppIdRestored(downgradeUsed = downgradeAvailable) }
		return StandardRunRestore(episodeEnded = true, requestDowngrade = downgradeAvailable)
	}

	private fun clearMode() {
		store.setSameAppIdEnabled(false)
		store.setPinnedVersionCode(null)
		store.setClobberConfirmed(false)
		store.setEpisodeRealApplicationId(null)
		guard.endEpisode()
	}

	/** Metrics can never affect the mode flow: a throwing sink degrades to a warning. */
	private inline fun report(block: () -> Unit) {
		try {
			block()
		} catch (e: Throwable) {
			log.warn("Quick Build metrics sink failed", e)
		}
	}

	private companion object {
		private val log = LoggerFactory.getLogger(SameAppIdModeController::class.java)
	}
}
