package com.itsaky.androidide.git

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.GitCommitDialogBinding
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import org.eclipse.jgit.api.Git
import java.io.File

object GitCommitTask {

    /**
     * Main entry point to start the commit process.
     * Performs pre-checks and then shows the UI.
     */
    fun commit(context: Context) {
        // Run pre-checks on a background thread to avoid blocking the UI
        Thread {
            try {
                val projectDir = ProjectManagerImpl.getInstance().projectDir
                    ?: throw IllegalStateException("No project is open.")
                val git = Git.open(projectDir)

                // --- PRE-COMMIT CHECKS ---

                // 1. Check for user.name and user.email configuration
                val config = git.repository.config
                val userName = config.getString("user", null, "name")
                val userEmail = config.getString("user", null, "email")
                if (userName.isNullOrBlank() || userEmail.isNullOrBlank()) {
                    throw IllegalStateException("Git user name/email not set. Please configure them in settings.")
                }

                // 2. Check if there are any actual changes to commit
                val status = git.status().call()
                if (status.isClean) {
                    runOnUiThread { flashError("No changes to commit.") }
                    return@Thread
                }

                // Get a list of all files with changes
                val changedFiles =
                    (status.modified + status.untracked + status.added + status.changed + status.removed).sorted()

                // If all checks pass, show the commit dialog on the UI thread
                runOnUiThread {
                    showCommitDialog(context, git, changedFiles)
                }

            } catch (e: Exception) {
                runOnUiThread { flashError("Git Error: ${e.message}") }
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * Displays the commit dialog to the user.
     */
    private fun showCommitDialog(context: Context, git: Git, filesToCommit: List<String>) {
        val binding = GitCommitDialogBinding.inflate(LayoutInflater.from(context))

        binding.tvOnBranch.text = "On Branch: ${git.repository.branch}"
        binding.teCommitMessage.hint = "Commit message"

        val adapter =
            ArrayAdapter(context, android.R.layout.simple_list_item_multiple_choice, filesToCommit)
        binding.lvFilesToCommit.adapter = adapter
        binding.lvFilesToCommit.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        for (i in 0 until adapter.count) {
            binding.lvFilesToCommit.setItemChecked(i, true)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.commit_changes)
            .setView(binding.root)
            .setPositiveButton(R.string.title_commit) { _, _ ->
                val message = binding.teCommitMessage.text.toString()
                if (message.isBlank()) {
                    flashError("Commit message cannot be empty.")
                    return@setPositiveButton
                }

                val checkedPositions = binding.lvFilesToCommit.checkedItemPositions
                val selectedFiles = (0 until adapter.count)
                    .filter { checkedPositions[it] }
                    .map { adapter.getItem(it)!! }

                if (selectedFiles.isEmpty()) {
                    flashError("No files selected to commit.")
                    return@setPositiveButton
                }

                performCommitWithRetry(context, git, selectedFiles, message)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Executes the commit on a background thread with a retry mechanism for ownership errors.
     */
    private fun performCommitWithRetry(
        context: Context,
        git: Git,
        files: List<String>,
        message: String
    ) {
        Thread {
            var success = false
            var attempt = 1
            while (!success && attempt <= 2) {
                try {
                    // Stage all selected files for commit
                    val addCommand = git.add()
                    files.forEach { addCommand.addFilepattern(it) }
                    addCommand.call()

                    // Perform the commit
                    git.commit().setMessage(message).call()

                    success = true // If we reach here, it worked.
                    runOnUiThread { flashSuccess("Commit successful!") }

                } catch (e: Exception) {
                    val errorMessage = e.message ?: ""
                    // Check for the specific "dubious ownership" error on the first attempt
                    if (attempt == 1 && errorMessage.contains(
                            "dubious ownership",
                            ignoreCase = true
                        )
                    ) {
                        runOnUiThread {
                            Toast.makeText(
                                context,
                                "Fixing ownership issue...",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        // Attempt to fix it
                        val fixed = fixSafeDirectory(context, git.repository.directory.parentFile)
                        if (!fixed) {
                            runOnUiThread { flashError("Failed to fix ownership issue. Commit aborted.") }
                            break // If the fix fails, stop trying.
                        }
                        // If fixed, the loop will continue for the second attempt.
                    } else {
                        // This is a different error, or the second attempt failed.
                        runOnUiThread { flashError("Commit failed: $errorMessage") }
                        break // Break the loop.
                    }
                }
                attempt++
            }
        }.start()
    }

    /**
     * Runs the 'git config' command to add the repository to the safe directories list.
     * Returns true if successful, false otherwise.
     */
    private fun fixSafeDirectory(context: Context, projectDir: File): Boolean {
        return try {
            val gitExecutable = File(context.filesDir, "usr/bin/git")
            val command = listOf(
                gitExecutable.absolutePath,
                "config",
                "--global",
                "--add",
                "safe.directory",
                projectDir.absolutePath
            )
            val process = ProcessBuilder(command).start()
            process.waitFor() == 0 // Return true if exit code is 0 (success)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}