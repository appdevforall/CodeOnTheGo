package com.itsaky.androidide.git

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blankj.utilcode.util.SizeUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.GitCommitDialogBinding
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import org.eclipse.jgit.api.Git
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object GitCommitTask {

    /**
     * Overloaded entry point to start the commit process with pre-selected files and a message.
     */
    fun commit(
        context: Context,
        selectedFiles: List<String>? = null,
        commitMessage: String? = null
    ) {
        Thread {
            try {
                val projectDir = ProjectManagerImpl.getInstance().projectDir
                    ?: throw IllegalStateException("No project is open.")
                val git = Git.open(projectDir)
                val config = git.repository.config

                val userName = config.getString("user", null, "name")
                val userEmail = config.getString("user", null, "email")

                if (userName.isNullOrBlank() || userEmail.isNullOrBlank()) {
                    runOnUiThread {
                        promptForGitUserInfo(context) { name, email ->
                            continueCommitProcess(context, git, name, email, selectedFiles, commitMessage)
                        }
                    }
                } else {
                    continueCommitProcess(context, git, userName, userEmail, selectedFiles, commitMessage)
                }
            } catch (e: Exception) {
                runOnUiThread { flashError("Git Pre-check Error: ${e.message}") }
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * Continues the commit process after user info has been verified or supplied.
     */
    private fun continueCommitProcess(
        context: Context,
        git: Git,
        userName: String,
        userEmail: String,
        preselectedFiles: List<String>?,
        prefilledMessage: String?
    ) {
        Thread {
            try {
                // If both a list of files and a non-blank message are provided, commit directly.
                if (!preselectedFiles.isNullOrEmpty() && !prefilledMessage.isNullOrBlank()) {

                    performCommitWithRetry(context, git, preselectedFiles, prefilledMessage, userName, userEmail)

                } else {
                    // **OLD LOGIC**: One or both parameters are missing, so show the dialog.
                    val filesToShow: List<String>
                    if (preselectedFiles != null) {
                        filesToShow = preselectedFiles
                    } else {
                        val status = git.status().call()
                        if (status.isClean) {
                            runOnUiThread { flashError("No changes to commit.") }
                            return@Thread
                        }
                        filesToShow = (status.modified + status.untracked + status.added + status.changed + status.removed).sorted()
                    }

                    runOnUiThread {
                        showCommitDialog(context, git, filesToShow, userName, userEmail, preselectedFiles, prefilledMessage)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { flashError("Git Status Error: ${e.message}") }
            }
        }.start()
    }

    /**
     * Displays the commit dialog, pre-filling fields if data is provided.
     */
    private fun showCommitDialog(
        context: Context,
        git: Git,
        allChangedFiles: List<String>,
        userName: String,
        userEmail: String,
        preselectedFiles: List<String>?,
        prefilledMessage: String?
    ) {
        val binding = GitCommitDialogBinding.inflate(LayoutInflater.from(context))

        binding.tvOnBranch.text = "On Branch: ${git.repository.branch}"

        // Pre-fill the commit message if it was passed
        binding.teCommitMessage.setText(prefilledMessage ?: "")
        binding.teCommitMessage.hint = "Commit message"

        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_multiple_choice, allChangedFiles)
        binding.lvFilesToCommit.adapter = adapter
        binding.lvFilesToCommit.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        // Check items based on pre-selection or default to all
        for (i in 0 until adapter.count) {
            val currentFile = adapter.getItem(i)!!
            val shouldCheck = preselectedFiles == null || currentFile in preselectedFiles
            binding.lvFilesToCommit.setItemChecked(i, shouldCheck)
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

                performCommitWithRetry(context, git, selectedFiles, message, userName, userEmail)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // `promptForGitUserInfo`, `performCommitWithRetry`, and `fixSafeDirectory` remain unchanged.
    // Make sure they are included in your object as provided in the previous step.

    private fun promptForGitUserInfo(
        context: Context,
        onConfigured: (name: String, email: String) -> Unit
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = SizeUtils.dp2px(16f)
            setPadding(padding, padding, padding, padding)
        }

        val nameInput = EditText(context).apply { hint = "User Name" }
        val emailInput = EditText(context).apply {
            hint = "Email"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        container.addView(nameInput)
        container.addView(emailInput)

        AlertDialog.Builder(context)
            .setTitle("Git User Info Required")
            .setMessage("Please set your global user name and email to continue.")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val email = emailInput.text.toString().trim()

                if (name.isBlank() || email.isBlank()) {
                    flashError("Name and email cannot be empty.")
                    promptForGitUserInfo(context, onConfigured)
                    return@setPositiveButton
                }

                Thread {
                    try {
                        val gitExecutable = File(context.filesDir, "usr/bin/git")
                        ProcessBuilder(listOf(gitExecutable.absolutePath, "config", "--global", "user.name", name)).start().waitFor()
                        ProcessBuilder(listOf(gitExecutable.absolutePath, "config", "--global", "user.email", email)).start().waitFor()
                        runOnUiThread {
                            flashSuccess("Git user info saved globally.")
                            onConfigured(name, email)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { flashError("Failed to save Git config: ${e.message}") }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                flashError("Commit canceled: user information is required.")
            }
            .show()
    }

    private fun performCommitWithRetry(context: Context, git: Git, files: List<String>, message: String, userName: String, userEmail: String) {
        Thread {
            var success = false
            var attempt = 1
            while (!success && attempt <= 2) {
                try {
                    val addCommand = git.add()
                    files.forEach { addCommand.addFilepattern(it) }
                    addCommand.call()

                    git.commit()
                        .setCommitter(userName, userEmail)
                        .setMessage(message)
                        .call()

                    success = true
                    runOnUiThread { flashSuccess("Commit successful!") }

                } catch (e: Exception) {
                    val errorMessage = e.message ?: ""
                    if (attempt == 1 && errorMessage.contains("dubious ownership", ignoreCase = true)) {
                        runOnUiThread { Toast.makeText(context, "Fixing ownership issue...", Toast.LENGTH_SHORT).show() }
                        val fixed = fixSafeDirectory(context, git.repository.directory.parentFile)
                        if (!fixed) {
                            runOnUiThread { flashError("Failed to fix ownership issue. Commit aborted.") }
                            break
                        }
                    } else {
                        runOnUiThread { flashError("Commit failed: $errorMessage") }
                        break
                    }
                }
                attempt++
            }
        }.start()
    }

    private fun fixSafeDirectory(context: Context, projectDir: File): Boolean {
        return try {
            val gitExecutable = File(context.filesDir, "usr/bin/git")
            val command = listOf(
                gitExecutable.absolutePath, "config", "--global", "--add", "safe.directory", projectDir.absolutePath
            )
            ProcessBuilder(command).start().waitFor() == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}