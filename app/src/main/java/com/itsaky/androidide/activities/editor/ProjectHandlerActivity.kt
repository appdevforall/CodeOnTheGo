/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.activities.editor

import android.content.Intent
import android.os.Bundle
import android.system.ErrnoException
import android.system.OsConstants
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup.MarginLayoutParams
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.GravityInt
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_FIND_ACTION_MENU
import com.itsaky.androidide.actions.ActionsRegistry.Companion.getInstance
import com.itsaky.androidide.actions.etc.FindInFileAction
import com.itsaky.androidide.actions.etc.FindInProjectAction
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.databinding.LayoutSearchProjectBinding
import com.itsaky.androidide.flashbar.Flashbar
import com.itsaky.androidide.fragments.FindActionDialog
import com.itsaky.androidide.fragments.SearchFieldToolbar
import com.itsaky.androidide.fragments.sheets.ProgressSheet
import com.itsaky.androidide.handlers.EditorBuildEventListener
import com.itsaky.androidide.handlers.LspHandler.connectClient
import com.itsaky.androidide.handlers.LspHandler.connectDebugClient
import com.itsaky.androidide.handlers.LspHandler.destroyLanguageServers
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.IDELanguageClientImpl
import com.itsaky.androidide.lsp.debug.DebugClientConnectionResult
import com.itsaky.androidide.lsp.java.utils.CancelChecker
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.plugins.extensions.ProjectSearchExtension
import com.itsaky.androidide.plugins.extensions.ProjectSearchRequest
import com.itsaky.androidide.plugins.extensions.ProjectSearchResult
import com.itsaky.androidide.plugins.extensions.ProjectSearchSection
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.projects.models.projectDir
import com.itsaky.androidide.quickbuild.AutostartBuild
import com.itsaky.androidide.quickbuild.GradleQuickBuildProvisioner
import com.itsaky.androidide.quickbuild.QuickBuildBenchHooks
import com.itsaky.androidide.quickbuild.QuickBuildFlash
import com.itsaky.androidide.quickbuild.QuickBuildFlashes
import com.itsaky.androidide.quickbuild.QuickBuildOutputNarrator
import com.itsaky.androidide.quickbuild.QuickBuildPrebuildStagger
import com.itsaky.androidide.quickbuild.QuickBuildStatusBarUpdate
import com.itsaky.androidide.quickbuild.quickBuildStatusBarUpdate
import com.itsaky.androidide.quickbuild.resolve
import com.itsaky.androidide.repositories.PluginRepository
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.services.builder.GradleBuildService
import com.itsaky.androidide.services.builder.GradleBuildServiceConnnection
import com.itsaky.androidide.services.builder.gradleDistributionParams
import com.itsaky.androidide.tooling.api.messages.AndroidInitializationParams
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.InitializeProjectParams
import com.itsaky.androidide.tooling.api.messages.result.InitializeResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.CACHE_READ_ERROR
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_DIRECTORY_INACCESSIBLE
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_DIRECTORY
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult.Failure.PROJECT_NOT_FOUND
import com.itsaky.androidide.tooling.api.messages.result.failure
import com.itsaky.androidide.tooling.api.messages.result.isSuccessful
import com.itsaky.androidide.tooling.api.models.BuildVariantInfo
import com.itsaky.androidide.tooling.api.models.mapToSelectedVariants
import com.itsaky.androidide.tooling.api.sync.ProjectSyncHelper
import com.itsaky.androidide.utils.DURATION_INDEFINITE
import com.itsaky.androidide.utils.DialogUtils.newMaterialDialogBuilder
import com.itsaky.androidide.utils.DialogUtils.showRestartPrompt
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.RecursiveFileSearcher
import com.itsaky.androidide.utils.dpToPx
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfoLong
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.flashbarBuilder
import com.itsaky.androidide.utils.onLongPress
import com.itsaky.androidide.utils.resolveAttr
import com.itsaky.androidide.utils.showOnUiThread
import com.itsaky.androidide.utils.withIcon
import com.itsaky.androidide.viewmodel.BottomSheetViewModel
import com.itsaky.androidide.viewmodel.BuildState
import com.itsaky.androidide.viewmodel.BuildVariantsViewModel
import com.itsaky.androidide.viewmodel.BuildViewModel
import com.itsaky.androidide.viewmodel.EditorViewModel.SearchResultSection
import io.github.rosemoe.sora.text.ICUUtils
import io.github.rosemoe.sora.util.IntPair
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.adfa.constants.CONTENT_KEY
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildNotice
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.service.provision.QuickBuildClobberCheck
import org.appdevforall.cotg.quickbuild.service.session.QuickBuildSessionManager
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import org.koin.android.ext.android.inject
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.net.SocketException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.stream.Collectors

/** @author Akash Yadav */
@Suppress("MemberVisibilityCanBePrivate")
abstract class ProjectHandlerActivity : BaseEditorActivity() {
	protected val buildVariantsViewModel by viewModels<BuildVariantsViewModel>()
	private val pluginRepository: PluginRepository by inject()

	protected var mSearchingProgress: ProgressSheet? = null
	protected var mFindInProjectDialog: AlertDialog? = null
	protected var syncNotificationFlashbar: Flashbar? = null

	private val buildViewModel by viewModels<BuildViewModel>()
	protected var initializingFuture: CompletableFuture<out InitializeResult?>? = null
	private val Throwable?.isFileNotFound: Boolean
		get() =
			this is FileNotFoundException ||
				this is NoSuchFileException ||
				(this is ErrnoException && this.errno == OsConstants.ENOENT)

	val findInProjectDialog: AlertDialog?
		get() {
			if (mFindInProjectDialog == null) {
				createFindInProjectDialog()
			}
			return mFindInProjectDialog
		}

	fun findActionDialog(actionData: ActionData): FindActionDialog {
		val shouldHideFindInFileAction = editorViewModel.getOpenedFileCount() != 0
		val registry = getInstance() as DefaultActionsRegistry

		return FindActionDialog(
			anchor = content.projectActionsToolbar.findViewById(R.id.menu_container),
			context = this,
			actionData = actionData,
			shouldShowFindInFileAction = shouldHideFindInFileAction,
			onFindInFileClicked = { data ->
				val findInFileAction =
					registry.findAction(
						location = EDITOR_FIND_ACTION_MENU,
						id = FindInFileAction().id,
					)
				if (findInFileAction != null) {
					registry.executeAction(findInFileAction, data)
				}
			},
			onFindInProjectClicked = { data ->
				val findInProjectAction =
					registry.findAction(
						location = EDITOR_FIND_ACTION_MENU,
						id = FindInProjectAction().id,
					)
				if (findInProjectAction != null) {
					registry.executeAction(findInProjectAction, data)
				}
			},
		)
	}

	protected val mBuildEventListener = EditorBuildEventListener()

	private val buildServiceConnection = GradleBuildServiceConnnection()

	private val internalBuildObserver =
		Observer<Boolean> { inProgress -> editorViewModel.isInternalBuildInProgress = inProgress }

	companion object {
		private val logger = LoggerFactory.getLogger(ProjectHandlerActivity::class.java)

		const val STATE_KEY_FROM_SAVED_INSTANACE = "ide.editor.isFromSavedInstance"
		const val STATE_KEY_SHOULD_INITIALIZE = "ide.editor.isInitializing"

		private const val PLUGIN_SEARCH_TIMEOUT_SECONDS = 10L
	}

	abstract fun doCloseAll()

	abstract fun saveOpenedFiles()

	override fun doDismissSearchProgress() {
		if (mSearchingProgress?.isShowing == true) {
			mSearchingProgress!!.dismiss()
		}
	}

