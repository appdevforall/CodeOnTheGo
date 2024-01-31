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
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blankj.utilcode.util.ThreadUtils
import com.itsaky.androidide.R
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.common.databinding.LayoutDialogProgressBinding
import com.itsaky.androidide.preferences.internal.GITHUB_PAT
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.tasks.TaskExecutor.executeAsyncProvideError
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

object GitPushTask {

//  val sshTransportConfigCallback = SshTransportConfigCallback()
  private val prefs = BaseApplication.getBaseInstance().prefManager

  fun push(context: Context) {

    val inflater =
      LayoutInflater.from(context).context.getSystemService(
        Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    inflater.inflate(R.layout.layout_dialog_progress, null)
    val binding = LayoutDialogProgressBinding.inflate(inflater, null, false)
    val view = binding.root
    binding.message.visibility = View.VISIBLE
    val builder = AlertDialog.Builder(context)
    builder.setTitle(R.string.pushing)
    builder.setView(view)
    builder.setCancelable(false)
    val targetDir = ProjectManagerImpl.getInstance().projectDir


    val progress = GitProgressMonitor(binding.progress, binding.message)

    val future =
      executeAsyncProvideError({

        val git = Git.open(targetDir)
        val cmd = git.push().setProgressMonitor(progress)
//          .setTransportConfigCallback(sshTransportConfigCallback)
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
          cmd.call()
        }
          return@executeAsyncProvideError
        }, { _, _ -> })

    val dialog = builder.show()

    future.whenComplete { result, error ->
      ThreadUtils.runOnUiThread {
        dialog?.dismiss()

        if (result == null || error != null) {
          TODO("ErrorOutput.ShowError(error, context)")
        } else {
          Toast.makeText(
            context,
            context.getString(R.string.push_completed),
            Toast.LENGTH_SHORT
          ).show()
        }

      }
    }

  }
}
