package com.itsaky.androidide.app

import android.app.Activity
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.actions.build.QuickBuildAction
import com.itsaky.androidide.actions.build.QuickBuildAction.Companion.TapOutcome
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.api.BuildOutputProvider
import com.itsaky.androidide.plugins.manager.services.IdeBuildServiceImpl
import com.itsaky.androidide.plugins.manager.services.QuickBuildProvider
import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import com.itsaky.androidide.plugins.services.QuickBuildResult
import com.itsaky.androidide.plugins.services.QuickBuildState
import com.itsaky.androidide.plugins.services.QuickBuildUnavailableReason
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.quickbuild.GradleQuickBuildProvisioner
import com.itsaky.androidide.quickbuild.QuickBuildGraphWarmUp
import com.itsaky.androidide.quickbuild.QuickBuildProjectSupport
import com.itsaky.androidide.quickbuild.QuickBuildRunTracker
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.FeatureFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildTone
import org.appdevforall.cotg.quickbuild.service.session.QuickBuildSessionManager
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Quick Build on behalf of a plugin (ADFA-6493): the same tap as the toolbar button, through
 * [QuickBuildAction.launchTap], with its outcome read off the session's status.
 *
 * @param foregroundActivity the activity on screen, or null when there is none.
 */
internal class PluginQuickBuildCoordinator(
	private val foregroundActivity: () -> Activity?,
) : QuickBuildProvider {
	/** Outlives the activity, as the session does: a run keeps going while the user is in their app. */
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	/** A plugin-started run, from its start until its result completes. */
	private class Run(
		val tracker: QuickBuildRunTracker,
		val result: CompletableFuture<QuickBuildResult>,
		val handoff: TapHandoff,
	) {
		/** Claimed by whichever comes first: the tap reaching the session, or a cancel before it does. */
		val claimed = AtomicBoolean(false)

		/** The tap, while it saves and asks about the installed app; null until launched. */
		@Volatile
		var tap: Job? = null
	}

	/** The plugin-started run in flight, so a second start is refused and a cancel reaches it. */
	private val running = AtomicReference<Run?>(null)

	init {
		// A stop on the toolbar ends a plugin's run as much as cancel() does; its tracker has to
		// know, or a stopped rebaseline would read as broken build files.
		QuickBuildAction.onStopTapped = { running.get()?.tracker?.onCancelRequested() }
	}

	override fun availability(): QuickBuildAvailability =
		unavailableReason { reason, variantName ->
			QuickBuildAvailability.Unavailable(
				reason,
				messageOf(reason, variantName) { id, args -> IDEApplication.instance.getString(id, *args) },
			)
		} ?: QuickBuildAvailability.Available

	override fun status(): QuickBuildState {
		// No refusal message: an agent may poll this, and only availability() reports why.
		if (unavailableReason { reason, _ -> reason } != null) return QuickBuildState.UNAVAILABLE
		return stateOf(QuickBuildAction.currentTone())
	}

	/**
	 * Why Quick Build cannot run now, passed to [onUnavailable] with the selected variant; null
	 * when it can. The module is the one a tap would provision, by the provisioner's own rule.
	 */
	private inline fun <T> unavailableReason(onUnavailable: (QuickBuildUnavailableReason, String?) -> T): T? {
		val projectManager = IProjectManager.getInstance()
		val module = GradleQuickBuildProvisioner.quickBuildModule(projectManager)
		val variantName = module?.getSelectedVariant()?.name
		val reason =
			unavailableReasonOf(
				experimentsEnabled = FeatureFlags.isExperimentsEnabled,
				projectOpen = projectManager.projectDirPath.isNotEmpty(),
				synced = projectManager.workspace != null,
				pluginProject = projectManager.isPluginProject(),
				hasAppModule = module != null,
				variantName = variantName,
			) ?: return null
		return onUnavailable(reason, variantName)
	}

	override fun cancel(): Boolean {
		val run = running.get()
		if (run != null && run.claimed.compareAndSet(false, true)) {
			// The tap is still saving or asking about the installed app, so nothing has reached
			// the session to stop: drop the tap, which also closes its dialog. A tap that ended
			// unclaimed (save failed, declined) has already completed, and was not running.
			val cancelled = run.result.complete(QuickBuildResult.Cancelled)
			run.tap?.cancel()
			return cancelled
		}
		if (run != null && !run.result.isDone) {
			// The tap is ours and past its gate, though the tone may not show BUILDING yet.
			run.tracker.onCancelRequested()
			run.handoff.cancel()
			return true
		}
		val sessionManager = sessionManager() ?: return false
		if (QuickBuildAction.currentTone() != QuickBuildTone.BUILDING) return false
		sessionManager.onCancelRequested()
		return true
	}

	override fun start(): CompletableFuture<QuickBuildResult> {
		val result = CompletableFuture<QuickBuildResult>()
		val unavailable = availability() as? QuickBuildAvailability.Unavailable
		if (unavailable != null) return refused(unavailable.message)
		val activity =
			foregroundActivity() as? EditorHandlerActivity
				?: return refused("No editor is open. Open the project in the editor first.")
		val sessionManager = sessionManager() ?: return refused("Quick Build is still starting. Try again in a moment.")
		if (QuickBuildAction.isBlockedByStandardBuild()) return refused(IdeBuildServiceImpl.BUILD_IN_PROGRESS_REASON)
		if (QuickBuildAction.currentTone() == QuickBuildTone.BUILDING) return refused(ALREADY_RUNNING)

		val tracker =
			QuickBuildRunTracker(
				start = sessionManager.status.value,
				startedAtMillis = SystemClock.elapsedRealtime(),
				buildOutput = ::buildOutput,
			)
		val run = Run(tracker, result, TapHandoff(sessionManager::onCancelRequested))
		if (!running.compareAndSet(null, run)) return refused(ALREADY_RUNNING)
		result.whenComplete { _, _ -> running.compareAndSet(run, null) }

		val tap =
			QuickBuildAction.launchTap(
				activity,
				sessionManager,
				beforeSession = { run.claimed.compareAndSet(false, true) },
			) { outcome ->
				when (outcome) {
					TapOutcome.STARTED -> {
						run.handoff.onTapQueued()
						val askOutstanding = { sessionManager.askOutstanding }
						scope.launch { result.complete(track(sessionManager.status, tracker, run.handoff::onBuildStarted, askOutstanding)) }
					}

					TapOutcome.SAVE_FAILED -> {
						result.complete(QuickBuildResult.Refused("The open files could not be saved."))
					}

					TapOutcome.ACTIVITY_GONE -> {
						result.complete(QuickBuildResult.Refused(EDITOR_CLOSED))
					}

					TapOutcome.DECLINED -> {
						result.complete(QuickBuildResult.Declined)
					}
				}
			}
		if (tap == null) {
			// A toolbar tap is still saving or asking; it is the Quick Build this one would have been.
			log.info("Not starting Quick Build for a plugin: {}", ALREADY_RUNNING)
			result.complete(QuickBuildResult.Refused(ALREADY_RUNNING))
			return result
		}
		run.tap = tap
		// A cancel that landed before the tap was stored could not reach it.
		if (result.getNow(null) == QuickBuildResult.Cancelled) tap.cancel()
		// A destroyed activity's scope drops the tap without reporting; nothing else would.
		tap.invokeOnCompletion { cause -> if (cause != null) result.complete(QuickBuildResult.Refused(EDITOR_CLOSED)) }
		return result
	}

	private fun refused(reason: String): CompletableFuture<QuickBuildResult> {
		log.info("Not starting Quick Build for a plugin: {}", reason)
		return CompletableFuture.completedFuture(QuickBuildResult.Refused(reason))
	}

	private fun sessionManager(): QuickBuildSessionManager? = QuickBuildGraphWarmUp.INSTANCE.sessionManagerOrNull

	private fun buildOutput(): String? = runCatching { BuildOutputProvider.getBuildOutputContent() }.getOrNull()

	/**
	 * Orders a stop after what it stops. A stop sent before the tap reaches the session is
	 * reduced against Idle, which drops it, and the tap then builds anyway; so a stop that
	 * arrives first waits for [onTapQueued]. The session reduces its events in the order sent.
	 *
	 * From a live session the tap only records its ask, and the build follows on the save's
	 * watcher batch; a stop in between withdraws the ask but has no build to cancel. A queued
	 * rebaseline is the same: only its Provisioning stops it. So a stop is sent again each
	 * time a build or rebaseline starts, until the run settles.
	 */
	internal class TapHandoff(
		private val sendCancel: () -> Unit,
	) {
		private var queued = false
		private var stopRequested = false

		/** Sends the stop now, or holds it until the tap is queued. */
		@Synchronized
		fun cancel() {
			stopRequested = true
			if (queued) sendCancel()
		}

		/** The tap was handed to the session; a held stop follows it. */
		@Synchronized
		fun onTapQueued() {
			queued = true
			if (stopRequested) sendCancel()
		}

		/** A build or rebaseline started after the tap; a stop already asked for reaches it. */
		@Synchronized
		fun onBuildStarted() {
			if (queued && stopRequested) sendCancel()
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(PluginQuickBuildCoordinator::class.java)

		private const val EDITOR_CLOSED = "The editor closed before Quick Build started."

		private const val ALREADY_RUNNING = "a Quick Build is already running"

		/** How long [track] follows a run before giving up, so a stuck session cannot hold the plugin slot. */
		internal const val TRACK_CEILING_MILLIS = 30 * 60_000L

		/**
		 * Follows [status] until [tracker] settles a result, giving an idle tap its quiet window,
		 * or gives up after [TRACK_CEILING_MILLIS] ([QuickBuildRunTracker.onGaveUp]).
		 *
		 * Never throws but for cancellation: it is the only thing that completes the plugin's
		 * future once the tap started, so a throw would hold the plugin slot for good.
		 *
		 * @param onBuildStarted told of each build or rebaseline seen starting, before [tracker] reads it.
		 * @param askOutstanding whether the session still owes a tap its answer; holds the quiet
		 *   window open where [QuickBuildRunTracker.waitsOnAsk] allows.
		 */
		internal suspend fun track(
			status: StateFlow<QuickBuildStatus>,
			tracker: QuickBuildRunTracker,
			onBuildStarted: () -> Unit = {},
			askOutstanding: () -> Boolean = { false },
			nowMillis: () -> Long = SystemClock::elapsedRealtime,
		): QuickBuildResult =
			try {
				withTimeoutOrNull(TRACK_CEILING_MILLIS) { settle(status, tracker, onBuildStarted, askOutstanding, nowMillis) }
					?: tracker.onGaveUp(TRACK_CEILING_MILLIS)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				log.error("Lost track of a plugin's Quick Build", e)
				QuickBuildResult.Failed("Quick Build lost track of this run: ${e.message ?: e.javaClass.simpleName}", null)
			}

		private suspend fun settle(
			status: StateFlow<QuickBuildStatus>,
			tracker: QuickBuildRunTracker,
			onBuildStarted: () -> Unit,
			askOutstanding: () -> Boolean,
			nowMillis: () -> Long,
		): QuickBuildResult =
			channelFlow {
				status.collectLatest { current ->
					if (current is QuickBuildStatus.Building || current is QuickBuildStatus.Provisioning) onBuildStarted()
					val settled = tracker.onStatus(current, nowMillis())
					when {
						settled != null -> {
							send(settled)
						}

						tracker.awaitingQuiet -> {
							// Cancelled by the next status, which collectLatest delivers instead.
							delay(QuickBuildRunTracker.QUIET_MILLIS)
							// A build still owed to the tap moves no status until it starts.
							while (tracker.waitsOnAsk(current) && askOutstanding()) delay(QuickBuildRunTracker.ASK_POLL_MILLIS)
							send(tracker.onQuiet(current))
						}
					}
				}
			}.first()

		/** [unavailableReasonOf] with its [messageOf]. */
		internal fun availabilityOf(
			experimentsEnabled: Boolean,
			projectOpen: Boolean,
			synced: Boolean,
			pluginProject: Boolean,
			hasAppModule: Boolean,
			variantName: String?,
			getString: (id: Int, args: Array<Any>) -> String,
		): QuickBuildAvailability {
			val reason =
				unavailableReasonOf(experimentsEnabled, projectOpen, synced, pluginProject, hasAppModule, variantName)
					?: return QuickBuildAvailability.Available
			return QuickBuildAvailability.Unavailable(reason, messageOf(reason, variantName, getString))
		}

		/** Why Quick Build cannot run, checked in the order the user would fix them; null when it can. */
		internal fun unavailableReasonOf(
			experimentsEnabled: Boolean,
			projectOpen: Boolean,
			synced: Boolean,
			pluginProject: Boolean,
			hasAppModule: Boolean,
			variantName: String?,
		): QuickBuildUnavailableReason? =
			when {
				!experimentsEnabled -> {
					QuickBuildUnavailableReason.EXPERIMENTS_DISABLED
				}

				!projectOpen -> {
					QuickBuildUnavailableReason.NO_PROJECT
				}

				// The plugin-project and module checks read the synced model, so this comes first.
				!synced -> {
					QuickBuildUnavailableReason.PROJECT_SYNCING
				}

				pluginProject -> {
					QuickBuildUnavailableReason.PLUGIN_PROJECT
				}

				!hasAppModule -> {
					QuickBuildUnavailableReason.NO_APP_MODULE
				}

				variantName != null && QuickBuildProjectSupport.nonDebuggableVariantMessage(variantName) != null -> {
					QuickBuildUnavailableReason.RELEASE_VARIANT
				}

				else -> {
					null
				}
			}

		/** What a plugin is told for [reason]: the toolbar's own text wherever the toolbar has one. */
		internal fun messageOf(
			reason: QuickBuildUnavailableReason,
			variantName: String?,
			getString: (id: Int, args: Array<Any>) -> String,
		): String =
			when (reason) {
				QuickBuildUnavailableReason.EXPERIMENTS_DISABLED -> {
					"Quick Build is experimental. Turn on experiments in Preferences to use it."
				}

				QuickBuildUnavailableReason.NO_PROJECT -> {
					"No project is open."
				}

				QuickBuildUnavailableReason.PROJECT_SYNCING -> {
					getString(R.string.quick_build_waiting_for_sync, emptyArray())
				}

				QuickBuildUnavailableReason.PLUGIN_PROJECT -> {
					getString(R.string.quick_build_unsupported_plugin_project, emptyArray())
				}

				QuickBuildUnavailableReason.NO_APP_MODULE -> {
					getString(R.string.quick_build_no_app_module, emptyArray())
				}

				QuickBuildUnavailableReason.RELEASE_VARIANT -> {
					getString(R.string.quick_build_non_debuggable_variant, arrayOf(variantName.orEmpty()))
				}

				QuickBuildUnavailableReason.NOT_SUPPORTED -> {
					"Quick Build is not supported"
				}
			}

		/** What the toolbar button shows, in the plugin API's terms. */
		internal fun stateOf(tone: QuickBuildTone): QuickBuildState =
			when (tone) {
				QuickBuildTone.READY -> QuickBuildState.READY
				QuickBuildTone.BUILDING -> QuickBuildState.BUILDING
				QuickBuildTone.SLOW -> QuickBuildState.FULL_BUILD_NEXT
				QuickBuildTone.RECONNECTING -> QuickBuildState.RECONNECTING
				QuickBuildTone.ERROR -> QuickBuildState.ERROR
			}
	}
}
