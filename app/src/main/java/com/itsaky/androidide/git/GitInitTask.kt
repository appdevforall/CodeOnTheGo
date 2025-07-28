package com.itsaky.androidide.git

import android.content.Context
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import org.eclipse.jgit.api.Git
import java.io.File

object GitInitTask {

    fun init(context: Context) {
        val projectDir = ProjectManagerImpl.getInstance().projectDir
        if (projectDir == null) {
            flashError("No project is open.")
            return
        }

        // Check if a .git directory already exists
        val gitDir = File(projectDir, ".git")
        if (gitDir.exists()) {
            flashError("Project is already a Git repository.")
            return
        }

        try {
            // Initialize the repository
            Git.init().setDirectory(projectDir).call()
            flashSuccess("Git repository initialized successfully!")

            // Optional: You might want to refresh UI elements that depend on Git status
            // For example, by sending an event or calling a method on a ViewModel.

        } catch (e: Exception) {
            flashError("Failed to initialize repository: ${e.message}")
            e.printStackTrace()
        }
    }
}