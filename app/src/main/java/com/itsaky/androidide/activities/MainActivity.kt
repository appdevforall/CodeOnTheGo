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
import com.itsaky.androidide.deeplink.ConsumedRequests
import com.itsaky.androidide.fragments.MainFragment
import com.itsaky.androidide.fragments.RecentProjectsFragment
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_RECENT_TOP
import com.itsaky.androidide.idetooltips.TooltipTag.SETUP_OVERVIEW
import com.itsaky.androidide.localWebServer.ServerConfig
import com.itsaky.androidide.localWebServer.WebServer
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.models.EditorIntentExtras
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
import com.itsaky.androidide.utils.DeepLinkProjectLookup
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
	private val consumedDeepLinkRequests = ConsumedRequests<DeepLinkRequest>()

	// The subset of consumedDeepLinkRequests recorded because the project could not be resolved,
	// rather than because anything was actually opened.
	//
	// The two have to be told apart by onNewIntent's re-forward gate. That gate exists to stop a
	// bounce loop: this activity opens a project, the editor decides the link names a different one
	// and bounces it straight back, and without the gate its dialog goes right back up. But a request
	// that failed to resolve never reached the editor at all, so no bounce can originate from it --
	// and DeepLinkRequest carries no nonce, so a genuinely new tap of the same URL is equal by value
	// to the failed one. Tapping a link for a project that does not exist yet, creating it, and
	// tapping again therefore died silently, forever, which is exactly the teacher-sends-a-student-a-
	// link flow the feature is for (ADFA-5067 review).
	//
	// Persisted alongside consumedDeepLinkRequests: without that, the same tap-create-tap sequence
	// across a process death lands back in the identical hole.
	private val unresolvedDeepLinkRequests = ConsumedRequests<DeepLinkRequest>()

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
		unresolvedDeepLinkRequests.restore(
			savedInstanceState?.let {
				BundleCompat.getParcelableArrayList(it, KEY_UNRESOLVED_DEEP_LINK_REQUESTS, DeepLinkRequest::class.java)
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
			// projectsRoot(), not the raw static: findValidProjects takes a non-null File, and
			// Environment.PROJECTS_DIR is assigned by the same unawaited loader coroutine SetupState's
			// KDoc describes -- and is not volatile. A null read here throws
			// Intrinsics.checkNotNullParameter inside a coroutine with no handler. The sibling call in
			// RecentProjectsFragment is wrapped in try/catch(Throwable); this one was not.
			val validProjects = findValidProjects(projectsRoot())
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

	// [deepLinkRequest] is the request this open is being performed FOR, threaded through from
	// handleDeepLinkRequest (null for non-deep-link opens) the same way root/pendingFileRequest
	// already are -- NOT re-read from latestDeepLinkRequest at consume time. That field tracks
	// whatever request arrived most recently, so reading it when the user answers a confirm dialog
	// could record a newer link B as consumed while actually opening this call's A, leaving A
	// unconsumed (re-shown on the next recreate) and B silently dropped.
	private fun handleOpenProject(
		root: File,
		pendingFileRequest: PendingFileRequest? = null,
		deepLinkRequest: DeepLinkRequest? = null,
	) {
		if (GeneralPreferences.confirmProjectOpen) {
			askProjectOpenPermission(root, pendingFileRequest, deepLinkRequest)
			return
		}
		// No confirmation gate -- opening happens immediately below, so this is "confirm time" for
		// consumedDeepLinkRequests' purposes.
		consumedDeepLinkRequests.add(deepLinkRequest)
		openProject(root, pendingFileRequest = pendingFileRequest)
	}

	// Tracked so a later overlapping request (e.g. two deep links arriving in quick succession while
	// GeneralPreferences.confirmProjectOpen is enabled) dismisses the dialog already showing instead
	// of stacking a second one underneath it -- letting both stack would let the user confirm the
	// visible (later) one, then unknowingly tap the earlier one now exposed behind it, triggering a
	// confusing second close-and-reopen inside the editor that just opened. Also dismissed in
	// onDestroy() to avoid leaking its window.
	private var activeOpenPermissionDialog: AlertDialog? = null

	// The request activeOpenPermissionDialog was raised for, so superseding or destroying the dialog
	// can record THAT request rather than whichever one happens to be latest at the time.
	private var activeOpenPermissionDialogRequest: DeepLinkRequest? = null

	// Whether activeOpenPermissionDialog (if any) came from a deep link -- see askProjectOpenPermission.
	private var activeOpenPermissionDialogIsDeepLink = false

	private fun askProjectOpenPermission(
		root: File,
		pendingFileRequest: PendingFileRequest? = null,
		deepLinkRequest: DeepLinkRequest? = null,
	) {
		val isDeepLink = deepLinkRequest != null
		// A deep link is an explicit, just-tapped user action and may always replace whatever's
		// showing (including another deep link's own dialog, e.g. two links arriving in quick
		// succession) -- but not the reverse: tryOpenLastProject's auto-open scan can complete
		// moments after a deep link's dialog is already up, and silently yanking that away for an
		// unrelated "open last project" prompt would be far more surprising than just dropping this
		// slower, non-explicit request instead.
		if (!isDeepLink && activeOpenPermissionDialogIsDeepLink && activeOpenPermissionDialog?.isShowing == true) {
			return
		}
		// AlertDialog.dismiss() fires neither the negative button nor the OnCancel listener (and
		// setCancelable(false) rules the latter out anyway), so the request the dialog being replaced
		// was raised for would never be recorded. Its Intent is still the task's launch Intent, so any
		// later recreate re-read it, found it unconsumed, and put the superseded dialog back up --
		// confirming it then switched the user to a project they had already moved past. Superseding a
		// dialog IS an answer to it, so record it here.
		if (activeOpenPermissionDialog?.isShowing == true) {
			consumedDeepLinkRequests.add(activeOpenPermissionDialogRequest)
		}
		activeOpenPermissionDialog?.dismiss()
		activeOpenPermissionDialogRequest = deepLinkRequest
		activeOpenPermissionDialogIsDeepLink = isDeepLink
		val builder = DialogUtils.newMaterialDialogBuilder(this)
		builder.setTitle(string.title_confirm_open_project)
		builder.setMessage(getString(string.msg_confirm_open_project, root.absolutePath))
		builder.setCancelable(false)
		builder.setPositiveButton(string.yes) { _, _ ->
			// The user has now actually confirmed -- "confirm time" for consumedDeepLinkRequests'
			// purposes, unlike merely having shown this dialog (see its own docs on why that
			// distinction matters for a recreate that happens while this dialog is still up).
			// deepLinkRequest, captured when this dialog was shown, is what gets recorded -- not
			// latestDeepLinkRequest, which by answer time can already point at a NEWER link than the
			// one this dialog was raised for (see handleOpenProject's doc).
			consumedDeepLinkRequests.add(deepLinkRequest)
			openProject(root, pendingFileRequest = pendingFileRequest)
		}
		// Consumed on decline too, not just on confirm. "No" is a decision the user made about this
		// request, so leaving it unconsumed meant every later recreate of this activity -- a dark-mode
		// toggle or a font-scale change, neither of which MainActivity declares in configChanges --
		// re-read the extra from the launch Intent and put the very same dialog back up, with no way
		// to make it stop.
		builder.setNegativeButton(string.no) { _, _ ->
			consumedDeepLinkRequests.add(deepLinkRequest)
		}
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
				putExtra(EditorIntentExtras.EXTRA_PROJECT_PATH, root.absolutePath)
				putExtra(EditorIntentExtras.EXTRA_PREVIOUS_PROJECT_PATH, previousProjectPath)
				if (hasTemplateIssues) {
					putExtra(EditorIntentExtras.EXTRA_HAS_TEMPLATE_ISSUES, true)
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
			// The consumed gate applies to a RE-FORWARD only. BaseEditorActivity bounces a request back
			// here when a link names a project other than the one it holds, and without the gate an
			// already-declined request had its dialog put straight back up. But gating every onNewIntent
			// would be worse: DeepLinkRequest carries no nonce, so deliberately tapping the same URL
			// again is equal by value to the earlier one, and an unconditional gate silently dropped it
			// -- forever, for links naming a project that did not exist when first tapped.
			?.takeIf {
				!intent.getBooleanExtra(EditorIntentExtras.EXTRA_REFORWARDED_DEEP_LINK, false) ||
					it !in consumedDeepLinkRequests ||
					// A request consumed only because its project could not be resolved never reached
					// the editor, so this bounce cannot be the loop the gate guards against -- it is a
					// fresh tap that happens to be value-equal to the earlier failure. Let it retry.
					it in unresolvedDeepLinkRequests
			}?.let { handleDeepLinkRequest(it) }
	}

	/**
	 * Resolves [request]'s project name to an on-disk project directory and opens it -- called when
	 * [DeepLinkActivity] has already determined no project is currently loaded.
	 *
	 * This still goes through [handleOpenProject] rather than calling [openProject] directly, so an
	 * open arriving by link is gated the same way as one from the project list when the user has
	 * asked for that gate ([GeneralPreferences.confirmProjectOpen]).
	 *
	 * That preference is *not* what makes this extra safe to trust -- it defaults to `false`. The
	 * boundary is the manifest: [MainActivity] is not exported, so this extra can only have come
	 * from [DeepLinkActivity], which parsed and validated the URI it came from. It was previously
	 * exported despite declaring no intent-filter of its own (`SplashActivity` holds the actual
	 * MAIN/LAUNCHER), which let any co-installed app send this extra directly and force an arbitrary
	 * project open with no user interaction at all. `DeepLinkTargetsNotExportedTest` pins that.
	 */
	private fun handleDeepLinkRequest(request: DeepLinkRequest) {
		// This attempt supersedes any earlier unresolved one for the same request. If it fails to
		// resolve again the failure branch re-records it; if it succeeds the request must stop being
		// exempt from the re-forward gate, or the bounce loop that gate exists to stop could resume.
		unresolvedDeepLinkRequests.remove(request)
		latestDeepLinkRequest = request
		lifecycleScope.launch(Dispatchers.IO) {
			val lookup = resolveDeepLinkProject(projectsRoot(), request.projectName)
			withContext(Dispatchers.Main) {
				// The activity may have started finishing while resolveDeepLinkProject was still
				// scanning disk -- lifecycleScope only cancels at ON_DESTROY, not the moment isFinishing
				// first flips true, so this continuation can otherwise still run and show a dialog on a
				// dying window.
				if (isFinishing || isDestroyed) return@withContext
				if (lookup !is DeepLinkProjectLookup.Found) {
					// Consumed even though nothing opened: the project does not exist, so retrying on
					// every recreate only re-shows "No project named X was found" indefinitely. Recorded
					// only when this request is still the current one, so a superseded slow resolve
					// cannot consume the newer request's slot.
					//
					// NotFound only. An Unverifiable result -- an EACCES straight after a
					// storage-permission change, an EIO on a flaky SD/FUSE mount -- says nothing about
					// whether the project exists, and recording it made a momentary filesystem failure
					// silence a valid link on every later delivery (ADFA-5067 review).
					if (lookup is DeepLinkProjectLookup.NotFound && latestDeepLinkRequest === request) {
						consumedDeepLinkRequests.add(request)
						// Tracked apart from the general consumed set so the re-forward gate in
						// onNewIntent can let this request through again -- see the field's docs.
						unresolvedDeepLinkRequests.add(request)
					}
					return@withContext
				}
				val projectDir = lookup.projectDir
				// A second, faster-resolving deep link superseded this one while it was still resolving
				// -- this stale, slower request must not now bounce the user back to its own (older)
				// target after they've already been taken to the newer one.
				if (latestDeepLinkRequest !== request) return@withContext
				// the request is recorded as consumed once this request is actually opened (or confirmed, if
				// GeneralPreferences.confirmProjectOpen is on) -- see handleOpenProject/
				// askProjectOpenPermission and consumedDeepLinkRequests' own docs for why marking it
				// here, before the user has necessarily responded to that confirm dialog, would be too
				// early.
				handleOpenProject(projectDir, pendingFileRequest = request.fileRequest, deepLinkRequest = request)
			}
		}
	}

	override fun onDestroy() {
		webServer?.stop()
		ITemplateProvider.getInstance().release()
		// Deliberately does NOT record the request consumed, unlike the supersession dismiss above.
		// This dismiss is only about not leaking the window; if this is a config-change recreate the
		// successor re-reads the launch Intent and should raise the dialog again, which is the
		// behaviour a user who has answered nothing expects.
		activeOpenPermissionDialog?.dismiss()
		super.onDestroy()
		_binding = null
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putParcelableArrayList(KEY_CONSUMED_DEEP_LINK_REQUESTS, consumedDeepLinkRequests.toSavedList())
		outState.putParcelableArrayList(KEY_UNRESOLVED_DEEP_LINK_REQUESTS, unresolvedDeepLinkRequests.toSavedList())
	}

	companion object {
		private const val KEY_CONSUMED_DEEP_LINK_REQUESTS = "consumedDeepLinkRequests"
		private const val KEY_UNRESOLVED_DEEP_LINK_REQUESTS = "unresolvedDeepLinkRequests"
	}
}
