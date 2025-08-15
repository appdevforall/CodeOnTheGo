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

package com.itsaky.androidide.git

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blankj.utilcode.util.ThreadUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.databinding.GitCommitDialogBinding
import com.itsaky.androidide.preferences.internal.GITHUB_EMAIL
import com.itsaky.androidide.preferences.internal.GITHUB_PAT
import com.itsaky.androidide.preferences.internal.GITHUB_USERNAME
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.tasks.TaskExecutor.executeAsyncProvideError
import com.itsaky.androidide.utils.FeedbackManager
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

object GitCommitTask {

        private val prefs = BaseApplication.getBaseInstance().prefManager

        //@RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun commit(/*project: Project,*/ context: Context) {

            val inflater =
                LayoutInflater.from(context).context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            inflater.inflate(R.layout.git_commit_dialog, null)

            val binding = GitCommitDialogBinding.inflate(inflater, null, false)
            val filesToCommit = ArrayList<String>()
            val filesUntracked = ArrayList<String>()
            val targetDir = ProjectManagerImpl.getInstance().projectDir


            binding.fabSendFeedback.setOnClickListener {
                FeedbackManager.showFeedbackDialog(
                    context = context,
                    currentScreen = "Git Commit",
                    customSubject = "Git Commit Feedback",
                    appVersion = com.itsaky.androidide.BuildConfig.VERSION_NAME
                )
            }

            val git : Git = try {
                 Git.open(targetDir)
            } catch(e : RepositoryNotFoundException) {
                null
            } ?: TODO("add alert dialog here to explain missing repo")
            val repo = git.repository
            val branch = repo.branch
            val result = git.status().call()
            val cf = git.diff().setCached(false).setShowNameOnly(true).call()

            if (cf.size == 0) {

                Toast.makeText(
                    context,
                    context.getString(R.string.commit_canceled_files_to_commit),
                    Toast.LENGTH_LONG
                )
                    .show();
                return;
            } else {
                for (name in cf) {
                    if (!name.newPath.isNullOrBlank()) {
                        filesToCommit.add(name.newPath)
                    }
                }
            }

            val sb: StringBuilder = StringBuilder()
            sb.append("On Branch ")
            var pos = sb.length
            sb.append(branch)
            val ssb = SpannableStringBuilder(sb)
            val bi = StyleSpan(Typeface.BOLD_ITALIC)
            ssb.setSpan(bi, pos, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            binding.tvOnBranch.text = ssb


            val status = git.status().call()
            sb.clear()
            ssb.clear()
            sb.append("Your branch is up to date with '")
            pos = sb.length
            sb.append("origin/master")
            var pos1 = sb.length
            sb.append("'")
            ssb.append(sb)
            ssb.setSpan(bi, pos, pos1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            binding.tvBranchStatus.text = ssb

            binding.teCommitMessage.hint = "enter commit message here"
            val adapter = ArrayAdapter<String>(
                context,
                android.R.layout.simple_list_item_multiple_choice,
                filesToCommit
            )
            binding.lvFilesToCommit.adapter = adapter
            binding.lvFilesToCommit.choiceMode = ListView.CHOICE_MODE_MULTIPLE
            for (i in 0 until adapter.count) {
                binding.lvFilesToCommit.setItemChecked(i, true)
            }

            // Set a listener to handle item check changes
            binding.lvFilesToCommit.setOnItemClickListener { _, _, position, _ ->
                // Handle the long click event
                val item = binding.lvFilesToCommit.getItemAtPosition(position).toString()
                val isChecked = binding.lvFilesToCommit.isItemChecked(position)
                // Return true to consume the long click event
                true
            }


            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.commit_changes)
            builder.setView(binding.root)
            builder.setPositiveButton(R.string.title_commit) { _, _ ->

                val userName = prefs.getString(GITHUB_USERNAME, "")
                val userEmail = prefs.getString(GITHUB_EMAIL, "")

                val future =
                    executeAsyncProvideError({

                        val msg = binding.teCommitMessage.text?.toString()
                        var file = File(targetDir, "/.git")
                        var path = file.toString()

                        if (userName.isNullOrBlank() && userEmail.isNullOrBlank()) {
                            ThreadUtils.runOnUiThread {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.set_user_and_password),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else if (msg.isNullOrBlank()) {
                            ThreadUtils.runOnUiThread {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.empty_commit),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else if (binding.lvFilesToCommit.checkedItemCount == 0) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.commit_canceled_no_files_to_commit),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {

                            val gitDir = Git.open(targetDir)
                            val cmd: CommitCommand = gitDir.commit()
                            for (i in 0 until adapter.count) {
                                if (binding.lvFilesToCommit.isItemChecked(i)) {
                                    cmd.setOnly(
                                        binding.lvFilesToCommit.getItemAtPosition(i).toString()
                                    )
                                }
                                cmd.setCommitter(userName.toString(), userEmail.toString())
                                    .setMessage(msg)
                                val token = prefs.getString(
                                        GITHUB_PAT,
                                        ""
                                    )
                                if (!token.isNullOrBlank()) {
                                    cmd.setCredentialsProvider(
                                        UsernamePasswordCredentialsProvider(
                                            "<token>",
                                            token
                                        )
                                    )
                                }

                                cmd.call()

                                ThreadUtils.runOnUiThread {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.committed_all_changes_to_repository_in,
                                            path
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                            }
                        }

                        return@executeAsyncProvideError
                    }, { _, _ -> })

                future.whenComplete { result, error ->
                    ThreadUtils.runOnUiThread {
                        //TODO error log
//                        if (result == null || error != null) {
//                            ErrorOutput.ShowError(error, context)
//                        }
                    }
                }

            }

            builder.setNegativeButton(android.R.string.cancel, null)
            builder.show()

        }
    }
