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

package com.itsaky.androidide.actions.github

import android.content.Context
import android.view.MenuItem
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.git.GitFetchTask
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.RepositoryNotFoundException


class GitHubFetchAction(context: Context, override val order: Int) : EditorActivityAction() {
  val context = context

  /**
   * A unique ID for this action.
   */
  override val id: String = "ide.editor.github.fetch"

  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(R.string.title_github_fetch)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_github)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    data.getActivity() ?: run {
      markInvisible()
      return
    }
    val targetDir = ProjectManagerImpl.getInstance().projectDir
    val git : Git? = try {
      Git.open(targetDir)
    } catch(e : RepositoryNotFoundException) {
      null
    }

    git ?: run {
      markInvisible()
      return
    }

    visible = true

    val projectManager = IProjectManager.getInstance()
    enabled = projectManager.getAndroidAppModules().isNotEmpty()
  }

  /**
   * Execute the action. The action executed in a background thread by default.
   *
   * @param data The data containing various information about the event.
   * @return `true` if this action was executed successfully, `false` otherwise.
   */
  override suspend fun execAction(data: ActionData): Any {
    GitFetchTask.fetch(context)
    return true
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    // prefer showing this in the overflow menu
    return MenuItem.SHOW_AS_ACTION_IF_ROOM
  }
}