	override fun doOpenHelp() {
		openHelpActivity()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		editorViewModel._isSyncNeeded.observe(this) { isSyncNeeded ->
			if (!isSyncNeeded) {
				// dismiss if already showing
				syncNotificationFlashbar?.dismiss()
				return@observe
			}

			if (syncNotificationFlashbar?.isShowing() == true) {
				// already shown
				return@observe
			}

			notifySyncNeeded()
		}

		observeStates()
		startServices()

		if (intent.getBooleanExtra("HAS_TEMPLATE_ISSUES", false)) {
			flashError(getString(string.msg_template_warnings))
		}
	}

	/**
	 * Low-spec device support (ADFA-4128): forward the framework signal so a live
	 * Quick Build session can give back the compile daemon's heap under memory pressure.
	 * See [QuickBuildSessionManager.onTrimMemory] for the per-level decision and the
	 * (lazy, auto-healing) re-warm path - nothing else is required here. Genuine memory
	 * pressure is the ONLY thing that reclaims the daemon: backgrounding CoGo (the user
	 * switching to their running proxy app mid-loop) deliberately keeps it warm, matching
	 * the standard Gradle build daemon's lifetime policy.
	 */
	override fun onTrimMemory(level: Int) {
		super.onTrimMemory(level)
		quickBuildSessionManager()?.onTrimMemory(level)
	}

