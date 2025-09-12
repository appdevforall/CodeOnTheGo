package com.itsaky.androidide.git

import android.content.Context
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import org.eclipse.jgit.api.Git
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object GitInitTask {

    fun init(context: Context, userName: String? = null, userEmail: String? = null) {
        val projectDir = ProjectManagerImpl.getInstance().projectDir

        val gitDir = File(projectDir, ".git")
        if (gitDir.exists()) {
            flashError("Project is already a Git repository.")
            return
        }

        // Run file and process operations on a background thread
        Thread {
            try {
                // Step 1: Initialize the repository with JGit
                val git = Git.init().setDirectory(projectDir).call()

                // Step 2: Configure user.name and user.email
                val config = git.repository.config
                if (!userName.isNullOrBlank() && !userEmail.isNullOrBlank()) {
                    // If parameters are provided, set them as LOCAL config for this repo
                    config.setString("user", null, "name", userName)
                    config.setString("user", null, "email", userEmail)
                    config.save()
                } else {
                    // If no parameters, check for a GLOBAL config
                    val globalName = config.getString("user", null, "name")
                    val globalEmail = config.getString("user", null, "email")
                    if (globalName.isNullOrBlank() || globalEmail.isNullOrBlank()) {
                        // If global config is missing, throw an error
                        throw IllegalStateException("Global Git user name/email not set. Please configure them in settings.")
                    }
                }

                // Step 3: Add the project directory to Git's safe.directory list
                val gitExecutable = File(context.filesDir, "usr/bin/git")
                if (!gitExecutable.exists()) {
                    throw IllegalStateException("Git executable not found. Please ensure the environment is set up.")
                }

                val command = listOf(
                    gitExecutable.absolutePath,
                    "config", "--global", "--add", "safe.directory",
                    projectDir.absolutePath
                )

                val process = ProcessBuilder(command).start()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    // All steps were successful
                    runOnUiThread {
                        flashSuccess("Git repository initialized successfully!")
                    }
                } else {
                    // The git config command failed
                    val errorOutput = BufferedReader(InputStreamReader(process.errorStream)).readText()
                    throw Exception("Failed to set safe.directory: $errorOutput")
                }

            } catch (e: Exception) {
                runOnUiThread {
                    flashError("Failed to initialize repository: ${e.message}")
                }
                e.printStackTrace()
            }
        }.start()
    }
}