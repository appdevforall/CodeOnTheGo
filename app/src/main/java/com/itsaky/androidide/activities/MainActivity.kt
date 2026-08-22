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

package com.itsaky.androidide.activities

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.IntentCompat
import androidx.core.graphics.Insets
import androidx.core.os.BundleCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import androidx.transition.doOnEnd
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.FeedbackButtonManager
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.activities.editor.EditorActivityKt
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityMainBinding
import com.itsaky.androidide.deeplink.ConsumedDeepLinkRequests
import com.itsaky.androidide.fragments.MainFragment
import com.itsaky.androidide.fragments.RecentProjectsFragment
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_RECENT_TOP
import com.itsaky.androidide.idetooltips.TooltipTag.SETUP_OVERVIEW
import com.itsaky.androidide.localWebServer.ServerConfig
import com.itsaky.androidide.localWebServer.WebServer
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.models.PendingFileRequest
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.repositories.RecentProjectRepository
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.shortcuts.IdeShortcutActions
import com.itsaky.androidide.shortcuts.ShortcutContext
import com.itsaky.androidide.shortcuts.ShortcutExecutionContext
import com.itsaky.androidide.shortcuts.ShortcutManager
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.MainScreenActions
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.utils.applyBottomWindowInsetsPadding
import com.itsaky.androidide.utils.findValidProjects
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.utils.hasVisibleDialog
import com.itsaky.androidide.utils.recordProjectOpenedBookkeeping
import com.itsaky.androidide.utils.resolveDeepLinkProject
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_CLONE_REPO
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_DELETE_PROJECTS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_MAIN
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_SAVED_PROJECTS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_DETAILS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_LIST
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.TOOLTIPS_WEB_VIEW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.slf4j.LoggerFactory
import java.io.File

class MainActivity : EdgeToEdgeIDEActivity() {
	private val log = LoggerFactory.getLogger(MainActivity::class.java)

	private val viewModel by viewModel<MainViewModel>()

	@Suppress("ktlint:standard:backing-property-naming")
	private var _binding: ActivityMainBinding? = null
	private val analyticsManager: IAnalyticsManager by inject()
	private val recentProjectRepository: RecentProjectRepository by inject()
	private var feedbackButtonManager: FeedbackButtonManager? = null
	private var webServer: WebServer? = null
	private val shortcutManager by lazy { ShortcutManager(applicationContext) }

	// Tracked so a slower, older deep-link resolve (still in flight when a second, faster-resolving
	// deep link arrives) can tell it's been superseded -- see handleDeepLinkRequest.
	private var latestDeepLinkRequest: DeepLinkRequest? = null

	// The last deep-link request actually opened (or, if GeneralPreferences.confirmProjectOpen is on,
	// actually confirmed) -- see handleOpenProject/askProjectOpenPermission. Persisted via
	// onSaveInstanceState rather than signalled by removing the Intent's own extra: a genuine process
	// death redelivers the ORIGINAL, unmutated launch Intent (extras and all) once the user returns to
	// the task, so an Intent-mutation-based "already handled" signal doesn't survive it and this same
	// request force-reopens a project the user has since navigated away from. A config-change
	// recreate, in contrast, preserves this field across the recreate but correctly leaves it unset
	// if the recreate happens before the user actually responds to the confirm dialog, so that dialog
	// (destroyed along with the old instance) gets a fresh retry on the new one instead of the link
	// being silently dropped.
	// Every request consumed in this task, not just the last one -- see the class for why one slot
	// was not enough.
	private val consumedDeepLinkRequests = ConsumedDeepLinkRequests()

	private val onBackPressedCallback =
		object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				viewModel.apply {
					// Ignore back press if project creating is in progress
					if (creatingProject.value == true) {
						return@apply
					}

					val newScreen =
						when (currentScreen.value) {
							SCREEN_TEMPLATE_DETAILS -> SCREEN_TEMPLATE_LIST
							SCREEN_TEMPLATE_LIST -> SCREEN_MAIN
							else -> SCREEN_MAIN
						}