	private fun observeStates() {
		bindQuickBuildOutput()
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch {
					buildViewModel.buildState.collect { onBuildStateChanged(it) }
				}
				quickBuildSessionManager()?.let { quickBuild ->
					// ADFA-4128: the toolbar icon reads the session status
					// pull-style in prepare(); nothing else rebuilds the toolbar when
					// e.g. a watcher-triggered build fails, so push every status
					// change into a menu refresh or the ATTENTION icon never shows.
					// Only the bar and the icon are collected here - the Build Output
					// narration is session-scoped (see [bindQuickBuildOutput]), since a
					// build the user backgrounded CoGo to watch still has to be logged.
					launch {
						var previousStatus: QuickBuildStatus? = null
						quickBuild.status.collect { status ->
							invalidateOptionsMenu()
							showQuickBuildStatus(previousStatus, status)
							previousStatus = status
						}
					}
					launch {
						quickBuild.userMessages.collect { flashError(it.resolve(this@ProjectHandlerActivity)) }
					}
					launch {
						// Session messages whose copy lives here rather than in
						// :quickbuild:core (it has no R). Deliberately NOT the error channel,
						// which flashes everything red: each notice picks its own tone, so a
						// build the user chose to stop does not read as a failure while a
						// reload that keeps crashing does.
						quickBuild.notices.collect { notice ->
							when (notice) {
								QuickBuildNotice.BUILD_CANCELLED -> {
									flashInfoLong(getString(string.info_build_cancelled))
								}

								QuickBuildNotice.RELOAD_CRASHED -> {
									flashError(getString(string.quick_build_reload_crashed))
								}

								QuickBuildNotice.RELINK_STUCK -> {
									flashError(getString(string.quick_build_relink_stuck))
								}

								QuickBuildNotice.TEST_SOURCE_IGNORED -> {
									// Nothing went wrong - the save landed, it just is not
									// something any build could deploy.
									flashInfoLong(getString(string.quick_build_test_source_ignored))
								}

								QuickBuildNotice.STALE_COMPONENT_HELPERS -> {
									// The deploy worked, so this is advisory, not an error.
									flashInfoLong(getString(string.quick_build_stale_component_helpers))
								}

								QuickBuildNotice.PROXY_APP_WONT_STAY_UP -> {
									// The one notice that gets a dialog: the user is in a closed
									// loop (saving cannot help, relaunching restarts the crash),
									// and the only way out is an action buried in a long-press
									// menu. A flash they can miss would leave them stuck.
									showProxyAppWontStayUpDialog()
								}
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Hands the Build Output pane to the session-scoped narrator (ADFA-4128), and takes it back
	 * when this activity is destroyed.
	 *
	 * Deliberately not a `repeatOnLifecycle` collector: the pane is a log, and a build that ran
	 * while the user was in their app - the whole point of a live-reload loop - has to appear in
	 * it too. Lines produced between the unbind and the next bind are held by the narrator.
	 *
	 * Resolving the narrator does not resolve the session manager, so this keeps the graph's
	 * "nothing spawns until the first tap" property.
	 */
	private fun bindQuickBuildOutput() {
		val narrator = quickBuildOutputNarrator() ?: return
		val sink: (String) -> Unit = ::appendBuildOutput
		narrator.bind(sink)
		lifecycle.addObserver(
			object : DefaultLifecycleObserver {
				override fun onDestroy(owner: LifecycleOwner) {
					narrator.unbind(sink)
				}
			},
		)
	}

	/**
	 * The Quick Build Build Output narrator (ADFA-4128), or null when the feature is off.
	 * Gated exactly like [quickBuildSessionManager].
	 */
	private fun quickBuildOutputNarrator(): QuickBuildOutputNarrator? {
		if (!FeatureFlags.isExperimentsEnabled) {
			return null
		}
		return runCatching { GlobalContext.get().get<QuickBuildOutputNarrator>() }
			.onFailure { logger.error("Quick Build output narrator unavailable", it) }
			.getOrNull()
	}

	/**
	 * Offers the one action that clears a proxy app which will not stay open.
	 *
	 * A dialog rather than a flash because every other affordance the user would reach for is a
	 * dead end - saving rebuilds a payload with nowhere to land, and the deploy failure's own
	 * "relaunch to reconnect" restarts the same crash. Restart session rebuilds and reinstalls the
	 * proxy app, which is what actually replaces the broken one - and is what this dialog's copy
	 * promises, so it must not stop at Idle and wait for a tap the user has no reason to expect.
	 *
	 * Dismissible: the user may prefer to fix their startup crash first and restart afterwards,
	 * and the notice is raised again if the streak continues past a success.
	 */
	private fun showProxyAppWontStayUpDialog() {
		if (isFinishing || isDestroyed) {
			return
		}
		newMaterialDialogBuilder(this)
			.setTitle(string.quick_build_wont_stay_up_title)
			.setMessage(string.quick_build_wont_stay_up_message)
			.setPositiveButton(string.quick_build_wont_stay_up_restart) { dialog, _ ->
				dialog.dismiss()
				quickBuildSessionManager()?.restartSessionAndReprovision()
			}.setNegativeButton(string.quick_build_wont_stay_up_dismiss) { dialog, _ ->
				dialog.dismiss()
			}.show()
	}

	/**
	 * Narrates the session's main stages on the same status line the standard build uses -
	 * provisioning, compiling, reloaded generation N, BUILD FAILED - so a Quick Build reads
	 * down there the way a Gradle build's task lines do.
	 *
	 * The mapping itself is the pure [quickBuildStatusBarUpdate]; this only applies it. A landed
	 * build always overwrites a failure line, so BUILD FAILED can never outlive the failure.
	 *
	 * Only clears a status line it wrote itself, so it cannot wipe a project-init or
	 * plugin-install message that landed while the session had nothing to say.
	 */
	private fun showQuickBuildStatus(
		previous: QuickBuildStatus?,
		status: QuickBuildStatus,
	) {
		when (val update = quickBuildStatusBarUpdate(previous, status)) {
			is QuickBuildStatusBarUpdate.Show -> {
				if (!update.onlyIfOwned || ownsQuickBuildStatus) {
					// setStatus resets ownership (any caller takes the bar over); reclaim it.
					setStatus(getString(update.text, *update.args.toTypedArray()))
					ownsQuickBuildStatus = true
				}
			}

			QuickBuildStatusBarUpdate.Clear -> {
				if (ownsQuickBuildStatus) {
					ownsQuickBuildStatus = false
					setStatus("")
				}
			}

			null -> {
				// Not news - leave whatever is showing alone.
			}
		}

		// The status line and the toolbar icon are both easy to miss while typing, so a failure
		// and the build that clears it also get the same flashbar a standard build raises.
		when (val flash = quickBuildFlashes.next(previous, status)) {
			is QuickBuildFlash.Failure -> {
				flashError(flash.text)
			}

			is QuickBuildFlash.Recovery -> {
				flashSuccess(flash.text)
			}

			null -> {
				// Not news - no bar.
			}
		}
	}

	private fun onBuildStateChanged(state: BuildState) {
		// ADFA-4128: closes out an autostarted standard build's measurement. Always false in
		// a release build, where nothing can autostart one.
		val suppressInstall =
			QuickBuildBenchHooks.standardBuildEnded(
				isTerminal = state !is BuildState.InProgress,
				isSuccess =
					state is BuildState.AwaitingInstall ||
						state is BuildState.Success ||
						state is BuildState.AwaitingPluginInstall,
			)
		editorViewModel.isBuildInProgress = (state is BuildState.InProgress)
		when (state) {
			is BuildState.Idle -> {
				// Nothing to do, build is finished or not started.
			}

			is BuildState.InProgress -> {
				setStatus(getString(R.string.status_building))
			}

			is BuildState.Success -> {
				flashSuccess(state.message)
			}

			is BuildState.Error -> {
				flashError(state.reason)
				// The StateFlow replays its value to every re-collect on lifecycle START;
				// consuming after one display stops a stale failure re-flashing on every
				// return to the app.
				buildViewModel.errorDisplayed()
			}

			is BuildState.AwaitingInstall -> {
				// An autostarted standard build's measurement ends at the build result, and
				// an unattended run must not pop the install dialog.
				if (!suppressInstall) {
					installApk(state)
				}
				buildViewModel.installationAttempted()
			}

			is BuildState.AwaitingPluginInstall -> {
				showPluginInstallDialog(state.cgpFile)
			}
		}
		// Refresh the toolbar icons (e.g., the run/stop button).
		invalidateOptionsMenu()
	}

	/**
	 * Confirm-on-switch (ADFA-4128), install half: Quick Build and Standard Run share the one
	 * package slot (the real applicationId), so this install replaces whatever holds it.
	 *
	 * The Run tap already asked, about the variant it was about to build, so this re-check is
	 * SILENT unless the answer moved while the build ran - which it can, because the APK names
	 * its own package and because an install or uninstall can happen in between. Asking about the
	 * APK rather than the current variant selection is the point: the selection can change during
	 * the build, and then the tap-time question was about a package this install does not touch.
	 */
	private fun installApk(state: BuildState.AwaitingInstall) {
		val clobberCheck = quickBuildClobberCheck()
		if (clobberCheck == null) {
			doInstallApk(state)
			return
		}
		val answerAtTap = buildViewModel.consumeClobberAnswerAtTap()
		lifecycleScope.launch {
			// Reading the APK's manifest is disk work, and on emulated storage that is not free.
			val apkApplicationId = withContext(Dispatchers.IO) { apkApplicationId(state.apkFile) }
			if (isDestroyed || isFinishing) {
				return@launch
			}
			val now =
				quickBuildClobberConfirmation(apkApplicationId, clobberCheck::standardRunNeedsConfirm)
			val onProceed = {
				// The Quick Build session's installed baseline is about to be replaced; stop it.
				// Keyed off the re-check rather than off whether a dialog was shown: a tap that
				// already confirmed this exact clobber skips the dialog but still clobbers.
				if (now != QuickBuildClobberConfirmation.NotNeeded) {
					quickBuildSessionManager()?.restartSession()
				}
				doInstallApk(state)
			}
			when (val decision = installTimeClobberConfirmation(answerAtTap, now)) {
				QuickBuildClobberConfirmation.NotNeeded -> {
					onProceed()
				}

				QuickBuildClobberConfirmation.NeededForUnknownAppId -> {
					confirmUnknownOccupantSwitch(onProceed)
				}

				is QuickBuildClobberConfirmation.Needed -> {
					confirmBuildTypeSwitch(
						getString(string.quick_build_switch_to_standard_title),
						getString(string.quick_build_switch_to_standard_message, decision.applicationId),
						onProceed,
					)
				}
			}
		}
	}

	/**
	 * The applicationId of the APK about to be installed, read from the archive itself.
	 *
	 * This is what makes the install-time check ask about the right package: the build's own
	 * output names it, so no amount of variant switching during the build can move it. Null when
	 * the archive cannot be parsed, which the caller treats as an unknown occupant and asks about.
	 *
	 * @param apk the built APK; parsed with the package manager, so it must exist on disk.
	 */
	private fun apkApplicationId(apk: File): String? =
		runCatching { packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName }
			.onFailure { logger.warn("Could not read the applicationId of {}", apk, it) }
			.getOrNull()
			?.takeIf { it.isNotBlank() }

	private fun doInstallApk(state: BuildState.AwaitingInstall) {
		apkInstallationViewModel.installApk(
			context = this,
			apk = state.apkFile,
			launchInDebugMode = state.launchInDebugMode,
		)

		if (state.launchProfilerAfterInstall) {
			// The "Profile" action built a profileable APK and just launched it; surface the
			// Profiler tab so the user can immediately profile the running app.
			bottomSheetViewModel.setSheetState(
				sheetState = BottomSheetBehavior.STATE_EXPANDED,
				currentTab = BottomSheetViewModel.TAB_PROFILER,
			)
		}
	}

	/**
	 * The Quick Build session manager (ADFA-4128), or null when the feature is off.
	 * Gated exactly like the action's registration in EditorActivityActions - the
	 * experiments flag only, no SDK check: Quick Build works from API 28, where a degraded
	 * resource shim covers 28/29. Resolving the Koin singleton is cheap -
	 * nothing spawns until the first quick build runs.
	 *
	 * Protected (not private): [EditorHandlerActivity]'s split-button dropdown
	 * calls this too, to trigger a quick build / restart from the long-press menu.
	 */
	protected fun quickBuildSessionManager(): QuickBuildSessionManager? {
		if (!FeatureFlags.isExperimentsEnabled) {
			return null
		}
		return runCatching { GlobalContext.get().get<QuickBuildSessionManager>() }
			.onFailure { logger.error("Quick Build session manager unavailable", it) }
			.getOrNull()
	}

	/**
	 * ADFA-4128 benchmark: a bench re-open of the ALREADY-OPEN project arrives here
	 * (single-top editor), not through project init. Claim + fire, mirroring the
	 * [onProjectInitialized] claim site. While the project is still initializing the
	 * latch is left armed - the init-path claim will consume it. The standard-mode path
	 * exists so the harness can measure a post-edit INCREMENTAL standard build on the
	 * warm Gradle daemon (a force-stop + fresh open would cold-start the daemon).
	 */
	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		if (!QuickBuildBenchHooks.isEnabled || editorViewModel.isInitializing) return
		fireAutostart(claimAutostart())
	}

	/**
	 * Whether Quick Build's text is what the status line currently shows. Cleared by every
	 * [setStatus] call (whoever writes the bar owns it), re-set by [showQuickBuildStatus]
	 * after its own writes. Gates session-end clears and passive refreshes so they never
	 * wipe another writer's line - a build's result stays up until the next build starts.
	 */
	private var ownsQuickBuildStatus = false

	/**
	 * Decides which Quick Build outcomes get a flashbar over the editor. Holds the one bit of
	 * history that decision needs (see [QuickBuildFlashes]), so it must outlive a single status
	 * emission - a per-emission instance would never see a recovery - AND a configuration
	 * change, which is why it lives on the ViewModel rather than here.
	 */
	private val quickBuildFlashes get() = editorViewModel.quickBuildFlashes

	/**
	 * Defers the eager Quick Build prebuild past the project-open contention spike (ADFA-4128
	 * ANR). On [editorActivityScope] so closing the project drops a still-pending warm-up
	 * outright - the teardown in [onPause] only covers work that already started.
	 */
	private val prebuildStagger = QuickBuildPrebuildStagger(editorActivityScope)

	/**
	 * Claims a pending benchmark autostart for the open project (ADFA-4128), or
	 * [AutostartBuild.NONE] when nothing is armed - which is always the case in a release
	 * build, where [QuickBuildBenchHooks] is the no-op twin. One-shot, matched by canonical
	 * path, so an unrelated project open never consumes the latch.
	 */
	private fun claimAutostart(): AutostartBuild {
		if (!QuickBuildBenchHooks.isEnabled) {
			return AutostartBuild.NONE
		}
		val canonical =
			runCatching { File(IProjectManager.getInstance().projectDirPath).canonicalPath }.getOrNull()
				?: return AutostartBuild.NONE
		return QuickBuildBenchHooks.claimAutostart(canonical)
	}

	/** Fires the build a claimed autostart asked for, in place of the human's first tap. */
	private fun fireAutostart(autostart: AutostartBuild) {
		when (autostart) {
			AutostartBuild.QUICK_BUILD -> quickBuildSessionManager()?.onQuickBuildTapped()
			AutostartBuild.STANDARD -> fireAutostartStandardBuild()
			AutostartBuild.NONE -> Unit
		}
	}

	/**
	 * [AutostartBuild.STANDARD]: fires the standard Run build exactly as the toolbar action
	 * would for a single-application project, stamping benchmark events around it so the
	 * harness reads the build duration. The post-build install is suppressed in
	 * [onBuildStateChanged] - the measurement ends at the build result, and an unattended run
	 * must not pop an install dialog.
	 */
	private fun fireAutostartStandardBuild() {
		val module = IProjectManager.getInstance().getAndroidAppModules().firstOrNull()
		val variant = module?.getSelectedVariant()
		if (module == null || variant == null) {
			logger.warn("Autostart standard build: no application module/variant to build")
			return
		}
		QuickBuildBenchHooks.standardBuildStarted(
			projectPath = IProjectManager.getInstance().projectDirPath,
			modulePath = module.path,
			variantName = variant.name,
		)
		buildViewModel.runQuickBuild(module, variant, launchInDebugMode = false)
	}

	/**
	 * The Quick Build confirm-on-switch check (ADFA-4128), or null when the feature is off.
	 * Gated exactly like [quickBuildSessionManager].
	 */
	protected fun quickBuildClobberCheck(): QuickBuildClobberCheck? {
		if (!FeatureFlags.isExperimentsEnabled) {
			return null
		}
		return runCatching { GlobalContext.get().get<QuickBuildClobberCheck>() }
			.onFailure { logger.error("Quick Build clobber check unavailable", it) }
			.getOrNull()
	}

	/**
	 * Quick Build install gate (ADFA-4128): the proxy app installs under the project's real
	 * applicationId. When a different build (the Standard Run app) currently occupies that
	 * id, installing the proxy app replaces it, so confirm first and run [onConfirmed] only on
	 * accept; otherwise [onConfirmed] runs immediately. A third-party occupant (different
	 * signing cert) is caught authoritatively by the provisioner's signature check, which
	 * refuses rather than clobbers.
	 */
	fun ensureQuickBuildClobberConfirmed(onConfirmed: () -> Unit) {
		// No check means the feature is off, and with it off no proxy app can exist to be
		// replaced - the only branch here that may skip the confirmation.
		val clobberCheck = quickBuildClobberCheck()
		if (clobberCheck == null) {
			onConfirmed()
			return
		}
		when (
			val decision =
				quickBuildClobberConfirmation(
					projectRealApplicationId(),
					clobberCheck::quickBuildNeedsConfirm,
				)
		) {
			QuickBuildClobberConfirmation.NotNeeded -> {
				onConfirmed()
			}

			QuickBuildClobberConfirmation.NeededForUnknownAppId -> {
				confirmUnknownOccupantSwitch(onConfirmed)
			}

			is QuickBuildClobberConfirmation.Needed -> {
				confirmBuildTypeSwitch(
					getString(string.quick_build_switch_to_quick_title),
					getString(string.quick_build_switch_to_quick_message, decision.applicationId),
					onConfirmed,
				)
			}
		}
	}

	/**
	 * Standard Run install gate (ADFA-4128), tap half: asks BEFORE the build rather than after it.
	 *
	 * A Run that will replace the Quick Build proxy app is worth knowing about while the choice is
	 * still cheap - asking only at install time spends a full Gradle build on a run the user then
	 * cancels. The question is asked about the variant being built as of THIS tap, which is what
	 * the build will produce, so there is no window in which the selection can drift out from
	 * under the question.
	 *
	 * @param applicationId the applicationId of the variant this tap is about to build; null when
	 *   the model names none, which asks rather than assuming the slot is empty.
	 * @param onConfirmed run only if the user accepts, carrying the answer this tap settled so the
	 *   install can tell whether it has since changed.
	 */
	fun ensureStandardRunClobberConfirmed(
		applicationId: String?,
		onConfirmed: (QuickBuildClobberConfirmation) -> Unit,
	) {
		// No check means the feature is off, and with it off no proxy app can exist to be
		// replaced - the only branch here that may skip the confirmation.
		val clobberCheck = quickBuildClobberCheck()
		if (clobberCheck == null) {
			onConfirmed(QuickBuildClobberConfirmation.NotNeeded)
			return
		}
		when (
			val decision =
				quickBuildClobberConfirmation(applicationId, clobberCheck::standardRunNeedsConfirm)
		) {
			QuickBuildClobberConfirmation.NotNeeded -> {
				onConfirmed(decision)
			}

			QuickBuildClobberConfirmation.NeededForUnknownAppId -> {
				confirmUnknownOccupantSwitch { onConfirmed(decision) }
			}

			is QuickBuildClobberConfirmation.Needed -> {
				confirmBuildTypeSwitch(
					getString(string.quick_build_switch_to_standard_title),
					getString(string.quick_build_switch_to_standard_message, decision.applicationId),
				) { onConfirmed(decision) }
			}
		}
	}

	/**
	 * The confirmation for a clobber we cannot describe: the project's applicationId did not
	 * resolve, so neither dialog's wording (each of which names the id and asserts what holds
	 * it) is true. Asks anyway rather than proceeding - see
	 * [QuickBuildClobberConfirmation.NeededForUnknownAppId].
	 */
	private fun confirmUnknownOccupantSwitch(onConfirmed: () -> Unit) {
		confirmBuildTypeSwitch(
			getString(string.quick_build_switch_unknown_app_title),
			getString(string.quick_build_switch_unknown_app_message),
			onConfirmed,
		)
	}

	private fun projectRealApplicationId(): String? {
		val projectManager = IProjectManager.getInstance()
		val module =
			projectManager.getAndroidAppModules().firstOrNull()
				?: projectManager.getAndroidModules().firstOrNull()
				?: return null
		return module
			.getSelectedVariant()
			?.mainArtifact
			?.applicationId
			?.takeIf { it.isNotBlank() }
	}

	/**
	 * The confirm-on-switch dialog (ADFA-4128): switching build type overwrites whatever
	 * currently occupies the project's real applicationId, so the confirm is destructive-styled
	 * and nothing installs before accept. Decline (button, back, or outside touch) leaves the
	 * installed app untouched.
	 */
	private fun confirmBuildTypeSwitch(
		title: String,
		message: String,
		onConfirm: () -> Unit,
	) {
		val dialog =
			newMaterialDialogBuilder(this)
				.setTitle(title)
				.setMessage(message)
				.setPositiveButton(string.quick_build_switch_confirm) { d, _ ->
					d.dismiss()
					onConfirm()
				}.setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
				.show()
		// Destructive styling: the confirm action replaces an installed app, so it must
		// not read as the default affirmative.
		dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
			resolveAttr(com.itsaky.androidide.resources.R.attr.colorError),
		)
	}

	/**
	 * Hand-back (ADFA-4128): called by [EditorBuildEventListener] whenever ANY
	 * external Gradle build finishes - success OR failure, Run button or "Run Gradle
	 * tasks". Even a failed build can have rewritten build/ outputs of the modules that
	 * DID compile (paths the quick-build watcher deliberately does not watch), so a live
	 * session refreshes its baseline from current disk either way. Over-refreshing is safe: it only
	 * marks the baseline untrusted. The session's own proxy app builds also land here, but
	 * the reducer drops the event in Provisioning/Prebuilding.
	 */
	fun onExternalGradleBuildFinished() {
		quickBuildSessionManager()?.onStandardRunCompleted()
	}

	private fun showPluginInstallDialog(cgpFile: File) {
		if (!cgpFile.exists()) {
			flashError(getString(string.msg_plugin_file_not_found))
			buildViewModel.pluginInstallationAttempted()
			return
		}
		val pluginName = cgpFile.nameWithoutExtension
		newMaterialDialogBuilder(this)
			.setTitle(string.title_install_plugin)
			.setMessage(getString(string.msg_install_plugin_prompt, pluginName))
			.setPositiveButton(string.btn_install) { dialog, _ ->
				dialog.dismiss()
				installPlugin(cgpFile)
			}.setNegativeButton(string.btn_later) { dialog, _ ->
				dialog.dismiss()
				buildViewModel.pluginInstallationAttempted()
			}.setOnCancelListener {
				buildViewModel.pluginInstallationAttempted()
			}.show()
	}

	private fun installPlugin(cgpFile: File) {
		lifecycleScope.launch {
			setStatus(getString(string.status_installing_plugin))
			val result = pluginRepository.installPluginFromFile(cgpFile)
			result
				.onSuccess {
					showRestartPrompt(this@ProjectHandlerActivity)
				}.onFailure { error ->
					flashError(
						getString(
							string.msg_plugin_install_failed,
							error.message ?: "Unknown error",
						),
					)
				}
			setStatus("")
			buildViewModel.pluginInstallationAttempted()
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.apply {
			putBoolean(STATE_KEY_SHOULD_INITIALIZE, !editorViewModel.isInitializing)
			putBoolean(STATE_KEY_FROM_SAVED_INSTANACE, true)
		}
	}

	override fun onPause() {
		super.onPause()
		if (isDestroying) {
			// reset these values here
			// sometimes, when the IDE closed and reopened instantly, these values prevent initialization
			// of the project
			ProjectManagerImpl.getInstance().destroy()

			// ADFA-4128: the Quick Build session manager is a process-wide Koin
			// singleton that outlives this activity, and its provisioner reads
			// IProjectManager.getInstance().projectDirPath fresh at build time rather
			// than a snapshot. Without this, closing a project while its eager prebuild
			// (or a live session) is still in flight lets that work silently keep
			// running once projectPath flips to whatever project opens next - either
			// racing the next project's own prebuild() into a permanent no-op (the
			// reducer treats a second PrebuildRequested while already Prebuilding as a
			// no-op) or building against the wrong directory. restartSession() is a
			// verified no-op when nothing is live (SessionReducerTest: "idle plus
			// SessionRestartRequested is a no-op").
			quickBuildSessionManager()?.restartSession()
			// The narrator is a process-wide singleton and its queue is per-project narration.
			// Held lines belong to the project being closed, so without this they flush into the
			// NEXT project's Build Output as that project's progress.
			quickBuildOutputNarrator()?.reset()

			editorViewModel.isInitializing = false
			editorViewModel.isBuildInProgress = false
			editorViewModel.isInternalBuildInProgress = false
		}
	}

	override fun onResume() {
		super.onResume()

		val service =
			Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService
		// The USER-visible flag, not the raw one: Quick Build's proxy app build occupies the same
		// Gradle slot on every project open, and latching the raw flag here left the editor
		// stuck showing "building" (progress bar + cancel label) for a build nobody started -
		// and, with its listener suppressed, nothing would ever clear it. That build's progress
		// rides the internal flag instead, which the bracket clears on every exit path.
		editorViewModel.isBuildInProgress = service?.isUserVisibleBuildInProgress == true
		editorViewModel.isInternalBuildInProgress = service?.isInternalBuildInProgress == true
		editorViewModel.isInitializing = initializingFuture?.isDone == false

		// ADFA-4128: a proxy app rebuild reinstall that ran while CoGo was backgrounded never
		// showed its confirm dialog - Android defers the PENDING_USER_ACTION broadcast
		// until the app is foregrounded, and the dialog-owning subscriber
		// (InstallationResultHandler via BaseEditorActivity) is EventBus lifecycle-bound
		// (registered onStart), so the deferred delivery can land before it re-registers.
		// Returning here is the first chance to re-prompt. No-op unless the session is
		// parked awaiting that retry (auto-retries are bounded by the reducer).
		quickBuildSessionManager()?.onHostForegrounded()

		invalidateOptionsMenu()
	}

	override fun preDestroy() {
		syncNotificationFlashbar?.dismiss()
		syncNotificationFlashbar = null

		if (isDestroying) {
			releaseServerListener()
			this.initializingFuture?.cancel(true)
			this.initializingFuture = null

			doCloseAll()
		}

		if (IDELanguageClientImpl.isInitialized()) {
			IDELanguageClientImpl.shutdown()
		}

		super.preDestroy()

		if (isDestroying) {
			try {
				stopLanguageServers()
			} catch (_: Exception) {
				log.error("Failed to stop editor services.")
			}

			try {
				unbindService(buildServiceConnection)
				buildServiceConnection.onConnected = {}
			} catch (_: Throwable) {
				log.error("Unable to unbind service")
			} finally {
				Lookup.getDefault().apply {
					(lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService?)
						?.setEventListener(null)

					unregister(BuildService.KEY_BUILD_SERVICE)
				}

				mBuildEventListener.release()
				editorViewModel.isBoundToBuildSerice = false
			}
		}
	}

	fun setStatus(status: CharSequence) {
		setStatus(status, Gravity.CENTER)
	}

	fun setStatus(
		status: CharSequence,
		@GravityInt gravity: Int,
	) {
		// Whoever writes the bar owns it: a build's task/result line must persist until the
		// next build takes the line over, so Quick Build's passive refreshes check this flag
		// (showQuickBuildStatus re-sets it right after its own writes).
		ownsQuickBuildStatus = false
		doSetStatus(status, gravity)
	}

	fun appendBuildOutput(str: String) {
		if (_binding == null || isDestroyed || isFinishing) return
		content.bottomSheet.appendBuildOut(str)
	}

	fun notifySyncNeeded() {
		notifySyncNeeded { initializeProject(forceSync = true) }
	}

	private fun notifySyncNeeded(onConfirm: () -> Unit) {
		val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
		if (buildService == null || editorViewModel.isInitializing || buildService.isBuildInProgress) return

		activityScope.launch(Dispatchers.Main.immediate) {
			syncNotificationFlashbar?.dismiss()
			syncNotificationFlashbar =
				flashbarBuilder(
					duration = DURATION_INDEFINITE,
					backgroundColor = resolveAttr(R.attr.colorSecondaryContainer),
					messageColor = resolveAttr(R.attr.colorOnSecondaryContainer),
				).withIcon(
					R.drawable.ic_sync,
					colorFilter = resolveAttr(R.attr.colorOnSecondaryContainer),
				).message(string.msg_sync_needed)
					.positiveActionText(string.btn_sync)
					.positiveActionTapListener {
						onConfirm()
						it.dismiss()
					}.negativeActionText(string.btn_ignore_changes)
					.negativeActionTapListener(Flashbar::dismiss)
					.build()

			syncNotificationFlashbar?.showOnUiThread()
		}
	}

	fun startServices() {
		val service =
			Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as GradleBuildService?
		if (editorViewModel.isBoundToBuildSerice && service != null) {
			log.info("Reusing already started Gradle build service")
			onGradleBuildServiceConnected(service)
			return
		} else {
			log.info("Binding to Gradle build service...")
		}

		buildServiceConnection.onConnected = this::onGradleBuildServiceConnected

		if (
			bindService(
				Intent(this, GradleBuildService::class.java),
				buildServiceConnection,
				BIND_AUTO_CREATE or BIND_IMPORTANT,
			)
		) {
			log.info("Bind request for Gradle build service was successful...")
		} else {
			log.error("Gradle build service doesn't exist or the IDE is not allowed to access it.")
		}

		lifecycleScope.launch {
			initLspClient()
		}
	}

	fun initializeProject(forceSync: Boolean = false) {
		val currentVariants = buildVariantsViewModel._buildVariants.value

		// no information about the build variants is available
		// use the default variant selections
		if (currentVariants == null) {
			log.debug(
				"No variant selection information available. " +
					"Default build variants will be selected.",
			)
			initializeProject(buildVariants = emptyMap(), forceSync = forceSync)
			return
		}

		// variant selection information is available
		// but there are updated & unsaved variant selections
		// use the updated variant selections to initialize the project
		if (buildVariantsViewModel.updatedBuildVariants.isNotEmpty()) {
			val newSelections = currentVariants.toMutableMap()
			newSelections.putAll(buildVariantsViewModel.updatedBuildVariants)

			val selectedVariants = newSelections.mapToSelectedVariants()
			log.debug(
				"Initializing project with new build variant selections: {}",
				selectedVariants,
			)

			initializeProject(buildVariants = selectedVariants, forceSync = forceSync)
			return
		}

		// variant selection information is available but no variant selections have been updated
		// the user might be trying to sync the project from options menu
		// initialize the project with the existing selected variants
		val selectedVariants = currentVariants.mapToSelectedVariants()
		log.debug("Re-initializing project with existing build variant selections")
		initializeProject(buildVariants = selectedVariants, forceSync = forceSync)
	}

	private fun showToast(message: String) {
		Toast.makeText(this@ProjectHandlerActivity, message, Toast.LENGTH_LONG).show()
	}

	private suspend fun handleMissingProjectDirectory(projectName: String) =
		withContext(Dispatchers.Main) {
			recentProjectsViewModel.deleteProject(projectName)
			showToast(getString(string.msg_project_dir_doesnt_exist))

			val intent =
				Intent(this@ProjectHandlerActivity, MainActivity::class.java).apply {
					addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
				}

			startActivity(intent)
			this@ProjectHandlerActivity.finish()
		}

	/**
	 * Initialize (sync) the project.
	 *
	 * @param buildVariants A map of project paths to the selected build
	 *    variants.
	 */
	fun initializeProject(
		buildVariants: Map<String, String>,
		forceSync: Boolean = false,
	) = activityScope.launch {
		val manager = ProjectManagerImpl.getInstance()
		val projectDir = File(manager.projectPath)
		if (!projectDir.exists()) {
			log.error("GradleProject directory does not exist. Cannot initialize project")
			handleMissingProjectDirectory(projectDir.name)
			return@launch
		}

		val needsSync =
			try {
				forceSync || manager.isGradleSyncNeeded(projectDir)
			} catch (e: Exception) {
				when (e) {
					is FileNotFoundException -> {
						handleMissingProjectDirectory(projectDir.name)
						return@launch
					}

					else -> {
						throw e
					}
				}
			}

		withContext(Dispatchers.Main.immediate) {
			preProjectInit()
		}

		val buildService =
			Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService
		if (buildService == null) {
			log.error("No build service found. Cannot initialize project.")
			return@launch
		}

		if (!buildService.isToolingServerStarted()) {
			flashError(string.msg_tooling_server_unavailable)
			return@launch
		}

		log.info("Sending init request to tooling server (needs sync: {})...", needsSync)
		initializingFuture =
			buildService.initializeProject(
				params =
					createProjectInitParams(
						projectDir = projectDir,
						buildVariants = buildVariants,
						needsGradleSync = needsSync,
						buildId = buildService.nextBuildId(BuildRunType.ProjectSync),
					),
			)

		initializingFuture!!.whenCompleteAsync { result, error ->
			releaseServerListener()

			if (result == null || !result.isSuccessful || error != null) {
				if (!CancelChecker.isCancelled(error)) {
					log.error("An error occurred initializing the project with Tooling API", error)
				}

				activityScope.launch(context = Dispatchers.Main) {
					postProjectInit(isSuccessful = false, failure = result?.failure)
				}
				return@whenCompleteAsync
			}

			onProjectInitialized(result as InitializeResult.Success)
		}
	}

	private fun createProjectInitParams(
		projectDir: File,
		buildVariants: Map<String, String>,
		needsGradleSync: Boolean,
		buildId: BuildId,
	): InitializeProjectParams =
		InitializeProjectParams(
			directory = projectDir.absolutePath,
			gradleDistribution = gradleDistributionParams,
			androidParams = createAndroidParams(buildVariants),
			needsGradleSync = needsGradleSync,
			buildId = buildId,
		)

	private fun createAndroidParams(buildVariants: Map<String, String>): AndroidInitializationParams {
		if (buildVariants.isEmpty()) {
			return AndroidInitializationParams.DEFAULT
		}

		return AndroidInitializationParams(buildVariants)
	}

	private fun releaseServerListener() {
		// Release reference to server listener in order to prevent memory leak
		(Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService?)
			?.setServerListener(null)
	}

	fun stopLanguageServers() {
		try {
			destroyLanguageServers(isChangingConfigurations)
		} catch (err: Throwable) {
			log.error("Unable to stop editor services. Please report this issue.", err)
		}
	}

	protected fun onGradleBuildServiceConnected(service: GradleBuildService) {
		log.info("Connected to Gradle build service")

		buildServiceConnection.onConnected = null
		editorViewModel.isBoundToBuildSerice = true
		Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, service)
		service.setEventListener(mBuildEventListener)

		// A stable observer instance, because this runs again whenever an already-bound service is
		// reused; LiveData ignores a re-add of the same observer for the same owner.
		service.internalBuildInProgress.observe(this, internalBuildObserver)

		if (service.isToolingServerStarted()) {
			if (service.isBuildInProgress) {
				log.info("Skipping project initialization while build is in progress")
				return
			}
			initializeProject()
			return
		}

		service.startToolingServer { pid ->
			memoryUsageWatcher.watchProcess(pid, PROC_GRADLE_TOOLING)
			resetMemUsageChart()

			service.metadata().whenComplete { metadata, err ->
				if (metadata == null || err != null) {
					log.error("Failed to get tooling server metadata")
					return@whenComplete
				}

				if (pid != metadata.pid) {
					log.warn(
						"Tooling server pid mismatch. Expected: {}, Actual: {}. Replacing memory watcher...",
						pid,
						metadata.pid,
					)
					memoryUsageWatcher.watchProcess(metadata.pid, PROC_GRADLE_TOOLING)
					resetMemUsageChart()
				}
			}

			initializeProject()
		}
	}

	protected open fun onProjectInitialized(result: InitializeResult.Success) {
		editorActivityScope.launch(Dispatchers.IO) {
			val manager = ProjectManagerImpl.getInstance()
			val gradleBuildResult = ProjectSyncHelper.readGradleBuild(result.cacheFile)
			if (gradleBuildResult.isFailure) {
				val error = gradleBuildResult.exceptionOrNull()
				log.error("Failed to read project cache", error)

				val isExpectedError = error.isFileNotFound

				if (error != null && !isExpectedError) {
					Sentry.captureException(error)
				}

				withContext(Dispatchers.Main) { postProjectInit(false, CACHE_READ_ERROR) }
				return@launch
			}

			manager.setup(gradleBuildResult.getOrThrow())
			manager.notifyProjectUpdate()
			updateBuildVariants(manager.androidBuildVariants)

			withContext(Dispatchers.Main) {
				postProjectInit(isSuccessful = true, failure = null)
			}
		}
	}

	protected open fun preProjectInit() {
		setStatus(getString(string.msg_initializing_project))
		editorViewModel.isInitializing = true
	}

	protected open fun postProjectInit(
		isSuccessful: Boolean,
		failure: TaskExecutionResult.Failure?,
	) {
		val manager = ProjectManagerImpl.getInstance()
		if (!isSuccessful) {
			// Get project name for error message
			val projectName =
				try {
					val project = manager.workspace?.rootProject
					if (project != null) {
						project.name.takeIf { it.isNotEmpty() }
							?: manager.projectDir.name
					} else {
						manager.projectDir.name
					}
				} catch (th: Throwable) {
					manager.projectDir.name
				}

			val initFailed =
				if (projectName.isNotEmpty()) {
					getString(string.msg_project_initialization_failed_with_name, projectName)
				} else {
					getString(string.msg_project_initialization_failed)
				}
			setStatus(initFailed)

			val msg =
				when (failure) {
					PROJECT_DIRECTORY_INACCESSIBLE -> string.msg_project_dir_inaccessible
					PROJECT_NOT_DIRECTORY -> string.msg_file_is_not_dir
					PROJECT_NOT_FOUND -> string.msg_project_dir_doesnt_exist
					CACHE_READ_ERROR -> string.msg_project_cache_read_failure
					else -> null
				}?.let {
					"$initFailed: ${getString(it)}"
				}

			flashError(msg ?: initFailed)

			editorViewModel.isInitializing = false
			return
		}

		initialSetup()
		setStatus(getString(string.msg_project_initialized))
		editorViewModel.isInitializing = false
		invalidateOptionsMenu()

		// ADFA-4128 benchmark: if the bench trampoline armed an autostart for THIS project,
		// claim it now - the adb-driven stand-in for the human's tap. Claimed BEFORE prebuild
		// so a standard-mode bench build runs alone on the daemon instead of racing the eager
		// proxy app build. Always NONE in a release build.
		val autostart = claimAutostart()

		// ADFA-4128: eager quick-build proxy app build, staggered past the project-open
		// contention spike (sync + both LSP setups + indexing) that starved input dispatch
		// into an ANR on-device - see QuickBuildPrebuildStagger. Fire-and-forget on the
		// session manager's own thread; installs nothing until the first tap, and a tap
		// during the window provisions immediately without waiting for it.
		//
		// Applying a Build Variants selection re-syncs the project and lands here too, so
		// this is also where a live session provisioned for the old variant gets torn down
		// and reprovisioned - the stagger fires that case through immediately, and the
		// variant is read at fire time so a deferred fire compares fresh state.
		if (!autostart.suppressesPrebuild) {
			prebuildStagger.onProjectSynced(
				sessionIsLive = {
					// `is` rather than equality: Idle carries lastStartFailed since B15, and
					// a failed-start Idle is still an idle session for the stagger's purposes.
					val state = quickBuildSessionManager()?.state?.value
					state != null && state !is QuickBuildSessionState.Idle
				},
				fire = {
					quickBuildSessionManager()?.onProjectSynced(GradleQuickBuildProvisioner.selectedVariantName())
				},
			)
		}

		fireAutostart(autostart)

		if (mFindInProjectDialog?.isShowing == true) {
			mFindInProjectDialog!!.dismiss()
		}

		mFindInProjectDialog = null // Create the dialog again if needed
	}

	private fun updateBuildVariants(buildVariants: Map<String, BuildVariantInfo>) {
		// avoid using the 'runOnUiThread' method defined in the activity
		com.itsaky.androidide.tasks.runOnUiThread {
			buildVariantsViewModel.buildVariants = buildVariants
			buildVariantsViewModel.resetUpdatedSelections()
		}
	}

	protected open fun createFindInProjectDialog(): AlertDialog? {
		val manager = ProjectManagerImpl.getInstance()
		if (manager.workspace == null) {
			log.warn("No root project model found. Is the project initialized?")
			flashError(getString(string.msg_project_not_initialized))
			return null
		}

		val moduleDirs =
			try {
				manager.gradleBuild!!
					.subProjectList
					.stream()
					.map { project -> project.projectDir }
					.collect(Collectors.toList())
			} catch (_: Throwable) {
				flashError(getString(string.msg_no_modules))
				emptyList()
			}

		return createFindInProjectDialog(moduleDirs)
	}

	protected open fun createFindInProjectDialog(moduleDirs: List<File>): AlertDialog? {
		val srcDirs = mutableListOf<File>()
		val binding = LayoutSearchProjectBinding.inflate(layoutInflater)
		binding.modulesContainer.removeAllViews()

		for (i in moduleDirs.indices) {
			val module = moduleDirs[i]
			val src = File(module, "src")

			if (!module.exists() || !module.isDirectory || !src.exists() || !src.isDirectory) {
				continue
			}

			val check = CheckBox(this)
			check.text = module.name
			check.isChecked = true

			val params = MarginLayoutParams(-2, -2)
			params.bottomMargin = dpToPx(4f)
			binding.modulesContainer.addView(check, params)
			srcDirs.add(src)
		}

		val builder = newMaterialDialogBuilder(this)
		builder.setTitle(string.menu_find_project)
		builder.setView(binding.root)
		builder.setCancelable(false)
		builder.setPositiveButton(string.menu_find) { dialog, _ ->
			val text =
				binding.input.editText!!
					.text
					.toString()
					.trim()
			if (text.isEmpty()) {
				flashError(string.msg_empty_search_query)
				return@setPositiveButton
			}

			val searchDirs = mutableListOf<File>()
			for (i in 0 until binding.modulesContainer.childCount) {
				val check = binding.modulesContainer.getChildAt(i) as CheckBox
				if (check.isChecked) {
					searchDirs.add(srcDirs[i])
				}
			}

			val extensions =
				binding.filter.editText!!
					.text
					.toString()
					.trim()
			val extensionList = mutableListOf<String>()
			if (extensions.isNotEmpty()) {
				if (extensions.contains("|")) {
					for (
					str in
					extensions
						.split(Pattern.quote("|").toRegex())
						.dropLastWhile { it.isEmpty() }
						.toTypedArray()
					) {
						val token = str.trim()
						if (token.isEmpty()) {
							continue
						}
						// Trim so a pipe-split token keeps no surrounding space; the file-name
						// suffix test is endsWith(), which a stray " kt" would never satisfy.
						extensionList.add(token)
					}
				} else {
					extensionList.add(extensions)
				}
			}

			if (searchDirs.isEmpty()) {
				flashError(string.msg_select_search_modules)
			} else {
				dialog.dismiss()

				getProgressSheet(string.msg_searching_project)?.apply {
					show(supportFragmentManager, "search_in_project_progress")
				}

				RecursiveFileSearcher.searchRecursiveAsync(
					text,
					extensionList,
					searchDirs,
				) { results ->
					val plugins = projectSearchPlugins()
					// When the built-in search finds nothing but a plugin may still match, keep
					// the progress indicator up until the plugin phase resolves.
					handleSearchResults(results, dismissProgress = plugins.isEmpty() || results.isNotEmpty())
					if (plugins.isNotEmpty()) {
						requestPluginSearchSections(plugins, text, extensionList, searchDirs, results)
					}
				}
			}
		}

		builder.setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
		val dialog = builder.create()
		dialog.onLongPress(includeEditTexts = true) { view ->
			if (
				view is EditText
			) {
				view.selectCurrentWord()
				view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
				SearchFieldToolbar(view).show()
				true
			} else if (view === binding.input ||
				view === binding.filter ||
				view.parent === binding.input ||
				view.parent === binding.filter
			) {
				true
			} else {
				TooltipManager.showIdeCategoryTooltip(
					context = this,
					anchorView = binding.root,
					tag = TooltipTag.DIALOG_FIND_IN_PROJECT,
				)
				true
			}
		}

		mFindInProjectDialog = dialog
		return mFindInProjectDialog
	}

	private fun projectSearchPlugins(): List<ProjectSearchExtension> =
		IDEApplication
			.getPluginManager()
			?.getAllPluginInstances()
			?.filterIsInstance<ProjectSearchExtension>()
			?: emptyList()

	private fun requestPluginSearchSections(
		plugins: List<ProjectSearchExtension>,
		query: String,
		extensions: List<String>,
		searchDirs: List<File>,
		exactResults: Map<File, List<SearchResult>>,
	) {
		val pluginManager = IDEApplication.getPluginManager() ?: return

		// handleSearchResults() just published the built-in results and bumped the generation;
		// capture it so a superseding search invalidates this fan-out's late results.
		val generation = editorViewModel.currentSearchGeneration
		val request =
			ProjectSearchRequest(
				query = query,
				roots = searchDirs,
				extensions = extensions,
			)
		// searchProject() is contractually called on the UI thread; launch the futures here.
		val futures =
			plugins.mapNotNull { plugin ->
				val pluginId =
					pluginManager.getPluginIdForInstance(plugin as com.itsaky.androidide.plugins.IPlugin)
						?: plugin.javaClass.name
				try {
					plugin
						.searchProject(request)
						.exceptionally { error ->
							logger.warn("Project search plugin '{}' failed", pluginId, error)
							// Runs on whatever thread completed the future; recordPluginCrash may
							// force-disable the plugin, which mutates UI (tabs, sidebar).
							runOnUiThread { pluginManager.recordPluginCrash(pluginId) }
							emptyList()
						}
				} catch (error: Exception) {
					logger.warn("Project search plugin '{}' failed", pluginId, error)
					pluginManager.recordPluginCrash(pluginId)
					null
				}
			}
		if (futures.isEmpty()) {
			doDismissSearchProgress()
			return
		}

		// lifecycleScope cancels on destroy, so a slow plugin cannot pin this Activity/ViewModel
		// or post to a dead Activity; await() suspends instead of blocking a commonPool worker.
		lifecycleScope.launch {
			val pluginSections =
				withContext(Dispatchers.Default) {
					try {
						withTimeoutOrNull(TimeUnit.SECONDS.toMillis(PLUGIN_SEARCH_TIMEOUT_SECONDS)) {
							CompletableFuture.allOf(*futures.toTypedArray()).await()
						}
						futures
							// getNow() skips futures still pending after the timeout; orEmpty()
							// guards a Java plugin completing with a null list.
							.flatMap { future -> future.getNow(emptyList()).orEmpty() }
							.mapNotNull { section -> section?.toSearchResultSection() }
					} catch (cancellation: CancellationException) {
						throw cancellation
					} catch (error: Exception) {
						logger.warn("Failed to collect project search plugin results", error)
						emptyList()
					}
				}
			val sections =
				buildList {
					if (exactResults.isNotEmpty()) {
						add(SearchResultSection(title = null, results = exactResults))
					}
					addAll(pluginSections)
				}
			editorViewModel.onSearchResultSectionsReady(generation, sections)
			doDismissSearchProgress()
		}
	}

	private fun ProjectSearchSection.toSearchResultSection(): SearchResultSection? {
		val grouped =
			results
				// mapNotNull guards a Java plugin returning null elements; a bare map() would
				// NPE in toSearchResult() and take down the whole fan-out.
				.mapNotNull { it?.toSearchResult() }
				.groupBy { it.file }
		return grouped
			.takeIf { it.isNotEmpty() }
			?.let { SearchResultSection(title = title, results = it) }
	}

	private fun ProjectSearchResult.toSearchResult(): SearchResult {
		val range =
			Range(
				Position(startLine, startColumn),
				Position(endLine, endColumn),
			)
		return SearchResult(range, file, linePreview, matchText)
	}

	fun EditText.selectCurrentWord() {
		val content = text ?: return
		if (content.isEmpty()) return

		val currentStart = selectionStart
		val currentEnd = selectionEnd

		if (currentStart < 0 || currentEnd > content.length || currentStart != currentEnd) {
			return
		}

		val range = ICUUtils.getWordRange(content, currentStart, true)
		val newStart = IntPair.getFirst(range)
		val newEnd = IntPair.getSecond(range)

		val isValidRange =
			newStart >= 0 &&
				newEnd <= content.length &&
				newStart <= newEnd

		if (isValidRange && newStart != newEnd) {
			setSelection(newStart, newEnd)
		}
	}

	private fun initialSetup() {
		val manager = ProjectManagerImpl.getInstance()
		try {
			val project = manager.workspace?.rootProject
			if (project == null) {
				log.warn("GradleProject not initialized. Skipping initial setup...")
				return
			}

			var projectName = project.name
			if (projectName.isEmpty()) {
				projectName = manager.projectDir.name
			}

			supportActionBar!!.subtitle = projectName
		} catch (_: Throwable) {
			// ignored
		}
	}

	private fun openHelpActivity() {
		val intent = Intent(this, HelpActivity::class.java)
		intent.putExtra(CONTENT_KEY, getString(string.docs_url))
		startActivity(intent)
	}

	private suspend fun initLspClient() {
		if (!IDELanguageClientImpl.isInitialized()) {
			IDELanguageClientImpl.initialize(this as EditorHandlerActivity)
		}

		connectClient(IDELanguageClientImpl.getInstance())

		val results =
			try {
				connectDebugClient(debuggerViewModel.debugClient).values
			} catch (e: Exception) {
				if (e is CancellationException) {
					throw e
				}

				Sentry.captureException(e)
				logger.error("Unable to connect LSP servers with debug client", e)
				listOf(DebugClientConnectionResult.Failure(cause = e))
			}

		if (results.any { it is DebugClientConnectionResult.Failure }) {
			// one or more debug adapters failed to initialize
			val message =
				buildString {
					results.filterIsInstance<DebugClientConnectionResult.Failure>().forEach { result ->
						val msg =
							result.contextRes?.let(::getString)
								?: result.context
								?: (result.cause as? SocketException?).let { err ->
									val msg = err?.message ?: ""
									when {
										msg.contains("EPERM") -> getString(string.debugger_error_errno_eperm)
										msg.contains("ECONNREFUSED") -> getString(string.debugger_error_errno_econnrefused)
										else -> null
									}
								}
								?: (result.cause as? ErrnoException? ?: result.cause?.cause as? ErrnoException?)?.let { err ->
									when (err.errno) {
										OsConstants.EPERM -> getString(string.debugger_error_errno_eperm)
										OsConstants.ECONNREFUSED -> getString(string.debugger_error_errno_econnrefused)
										else -> getString(R.string.debugger_error_errno, err.errno)
									}
								}
								?: getString(R.string.debugger_error_debugger_startup_failure)

						append(msg)
						append(System.lineSeparator())
					}

					if (isNotBlank()) {
						append(System.lineSeparator())
					}

					append(getString(R.string.debugger_error_suggestion_network_restriction))
				}

			withContext(Dispatchers.Main) {
				newMaterialDialogBuilder(this@ProjectHandlerActivity)
					.setTitle(R.string.debugger_error_network_access_error)
					.setMessage(message)
					.setPositiveButton(android.R.string.ok, null)
					.show()
			}
		}
	}

	open fun getProgressSheet(msg: Int): ProgressSheet? {
		doDismissSearchProgress()

		mSearchingProgress =
			ProgressSheet().also {
				it.isCancelable = false
				it.setMessage(getString(msg))
				it.setSubMessageEnabled(false)
			}

		return mSearchingProgress
	}
}
