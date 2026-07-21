package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.SameAppIdGuard
import org.appdevforall.cotg.quickbuild.domain.SameAppIdRefusalReason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class SameAppIdModeControllerTest {
	private val appId = "com.example.app"
	private val cert = "aa".repeat(32)
	private val otherCert = "bb".repeat(32)

	private class MemoryModeStore : QuickBuildModeStore {
		private var enabled = false
		private var pinned: Int? = null
		private var confirmed = false
		private var realAppId: String? = null
		private var restorePending = false

		override fun isSameAppIdEnabled(): Boolean = enabled

		override fun setSameAppIdEnabled(enabled: Boolean) {
			this.enabled = enabled
		}

		override fun pinnedVersionCode(): Int? = pinned

		override fun setPinnedVersionCode(versionCode: Int?) {
			pinned = versionCode
		}

		override fun isClobberConfirmed(): Boolean = confirmed

		override fun setClobberConfirmed(confirmed: Boolean) {
			this.confirmed = confirmed
		}

		override fun episodeRealApplicationId(): String? = realAppId

		override fun setEpisodeRealApplicationId(applicationId: String?) {
			realAppId = applicationId
		}

		override fun isRestoreDowngradePending(): Boolean = restorePending

		override fun setRestoreDowngradePending(pending: Boolean) {
			restorePending = pending
		}
	}

	private class FakePackages : InstalledPackages {
		val versionCodes = mutableMapOf<String, Long>()
		val certs = mutableMapOf<String, String>()

		override fun uid(packageName: String): Int? = if (packageName in versionCodes) 10001 else null

		override fun lastUpdateTime(packageName: String): Long? = null

		override fun apkFile(packageName: String): File? = null

		override fun versionCode(packageName: String): Long? = versionCodes[packageName]

		override fun signingCertSha256(packageName: String): String? = certs[packageName]
	}

	private class RecordingMetrics : QuickBuildMetricsSink {
		val events = mutableListOf<String>()
		var throwOnCall = false

		private fun record(event: String) {
			if (throwOnCall) throw IllegalStateException("sink down")
			events += event
		}

		override fun onSessionStarted() = Unit

		override fun onBuildStarted(
			buildId: Long,
			route: org.appdevforall.cotg.quickbuild.domain.BuildRoute,
			changes: org.appdevforall.cotg.quickbuild.domain.ChangedFiles,
		) = Unit

		override fun onBuildFinished(
			buildId: Long,
			outcome: org.appdevforall.cotg.quickbuild.domain.BuildOutcome,
		) = Unit

		override fun onInvalidation(reason: org.appdevforall.cotg.quickbuild.domain.InvalidationReason) = Unit

		override fun onRebaseline(
			isSuccess: Boolean,
			durationMillis: Long,
		) = Unit

		override fun onSameAppIdEntered(updateInstall: Boolean) = record("entered:$updateInstall")

		override fun onSameAppIdClobberConfirmed() = record("confirmed")

		override fun onSameAppIdRefused(reason: SameAppIdRefusalReason) = record("refused:$reason")

		override fun onSameAppIdRestored(downgradeUsed: Boolean) = record("restored:$downgradeUsed")
	}

	private val store = MemoryModeStore()
	private val packages = FakePackages()
	private val guard = SameAppIdGuard()
	private val metrics = RecordingMetrics()
	private var modeChanges = 0

	private fun controller(cogoCert: String? = cert) =
		SameAppIdModeController(
			store = store,
			packages = packages,
			guard = guard,
			metrics = metrics,
			cogoCertSha256 = { cogoCert },
			onModeChanged = { modeChanges++ },
		)

	@Test
	fun `entry with nothing installed warns without the replaced line and pins 1`() {
		val request = controller().requestEntry(appId, projectVersionCode = null)

		val warning = request as SameAppIdModeController.EntryRequest.ShowWarning
		assertThat(warning.existingInstallReplaced).isFalse()
		assertThat(warning.updateInstall).isFalse()
		assertThat(warning.pinnedVersionCode).isEqualTo(1)
	}

	@Test
	fun `entry over a matching install warns as a data-preserving update`() {
		packages.versionCodes[appId] = 41
		packages.certs[appId] = cert

		val request = controller().requestEntry(appId, projectVersionCode = 3)

		val warning = request as SameAppIdModeController.EntryRequest.ShowWarning
		assertThat(warning.existingInstallReplaced).isTrue()
		assertThat(warning.updateInstall).isTrue()
		assertThat(warning.pinnedVersionCode).isEqualTo(42)
	}

	@Test
	fun `entry over a foreign-signed install refuses with analytics and touches nothing`() {
		packages.versionCodes[appId] = 1
		packages.certs[appId] = otherCert

		val request = controller().requestEntry(appId, projectVersionCode = null)

		assertThat(request).isInstanceOf(SameAppIdModeController.EntryRequest.Refused::class.java)
		assertThat(metrics.events).containsExactly("refused:SIGNATURE_MISMATCH")
		assertThat(store.isSameAppIdEnabled()).isFalse()
		assertThat(guard.isEpisodeConfirmed()).isFalse()
		assertThat(modeChanges).isEqualTo(0)
	}

	@Test
	fun `confirmEntry persists the episode, mints the token and flips the mode`() {
		packages.versionCodes[appId] = 41
		packages.certs[appId] = cert
		val controller = controller()
		controller.requestEntry(appId, projectVersionCode = null)

		controller.confirmEntry()

		assertThat(store.isSameAppIdEnabled()).isTrue()
		assertThat(store.pinnedVersionCode()).isEqualTo(42)
		assertThat(store.isClobberConfirmed()).isTrue()
		assertThat(store.episodeRealApplicationId()).isEqualTo(appId)
		assertThat(guard.isEpisodeConfirmed()).isTrue()
		assertThat(metrics.events).containsExactly("confirmed", "entered:true").inOrder()
		assertThat(modeChanges).isEqualTo(1)
		assertThat(controller.needsEntryConfirmation()).isFalse()
		// The guard now authorizes an install over the real id.
		guard.checkInstall(sameAppId = true, targetPackage = appId, realApplicationId = appId)
	}

	@Test
	fun `confirmEntry without a pending request fails loud`() {
		assertThrows<IllegalStateException> { controller().confirmEntry() }
	}

	@Test
	fun `declining a first entry leaves everything off`() {
		val controller = controller()
		controller.requestEntry(appId, projectVersionCode = null)

		controller.declineEntry()

		assertThat(metrics.events).containsExactly("refused:USER_DECLINED")
		assertThat(store.isSameAppIdEnabled()).isFalse()
		assertThat(guard.isEpisodeConfirmed()).isFalse()
		assertThat(modeChanges).isEqualTo(0)
	}

	@Test
	fun `declining a re-entry turns the toggle back off and signals the flip`() {
		store.setSameAppIdEnabled(true)
		val controller = controller()
		controller.requestEntry(appId, projectVersionCode = null)

		controller.declineEntry()

		assertThat(store.isSameAppIdEnabled()).isFalse()
		assertThat(modeChanges).isEqualTo(1)
	}

	@Test
	fun `disableMode clears the episode and signals the rebaseline boundary`() {
		confirmedEpisode()
		modeChanges = 0

		controller().disableMode()

		assertThat(store.isSameAppIdEnabled()).isFalse()
		assertThat(store.pinnedVersionCode()).isNull()
		assertThat(guard.isEpisodeConfirmed()).isFalse()
		assertThat(modeChanges).isEqualTo(1)
	}

	@Test
	fun `a Standard Run install ends the episode and requests the downgrade`() {
		confirmedEpisode()
		metrics.events.clear()

		val restore = controller().onStandardRunInstall(downgradeAvailable = true)

		assertThat(restore.episodeEnded).isTrue()
		assertThat(restore.requestDowngrade).isTrue()
		assertThat(guard.isEpisodeConfirmed()).isFalse()
		assertThat(store.pinnedVersionCode()).isNull()
		assertThat(store.isClobberConfirmed()).isFalse()
		// The per-project OPT-IN stays on: the next bolt tap re-enters via the warning.
		assertThat(store.isSameAppIdEnabled()).isTrue()
		// The downgrade authority is persisted so a restart-and-retry still requests it.
		assertThat(store.isRestoreDowngradePending()).isTrue()
		assertThat(metrics.events).containsExactly("restored:true")
	}

	@Test
	fun `on API 28 the restore reports no downgrade request`() {
		confirmedEpisode()
		metrics.events.clear()

		val restore = controller().onStandardRunInstall(downgradeAvailable = false)

		assertThat(restore.episodeEnded).isTrue()
		assertThat(restore.requestDowngrade).isFalse()
		assertThat(metrics.events).containsExactly("restored:false")
	}

	@Test
	fun `a Standard Run install without an episode is a plain install`() {
		val restore = controller().onStandardRunInstall(downgradeAvailable = true)

		assertThat(restore.episodeEnded).isFalse()
		assertThat(restore.requestDowngrade).isFalse()
		assertThat(metrics.events).isEmpty()
	}

	@Test
	fun `later Run installs after a restore still request the downgrade`() {
		confirmedEpisode()
		val controller = controller()
		controller.onStandardRunInstall(downgradeAvailable = true)

		val second = controller.onStandardRunInstall(downgradeAvailable = true)

		assertThat(second.episodeEnded).isFalse()
		assertThat(second.requestDowngrade).isTrue()
	}

	@Test
	fun `a restore cancelled and retried after a restart still requests the downgrade`() {
		confirmedEpisode()
		// Tap Run: the episode ends and the (persisted) downgrade authority is recorded.
		controller().onStandardRunInstall(downgradeAvailable = true)

		// Model a CoGo restart: a fresh controller with a FRESH guard (in-memory episode
		// state lost) over the SAME persisted store. The episode is already ended, so
		// restoreEpisode does not re-arm it.
		val afterRestart =
			SameAppIdModeController(
				store = store,
				packages = packages,
				guard = SameAppIdGuard(),
				metrics = metrics,
				cogoCertSha256 = { cert },
				onModeChanged = { modeChanges++ },
			)
		afterRestart.restoreEpisode()

		val retry = afterRestart.onStandardRunInstall(downgradeAvailable = true)

		assertThat(retry.episodeEnded).isFalse()
		assertThat(retry.requestDowngrade).isTrue()
	}

	@Test
	fun `entering a new episode clears a pending restore downgrade`() {
		packages.versionCodes[appId] = 41
		packages.certs[appId] = cert
		store.setRestoreDowngradePending(true)
		val controller = controller()
		controller.requestEntry(appId, projectVersionCode = null)

		controller.confirmEntry()

		assertThat(store.isRestoreDowngradePending()).isFalse()
	}

	@Test
	fun `disabling the mode clears a pending restore downgrade`() {
		confirmedEpisode()
		controller().onStandardRunInstall(downgradeAvailable = true)

		controller().disableMode()

		assertThat(store.isRestoreDowngradePending()).isFalse()
	}

	@Test
	fun `restoreEpisode re-arms the guard from a persisted confirmed episode`() {
		store.setSameAppIdEnabled(true)
		store.setPinnedVersionCode(42)
		store.setClobberConfirmed(true)
		store.setEpisodeRealApplicationId(appId)

		controller().restoreEpisode()

		assertThat(guard.isEpisodeConfirmed()).isTrue()
		guard.checkInstall(sameAppId = true, targetPackage = appId, realApplicationId = appId)
	}

	@Test
	fun `restoreEpisode ignores an unconfirmed persisted state`() {
		store.setSameAppIdEnabled(true)
		store.setEpisodeRealApplicationId(appId)

		controller().restoreEpisode()

		assertThat(guard.isEpisodeConfirmed()).isFalse()
	}

	@Test
	fun `a throwing metrics sink never breaks the mode flow`() {
		packages.versionCodes[appId] = 1
		packages.certs[appId] = cert
		metrics.throwOnCall = true
		val controller = controller()
		controller.requestEntry(appId, projectVersionCode = null)

		controller.confirmEntry()

		assertThat(store.isSameAppIdEnabled()).isTrue()
		assertThat(guard.isEpisodeConfirmed()).isTrue()
	}

	private fun confirmedEpisode() {
		packages.versionCodes[appId] = 41
		packages.certs[appId] = cert
		val controller = controller()
		controller.requestEntry(appId, projectVersionCode = null)
		controller.confirmEntry()
	}
}
