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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.transition.TransitionManager
import androidx.transition.doOnEnd
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.activities.editor.EditorActivityKt
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityMainBinding
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.activities.SecondaryScreen
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_DELETE_PROJECTS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_MAIN
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_SAVED_PROJECTS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_DETAILS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_LIST
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.TOOLTIPS_WEB_VIEW
import org.appdevforall.localwebserver.WebServer
import org.appdevforall.localwebserver.ServerConfig
import com.itsaky.androidide.utils.Environment
import org.koin.android.ext.android.inject
import org.slf4j.LoggerFactory

import com.itsaky.androidide.utils.FileDeleteUtils
import java.io.File

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.appcompat.app.AppCompatActivity
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_RECENT_TOP
import com.itsaky.androidide.idetooltips.TooltipTag.SETUP_OVERVIEW
import org.appdevforall.codeonthego.common.ui.FeedbackButtonManager

class MainActivity : EdgeToEdgeIDEActivity() {

    private val DATABASENAME = "documentation.db"
    private val log = LoggerFactory.getLogger(MainActivity::class.java)

    private val viewModel by viewModels<MainViewModel>()
    private var _binding: ActivityMainBinding? = null
    private val analyticsManager: IAnalyticsManager by inject()

    companion object {
        private var instance: MainActivity? = null

        // This method will be used to get access to MainActivity instance
        fun getInstance(): MainActivity? {
            return instance
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            viewModel.apply {

                // Ignore back press if project creating is in progress
                if (creatingProject.value == true) {
                    return@apply
                }

                val newScreen = when (currentScreen.value) {
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

        openLastProject()
        setupSecondaryDisplay()

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
        instance = this
    }

    override fun onResume() {
        super.onResume()
        // Setting this up in onResume instead on onCreate so that the updated fab's position
        // is retrieved when navigating between MainActivity's fragments
        FeedbackButtonManager(
            activity = this,
            feedbackFab = binding.fabFeedback,
        ).setupDraggableFab()
    }

    override fun onApplySystemBarInsets(insets: Insets) {
        binding.fragmentContainersParent.setPadding(
            insets.left, 0, insets.right, insets.bottom
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
                (previous == SCREEN_TEMPLATE_LIST || previous == SCREEN_TEMPLATE_DETAILS) && (screen == SCREEN_TEMPLATE_LIST || screen == SCREEN_TEMPLATE_DETAILS)

            val axis = if (setAxisToX) {
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

        val currentFragment = when (screen) {
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
        _binding = ActivityMainBinding.inflate(layoutInflater)
        return binding.root
    }

    private fun showToolTip(tag: String) {
        TooltipManager.showTooltip(
            this, binding.root,
            tag
        )
    }

    private fun openLastProject() {
        binding.root.post { tryOpenLastProject() }
    }

    private fun tryOpenLastProject() {
        if (!GeneralPreferences.autoOpenProjects) {
            return
        }

        val openedProject = GeneralPreferences.lastOpenedProject
        if (GeneralPreferences.NO_OPENED_PROJECT == openedProject) {
            return
        }

        if (TextUtils.isEmpty(openedProject)) {
            app
            flashInfo(string.msg_opened_project_does_not_exist)
            return
        }

        val project = File(openedProject)
        if (!project.exists()) {
            flashInfo(string.msg_opened_project_does_not_exist)
            return
        }

        if (GeneralPreferences.confirmProjectOpen) {
            askProjectOpenPermission(project)
            return
        }

        openProject(project)
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

        // Track project open in Firebase Analytics
        analyticsManager.trackProjectOpened(root.absolutePath)

        val intent = Intent(this, EditorActivityKt::class.java).apply {
            putExtra("PROJECT_PATH", root.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        startActivity(intent)
    }

    internal fun deleteProject(root: File) {
        ProjectManagerImpl.getInstance().projectPath = root.absolutePath
        try {
            FileDeleteUtils.deleteRecursive(root)
        } catch (e: Exception) {
            flashInfo(string.msg_delete_existing_project_failed)
        }
    }

    private fun startWebServer() {
        try {
            val dbFile = Environment.DOC_DB

            if (!dbFile.exists()) {
                log.warn(
                    "Database file not found at: {} - WebServer will not start",
                    dbFile.absolutePath
                )
                return
            }

            log.info("Starting WebServer - database file exists at: {}", dbFile.absolutePath)
            val webServer = WebServer(ServerConfig(databasePath = dbFile.absolutePath))
            Thread { webServer.start() }.start()

        } catch (e: Exception) {
            log.error("Failed to start WebServer", e)
        }
    }

    override fun onDestroy() {
        ITemplateProvider.getInstance().release()
        super.onDestroy()
        _binding = null
    }

    private fun setupSecondaryDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

        val secondDisplay = displays.firstOrNull { display ->
            display.displayId != Display.DEFAULT_DISPLAY
        }
        secondDisplay?.let {
            val presentation = SecondaryScreen(this, it)
            presentation.show()
        }
    }
}
