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
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.transition.TransitionManager
import androidx.transition.doOnEnd
import com.google.android.material.transition.MaterialSharedAxis
import com.itsaky.androidide.activities.editor.EditorActivityKt
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityMainBinding
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.flashInfo
import com.itsaky.androidide.viewmodel.MainViewModel
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_DELETE_PROJECTS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_MAIN
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_SAVED_PROJECTS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_DETAILS
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.SCREEN_TEMPLATE_LIST
import com.itsaky.androidide.viewmodel.MainViewModel.Companion.TOOLTIPS_WEB_VIEW

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

import android.hardware.display.DisplayManager
import android.view.Display

class MainActivity : EdgeToEdgeIDEActivity() {

    private val DATABASENAME = "documentation.db"
    private val TAG = "MainActivity"

    private val viewModel by viewModels<MainViewModel>()
    private var _binding: ActivityMainBinding? = null

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

        transferDatabaseFromAssets(this, DATABASENAME)

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

    /**
     * Transfers a database from the assets folder to the device's internal storage.
     *
     * @param context The application context.
     * @param databaseName The name of the database file in the assets folder (e.g., "mydatabase.db").
     * @return true if the database was transferred successfully, false otherwise.
     */
    fun transferDatabaseFromAssets(context: Context, databaseName: String): Boolean {
        //database is 128M so pick a large buffer size for speed
        val BUFFERSIZE = 1024*1024

        val dbPath = context.getDatabasePath(databaseName)
        Log.d(TAG, "transferDatabaseFromAssets\\\\dbPath = $dbPath")

        // Check if the database already exists in internal storage.
        if (dbPath.exists()) {
            Log.d(TAG, "Database $databaseName already exists at ${dbPath.absolutePath}")
            return true // Or false, depending on your desired behavior if the file exists
        }

        // Ensure the directory exists.
        val dbDir = File(dbPath.parent!!) // Use non-null assertion as getDatabasePath's parent is never null
        if (!dbDir.exists()) {
            if (!dbDir.mkdirs()) {
                Log.e(TAG, "Failed to create database directory: ${dbDir.absolutePath} for $DATABASENAME")
                return false
            }
            Log.d(TAG, "Database directory created at ${dbDir.absolutePath}")
        }

        // Copy the database file from assets to internal storage.

        val inputStream: InputStream = context.assets.open("database/$databaseName") // Corrected path
        val outputStream = FileOutputStream(dbPath)
        try {
            val buffer = ByteArray(BUFFERSIZE) // Use a reasonable buffer size
            var length: Int
            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            Log.d(TAG, "Database $databaseName successfully to ${dbPath.absolutePath}")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy database $databaseName: ${e.message}")
            e.printStackTrace() // Print the stack trace to help with debugging
            return false
        } finally {
            outputStream.flush()
            outputStream.close()
            inputStream.close()
        }
    }


    override fun onApplySystemBarInsets(insets: Insets) {
        binding.fragmentContainersParent.setPadding(
            insets.left, 0, insets.right, insets.bottom
        )
    }

    private fun onScreenChanged(screen: Int?) {
        val previous = viewModel.previousScreen
        if (previous != -1) {
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
    }

    override fun bindLayout(): View {
        _binding = ActivityMainBinding.inflate(layoutInflater)
        return binding.root
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
        startActivity(Intent(this, EditorActivityKt::class.java))
    }

    internal fun deleteProject(root: File) {
        ProjectManagerImpl.getInstance().projectPath = root.absolutePath
        try {
            val directory = File(ProjectManagerImpl.getInstance().projectPath)
            val parentDir = directory.parent
            deleteRecursive(directory)
        } catch (e: Exception) {
            flashInfo(string.msg_delete_existing_project_failed)
        }
    }

    fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory) for (child in fileOrDirectory.listFiles()) deleteRecursive(
            child
        )

        fileOrDirectory.delete()
    }

    override fun onDestroy() {
        ITemplateProvider.getInstance().release()
        super.onDestroy()
        _binding = null
    }

    private fun setupSecondaryDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        var secondDisplay: Display? = null

        for (display in displays) {
            if (display.displayId != Display.DEFAULT_DISPLAY) {
                secondDisplay = display
                break
            }
        }
        secondDisplay?.let {
            val presentation = SecondaryScreen(this, it)
            presentation.show()
        }
    }
}
