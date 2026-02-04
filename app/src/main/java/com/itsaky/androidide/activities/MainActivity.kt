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
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import androidx.transition.doOnEnd
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.FeedbackButtonManager
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.editor.EditorActivityKt
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityMainBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_RECENT_TOP
import com.itsaky.androidide.idetooltips.TooltipTag.SETUP_OVERVIEW
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.utils.findValidProjects
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_DELETE_PROJECTS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_MAIN
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_SAVED_PROJECTS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_DETAILS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_LIST
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.TOOLTIPS_WEB_VIEW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.localwebserver.ServerConfig
import org.appdevforall.localwebserver.WebServer
import org.koin.android.ext.android.inject
import org.slf4j.LoggerFactory
import java.io.File

class MainActivity : EdgeToEdgeIDEActivity() {
	private val log = LoggerFactory.getLogger(MainActivity::class.java)

	private val viewModel by viewModels<MainViewModel>()

	@Suppress("ktlint:standard:backing-property-naming")
	private var _binding: ActivityMainBinding? = null
	private val analyticsManager: IAnalyticsManager by inject()
	private var feedbackButtonManager: FeedbackButtonManager? = null

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

		// Start WebServer after installation is complete
		startWebServer()

		if (savedInstanceState == null) { openLastProject() }

		if (FeatureFlags.isExperimentsEnabled) {
			binding.codeOnTheGoLabel.title = getString(R.string.app_name) + "."
		}

		feedbackButtonManager =
			FeedbackButtonManager(
				activity = this,
				feedbackFab = binding.fabFeedback,
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

		// Show warning dialog if today's date is after April 26, 2026
		val targetDate =
			java.util.Calendar.getInstance().apply {
				set(2026, 3, 26) // Month is 0-indexed, so 3 = April
			}
		val comparisonDate = java.util.Calendar.getInstance()
		if (comparisonDate.after(targetDate)) {
			showWarningDialog()
		}
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

	override fun onResume() {
		super.onResume()
		feedbackButtonManager?.loadFabPosition()
	}

	override fun onApplySystemBarInsets(insets: Insets) {
		// onApplySystemBarInsets can be called before bindLayout() sets _binding
		_binding?.fragmentContainersParent?.setPadding(
			insets.left,
			0,
			insets.right,
			insets.bottom,
		)
	}

	private fun onScreenChanged(screen: Int?) {
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
				else -> throw IllegalArgumentException("Invalid screen id: '$screen'")
			}

		for (fragment in arrayOf(
			binding.main,
			binding.templateList,
			binding.templateDetails,
			binding.tooltipWebView,
			binding.savedProjectsView,
			binding.deleteProjectsView,
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

			val projectToOpen = validProjects.find { it.absolutePath == lastOpenedPath }
				?: validProjects.maxByOrNull { it.lastModified() }

			withContext(Dispatchers.Main) {
				when {
        	projectToOpen != null -> handleOpenProject(projectToOpen)

        	lastOpenedPath.isNotBlank() && lastOpenedPath != GeneralPreferences.NO_OPENED_PROJECT -> {
        		if (!File(lastOpenedPath).exists()) {
        			flashInfo(string.msg_opened_project_does_not_exist)
        		}
        	}

        	else -> Unit
				}
			}
		}
	}

	private fun handleOpenProject(root: File) {
		if (GeneralPreferences.confirmProjectOpen) {
			askProjectOpenPermission(root)
			return
		}
		openProject(root)
	}

	private fun askProjectOpenPermission(root: File) {
		val builder = DialogUtils.newMaterialDialogBuilder(this)
		builder.setTitle(string.title_confirm_open_project)
		builder.setMessage(getString(string.msg_confirm_open_project, root.absolutePath))
		builder.setCancelable(false)
		builder.setPositiveButton(string.yes) { _, _ -> openProject(root) }
		builder.setNegativeButton(string.no, null)
		builder.show()
	}

	internal fun openProject(root: File) {
		ProjectManagerImpl.getInstance().projectPath = root.absolutePath
		GeneralPreferences.lastOpenedProject = root.absolutePath

		// Track project open in Firebase Analytics
		analyticsManager.trackProjectOpened(root.absolutePath)

		if (isFinishing) {
			return
		}

		val intent =
			Intent(this, EditorActivityKt::class.java).apply {
				putExtra("PROJECT_PATH", root.absolutePath)
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			}

		startActivity(intent)
	}

    private fun startWebServer() {
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val dbFile = Environment.DOC_DB
				log.info("Starting WebServer - using database file from: {}", dbFile.absolutePath)
				val webServer = WebServer(ServerConfig(databasePath = dbFile.absolutePath))
				webServer.start()
			} catch (e: Exception) {
				log.error("Failed to start WebServer", e)
			}
		}
	}

	override fun onDestroy() {
		ITemplateProvider.getInstance().release()
		super.onDestroy()
		_binding = null
	}
}