					if (currentScreen.value != newScreen) {
						setScreen(newScreen)
					}
				}
			}
		}

	private val binding: ActivityMainBinding
		get() = checkNotNull(_binding)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		MainScreenActions.register(this)

		// Start WebServer after installation is complete
		startWebServer()

		val deepLinkRequest =
			IntentCompat.getParcelableExtra(intent, DeepLinkRequest.EXTRA_KEY, DeepLinkRequest::class.java)
		consumedDeepLinkRequests.restore(
			savedInstanceState?.let {
				BundleCompat.getParcelableArrayList(it, KEY_CONSUMED_DEEP_LINK_REQUESTS, DeepLinkRequest::class.java)
			},
		)
		// A config change this activity doesn't declare (e.g. font scale, day/night) recreates it with
		// savedInstanceState != null while handleDeepLinkRequest's resolve may still be in flight --
		// the old instance's lifecycleScope (and its coroutine) is cancelled with it. Gating solely on
		// savedInstanceState == null would silently lose a not-yet-consumed request instead of
		// retrying it on the new instance; comparing against consumedDeepLinkRequests (restored above)
		// rather than just checking deepLinkRequest != null is what tells a genuinely new/not-yet-acted-
		// on request apart from the system redelivering the same original launch Intent verbatim after
		// this same request was already fully handled (see consumedDeepLinkRequests' own docs).
		if (deepLinkRequest != null && deepLinkRequest !in consumedDeepLinkRequests) {
			handleDeepLinkRequest(deepLinkRequest)
		} else if (savedInstanceState == null) {
			openLastProject()
		}

		if (FeatureFlags.isExperimentsEnabled) {
			binding.codeOnTheGoLabel.title = getString(R.string.app_name) + "."
		}

		feedbackButtonManager =
			FeedbackButtonManager(
				activity = this,
				feedbackFab = binding.fabFeedback.root,
			)

		feedbackButtonManager?.setupDraggableFab()

		viewModel.currentScreen.observe(this) { screen ->
			if (screen == -1) {
				return@observe
			}

			onScreenChanged(screen)
			onBackPressedCallback.isEnabled = screen != SCREEN_MAIN
		}

		// Data in a ViewModel is kept between activity rebuilds on
		// configuration changes (i.e. screen rotation)
		// * previous == -1 and current == -1 -> this is an initial instantiation of the activity
		if (viewModel.currentScreen.value == -1 && viewModel.previousScreen == -1) {
			viewModel.setScreen(SCREEN_MAIN)
		} else {
			onScreenChanged(viewModel.currentScreen.value)
		}

		onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

		// Show warning dialog if today's date is after October 28 2026
		val targetDate =
			java.util.Calendar.getInstance().apply {
				set(2026, 9, 28) // Month is 0-indexed, so 9 = October
			}
		val comparisonDate = java.util.Calendar.getInstance()
		if (comparisonDate.after(targetDate)) {
			showWarningDialog()
		}
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean =
		shortcutManager.dispatch(
			event = event,
			context = ShortcutContext.MAIN,
			focusView = currentFocus,
			hasModal = supportFragmentManager.hasVisibleDialog(),
			executionContext = mainShortcutExecutionContext,
		) || super.dispatchKeyEvent(event)

	private val mainShortcutExecutionContext by lazy {
		ShortcutExecutionContext(
			ideShortcutActions =
				IdeShortcutActions {
					ActionData.create(this)
				},
		)
	}

	fun showCreateProject(): Boolean {
		viewModel.setScreen(SCREEN_TEMPLATE_LIST)
		return true
	}

	fun showOpenProject(): Boolean {
		viewModel.setScreen(SCREEN_SAVED_PROJECTS)
		return true
	}

	fun showCloneRepository(): Boolean {
		viewModel.setScreen(SCREEN_CLONE_REPO)
		return true
	}

	private fun showWarningDialog() {
		val builder = DialogUtils.newMaterialDialogBuilder(this)

		// Set the dialog's title and message
		builder.setTitle(getString(R.string.title_warning))
		builder.setMessage(getString(R.string.download_codeonthego_message))

		// Add the "OK" button and its click listener
		builder.setPositiveButton(getString(android.R.string.ok)) { _, _ ->
			UrlManager.openUrl(getString(R.string.download_codeonthego_url), null)
		}

		// Add the "Cancel" button
		builder.setNegativeButton(getString(R.string.url_consent_cancel), null)
		builder.show()
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		recreateVisibleFragmentView()
	}

	override fun onResume() {
		super.onResume()
		MainScreenActions.register(this)
		feedbackButtonManager?.loadFabPosition()
	}

	override fun onPause() {
		MainScreenActions.clear()
		super.onPause()
	}

	/**
	 * With configChanges="orientation|screenSize", the activity is not recreated on rotation,
	 * so fragment views stay inflated with the initial layout. Replace the visible fragment
	 * with a new instance so it re-inflates and picks up layout-land when in landscape.
	 */
	private fun recreateVisibleFragmentView() {
		when (viewModel.currentScreen.value) {
			SCREEN_MAIN -> {
				supportFragmentManager
					.beginTransaction()
					.setReorderingAllowed(true)
					.replace(R.id.main, MainFragment())
					.commitNow()
			}

			SCREEN_SAVED_PROJECTS -> {
				supportFragmentManager
					.beginTransaction()
					.setReorderingAllowed(true)
					.replace(R.id.saved_projects_view, RecentProjectsFragment())
					.commitNow()
			}

			else -> {}
		}
	}

	override fun onApplyWindowInsets(insets: WindowInsetsCompat) {
		super.onApplyWindowInsets(insets)
		_binding?.root?.applyBottomWindowInsetsPadding(insets)
	}

	override fun onApplySystemBarInsets(insets: Insets) {
		// onApplySystemBarInsets can be called before bindLayout() sets _binding
		// Use 0 for bottom so fragment content stretches to the screen bottom (no white bar).
		_binding?.fragmentContainersParent?.setPadding(
			insets.left,
			0,
			insets.right,
			0,
		)
	}

	private fun onScreenChanged(screen: Int?) {
		// When navigating to main (e.g. Exit from saved projects), replace the fragment so it
		// inflates with the current configuration (landscape -> 3 columns, portrait -> 1 column).
		if (screen == SCREEN_MAIN) recreateVisibleFragmentView()

		val previous = viewModel.previousScreen
		if (previous != -1) {
			closeKeyboard()

			// template list -> template details
			// ------- OR -------
			// template details -> template list
			val setAxisToX =
				(previous == SCREEN_TEMPLATE_LIST || previous == SCREEN_TEMPLATE_DETAILS) &&
					(screen == SCREEN_TEMPLATE_LIST || screen == SCREEN_TEMPLATE_DETAILS)

			val axis =
				if (setAxisToX) {
					MaterialSharedAxis.X
				} else {
					MaterialSharedAxis.Y
				}

			val isForward = (screen ?: 0) - previous == 1

			val transition = MaterialSharedAxis(axis, isForward)
			transition.doOnEnd {
				viewModel.isTransitionInProgress = false
				onBackPressedCallback.isEnabled = viewModel.currentScreen.value != SCREEN_MAIN
			}

			viewModel.isTransitionInProgress = true
			TransitionManager.beginDelayedTransition(binding.root, transition)
		}

		val currentFragment =
			when (screen) {
				SCREEN_MAIN -> binding.main
				SCREEN_TEMPLATE_LIST -> binding.templateList
				SCREEN_TEMPLATE_DETAILS -> binding.templateDetails
				TOOLTIPS_WEB_VIEW -> binding.tooltipWebView
				SCREEN_SAVED_PROJECTS -> binding.savedProjectsView
				SCREEN_DELETE_PROJECTS -> binding.deleteProjectsView
				SCREEN_CLONE_REPO -> binding.cloneRepositoryView
				else -> throw IllegalArgumentException("Invalid screen id: '$screen'")
			}

		for (fragment in arrayOf(
			binding.main,
			binding.templateList,
			binding.templateDetails,
			binding.tooltipWebView,
			binding.savedProjectsView,
			binding.deleteProjectsView,
			binding.cloneRepositoryView,
		)) {
			fragment.isVisible = fragment == currentFragment
		}

		binding.codeOnTheGoLabel.setOnLongClickListener {
			when (screen) {
				SCREEN_SAVED_PROJECTS -> showToolTip(PROJECT_RECENT_TOP)
				SCREEN_TEMPLATE_DETAILS -> showToolTip(SETUP_OVERVIEW)
			}
			true
		}
	}

	override fun bindLayout(): View {
		val binding = ActivityMainBinding.inflate(layoutInflater)
		_binding = binding
		return binding.root
	}

	private fun showToolTip(tag: String) {
		TooltipManager.showIdeCategoryTooltip(this, binding.root, tag)
	}

	private fun openLastProject() {
		// bindLayout() is called by super.onCreate() before this method runs
		binding.root.post { tryOpenLastProject() }
	}

	private fun tryOpenLastProject() {
		if (!GeneralPreferences.autoOpenProjects) return

		lifecycleScope.launch(Dispatchers.IO) {
			val validProjects = findValidProjects(Environment.PROJECTS_DIR)
			val lastOpenedPath = GeneralPreferences.lastOpenedProject

			val projectToOpen =
				validProjects.find { it.absolutePath == lastOpenedPath }
					?: validProjects.maxByOrNull { it.lastModified() }

			withContext(Dispatchers.Main) {
				when {
					projectToOpen != null -> {
						handleOpenProject(projectToOpen)
					}

					lastOpenedPath.isNotBlank() && lastOpenedPath != GeneralPreferences.NO_OPENED_PROJECT -> {
						if (!File(lastOpenedPath).exists()) {
							flashInfo(string.msg_opened_project_does_not_exist)
						}
					}

					else -> {
						Unit
					}
				}
			}
		}
	}

	private fun handleOpenProject(
		root: File,
		pendingFileRequest: PendingFileRequest? = null,
		isDeepLink: Boolean = false,
	) {
		if (GeneralPreferences.confirmProjectOpen) {
			askProjectOpenPermission(root, pendingFileRequest, isDeepLink)
			return
		}
		// No confirmation gate -- opening happens immediately below, so this is "confirm time" for
		// consumedDeepLinkRequests' purposes.
		if (isDeepLink) {
			consumedDeepLinkRequests.add(latestDeepLinkRequest)
		}
		openProject(root, pendingFileRequest = pendingFileRequest)
	}

	// Tracked so a later overlapping request (e.g. two deep links arriving in quick succession while
	// GeneralPreferences.confirmProjectOpen is enabled) dismisses the dialog already showing instead
	// of stacking a second one underneath it -- letting both stack would let the user confirm the
	// visible (later) one, then unknowingly tap the earlier one now exposed behind it, triggering a
	// confusing second close-and-reopen inside the editor that just opened. Also dismissed in
	// onDestroy() to avoid leaking its window.
	private var activeOpenPermissionDialog: AlertDialog? = null

	// Whether activeOpenPermissionDialog (if any) came from a deep link -- see askProjectOpenPermission.
	private var activeOpenPermissionDialogIsDeepLink = false

	private fun askProjectOpenPermission(
		root: File,
		pendingFileRequest: PendingFileRequest? = null,
		isDeepLink: Boolean = false,
	) {
		// A deep link is an explicit, just-tapped user action and may always replace whatever's
		// showing (including another deep link's own dialog, e.g. two links arriving in quick
		// succession) -- but not the reverse: tryOpenLastProject's auto-open scan can complete
		// moments after a deep link's dialog is already up, and silently yanking that away for an
		// unrelated "open last project" prompt would be far more surprising than just dropping this
		// slower, non-explicit request instead.
		if (!isDeepLink && activeOpenPermissionDialogIsDeepLink && activeOpenPermissionDialog?.isShowing == true) {
			return
		}
		activeOpenPermissionDialog?.dismiss()
		activeOpenPermissionDialogIsDeepLink = isDeepLink
		val builder = DialogUtils.newMaterialDialogBuilder(this)
		builder.setTitle(string.title_confirm_open_project)
		builder.setMessage(getString(string.msg_confirm_open_project, root.absolutePath))
		builder.setCancelable(false)
		builder.setPositiveButton(string.yes) { _, _ ->
			// The user has now actually confirmed -- "confirm time" for consumedDeepLinkRequests'
			// purposes, unlike merely having shown this dialog (see its own docs on why that
			// distinction matters for a recreate that happens while this dialog is still up).
			if (isDeepLink) {
				consumedDeepLinkRequests.add(latestDeepLinkRequest)
			}
			openProject(root, pendingFileRequest = pendingFileRequest)
		}
		builder.setNegativeButton(string.no, null)
		activeOpenPermissionDialog = builder.show()
	}

	internal fun openProject(
		root: File,
		project: RecentProject? = null,
		hasTemplateIssues: Boolean = false,
		pendingFileRequest: PendingFileRequest? = null,
	) {
		// Captured before the bookkeeping call below overwrites it: EditorHandlerActivity.onNewIntent
		// (already-live singleTask instance) needs to know what project WAS open to tell a genuine
		// switch from a same-project no-op, but recordProjectOpenedBookkeeping's synchronous
		// ProjectManagerImpl.projectPath write below makes that global read the NEW path by the time
		// onNewIntent runs -- comparing against it there would always see "already open".
		val previousProjectPath = IProjectManager.getInstance().projectDirPath

		// Bookkeeping (Recents/analytics/lastOpenedProject) must run regardless of isFinishing --
		// only the startActivity() below is unsafe from a finishing activity.
		recordProjectOpenedBookkeeping(recentProjectRepository, root, project, analyticsManager)

		if (isFinishing) {
			return
		}

		val intent =
			Intent(this, EditorActivityKt::class.java).apply {
				putExtra("PROJECT_PATH", root.absolutePath)
				putExtra("PREVIOUS_PROJECT_PATH", previousProjectPath)
				if (hasTemplateIssues) {
					putExtra("HAS_TEMPLATE_ISSUES", true)
				}
				pendingFileRequest?.let { putExtra(PendingFileRequest.EXTRA_KEY, it) }
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			}

		startActivity(intent)
	}

	private fun startWebServer() {
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val dbFile = Environment.DOC_DB
				log.info("Starting WebServer - using database file from: {}", dbFile.absolutePath)
				val server =
					WebServer(
						ServerConfig(
							databasePath = dbFile.absolutePath,
							fileDirPath = applicationContext.filesDir.absolutePath,
						),
					)
				webServer = server
				server.start()
			} catch (e: Exception) {
				log.error("Failed to start WebServer", e)
			} finally {
				webServer = null
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		IntentCompat
			.getParcelableExtra(intent, DeepLinkRequest.EXTRA_KEY, DeepLinkRequest::class.java)
			?.let { handleDeepLinkRequest(it) }
	}

	/**
	 * Resolves [request]'s project name to an on-disk project directory and opens it -- called when
	 * [DeepLinkActivity] has already determined no project is currently loaded.
	 *
	 * This still goes through [handleOpenProject] (honoring [GeneralPreferences.confirmProjectOpen])
	 * rather than calling [openProject] directly: [MainActivity] is `exported="true"` -- not because
	 * anything requires it to be (`SplashActivity` holds the actual MAIN/LAUNCHER intent-filter;
	 * [MainActivity] has none of its own), which is exactly why any co-installed app can target it
	 * directly with this same extra, bypassing [DeepLinkActivity]'s own URI re-validation entirely.
	 * Skipping the confirmation gate here would let such an app silently force a project open with no
	 * user interaction at all.
	 */
	private fun handleDeepLinkRequest(request: DeepLinkRequest) {
		latestDeepLinkRequest = request
		lifecycleScope.launch(Dispatchers.IO) {
			val projectDir = resolveDeepLinkProject(Environment.PROJECTS_DIR, request.projectName)
			withContext(Dispatchers.Main) {
				// The activity may have started finishing while resolveDeepLinkProject was still
				// scanning disk -- lifecycleScope only cancels at ON_DESTROY, not the moment isFinishing
				// first flips true, so this continuation can otherwise still run and show a dialog on a
				// dying window.
				if (isFinishing || isDestroyed) return@withContext
				projectDir ?: return@withContext
				// A second, faster-resolving deep link superseded this one while it was still resolving
				// -- this stale, slower request must not now bounce the user back to its own (older)
				// target after they've already been taken to the newer one.
				if (latestDeepLinkRequest !== request) return@withContext
				// the request is recorded as consumed once this request is actually opened (or confirmed, if
				// GeneralPreferences.confirmProjectOpen is on) -- see handleOpenProject/
				// askProjectOpenPermission and consumedDeepLinkRequests' own docs for why marking it
				// here, before the user has necessarily responded to that confirm dialog, would be too
				// early.
				handleOpenProject(projectDir, pendingFileRequest = request.fileRequest, isDeepLink = true)
			}
		}
	}

	override fun onDestroy() {
		webServer?.stop()
		ITemplateProvider.getInstance().release()
		activeOpenPermissionDialog?.dismiss()
		super.onDestroy()
		_binding = null
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putParcelableArrayList(KEY_CONSUMED_DEEP_LINK_REQUESTS, consumedDeepLinkRequests.toSavedList())
	}

	companion object {
		private const val KEY_CONSUMED_DEEP_LINK_REQUESTS = "consumedDeepLinkRequests"
	}
}
