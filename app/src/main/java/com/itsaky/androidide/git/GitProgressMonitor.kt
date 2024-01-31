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

import android.widget.TextView
import com.blankj.utilcode.util.ThreadUtils
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.eclipse.jgit.lib.ProgressMonitor

class GitProgressMonitor(val progress: LinearProgressIndicator, val message: TextView) :
  ProgressMonitor {

  private var cancelled = false

  fun cancel() {
    cancelled = true
  }

  override fun start(totalTasks: Int) {
    ThreadUtils.runOnUiThread { progress.max = totalTasks }
  }

  override fun beginTask(title: String?, totalWork: Int) {
    ThreadUtils.runOnUiThread { message.text = title }
  }

  override fun update(completed: Int) {
    ThreadUtils.runOnUiThread { progress.progress = completed }
  }

  override fun endTask() {}

  override fun isCancelled(): Boolean {
    return cancelled || Thread.currentThread().isInterrupted
  }

  override fun showDuration(p0: Boolean) {
    TODO("Not yet implemented")
  }
}
